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
import journeymap.client.api.model.MapPolygon;
import journeymap.client.api.model.ShapeProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.dimension.DimensionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.ChunkRectangleReducer;
import world.thearchive.wdl.core.OverlayHighlights;
import world.thearchive.wdl.platform.PlatformBridge;

/**
 * Pushes the wdl covered-area overlay onto JourneyMap and keeps it in step with the session. Owned by
 * {@link WdlJourneyMapPlugin}: the plugin refreshes the API handle on each JourneyMap init, then asks this driver to
 * subscribe to mapping events and to wire the loader tick and disconnect hooks. JourneyMap's {@code MAPPING_EVENT}
 * subscription is permanent, with no unsubscribe, so the subscribe is guarded to run at most once; the tick and
 * disconnect wiring is likewise one-shot, since it happens on whichever of the wdl init and the plugin init completes
 * second.
 *
 * <p>Threading: the client tick reads the coverage facade and applies finished geometry, both on the client main
 * thread. Each rebuild is handed to a single background worker (one at a time) that reduces the chunk sets to
 * rectangles and builds their polygons, then parks the result in {@link #pendingBatch} for the next tick to show. The
 * mapping and disconnect callbacks run on the client thread as well, so the overlay removals they issue are safe.
 */
public final class JourneyMapOverlayDriver {
    private static final Logger LOGGER = LogManager.getLogger(JourneyMapOverlayDriver.class);

    private static final String MOD_ID = "wdl";

    // A sentinel below every real generation (a sum of two non-negative version counters), so the first tick and
    // every forced re-assert compare unequal and rebuild.
    private static final long NO_GENERATION = -1L;

    // The fine reducer runs unbounded for the common compact download; past this many rectangles in either tone
    // the two tones are coarsened together onto one grid instead, bounding the polygon work for a pathologically
    // fragmented coverage. The joint coarsen grid is floor(sqrt) per axis, so this squares to an exact cell bound.
    private static final int MAX_RECTANGLES = 4096;

    // Refreshed by setApi on each JourneyMap init so the callbacks always read the live handle; never null once
    // an init has run, a lifecycle NullAway cannot model.
    @SuppressWarnings("NullAway.Init")
    private volatile IClientAPI api;

    // One reused daemon thread: single-flight already serializes the rebuilds, and a daemon needs no shutdown.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "wdl-journeymap-overlay");
        thread.setDaemon(true);
        return thread;
    });

    private boolean wired;      // wireOnce guard
    private boolean subscribed;

    // True between JourneyMap MAPPING_STARTED and MAPPING_STOPPED. The tick never rebuilds while false. Volatile
    // because it is set from the mapping callback and read from the tick.
    private volatile boolean mapping;

    // Whether overlays are on the map, so the stopped and not-recording paths clear them exactly once. Client
    // thread only.
    private boolean shown;

    // Rebuild debounce: the overlay rebuilds at most once per this many client ticks, coalescing the rapid
    // coverage changes of active movement into one remove-and-show so it does not redraw every tick. About a half
    // second at twenty ticks per second, near the sibling provider's own refresh cadence. clientTicks counts every
    // tick; lastDispatchTick is when the last rebuild was dispatched, seeded one interval in the past so the first
    // change dispatches at once. Both stay small and non-negative, so their difference never overflows. Client
    // thread only.
    private static final long REBUILD_DEBOUNCE_TICKS = 10L;
    private long clientTicks;
    private long lastDispatchTick = -REBUILD_DEBOUNCE_TICKS;

    // The last generation and dimension the tick acted on, to skip a rebuild when neither changed. Client thread.
    private long lastGeneration = NO_GENERATION;
    private @Nullable DimensionType lastDimension;

    // The highest generation whose geometry has been applied, so a late worker result carrying an older
    // generation is discarded. Client thread.
    private long lastAppliedGeneration = NO_GENERATION;

    // Single-flight: one worker builds at a time. Set at dispatch, cleared when its result is consumed. Client
    // thread.
    private boolean workerInFlight;

    // Set when a change arrives while a worker is building, so exactly one rebuild follows the current one.
    private boolean rerunPending;

    // The worker's finished geometry, handed back for the next tick to show. Volatile: written on the worker,
    // read on the client thread. A batch whose layers are null means the build threw, and the map is left as is.
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
        // Registering off the client thread is fine: both loaders guard their listener lists, Fabric's event
        // register under a monitor with a volatile invoker and NeoForge's bus under a write lock. The guard above
        // is not a memory barrier, so it only holds for one caller; JourneyMap initializing the plugin twice
        // would double these registrations, which costs a halved rebuild debounce and nothing else.
        bridge.onClientTickEnd(this::onClientTick);
        bridge.onDisconnect(this::onDisconnect);
    }

    void onClientEvent(ClientEvent event) {
        if (event.type == ClientEvent.Type.MAPPING_STARTED) {
            mapping = true;
            // Force the next tick to rebuild from scratch for the freshly mapped world.
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
            DimensionType dimension = mc.level.getDimension().getType();
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

    /**
     * Consume a finished worker result on the client thread: release the single-flight guard, show the geometry when it
     * is still current and the session is recording, and re-arm one more rebuild if a change arrived while the worker
     * ran.
     */
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
            // Invalidate the skip guard so the change that arrived mid-build rebuilds below with fresh data.
            lastGeneration = NO_GENERATION;
        }
    }

    /**
     * Clear the previous polygons and show each tone's hulls as fresh overlays every rebuild. Reusing and mutating the
     * retained overlay instances in place, rather than removing and re-showing, did not update the drawn map and left
     * it frozen at the first geometry, and the tone's hull count varies per rebuild anyway, so a clear and re-show is
     * the reliable path. A failing overlay is isolated from the rest of the batch.
     */
    private void applyBatch(IClientAPI localApi, DimensionType dimension, List<ToneLayer> layers,
            long generation) {
        localApi.removeAll(MOD_ID, DisplayType.Polygon);
        int dimensionId = dimension.getId();
        int count = 0;
        for (ToneLayer layer : layers) {
            for (MapPolygon polygon : layer.polygons()) {
                PolygonOverlay overlay = new PolygonOverlay(MOD_ID, "wdl-coverage-" + count, dimensionId,
                        layer.style(), polygon);
                showQuietly(localApi, overlay);
                count++;
            }
        }
        LOGGER.debug("wdl coverage overlay: showed {} polygons at generation {}", count, generation);
    }

    /** Show one overlay, isolating a checked-throwing failure or a degenerate hull rejected in construction. */
    private static void showQuietly(IClientAPI localApi, PolygonOverlay overlay) {
        try {
            localApi.show(overlay);
        } catch (Throwable e) {
            LOGGER.warn("failed to show a wdl coverage overlay polygon", e);
        }
    }

    /** Remove every shown polygon exactly once, so the not-recording and stopped paths do not repeat the call. */
    private void hideAll(IClientAPI localApi) {
        if (!shown) {
            return;
        }
        localApi.removeAll(MOD_ID, DisplayType.Polygon);
        shown = false;
    }

    private void dispatch(long generation, DimensionType dimension) {
        String dimensionId = DimensionType.getName(dimension).toString();
        // Snapshot saved strictly before covered: the facade mirrors the covered read onto the saved set until
        // the send range is calibrated, and this order is what lets that mirror yield an empty suspect set rather
        // than a persistent cold-start suspect ring.
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

    /** Off-thread geometry: partition the saved set into tones, reduce each to rectangles, and trace polygons. */
    private static OverlayBatch buildBatch(long generation, DimensionType dimension, long[] saved, long[] covered,
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
        List<MapPolygon> polygons = CoveragePolygons.polygons(rectangles);
        if (!polygons.isEmpty()) {
            layers.add(new ToneLayer(CoveragePolygons.toneStyle(rgb), polygons));
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

    private static final class ToneLayer {
        private final ShapeProperties style;
        private final List<MapPolygon> polygons;

        ToneLayer(ShapeProperties style, List<MapPolygon> polygons) {
            this.style = style;
            this.polygons = polygons;
        }

        ShapeProperties style() {
            return style;
        }

        List<MapPolygon> polygons() {
            return polygons;
        }
    }

    private static final class OverlayBatch {
        private final long generation;
        private final DimensionType resourceKey;
        // Null when the off-thread build threw: the tick then releases the single-flight guard without disturbing
        // the polygons already on the map.
        private final @Nullable List<ToneLayer> layers;

        OverlayBatch(long generation, DimensionType resourceKey, @Nullable List<ToneLayer> layers) {
            this.generation = generation;
            this.resourceKey = resourceKey;
            this.layers = layers;
        }
    }
}
