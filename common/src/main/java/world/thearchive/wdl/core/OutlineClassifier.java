// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The pure three-way outline classifier. Given a pre-paired logical container (its one or two block pos keys, its
 * borne-entity id if any, and whether it is an ender chest) plus the session captured-set and the prior-session
 * recovered-set, it decides whether the container draws no rim (captured this session), the recovered hue, or the
 * unsaved hue, in that precedence. The any-position rule makes a multi-block container one entry: a double chest
 * classifies captured (or recovered) when EITHER half matches. MC-free and Java-8-clean; the per-frame face geometry is
 * the separate rule of {@link RimFace}, not selected here.
 */
public final class OutlineClassifier {
    private OutlineClassifier() {}

    public static OutlineClass classify(long[] blockKeys, @Nullable String liveTypeId, @Nullable UUID entityId,
            boolean ender, CapturedContainers captured, RecoveredCoverage recovered) {
        if (isCaptured(blockKeys, liveTypeId, entityId, ender, captured, recovered)) {
            return OutlineClass.CAPTURED;
        }
        if (entityId != null && recovered.containsEntity(entityId)) {
            return OutlineClass.RECOVERED;
        }
        for (long blockKey : blockKeys) {
            if (recovered.contains(blockKey)) {
                return OutlineClass.RECOVERED;
            }
        }
        return OutlineClass.UNSAVED;
    }

    /**
     * The per-slot classify for one chiseled bookshelf: a single logical container whose six slots are captured one
     * interaction at a time, taken as bitmasks where bit {@code n} is slot {@code n}. It draws no rim once every
     * occupied slot was captured this session, the recovered hue once every occupied slot was saved in a prior session
     * and none is disturbed this session, and the unsaved hue otherwise. An empty bookshelf ({@code occupiedMask} 0)
     * falls into the no-rim case.
     *
     * <p>Recovered tests the two masks together, because the save holds their union: a shelf's stored items are unioned
     * by slot rather than replaced, so a slot this session captured and a slot a prior session saved are both in the
     * copy on disk and neither puts the other at risk. A shelf whose every occupied slot is covered either way is
     * therefore saved, and only a slot covered by neither warrants the unsaved hue.
     */
    public static OutlineClass classifyBookshelf(int occupiedMask, int capturedMask, int savedMask) {
        if ((occupiedMask & ~capturedMask) == 0) {
            return OutlineClass.CAPTURED;
        }
        if ((occupiedMask & ~(capturedMask | savedMask)) == 0) {
            return OutlineClass.RECOVERED;
        }
        return OutlineClass.UNSAVED;
    }

    /** The rim hue for a classification: the recovered hue for {@link OutlineClass#RECOVERED}, else the unsaved hue. */
    public static MarkerHue hueFor(OutlineClass classification, OutlineConfig config) {
        return classification == OutlineClass.RECOVERED ? config.recoveredColor() : config.unscannedColor();
    }

    private static boolean isCaptured(long[] blockKeys, @Nullable String liveTypeId, @Nullable UUID entityId,
            boolean ender, CapturedContainers captured, RecoveredCoverage recovered) {
        // No rim once any copy of the shared ender inventory exists: captured this session, or already saved by a
        // prior download and carried forward on this resume. The restored fact routes here, to the no-rim class,
        // not to the recovered hue: an ender chest has no per-position content to mark recovered.
        if (ender && (captured.enderCaptured() || recovered.enderRecovered())) {
            return true;
        }
        if (entityId != null && captured.containsEntity(entityId)) {
            return true;
        }
        for (long blockKey : blockKeys) {
            if (captured.containsBlock(blockKey) && capturedTypeMatches(captured, blockKey, liveTypeId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the type recorded for a captured block position still matches the live block-entity type there (Gate 2).
     * A captured key with no recorded type falls back to bare membership, so the gate only ever adds precision and
     * never re-rims a validly captured container.
     */
    private static boolean capturedTypeMatches(CapturedContainers captured, long blockKey,
            @Nullable String liveTypeId) {
        String recorded = captured.capturedBlockType(blockKey);
        return recorded == null || recorded.equals(liveTypeId);
    }
}
