// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.LiveCaptureSession;
import world.thearchive.wdl.adapter.OutlineRim;
import world.thearchive.wdl.adapter.OutlineTracker;
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
 * {@link #start} asserts the {@code isRemoteWorld()} entry condition {@code Wdl.startDownload} guards on, plus the
 * fixture precondition {@code !isLocalServer()} that makes this a real multiplayer connection, before recording begins,
 * so the suite checks the production activation gate's condition and not only the capture engine. That gate holds on
 * the dedicated-server connection the fixture provides; on a singleplayer integrated server's memory connection it
 * would not, turning the run red instead of silently passing.
 */
@SuppressWarnings("UnstableApiUsage")
final class CaptureDriver {
    private static final int SAVE_TIMEOUT_TICKS = 200;

    private final ClientGameTestContext context;
    private final CaptureController controller;
    private final DownloadTarget target;
    private final PlatformBridge bridge;
    private final AtomicReference<@Nullable Thread> pokeThread;

    private volatile WdlConfig liveConfig;

    private CaptureDriver(ClientGameTestContext context, CaptureController controller, DownloadTarget target,
            PlatformBridge bridge, WdlConfig config, AtomicReference<@Nullable Thread> pokeThread) {
        this.context = context;
        this.controller = controller;
        this.target = target;
        this.bridge = bridge;
        this.liveConfig = config;
        this.pokeThread = pokeThread;
    }

    static CaptureDriver start(ClientGameTestContext context, DownloadTarget target, WdlConfig config) {
        return start(context, target, config, false);
    }

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
        return new CaptureDriver(context, controller, target, bridge, config, pokeThread);
    }

    @Nullable
    Thread completionPokeThread() {
        return pokeThread.get();
    }

    /**
     * Call it before {@link #stopAndAwaitSave}, since the controller returns the empty set once idle.
     */
    boolean isCaptured(Predicate<CapturedContainers> query) {
        return context.computeOnClient(client -> query.test(controller.capturedContainers()));
    }

    /**
     * Not {@code Wdl.outlineDrawSet()}: that static draw-set is rebuilt only while the static {@code Wdl.controller} is
     * recording, and this driver records on its own controller, so the static tracker stays cleared for a driver-run
     * test's whole life.
     */
    List<AABB> entityRimBoxes() {
        return context.computeOnClient(client -> {
            OutlineTracker tracker = new OutlineTracker();
            tracker.useBobbyFilter(BobbyChunkFilter.resolve(bridge));
            tracker.tick(client.level, client.gameRenderer.getMainCamera().position(), liveConfig.outline(),
                    controller.aidToggles(liveConfig), controller.capturedContainers(), controller.recoveredCoverage());
            List<AABB> boxes = new ArrayList<>();
            for (List<OutlineRim> sectionRims : tracker.drawSet().sections().values()) {
                for (OutlineRim rim : sectionRims) {
                    if (rim.isEntityRim()) {
                        boxes.add(rim.box());
                    }
                }
            }
            return List.copyOf(boxes);
        });
    }

    /**
     * Commit a settings edit mid-download: the running session keeps the config it started with, and only the aid reads
     * see the new one.
     */
    void editSettings(WdlConfig edited) {
        liveConfig = edited;
    }

    long[] overlaySavedChunks(String dimensionId) {
        return controller.overlaySavedChunks(liveConfig, dimensionId);
    }

    long[] rawSavedChunks(String dimensionId) {
        return controller.savedChunks().snapshot(dimensionId);
    }

    long[] overlayCoveredChunks(String dimensionId) {
        return controller.overlayCoveredChunks(liveConfig, dimensionId);
    }

    int rangeRadiusChunks(String dimensionId, int capChunks) {
        return controller.sendRange().radiusChunks(dimensionId, capChunks);
    }

    boolean isCalibrated(String dimensionId) {
        return controller.sendRange().isCalibrated(dimensionId);
    }

    CaptureDriver tick(int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            context.runOnClient(client -> controller.tick());
            context.waitTick();
        }
        return this;
    }

    void tickUntilWindowExpired() {
        long ticks = SendRangeSampler.WINDOW_NANOS / 50_000_000L + 20;
        tick((int) ticks);
    }

    /**
     * Wire this driver's controller to the loader's disconnect event, the one line production runs at startup. Without
     * it a disconnect leaves the driver recording, because the driver deliberately runs its own controller.
     */
    void armDisconnectAutoStop() {
        bridge.onDisconnect(controller::onDisconnect);
    }

    Path awaitSaveAfterDisconnect() {
        boolean saved = false;
        for (int tick = 0; tick < SAVE_TIMEOUT_TICKS && !saved; tick++) {
            saved = context.computeOnClient(client -> {
                controller.tick();
                return controller.state() == CaptureState.IDLE;
            });
            if (!saved) {
                context.waitTick();
            }
        }
        if (!saved) {
            throw new AssertionError("capture '" + target.folderName() + "' did not finish saving after the "
                    + "disconnect within " + SAVE_TIMEOUT_TICKS + " ticks");
        }
        return context.computeOnClient(client -> client.getLevelSource().getBaseDir().resolve(target.folderName()));
    }

    Path stopAndAwaitSave() {
        return stopAndAwaitSave(true, "did not finish saving");
    }

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

    static Path capture(ClientGameTestContext context, DownloadTarget target, WdlConfig config, int captureTicks) {
        return start(context, target, config).tick(captureTicks).stopAndAwaitSave();
    }
}
