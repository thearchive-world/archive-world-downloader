// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.journeymap;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import journeymap.client.api.IClientAPI;
import journeymap.client.api.display.DisplayType;
import journeymap.client.api.display.PolygonOverlay;
import journeymap.client.api.event.ClientEvent;
import journeymap.client.api.model.MapPolygonWithHoles;
import journeymap.client.api.model.ShapeProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.ChunkRectangleReducer;
import world.thearchive.wdl.core.OverlayHighlights;
import world.thearchive.wdl.platform.PlatformBridge;

public final class JourneyMapOverlayDriver {
    private static final Logger LOGGER = LoggerFactory.getLogger(JourneyMapOverlayDriver.class);

    private static final String MOD_ID = "wdl";

    private static final long NO_GENERATION = -1L;

    private static final int MAX_RECTANGLES = 4096;

    // Never null once an init has run, a lifecycle NullAway cannot model.
    @SuppressWarnings("NullAway.Init")
    private volatile IClientAPI api;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "wdl-journeymap-overlay");
        thread.setDaemon(true);
        return thread;
    });

    private boolean wired;
    private boolean subscribed;

    // Volatile because it is set from the mapping callback and read from the tick.
    private volatile boolean mapping;

    private boolean shown;

    private static final long REBUILD_DEBOUNCE_TICKS = 10L;
    private long clientTicks;
    private long lastDispatchTick = -REBUILD_DEBOUNCE_TICKS;

    private long lastGeneration = NO_GENERATION;
    private @Nullable ResourceKey<Level> lastDimension;

    private long lastAppliedGeneration = NO_GENERATION;

    private boolean workerInFlight;

    private boolean rerunPending;

    // Volatile: written on the worker, read on the client thread.
    private volatile @Nullable OverlayBatch pendingBatch;

    void initialize(IClientAPI api) {
        this.api = api;
        if (subscribed) {
            return;
        }
        subscribed = true;
        api.subscribe(MOD_ID, EnumSet.of(ClientEvent.Type.MAPPING_STARTED, ClientEvent.Type.MAPPING_STOPPED));
    }

    void wireOnce() {
        if (wired) {
            return;
        }
        wired = true;
        PlatformBridge bridge = Wdl.platformBridge();
        bridge.onClientTickEnd(this::onClientTick);
        bridge.onDisconnect(this::onDisconnect);
    }

    void onClientEvent(ClientEvent event) {
        if (event.type == ClientEvent.Type.MAPPING_STARTED) {
            mapping = true;
            lastGeneration = NO_GENERATION;
            lastDimension = null;
        } else if (event.type == ClientEvent.Type.MAPPING_STOPPED) {
            mapping = false;
            hideAll(api);
        }
    }

    private void onClientTick() {
        try {
            clientTicks++;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) {
                return;
            }
            IClientAPI localApi = api;
            if (!mapping) {
                return;
            }
            CaptureState state = Wdl.state();
            drainWorker(localApi, state);
            if (state != CaptureState.RECORDING) {
                hideAll(localApi);
                lastGeneration = NO_GENERATION;
                lastDimension = null;
                return;
            }
            // Read the generation strictly before the facade snapshots, so a change landing after this read is
            // seen by a later tick at a higher generation rather than silently folded into this build.
            long generation = Wdl.overlayGeneration();
            ResourceKey<Level> dimension = mc.level.dimension();
            if (generation == lastGeneration && dimension.equals(lastDimension)) {
                return;
            }
            if (!localApi.playerAccepts(MOD_ID, DisplayType.Polygon)) {
                return;
            }
            if (workerInFlight) {
                rerunPending = true;
                lastGeneration = generation;
                lastDimension = dimension;
                return;
            }
            if (clientTicks - lastDispatchTick < REBUILD_DEBOUNCE_TICKS) {
                // Inside the debounce window: leave lastGeneration unchanged so this change is not marked seen,
                // and a later tick dispatches it once the window passes.
                return;
            }
            lastGeneration = generation;
            lastDimension = dimension;
            lastDispatchTick = clientTicks;
            dispatch(generation, dimension);
        } catch (Throwable e) {
            // This runs on the loader's shared end-of-client-tick, so a throw here must not escape and disrupt
            // the other tick consumers.
            LOGGER.warn("the wdl JourneyMap overlay tick failed", e);
        }
    }

    private void drainWorker(IClientAPI localApi, CaptureState state) {
        OverlayBatch batch = pendingBatch;
        if (batch == null) {
            return;
        }
        pendingBatch = null;
        workerInFlight = false;
        List<ToneLayer> layers = batch.layers;
        if (state == CaptureState.RECORDING && layers != null && batch.generation >= lastAppliedGeneration) {
            applyBatch(localApi, batch.resourceKey, layers, batch.generation);
            lastAppliedGeneration = batch.generation;
            shown = true;
        }
        if (rerunPending) {
            rerunPending = false;
            // Invalidate the skip guard so the change that arrived mid-build rebuilds with fresh data.
            lastGeneration = NO_GENERATION;
        }
    }

    /**
     * Reusing and mutating the retained overlay instances in place, rather than removing and re-showing, did not update
     * the drawn map and left it frozen at the first geometry, and the tone's hull count varies per rebuild anyway, so a
     * clear and re-show is the reliable path.
     */
    private void applyBatch(IClientAPI localApi, ResourceKey<Level> resourceKey, List<ToneLayer> layers,
            long generation) {
        localApi.removeAll(MOD_ID, DisplayType.Polygon);
        int count = 0;
        for (ToneLayer layer : layers) {
            for (MapPolygonWithHoles hull : layer.hulls()) {
                showQuietly(localApi, new PolygonOverlay(MOD_ID, "wdl-coverage-" + count, resourceKey,
                        layer.style(), hull));
                count++;
            }
        }
        LOGGER.debug("wdl coverage overlay: showed {} polygons at generation {}", count, generation);
    }

    private static void showQuietly(IClientAPI localApi, PolygonOverlay overlay) {
        try {
            localApi.show(overlay);
        } catch (Throwable e) {
            LOGGER.warn("failed to show a wdl coverage overlay polygon", e);
        }
    }

    private void hideAll(IClientAPI localApi) {
        if (!shown) {
            return;
        }
        localApi.removeAll(MOD_ID, DisplayType.Polygon);
        shown = false;
    }

    private void dispatch(long generation, ResourceKey<Level> dimension) {
        String dimensionId = dimension.location().toString();
        // Snapshot saved strictly before covered: the facade mirrors the covered read onto the saved set until the send
        // range is calibrated, and this order is what lets that mirror yield an empty suspect set.
        long[] saved = Wdl.overlaySavedChunks(dimensionId);
        long[] covered = Wdl.overlayCoveredChunks(dimensionId);
        int coveredRgb = Wdl.config().overlayCoveredColor().rgb();
        int suspectRgb = Wdl.config().overlaySuspectColor().rgb();
        workerInFlight = true;
        try {
            worker.execute(() -> {
                OverlayBatch built;
                try {
                    built = buildBatch(generation, dimension, saved, covered, coveredRgb, suspectRgb);
                } catch (Throwable e) {
                    LOGGER.warn("failed to build the wdl coverage overlay geometry", e);
                    built = new OverlayBatch(generation, dimension, null);
                }
                pendingBatch = built;
            });
        } catch (Throwable e) {
            // A rejected dispatch would otherwise leave the single-flight guard set with no result to clear it.
            workerInFlight = false;
            LOGGER.warn("failed to dispatch the wdl coverage overlay build", e);
        }
    }

    private static OverlayBatch buildBatch(long generation, ResourceKey<Level> dimension, long[] saved, long[] covered,
            int coveredRgb, int suspectRgb) {
        OverlayHighlights.TonePartition part = OverlayHighlights.partitionTones(saved, covered);
        int[] coveredFine = ChunkRectangleReducer.reduce(part.covered());
        int[] suspectFine = ChunkRectangleReducer.reduce(part.suspect());
        int[] coveredRectangles;
        int[] suspectRectangles;
        if (rectangleCount(coveredFine) > MAX_RECTANGLES || rectangleCount(suspectFine) > MAX_RECTANGLES) {
            // Coarsen both tones on one shared grid so a mixed cell resolves to a single tone; coarsening either
            // tone alone would paint a cell in both.
            ChunkRectangleReducer.ToneRectangles coarse = ChunkRectangleReducer.coarsenTones(saved, covered,
                    MAX_RECTANGLES);
            coveredRectangles = coarse.covered;
            suspectRectangles = coarse.suspect;
            LOGGER.debug("coarsened wdl coverage overlay: covered {} suspect {} fine rectangles exceeded {}",
                    rectangleCount(coveredFine), rectangleCount(suspectFine), MAX_RECTANGLES);
        } else {
            coveredRectangles = coveredFine;
            suspectRectangles = suspectFine;
        }
        List<ToneLayer> layers = new ArrayList<>(2);
        addLayer(layers, coveredRectangles, coveredRgb);
        addLayer(layers, suspectRectangles, suspectRgb);
        return new OverlayBatch(generation, dimension, layers);
    }

    private static void addLayer(List<ToneLayer> layers, int[] rectangles, int rgb) {
        List<MapPolygonWithHoles> hulls = CoveragePolygons.hulls(rectangles);
        if (!hulls.isEmpty()) {
            layers.add(new ToneLayer(CoveragePolygons.toneStyle(rgb), hulls));
        }
    }

    private static int rectangleCount(int[] rectangles) {
        return rectangles.length / 4;
    }

    private void onDisconnect() {
        hideAll(api);
        mapping = false;
        lastGeneration = NO_GENERATION;
        lastDimension = null;
    }

    private record ToneLayer(ShapeProperties style, List<MapPolygonWithHoles> hulls) {}

    private static final class OverlayBatch {
        private final long generation;
        private final ResourceKey<Level> resourceKey;
        // Null when the off-thread build threw: the tick still releases the single-flight guard
        // and leaves the map as is.
        private final @Nullable List<ToneLayer> layers;

        OverlayBatch(long generation, ResourceKey<Level> resourceKey, @Nullable List<ToneLayer> layers) {
            this.generation = generation;
            this.resourceKey = resourceKey;
            this.layers = layers;
        }
    }
}
