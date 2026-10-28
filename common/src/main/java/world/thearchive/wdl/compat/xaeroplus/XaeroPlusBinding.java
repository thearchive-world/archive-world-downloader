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

final class XaeroPlusBinding {
    // The feature id doubles as the label in XaeroPlus's Draw-Order screen (it draws the raw id), so it is the mod
    // display name, not a machine id. XaeroPlus validates ids with an unanchored find() over [a-zA-Z0-9_-]+ and
    // round-trips the internal space, so the space is fine.
    private static final String FEATURE_ID = "Archive World Downloader";
    private static final int OVERLAY_ALPHA = 0x64000000;

    private XaeroPlusBinding() {}

    static void register() {
        Globals.drawManager.registry().register(
                DrawFeatureFactory.multiColorAsyncChunkHighlights(FEATURE_ID, XaeroPlusBinding::supplyHighlights,
                        XaeroPlusBinding::color));
    }

    private static int color(long chunkPos, long label) {
        int coveredRgb = Wdl.config().overlayCoveredColor().rgb();
        int suspectRgb = Wdl.config().overlaySuspectColor().rgb();
        return OverlayHighlights.toneColor(label, coveredRgb, suspectRgb, OVERLAY_ALPHA);
    }

    /**
     * Gated twice: only while a download is RECORDING, and only when the dimension argument matches the one the player
     * is physically in. The argument is the dimension XaeroPlus's map is displaying, not where the player stands, and
     * those diverge when its persisted map-dimension switch leaves the map on another dimension; the same feature
     * registry draws the minimap, so without this gate that player would see the other dimension's coverage on their
     * minimap. Do not drop it as redundant.
     *
     * <p>Runs on XaeroPlus's cache-refresh executor, a plain background pool and not the render thread, so everything
     * it touches has to be safe to read from off-thread.
     */
    private static Long2LongMap supplyHighlights(int regionX, int regionZ, int regionSize,
            ResourceKey<Level> dimension) {
        if (Wdl.state() != CaptureState.RECORDING) {
            return Long2LongMaps.EMPTY_MAP;
        }
        if (!ChunkUtils.getActualDimension().equals(dimension)) {
            return Long2LongMaps.EMPTY_MAP;
        }
        String dimensionId = dimension.identifier().toString();
        return OverlayHighlights.tag(Wdl.overlaySavedChunks(dimensionId), Wdl.overlayCoveredChunks(dimensionId));
    }
}
