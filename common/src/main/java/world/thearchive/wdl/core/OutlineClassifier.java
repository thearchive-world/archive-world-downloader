// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
     * The {@link OutlineClass#RECOVERED} test reads {@code capturedMask | savedMask}, not {@code savedMask} alone: the
     * carry-forward for a slot-captured container unions its slots per slot rather than replacing the list.
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

    private static boolean capturedTypeMatches(CapturedContainers captured, long blockKey,
            @Nullable String liveTypeId) {
        String recorded = captured.capturedBlockType(blockKey);
        return recorded == null || recorded.equals(liveTypeId);
    }
}
