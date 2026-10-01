// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.ArrayDeque;

/**
 * A click older than the window is stale, and a taken intent is consumed, so it seeds at most one bind. A later intent
 * on a different target overwrites an earlier unconsumed one (last-intent-wins) and leaves a superseded marker: pairing
 * the overwritten intent's open with the latch could bind its menu's contents to the second target (a corrupt archive).
 * Each marker poisons at most one later open into {@link Target#SUPERSEDED}, which must bind nothing, not even the
 * crosshair; markers age out with the same window. Re-recording the same target refreshes the pending intent's tick and
 * mints no marker.
 *
 * <p>Overwriting a pending {@link Target#ENTITY} intent that is flagged menu-incapable mints no superseded marker.
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

    /**
     * Dismiss a pending click on the entity with network id {@code entityId}. Left latched, a menu-capable click could
     * supersede the next real intent and poison that open. Entity-precise, touches nothing else: a pending block click,
     * a vehicle intent, a click on a different entity, and all superseded markers (they may be owed to genuinely
     * in-flight opens) stay as they are.
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
     * {@link Target#SUPERSEDED} without touching the pending intent: the latch may still be owed to a later open.
     * Otherwise consumes the intent, so it seeds at most one open and a stale one cannot resurrect.
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
