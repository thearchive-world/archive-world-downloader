// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.xaeroplus;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaeroplus.Globals;
import xaeroplus.feature.render.DrawFeatureFactory;
import xaeroplus.util.ChunkUtils;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.OverlayHighlights;

/**
 * Holds every {@code xaeroplus.*} reference for the overlay, reached only after {@link XaeroPlusIntegration}'s
 * present-check. Registers one two-tone async chunk-highlight feature: the supplier hands XaeroPlus every saved chunk
 * for the player's current dimension, labeled covered or suspect; the color function draws each chunk in the user's
 * live hue for its tone, both at a fixed ~39% alpha. A covered chunk is one the recording path brought within entity
 * send range, so its item frames, paintings, and armor stands were sent and captured; a suspect chunk holds terrain but
 * was never within that range, so a map-art frame there is a blank tile the player must weave close to recover.
 */
final class XaeroPlusBinding {
    // The feature id doubles as the label in XaeroPlus's Draw-Order screen (it draws the raw id), so it is the mod
    // display name, not a machine id. XaeroPlus validates ids with an unanchored find() over [a-zA-Z0-9_-]+ and
    // round-trips the internal space, so the space is fine.
    private static final String FEATURE_ID = "Archive World Downloader";
    // Fixed overlay alpha, 100/255 (~39%), packed onto the per-chunk rgb at draw.
    private static final int OVERLAY_ALPHA = 0x64000000;

    private XaeroPlusBinding() {}

    static void register() {
        Globals.drawManager.registry().register(
                DrawFeatureFactory.multiColorAsyncChunkHighlights(FEATURE_ID, XaeroPlusBinding::supplyHighlights,
                        XaeroPlusBinding::color));
    }

    /**
     * The per-chunk overlay color: the user's live covered marker hue for a covered chunk, their live suspect hue
     * otherwise, both at the fixed overlay alpha. XaeroPlus calls this on the render thread while it rebuilds the
     * highlight vertex buffer, and only when that buffer needs refreshing rather than every frame, so a live hue edit
     * lands on the overlay's refresh cadence and not instantly.
     */
    private static int color(long chunkPos, long label) {
        int coveredRgb = Wdl.config().overlayCoveredColor().rgb();
        int suspectRgb = Wdl.config().overlaySuspectColor().rgb();
        return OverlayHighlights.toneColor(label, coveredRgb, suspectRgb, OVERLAY_ALPHA);
    }

    /**
     * Every saved chunk for the dimension XaeroPlus is drawing, each labeled covered or suspect, or an empty map. A
     * chunk is covered when it is in the covered snapshot and suspect otherwise.
     *
     * <p>Gated twice: only while a download is RECORDING, and only when the dimension argument matches the one the
     * player is physically in. The second gate is not what keeps the nether and the overworld apart, the indexes are
     * already keyed per dimension. The argument is the dimension XaeroPlus's map is displaying, not where the player
     * stands, and those diverge when its persisted map-dimension switch leaves the map on another dimension; the same
     * feature registry draws the minimap, so without this gate that player would see the other dimension's coverage on
     * their minimap. Do not drop it as redundant.
     *
     * <p>Runs on XaeroPlus's cache-refresh executor, a plain background pool and not the render thread, so everything
     * it touches has to be safe to read from off-thread: the state gate and the config are volatile, both snapshots are
     * thread-safe, the indexes carry the guarantee their owner documents, and the dimension read is only a suppression
     * predicate, so a stale one draws nothing rather than the wrong dimension.
     */
    private static Long2LongMap supplyHighlights(int regionX, int regionZ, int regionSize,
            ResourceKey<Level> dimension) {
        if (Wdl.state() != CaptureState.RECORDING) {
            return Long2LongMaps.EMPTY_MAP;
        }
        if (!ChunkUtils.getActualDimension().equals(dimension)) {
            return Long2LongMaps.EMPTY_MAP;
        }
        String dimensionId = dimension.location().toString();
        return OverlayHighlights.tag(Wdl.overlaySavedChunks(dimensionId), Wdl.overlayCoveredChunks(dimensionId));
    }
}
