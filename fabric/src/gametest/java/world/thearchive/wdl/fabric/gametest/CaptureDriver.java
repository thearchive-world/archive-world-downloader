// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.LiveCaptureSession;
import world.thearchive.wdl.adapter.VersionAdapter;
import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
import world.thearchive.wdl.core.CaptureController;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.CapturedContainers;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.SendRangeSampler;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.fabric.FabricPlatformBridge;
import world.thearchive.wdl.platform.PlatformBridge;

/**
 * Drives a real capture lifecycle from a client game test the way the production client tick does: start a
 * {@link LiveCaptureSession} on its own {@link CaptureController}, pump the controller each client tick while
 * recording, stop, then await the asynchronous save, either polling the controller or (for the paused-replay case)
 * leaving the completion poke as its only route out. It goes through the real capture SPI (the same
 * {@code LiveCaptureSession} the keybind path builds), so what runs here is the production capture loop reading the
 * live {@code ClientLevel} and the inbound entity tee, not a direct call into the encoder.
 *
 * <p>{@link #start} asserts the {@code isRemoteWorld()} entry condition {@code Wdl.startDownload} guards on, plus the
 * fixture precondition {@code !isLocalServer()} that makes this a real multiplayer connection, before recording begins,
 * so the suite runs the production activation gate and not only the capture engine. That gate holds on the
 * dedicated-server connection the fixture provides; on a singleplayer integrated server's memory connection it would
 * not, turning the run red instead of silently passing. It diverges from {@code Wdl.startDownload} in one mechanical
 * respect: the driver runs the session on its own {@link CaptureController} with an explicit {@link DownloadTarget}, so
 * a test starts, stops, and awaits the save deterministically, where production uses a static singleton controller and
 * server-name folder resolution (adding a shipped-jar stop entry point for the test is out of scope).
 *
 * <p>It is a handle, not a one-shot, so a test can record, perform a server action mid-capture (such as spawning an
 * entity so the inbound packet tee, not the loaded-chunk prime, captures it), record more, then stop. {@link #capture}
 * is the one-shot convenience for the simple record-then-save case.
 */
@SuppressWarnings("UnstableApiUsage")
final class CaptureDriver {
    /** Client ticks to wait for the background writer to finish after stop before declaring the save stuck. */
    private static final int SAVE_TIMEOUT_TICKS = 200;

    private final ClientGameTestContext context;
    private final CaptureController controller;
    private final DownloadTarget target;
    private final AtomicReference<@Nullable Thread> pokeThread;

    // What the settings currently say, which production keeps in Wdl.currentConfig and refreshes when the settings
    // screen closes; the session went on capturing under whatever it was handed at start.
    private volatile WdlConfig liveConfig;

    private CaptureDriver(ClientGameTestContext context, CaptureController controller, DownloadTarget target,
            WdlConfig config, AtomicReference<@Nullable Thread> pokeThread) {
        this.context = context;
        this.controller = controller;
        this.target = target;
        this.liveConfig = config;
        this.pokeThread = pokeThread;
    }

    /** Begin recording {@code target} from the connected world; pump it with {@link #tick} and end with stop. */
    static CaptureDriver start(ClientGameTestContext context, DownloadTarget target, WdlConfig config) {
        // No map overlay mod is on the gametest classpath (XaeroPlus hard-depends on Xaero's maps, whose startup
        // modal blocks a headless run), so the overlay is inert here unless a test explicitly arms it.
        return start(context, target, config, false);
    }

    /**
     * As {@link #start(ClientGameTestContext, DownloadTarget, WdlConfig)}, forcing {@code overlayActive} so a test can
     * exercise the coverage overlay resume seed even though no map overlay mod is on the gametest classpath.
     */
    static CaptureDriver start(ClientGameTestContext context, DownloadTarget target, WdlConfig config,
            boolean overlayActive) {
        CaptureController controller = new CaptureController();
        PlatformBridge bridge = new FabricPlatformBridge();
        AtomicReference<@Nullable Thread> pokeThread = new AtomicReference<>();
        // Record where the completion poke lands, not just that it landed. The state a poke produces is
        // thread-blind, so without this an executor that runs the poke on the writer's background thread
        // would leave every state assertion green and the marshal seam unpinned at its call site.
        Runnable poke = () -> {
            pokeThread.set(Thread.currentThread());
            controller.tick();
        };
        context.runOnClient(client -> {
            Check.that(!Minecraft.getInstance().isLocalServer(), "activation gate fixture check failed: the "
                    + "fixture must be a real multiplayer connection, not a local server");
            Check.that(bridge.isRemoteWorld(), "activation gate failed: the fixture is not a remote world");
            VersionAdapter adapter = ServiceLoader.load(VersionAdapter.class, Wdl.class.getClassLoader())
                    .findFirst().orElseThrow();
            controller.start(() -> new LiveCaptureSession(adapter, bridge, config, client.level, target,
                    controller.savedChunks(), controller.coveredChunks(), controller.sendRange(), overlayActive,
                    client.getCameraEntity() != client.player, BobbyChunkFilter.resolve(bridge), poke));
        });
        return new CaptureDriver(context, controller, target, config, pokeThread);
    }

    /**
     * The thread the save-completion poke last ran on, or null when it has not run yet. Lets a test that withholds the
     * controller tick assert the poke was marshaled onto the client thread rather than merely observing that the
     * controller reached idle.
     */
    @Nullable
    Thread completionPokeThread() {
        return pokeThread.get();
    }

    /**
     * Evaluate {@code query} against the live captured-set on the client thread, for asserting the content-gate while
     * still recording. The query runs on the client thread because the published view wraps the session's live sets,
     * which only that thread may read; call it before {@link #stopAndAwaitSave}, since the controller returns the empty
     * set once idle.
     */
    boolean isCaptured(Predicate<CapturedContainers> query) {
        return context.computeOnClient(client -> query.test(controller.capturedContainers()));
    }

    /**
     * Commit a settings edit the way closing the settings screen does mid-download: the running session keeps the
     * config it started with, and only the aid reads see the new one.
     */
    void editSettings(WdlConfig edited) {
        liveConfig = edited;
    }

    /** The saved-chunk overlay snapshot, read off the client thread on purpose as an overlay provider does. */
    long[] overlaySavedChunks(String dimensionId) {
        return controller.overlaySavedChunks(liveConfig, dimensionId);
    }

    /**
     * The raw saved-chunk index snapshot, bypassing the {@code renderCoverageOverlay} gate that
     * {@link #overlaySavedChunks} applies, so a test can assert the index actually filled during capture independently
     * of the overlay read (proving the gate hides a populated index, not a trivially empty one).
     */
    long[] rawSavedChunks(String dimensionId) {
        return controller.savedChunks().snapshot(dimensionId);
    }

    /** The covered-chunk overlay snapshot, through every gate {@link Wdl#overlayCoveredChunks} applies. */
    long[] overlayCoveredChunks(String dimensionId) {
        return controller.overlayCoveredChunks(liveConfig, dimensionId);
    }

    /** The estimator's covered radius for the dimension, chunk units, clamped by capChunks. */
    int rangeRadiusChunks(String dimensionId, int capChunks) {
        return controller.sendRange().radiusChunks(dimensionId, capChunks);
    }

    /** Whether the estimator has accepted any send-range sample for the dimension yet. */
    boolean isCalibrated(String dimensionId) {
        return controller.sendRange().isCalibrated(dimensionId);
    }

    /** Advance {@code ticks} client ticks, pumping the controller each one as the production tick hook does. */
    CaptureDriver tick(int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            context.runOnClient(client -> controller.tick());
            context.waitTick();
        }
        return this;
    }

    /** Pump capture ticks until the sampler's start/anomaly window has expired, plus margin. */
    void tickUntilWindowExpired() {
        long ticks = SendRangeSampler.WINDOW_NANOS / 50_000_000L + 20;
        tick((int) ticks);
    }

    /** Stop recording and pump until the background writer reports done; returns the save root on disk. */
    Path stopAndAwaitSave() {
        return stopAndAwaitSave(true, "did not finish saving");
    }

    /**
     * Stop recording and await the save WITHOUT ever ticking this driver's controller, so the only route to IDLE is the
     * marshaled completion poke drained by runAllTasks (the paused-replay decoupling). Returns the save root on disk.
     */
    Path stopAndAwaitSaveWithoutTick() {
        return stopAndAwaitSave(false, "did not reach IDLE via the completion poke");
    }

    private Path stopAndAwaitSave(boolean tickController, String timeoutReason) {
        context.runOnClient(client -> controller.stop());
        boolean saved = false;
        for (int tick = 0; tick < SAVE_TIMEOUT_TICKS && !saved; tick++) {
            saved = context.computeOnClient(client -> {
                if (tickController) {
                    controller.tick();
                }
                return controller.state() == CaptureState.IDLE;
            });
            if (!saved) {
                context.waitTick();
            }
        }
        if (!saved) {
            throw new AssertionError("capture '" + target.folderName() + "' " + timeoutReason + " within "
                    + SAVE_TIMEOUT_TICKS + " ticks");
        }
        return context.computeOnClient(client -> client.getLevelSource().getBaseDir().resolve(target.folderName()));
    }

    /** One-shot: record {@code captureTicks} then save. The simple record-then-save case (no mid-capture action). */
    static Path capture(ClientGameTestContext context, DownloadTarget target, WdlConfig config, int captureTicks) {
        return start(context, target, config).tick(captureTicks).stopAndAwaitSave();
    }
}
