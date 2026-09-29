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

    public void recordBlockClick(long blockPosKey, long tick) {
        markSupersededUnless(pending == Target.BLOCK && this.blockPosKey == blockPosKey);
        this.pending = Target.BLOCK;
        this.blockPosKey = blockPosKey;
        this.clickTick = tick;
    }

    public void recordEntityClick(int entityId, long tick, boolean menuIncapable) {
        markSupersededUnless(pending == Target.ENTITY && this.entityId == entityId);
        this.pending = Target.ENTITY;
        this.entityId = entityId;
        this.pendingEntityMenuIncapable = menuIncapable;
        this.clickTick = tick;
    }

    public void recordVehicleOpenIntent(int vehicleId, long tick) {
        markSupersededUnless(pending == Target.VEHICLE && this.vehicleId == vehicleId);
        this.pending = Target.VEHICLE;
        this.vehicleId = vehicleId;
        this.clickTick = tick;
    }

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
     * {@link Target#SUPERSEDED} without touching the pending intent. Otherwise consumes the intent, so it seeds at most
     * one open and a stale one cannot resurrect.
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

    public int vehicleId() {
        return vehicleId;
    }

    /** Drop any pending click and superseded markers, so a following {@link #resolve} is {@link Target#NONE}. */
    public void clear() {
        pending = Target.NONE;
        supersededClickTicks.clear();
    }
}
