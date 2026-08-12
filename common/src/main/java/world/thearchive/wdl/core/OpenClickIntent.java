// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.ArrayDeque;

/**
 * Remembers the local player's last open-seeding intent (a right-click on a block or entity, or an open-inventory
 * request while riding), so a container menu that opens a beat later binds to that INTENDED target rather than to the
 * live crosshair. Between the seeding action and the menu appearing there is no open screen, so the camera is not
 * frozen and the crosshair keeps tracking the view; a player who nudges their aim while the container opens would
 * otherwise bind the menu to whatever the crosshair drifted onto, or drop it. This latches the intent and hands it to
 * the open-time bind.
 *
 * <p>MC-free by construction: it names no {@code net.minecraft.*} type and works on a packed {@code BlockPos.asLong()},
 * a network entity id, and a tick, so it unit-tests with hand-fed ticks and ports across era-bands. The clicked entity
 * reference for an {@link Target#ENTITY} intent lives in the adapter, the way {@link ContainerAssociation} keeps the
 * bound entity UUID there; only the kind and freshness are decided here. The rules that keep it conservative: a click
 * older than the window is stale (the open it would have seeded never arrived, so it seeds nothing), and a taken intent
 * is consumed, so it seeds at most one bind. Take-once is narrower than it reads: a {@link Target#SUPERSEDED} resolve
 * consumes only the marker and leaves the intent latched, so an intent whose own open a marker took stays claimable
 * until the window expires, and nothing else bounds it. A later intent on a different target overwrites an earlier
 * unconsumed one (last-intent-wins) and leaves a superseded marker: on a lagged connection both opens are in flight,
 * they arrive in action order, and the first open belongs to the OVERWRITTEN intent, so pairing it with the latch would
 * bind the first menu's contents to the second target (a corrupt archive). Each marker poisons exactly one later open
 * into {@link Target#SUPERSEDED}, which must bind nothing, not even the crosshair; markers age out with the same
 * window. Re-recording the same target only refreshes the latch.
 *
 * <p>An entity click also carries whether the clicked entity can open a menu at all. Overwriting a pending
 * {@link Target#ENTITY} intent that is flagged menu-incapable mints no superseded marker: an entity that cannot open a
 * menu owed no open to begin with, so there is nothing to poison. A menu-capable entity click, and every other target
 * kind, still mints its marker on overwrite.
 */
public final class OpenClickIntent {
    /**
     * Which target the pending intent was on: {@link #NONE} when there is no fresh unconsumed intent (the open is
     * unattributed and binds nothing), {@link #VEHICLE} when the open was seeded by the client's own open-inventory
     * request while riding a container vehicle, which it names by network id, {@link #SUPERSEDED} when this open
     * belongs to an intent that a later one overwrote (bind nothing at all: the crosshair by now tracks the newer
     * intent).
     */
    public enum Target {
        NONE,
        BLOCK,
        ENTITY,
        VEHICLE,
        SUPERSEDED
    }

    private final long windowTicks;
    private final ArrayDeque<Long> supersededClickTicks = new ArrayDeque<>();
    private Target pending = Target.NONE;
    private long blockPosKey;
    private int entityId;
    private boolean pendingEntityMenuIncapable;
    private int vehicleId;
    private long clickTick;

    public OpenClickIntent(long windowTicks) {
        this.windowTicks = windowTicks;
    }

    /** Record a right-click on the block at {@code blockPosKey} on {@code tick}; overwrites any earlier click. */
    public void recordBlockClick(long blockPosKey, long tick) {
        markSupersededUnless(pending == Target.BLOCK && this.blockPosKey == blockPosKey);
        this.pending = Target.BLOCK;
        this.blockPosKey = blockPosKey;
        this.clickTick = tick;
    }

    /**
     * Record a right-click on the entity with network id {@code entityId} on {@code tick}, overwriting any earlier
     * click; the clicked entity itself is the adapter's. {@code menuIncapable} marks an entity that cannot open a menu
     * at all (see the class doc).
     */
    public void recordEntityClick(int entityId, long tick, boolean menuIncapable) {
        markSupersededUnless(pending == Target.ENTITY && this.entityId == entityId);
        this.pending = Target.ENTITY;
        this.entityId = entityId;
        this.pendingEntityMenuIncapable = menuIncapable;
        this.clickTick = tick;
    }

    /**
     * Record an open-inventory request the client sent while riding the container vehicle with network id
     * {@code vehicleId} on {@code tick}, overwriting any earlier intent. The vehicle open is click-less (the request is
     * a server command and fires no use event), so without this latch a stray unconsumed click would pair with the
     * vehicle's own open and route it onto the block axis.
     *
     * <p>The id is what makes the intent name its vehicle, and the bind requires {@link #vehicleId} to equal the RIDDEN
     * vehicle before it claims the open. Without that equality the intent is a bare "some vehicle open is owed" flag,
     * and a menu that opens while the player has since changed vehicles binds one vehicle's contents onto another; no
     * slot-count guard separates two container vehicles of the same size. Recording a request for the same vehicle
     * again only refreshes the latch, so the repeat opens vanilla itself sends from one held key cannot poison each
     * other.
     */
    public void recordVehicleOpenIntent(int vehicleId, long tick) {
        markSupersededUnless(pending == Target.VEHICLE && this.vehicleId == vehicleId);
        this.pending = Target.VEHICLE;
        this.vehicleId = vehicleId;
        this.clickTick = tick;
    }

    /**
     * Dismiss a pending click on the entity with network id {@code entityId}: the interact ended in MOUNTING that
     * entity (entering a chest boat, riding up a donkey), which is exclusive with opening a menu, so no open is owed to
     * the click. Left latched it would supersede the next real intent and poison that open. Entity-precise, touches
     * nothing else: a pending block click, a vehicle intent, a click on a different entity, and all superseded markers
     * (they may be owed to genuinely in-flight opens) stay as they are.
     */
    public void dismissEntityClick(int entityId) {
        if (pending == Target.ENTITY && this.entityId == entityId) {
            pending = Target.NONE;
        }
    }

    private void markSupersededUnless(boolean sameTarget) {
        if (pending != Target.NONE && !sameTarget
                && !(pending == Target.ENTITY && pendingEntityMenuIncapable)) {
            supersededClickTicks.addLast(clickTick);
        }
    }

    /**
     * Take the pending intent (a click or a vehicle open) if it is fresh at {@code nowTick}, returning its kind; a
     * stale or absent intent returns {@link Target#NONE}. A fresh superseded marker takes precedence and returns
     * {@link Target#SUPERSEDED} without touching the pending intent: the open being resolved belongs to the overwritten
     * intent, and the latch is still owed to a later open. Otherwise consumes the intent, so it seeds at most one open
     * and a stale one cannot resurrect. When this returns {@link Target#BLOCK}, {@link #blockPosKey} is the clicked
     * block pos.
     */
    public Target resolve(long nowTick) {
        while (!supersededClickTicks.isEmpty() && nowTick - supersededClickTicks.peekFirst() > windowTicks) {
            supersededClickTicks.removeFirst();
        }
        if (!supersededClickTicks.isEmpty()) {
            supersededClickTicks.removeFirst();
            return Target.SUPERSEDED;
        }
        Target resolved = pending != Target.NONE && nowTick - clickTick <= windowTicks ? pending : Target.NONE;
        pending = Target.NONE;
        return resolved;
    }

    /** The clicked block pos, meaningful only immediately after {@link #resolve} returned {@link Target#BLOCK}. */
    public long blockPosKey() {
        return blockPosKey;
    }

    /**
     * The network id of the vehicle the recorded open-inventory request was sent while riding, meaningful only
     * immediately after {@link #resolve} returned {@link Target#VEHICLE}.
     */
    public int vehicleId() {
        return vehicleId;
    }

    /** Drop any pending click and superseded markers, so a following {@link #resolve} is {@link Target#NONE}. */
    public void clear() {
        pending = Target.NONE;
        supersededClickTicks.clear();
    }
}
