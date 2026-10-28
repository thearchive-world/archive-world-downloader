// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import world.thearchive.wdl.adapter.CompletionMarshal;
import world.thearchive.wdl.adapter.ConnectionTee;
import world.thearchive.wdl.adapter.LiveCaptureSession;
import world.thearchive.wdl.adapter.OutlineDrawSet;
import world.thearchive.wdl.adapter.OutlineTracker;
import world.thearchive.wdl.adapter.VersionAdapter;
import world.thearchive.wdl.client.WdlDownloadsScreen;
import world.thearchive.wdl.client.WdlSettingsScreen;
import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
import world.thearchive.wdl.compat.xaeroplus.XaeroPlusIntegration;
import world.thearchive.wdl.core.AtomicFileWrite;
import world.thearchive.wdl.core.CaptureController;
import world.thearchive.wdl.core.CaptureCounts;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.CaptureToggleGuard;
import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.ConfigSchema;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.ReadyLatch;
import world.thearchive.wdl.core.SaveFailureComposer;
import world.thearchive.wdl.core.SaveFailureReason;
import world.thearchive.wdl.core.SaveStage;
import world.thearchive.wdl.core.ToastCopy;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.browse.DownloadCatalog;
import world.thearchive.wdl.core.browse.DownloadEntry;
import world.thearchive.wdl.core.browse.DownloadFolders;
import world.thearchive.wdl.core.browse.SinglePlayerTaint;
import world.thearchive.wdl.core.browse.TargetResolver;
import world.thearchive.wdl.core.export.RestoreOperation;
import world.thearchive.wdl.core.export.RestoreSource;
import world.thearchive.wdl.core.update.SemVer;
import world.thearchive.wdl.platform.PlatformBridge;
import world.thearchive.wdl.platform.WdlCommands;
import world.thearchive.wdl.update.HttpTransport;
import world.thearchive.wdl.update.RuntimeInfo;
import world.thearchive.wdl.update.UpdateAvailable;
import world.thearchive.wdl.update.UpdateCheck;

public final class Wdl {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Set once by initialize() from the loader entrypoint, so the uninitialized-field check is suppressed on
    // these two.
    @SuppressWarnings("NullAway.Init")
    private static VersionAdapter adapter;

    @SuppressWarnings("NullAway.Init")
    private static PlatformBridge bridge;

    private static final ReadyLatch readyLatch = new ReadyLatch();

    private static final CaptureController controller = new CaptureController();

    private static final UpdateCheck updateCheck = new UpdateCheck();

    private static final Executor warmupWorker = daemonWorker("wdl-worldgen-warmup");

    private static final OutlineTracker outlineTracker = new OutlineTracker();

    private static BobbyChunkFilter bobbyFilter = BobbyChunkFilter.INACTIVE;

    // Volatile because the client thread writes it while XaeroPlus's cache-refresh executor reads it; the config
    // objects are deeply immutable, so publishing the reference is all this needs.
    private static volatile WdlConfig currentConfig = WdlConfig.DEFAULTS;

    private static @Nullable String activeDownloadName;

    // A screen opened synchronously from a chat command is clobbered by ChatScreen's post-dispatch setScreen(null)
    // unless the loader guards that close. Open it on the next client tick instead, after that close has run.
    private static @Nullable Runnable pendingScreenOpen;

    // Constructed by initialize() once the controller and bridge are live.
    @SuppressWarnings("NullAway.Init")
    private static ResumeFlow resumeFlow;

    // The live restore worker's state, volatile because the permanent shutdown hook reads it from its own thread: the
    // operation for abort and the swap-window read, the worker for the bounded join.
    private static volatile @Nullable RestoreOperation activeRestore;
    private static volatile @Nullable Thread restoreWorker;
    private static volatile @Nullable CompletableFuture<RestoreOperation.Result> restoreResult;

    private static @Nullable String restoreFolderName;

    private static volatile @Nullable CompletableFuture<RestoreOperation.RestoreSweep.SweepResult> sweepResult;

    private static boolean lastRestoreChangedDisk = true;

    private Wdl() {}

    /** The current MC version. */
    public static String mcVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    /**
     * Run {@code runnable} once the platform bridge is initialized (immediately if it already is).
     */
    public static void runWhenReady(Runnable runnable) {
        readyLatch.runWhenReady(runnable);
    }

    /** The loader platform bridge. */
    public static PlatformBridge platformBridge() {
        return bridge;
    }

    /**
     * Wire the capture controller to the loader hooks.
     */
    public static void initialize(PlatformBridge platformBridge) {
        // Route core's java.util.logging into latest.log first, so a fail-soft warning from config load or any
        // later core step is visible (the MC runtime does not forward JUL to the log on its own).
        CoreLogHandler.install();
        bridge = platformBridge;
        bobbyFilter = BobbyChunkFilter.resolve(bridge);
        outlineTracker.useBobbyFilter(bobbyFilter);
        adapter = ServiceLoader.load(VersionAdapter.class, Wdl.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No VersionAdapter service is registered"));

        LOGGER.info("loaded on Minecraft {}: adapter={}, bridge={}",
                mcVersion(), adapter.getClass().getSimpleName(), bridge.getClass().getSimpleName());

        currentConfig = WdlConfig.load(configPath());
        LOGGER.info("config file: {}", configPath());
        dispatchUpdateCheck(currentConfig);
        resumeFlow = new ResumeFlow(controller, bridge, () -> WdlConfig.load(configPath()),
                Wdl::deferScreen, Wdl::startDownload);

        bridge.registerToggleKeybind(Wdl::onToggle);
        bridge.registerDownloadsKeybind(Wdl::openDownloadsScreen);
        bridge.addPauseMenuButtons(Wdl::pausePrimaryLabelKey, Wdl::isPausePrimaryEnabled, Wdl::onPausePrimary,
                Wdl::openSettingsScreen);
        bridge.onClientTickEnd(Wdl::onClientTick);
        bridge.onDisconnect(controller::onDisconnect);
        // Where the version has a configuration phase, a backend transfer re-enters it and fires no disconnect hook, so
        // the tee raises its own signal and the controller polls it each tick, stopping the download.
        controller.setTransferStopPoll(ConnectionTee::consumeTransferSignal);
        bridge.onServerJoin(controller::onServerJoin);
        bridge.onServerJoin(Wdl::onServerJoin);
        bridge.onServerJoin(Wdl::onUpdateAvailableJoin);
        bridge.registerCommands(new WdlCommands(Wdl::onStart, Wdl::onStartNamed, Wdl::onStop, Wdl::onStatus,
                Wdl::onConfig, Wdl::onResume, Wdl::openDownloadsFromCommand));
        Runtime.getRuntime().addShutdownHook(new Thread(Wdl::abortRestoreOnShutdown, "wdl-restore-shutdown"));
        XaeroPlusIntegration.initialize(bridge);
        readyLatch.markReadyAndDrain();
    }

    /**
     * Abort any live restore so the worker backs out at its next phase check, and only while the worker is inside the
     * two-rename swap window wait for it, bounded to three seconds, so a quitting client gives the swap that long to
     * finish rather than abandoning the folder mid-swap.
     */
    private static void abortRestoreOnShutdown() {
        RestoreOperation operation = activeRestore;
        if (operation == null) {
            return;
        }
        operation.abort();
        Thread worker = restoreWorker;
        if (worker == null) {
            return;
        }
        long start = System.nanoTime();
        long timeoutNanos = TimeUnit.SECONDS.toNanos(3);
        try {
            while (operation.inSwapWindow() && worker.isAlive() && System.nanoTime() - start < timeoutNanos) {
                worker.join(50L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void onToggle() {
        CaptureState state = controller.state();
        if (state != CaptureState.IDLE) {
            if (currentConfig.showChatMessages()) {
                bridge.sendChat(state == CaptureState.RECORDING
                        ? ChatCopy.saving()
                        : ChatCopy.busy(state, isSweepActive()));
            }
            controller.stop();
            return;
        }
        // The isRemoteWorld conjunct is load-bearing here: without it a keybind press in a genuine singleplayer world
        // would answer with the needs-a-name refusal instead of falling through to the join-a-server refusal.
        if (bridge.isRemoteWorld() && !hasSourceIdentity()) {
            bridge.sendChat(ChatCopy.startNeedsName());
            return;
        }
        resumeFlow.begin(defaultBaseName(), true);
    }

    /**
     * The config is re-read so a hand-edit applies.
     */
    private static void onServerJoin() {
        if (!WdlConfig.load(configPath()).worldOutput().autoDownload()) {
            return;
        }
        CaptureState state = controller.state();
        if (state == CaptureState.RESTORING && bridge.isRemoteWorld()) {
            bridge.sendChat(ChatCopy.busy(state, isSweepActive()));
            return;
        }
        if (state != CaptureState.IDLE || !bridge.isRemoteWorld()) {
            return;
        }
        // Must stay below the gate above: this hook also fires for the integrated server, so hoisting it would speak on
        // every genuine singleplayer world load while autoDownload is on.
        if (!hasSourceIdentity()) {
            bridge.sendChat(ChatCopy.startNeedsName());
            return;
        }
        resumeFlow.begin(defaultBaseName(), false);
    }

    private static void dispatchUpdateCheck(WdlConfig config) {
        String runningRaw = bridge.modVersion();
        Optional<SemVer> running = SemVer.parse(runningRaw);
        String modrinthId = modrinthId();
        if (running.isEmpty() || modrinthId.isEmpty()) {
            return;
        }
        RuntimeInfo info = new RuntimeInfo(bridge.loaderName().toLowerCase(Locale.ROOT), mcVersion(),
                running.get(), runningRaw, modrinthId);
        updateCheck.dispatch(info, config, new HttpTransport(), daemonWorker("wdl-update-check"));
    }

    private static Executor daemonWorker(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.start();
        };
    }

    private static String modrinthId() {
        try (InputStream stream = Wdl.class.getResourceAsStream("/wdl-publishing.properties")) {
            if (stream == null) {
                return "";
            }
            Properties properties = new Properties();
            properties.load(stream);
            return properties.getProperty("modrinthId", "").trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Second join hook, deliberately separate from {@link #onServerJoin()} (which early-returns when
     * {@code autoDownload} is off). The config is re-read so a hand-edit applies.
     */
    private static void onUpdateAvailableJoin() {
        Optional<UpdateAvailable> available = updateCheck.available();
        if (available.isEmpty()) {
            return;
        }
        if (!updateCheck.consumeChatPending(WdlConfig.load(configPath()).showChatMessages())) {
            return;
        }
        UpdateAvailable update = available.get();
        bridge.sendChat(ChatCopy.updateAvailable(update.runningDisplay(), update.latestDisplay(),
                UpdateCheck.MODRINTH_PAGE_URL, UpdateCheck.CURSEFORGE_PAGE_URL));
    }

    /**
     * Re-loads the config so hand-edits apply on the next download.
     */
    private static void startDownload(DownloadTarget target) {
        CaptureState state = controller.state();
        if (state != CaptureState.IDLE) {
            sendRefusal(target.origin(), ToastCopy.busy(state, isSweepActive()),
                    ChatCopy.busy(state, isSweepActive()));
            return;
        }
        if (!bridge.isRemoteWorld()) {
            sendRefusal(target.origin(), ToastCopy.joinMultiplayer(), ChatCopy.joinMultiplayer());
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        WdlConfig config = WdlConfig.load(configPath());
        currentConfig = config;
        Path savesDirectory = minecraft.getLevelSource().getBaseDir();
        if (target.mode() == DownloadMode.NEW
                && RestoreOperation.attemptReferences(savesDirectory, target.folderName())) {
            // A torn restore attempt still stages this name under the temporary root; a NEW download landing on it
            // would race the next sweep's roll-back.
            sendRefusal(target.origin(), ToastCopy.refuseTornAttempt(), ChatCopy.refuseTornAttempt());
            return;
        }
        if (target.mode() == DownloadMode.RESUME) {
            Path saveFolder = savesDirectory.resolve(target.folderName());
            if (!DownloadFolders.isWdlManaged(saveFolder)) {
                // The managed re-test ahead of the taint re-test: the folder may have changed since the confirm cascade
                // classified it.
                if (Files.exists(saveFolder)) {
                    boolean suggestRename = target.origin() != DownloadTarget.Origin.SCREEN;
                    sendRefusal(target.origin(), ToastCopy.refuseOccupant(target.folderName(), suggestRename),
                            ChatCopy.refuseOccupant(target.folderName(), suggestRename));
                } else {
                    sendRefusal(target.origin(), ToastCopy.refuseFolderMissing(target.folderName()),
                            ChatCopy.refuseFolderMissing(target.folderName()));
                }
                return;
            }
            // Only REFUSE stops here: the resume paths all ran this cascade, and there is no prompt left to show.
            if (SinglePlayerTaint.decide(SinglePlayerTaint.classify(saveFolder),
                    config.blockTaintedResume()) == SinglePlayerTaint.Decision.REFUSE) {
                sendRefusal(target.origin(), ToastCopy.refuseTainted(), ChatCopy.refuseTainted());
                return;
            }
        }
        activeDownloadName = target.worldName() != null ? target.worldName() : target.folderName();
        // State-independent of captureEntities, so a signal raised between downloads is discarded for every
        // download kind, not only when an entity capture activates.
        ConnectionTee.clearTransferSignal();
        controller.start(() -> new LiveCaptureSession(adapter, bridge, config, level, target,
                controller.savedChunks(), controller.coveredChunks(), controller.sendRange(),
                bridge.isModLoaded("xaeroplus") || bridge.isModLoaded("journeymap"),
                minecraft.getCameraEntity() != minecraft.player, bobbyFilter,
                controller::tick));
        if (config.showChatMessages()) {
            bridge.sendChat(target.mode() == DownloadMode.RESUME
                    ? ChatCopy.resuming(target.folderName())
                    : ChatCopy.downloading(target.folderName()));
            if (CaptureToggleGuard.isCapturePartiallyDisabled(config)) {
                bridge.sendChat(ChatCopy.capturePartiallyDisabled());
            }
        }
    }

    private static void sendRefusal(DownloadTarget.Origin origin, ToastCopy toast, ChatCopy chat) {
        if (DownloadTarget.refusalUsesToast(origin)) {
            bridge.sendToast(toast);
        } else {
            bridge.sendChat(chat);
        }
    }

    static boolean isSweepActive() {
        return sweepResult != null;
    }

    /**
     * A controller that is not idle refuses with the busy toast.
     */
    public static void launchRestore(Path savesDirectory, String folderName, RestoreSource pinnedSource) {
        if (!controller.tryBeginRestoring()) {
            bridge.sendToast(ToastCopy.busy(controller.state(), isSweepActive()));
            return;
        }
        RestoreOperation operation = RestoreOperation.create(savesDirectory, folderName, pinnedSource,
                WdlConfig.load(configPath()).zipOnResume());
        operation.publishLoadedWorld(loadedWorldPath(Minecraft.getInstance()));
        CompletableFuture<RestoreOperation.Result> result = new CompletableFuture<>();
        activeRestore = operation;
        restoreResult = result;
        restoreFolderName = folderName;
        // RestoreOperation.run is documented never-throws, but an Error escaping its own catch must still complete the
        // future or the controller stays RESTORING forever.
        Thread worker = new Thread(() -> {
            try {
                result.complete(operation.run());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        }, "wdl-restore");
        worker.setDaemon(true);
        restoreWorker = worker;
        // Attach the marshal before starting the worker: attached afterwards it could meet an already-finished worker
        // and run the poke inline on the dispatching stack.
        CompletionMarshal.scheduleCompletionPoke(result, Minecraft.getInstance(), Wdl::restoreTick);
        try {
            worker.start();
        } catch (Throwable e) {
            // The dispatch-failure flip-back: a worker that never started leaves nothing to poll, so the
            // controller must not stay RESTORING forever.
            activeRestore = null;
            restoreWorker = null;
            restoreResult = null;
            restoreFolderName = null;
            controller.endRestoring();
            LOGGER.error("restore worker for {} failed to start", folderName, e);
        }
    }

    public static void launchSweep(Path savesDirectory) {
        if (!controller.tryBeginRestoring()) {
            bridge.sendToast(ToastCopy.busy(controller.state(), isSweepActive()));
            return;
        }
        CompletableFuture<RestoreOperation.RestoreSweep.SweepResult> result = new CompletableFuture<>();
        sweepResult = result;
        // Unlike RestoreOperation.run, RestoreSweep.run carries no never-throws contract; an escaped throw
        // must still complete the future or the controller stays RESTORING forever.
        Thread worker = new Thread(() -> {
            try {
                result.complete(RestoreOperation.RestoreSweep.run(savesDirectory));
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        }, "wdl-restore-sweep");
        worker.setDaemon(true);
        CompletionMarshal.scheduleCompletionPoke(result, Minecraft.getInstance(), Wdl::restoreTick);
        try {
            worker.start();
        } catch (Throwable e) {
            sweepResult = null;
            controller.endRestoring();
            LOGGER.error("restore sweep worker failed to start", e);
        }
    }

    private static void openDownloadsScreen() {
        deferScreen(() -> showDownloadsScreen(false));
    }

    private static void openDownloadsFromCommand() {
        deferScreen(() -> showDownloadsScreen(true));
    }

    /**
     * The pause button is not chat-originated, so it needs none of the tick deferral the command path uses to survive
     * the post-dispatch chat close; opening inline is what lets it work while a replay's paused timer has suspended the
     * game tick.
     */
    private static void openDownloadsScreenNow() {
        // The inline open consumes the deferred slot the same way a deferred open would. The slot holds any parked
        // action, a resume start or a confirm as much as a plain open, so this can discard one.
        pendingScreenOpen = null;
        showDownloadsScreen(false);
    }

    private static void showDownloadsScreen(boolean expandExistingList) {
        Minecraft minecraft = Minecraft.getInstance();
        Path savesDirectory = minecraft.getLevelSource().getBaseDir();
        Path loadedWorld = loadedWorldPath(minecraft);
        Supplier<List<DownloadEntry>> entries = () -> {
            try {
                return DownloadCatalog.list(savesDirectory, loadedWorld);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("failed to list the downloads for the screen", e);
                return List.of();
            }
        };
        WdlConfig config = WdlConfig.load(configPath());
        WorldgenWarmup.dispatchForScreenOpen(config.worldOutput().worldType(),
                adapter.levelDataWriter()::warmWorldgen, warmupWorker);
        minecraft.gui.setScreen(new WdlDownloadsScreen(minecraft.gui.screen(), savesDirectory, loadedWorld, entries,
                expandExistingList, defaultDownloadName(minecraft), config.appendDateSuffix(), config.confirmResume(),
                config.blockTaintedResume(), config.zipOnResume(), config.remapMapIds(),
                CaptureToggleGuard.isCapturePartiallyDisabled(config), bridge.modVersion(), mcVersion(),
                Wdl::startDownload, Wdl::state, controller::stop,
                bridge::sendToast, bridge::isRemoteWorld, activeDownloadName));
    }

    /**
     * Advance the controller each tick, then consume any pending screen open. The slot is cleared before the open runs,
     * so a re-entrant defer during the open is preserved rather than dropped.
     */
    private static void onClientTick() {
        controller.tick();
        outlineTick();
        restoreTick();
        Runnable open = pendingScreenOpen;
        if (open != null) {
            pendingScreenOpen = null;
            open.run();
        }
    }

    private static void restoreTick() {
        RestoreOperation operation = activeRestore;
        if (operation != null) {
            operation.publishLoadedWorld(loadedWorldPath(Minecraft.getInstance()));
            CompletableFuture<RestoreOperation.Result> result = restoreResult;
            if (result == null || !result.isDone()) {
                return;
            }
            String folderName = restoreFolderName != null ? restoreFolderName : "";
            activeRestore = null;
            restoreWorker = null;
            restoreResult = null;
            restoreFolderName = null;
            controller.endRestoring();
            lastRestoreChangedDisk = true;
            try {
                surfaceRestoreOutcome(result.join(), folderName);
            } catch (CompletionException e) {
                LOGGER.error("the restore of {} failed unexpectedly", folderName, e);
            }
            return;
        }
        CompletableFuture<RestoreOperation.RestoreSweep.SweepResult> sweep = sweepResult;
        if (sweep == null || !sweep.isDone()) {
            return;
        }
        sweepResult = null;
        controller.endRestoring();
        try {
            RestoreOperation.RestoreSweep.SweepResult result = sweep.join();
            lastRestoreChangedDisk = result.changedDisk();
            surfaceSweepOutcome(result);
        } catch (CompletionException e) {
            lastRestoreChangedDisk = true; // unknown mutation, so fail toward the refresh
            LOGGER.error("the restore sweep failed unexpectedly", e);
        }
    }

    private static void surfaceRestoreOutcome(RestoreOperation.Result result, String folderName) {
        switch (result.outcome()) {
            case RESTORED -> bridge.sendToast(ToastCopy.restored(folderName));
            case RESTORED_WITH_REMNANTS -> {
                LOGGER.warn("restore of {} left remnants under {}; the next sweep cleans them up",
                        folderName, RestoreOperation.TEMPORARY_ROOT);
                bridge.sendToast(ToastCopy.restored(folderName));
            }
            case RELOCATED -> {
                Path sibling = Objects.requireNonNull(result.relocatedTo(), "RELOCATED names its sibling");
                bridge.sendToast(ToastCopy.restoreRefusedRelocated(folderLabel(sibling)));
            }
            case ABORTED -> LOGGER.info("restore of {} aborted by shutdown", folderName);
            case NOT_MANAGED, FILE_OCCUPANT -> bridge.sendToast(ToastCopy.refuseOccupant(folderName, false));
            case FOLDER_MISSING -> bridge.sendToast(ToastCopy.refuseFolderMissing(folderName));
            case NOT_TAINTED -> bridge.sendToast(ToastCopy.restoreRefusedNotTainted());
            case TAINT_UNKNOWN -> bridge.sendToast(ToastCopy.restoreRefusedTaintUnknown());
            case SOURCE_CHANGED -> bridge.sendToast(ToastCopy.restoreRefusedSourceChanged());
            case WORLD_IN_USE -> bridge.sendToast(ToastCopy.restoreRefusedWorldInUse());
            case SNAPSHOT_FAILED -> bridge.sendToast(ToastCopy.restoreRefusedSnapshotFailed());
            case DISK_FULL -> bridge.sendToast(ToastCopy.restoreRefusedDiskFull());
            case EXTRACT_REFUSED -> bridge.sendToast(ToastCopy.restoreRefusedExtractRefused());
            case SWAP_FAILED -> bridge.sendToast(ToastCopy.restoreRefusedSwapFailed(
                    describeSurvivingPaths(result.survivingPaths())));
            default -> LOGGER.error("unexpected restore outcome {} for {}", result.outcome(), folderName);
        }
    }

    private static void surfaceSweepOutcome(RestoreOperation.RestoreSweep.SweepResult result) {
        for (Path folder : result.movedBack()) {
            bridge.sendToast(ToastCopy.sweepMovedBack(folderLabel(folder)));
        }
        for (Path sibling : result.relocated()) {
            bridge.sendToast(ToastCopy.sweepRelocated(folderLabel(sibling)));
        }
        for (Path folder : result.missingDeferred()) {
            bridge.sendToast(ToastCopy.sweepMissingDeferred(folderLabel(folder)));
        }
    }

    private static String describeSurvivingPaths(List<Path> survivingPaths) {
        Path savesDirectory = Minecraft.getInstance().getLevelSource().getBaseDir();
        return survivingPaths.stream()
                .map(path -> {
                    try {
                        return savesDirectory.relativize(path).toString();
                    } catch (IllegalArgumentException e) {
                        return path.toString();
                    }
                })
                .collect(Collectors.joining(", "));
    }

    private static String folderLabel(Path folder) {
        Path name = folder.getFileName();
        return name != null ? name.toString() : folder.toString();
    }

    /**
     * Maintain the unsaved-container outline draw-set after the capture tick, so it reads this tick's fresh
     * captured-set.
     */
    private static void outlineTick() {
        if (controller.state() != CaptureState.RECORDING) {
            outlineTracker.clear();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            outlineTracker.clear();
            return;
        }
        outlineTracker.tick(level, minecraft.gameRenderer.mainCamera().position(), currentConfig.outline(),
                controller.aidToggles(currentConfig), controller.capturedContainers(),
                controller.recoveredCoverage());
    }

    private static void deferScreen(Runnable open) {
        pendingScreenOpen = open;
    }

    private static void openSettingsScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(createSettingsScreen(minecraft.gui.screen()));
    }

    /**
     * Build the in-mod settings screen with {@code parent} as its back target. Safe before a world loads: the curated
     * game-rule set reads the band's default rules, not a live level.
     */
    public static Screen createSettingsScreen(@Nullable Screen parent) {
        return new WdlSettingsScreen(parent, WdlConfig.load(configPath()), Wdl::saveConfig,
                adapter.levelDataWriter().curatedGameRules(), bridge::isModLoaded);
    }

    /**
     * The reload still runs after a failed write, so the snapshot is what loading the file gives, not the values that
     * failed to write.
     */
    private static void saveConfig(WdlConfig config) {
        Path file = configPath();
        try {
            AtomicFileWrite.write(file, ConfigSchema.renderConfigFile(config).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.warn("failed to write wdl.properties from the settings menu", e);
            SaveFailureReason reason = SaveFailureComposer.describe(e);
            bridge.sendChat(ChatCopy.saveFailed(reason)); // never suppressed by showChatMessages
            ToastCopy toast = ToastCopy.settingsError(config.showToasts(), reason);
            if (toast != null) {
                bridge.sendToast(toast);
            }
        }
        currentConfig = WdlConfig.load(file);
        controller.onSettingsCommitted();
    }

    // The pause-menu primary button's label and enabled-state are resolved when the pause menu opens; they can go one
    // open stale during a drain. onPausePrimary re-reads the state at click, so the action stays correct even when the
    // label does not.

    private static String pausePrimaryLabelKey() {
        return switch (controller.state()) {
            case IDLE -> "wdl.screen.downloads.open";
            case RECORDING -> "wdl.screen.downloads.stop_download";
            case SAVING -> "wdl.screen.downloads.saving";
            case RESTORING -> "wdl.screen.downloads.open";
        };
    }

    private static boolean isPausePrimaryEnabled() {
        return controller.state() != CaptureState.SAVING;
    }

    private static void onPausePrimary() {
        switch (controller.state()) {
            case RECORDING -> {
                if (currentConfig.showChatMessages()) {
                    bridge.sendChat(ChatCopy.saving());
                }
                controller.stop();
                Minecraft.getInstance().gui.setScreen(null);
            }
            case SAVING -> {}
            case RESTORING -> openDownloadsScreenNow();
            default -> openDownloadsScreenNow();
        }
    }

    static @Nullable Path loadedWorldPath(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        return server != null ? server.getWorldPath(LevelResource.ROOT) : null;
    }

    private static String defaultDownloadName(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        return server != null && server.name != null && !server.name.isBlank() ? server.name : "download";
    }

    /**
     * The current server's name when it sanitizes to a usable name, else a generic default, so the implicit paths
     * always carry a usable name into {@link ResumeFlow#begin}.
     */
    private static String defaultBaseName() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        String name = server != null ? server.name : null;
        return name != null && TargetResolver.hasUsableName(name) ? name : "world";
    }

    /**
     * Deliberately does not inspect the name: a server whose name sanitizes to nothing still has an identity, and
     * refusing it would take away an implicit start.
     */
    private static boolean hasSourceIdentity() {
        return Minecraft.getInstance().getCurrentServer() != null;
    }

    private static Path configPath() {
        return bridge.configDirectory().resolve("wdl.properties");
    }

    private static void onStart() {
        bridge.sendChat(ChatCopy.startNeedsName());
    }

    private static void onStartNamed(String name) {
        resumeFlow.startNamed(name);
    }

    private static void onStop() {
        CaptureState state = controller.state();
        if (state == CaptureState.IDLE) {
            bridge.sendChat(ChatCopy.notDownloading());
            return;
        }
        if (currentConfig.showChatMessages()) {
            bridge.sendChat(state == CaptureState.RECORDING
                    ? ChatCopy.saving()
                    : ChatCopy.busy(state, isSweepActive()));
        }
        controller.stop();
    }

    private static void onResume(String name) {
        resumeFlow.resume(name);
    }

    private static void onStatus() {
        bridge.sendChat(ChatCopy.status(controller.state(), controller.counts(), isSweepActive()));
    }

    private static void onConfig() {
        WdlConfig config = WdlConfig.load(configPath());
        bridge.sendChat(ChatCopy.configFile(configPath().toString()));
        bridge.sendChat(ChatCopy.data("captureEntities=" + config.captureEntities()
                + ", captureContainers=" + config.captureContainers()
                + ", openInCreative=" + config.worldOutput().openInCreative()));
        bridge.sendChat(ChatCopy.data("savePlayerInventory=" + config.savePlayerInventory()
                + ", savePlayerEnderChest=" + config.savePlayerEnderChest()
                + ", saveItemCoordinates=" + config.saveItemCoordinates()));
    }

    /** The current capture state. */
    public static CaptureState state() {
        return controller.state();
    }

    /** Live counts while recording, the stop-time frozen snapshot through saving. */
    public static CaptureCounts counts() {
        return controller.counts();
    }

    /** The capture's elapsed wall-clock time, frozen at stop through saving and the done linger. */
    public static long elapsedMillis() {
        return controller.elapsedMillis();
    }

    /** The finalization phase while saving, {@link SaveStage#NONE} otherwise. */
    public static SaveStage saveStage() {
        return controller.saveStage();
    }

    /** The finalization phase's fraction while saving, 0 otherwise. */
    public static float saveProgress() {
        return controller.saveProgress();
    }

    /** Milliseconds since the last save completed while within the done-linger hold, else empty. */
    public static OptionalLong doneElapsedMillis() {
        return controller.doneElapsedMillis();
    }

    /** The most recently loaded config (no disk IO on the render path). */
    public static WdlConfig config() {
        return currentConfig;
    }

    /** The saved chunk-position longs for a dimension; see {@link CaptureController#overlaySavedChunks}. */
    public static long[] overlaySavedChunks(String dimensionId) {
        return controller.overlaySavedChunks(currentConfig, dimensionId);
    }

    /** The covered chunk-position longs for a dimension; see {@link CaptureController#overlayCoveredChunks}. */
    public static long[] overlayCoveredChunks(String dimensionId) {
        return controller.overlayCoveredChunks(currentConfig, dimensionId);
    }

    /** A monotonic overlay generation; see {@link CaptureController#overlayGeneration}. */
    public static long overlayGeneration() {
        return controller.overlayGeneration();
    }

    /** The unsaved-container outline draw-set. */
    public static OutlineDrawSet outlineDrawSet() {
        return outlineTracker.drawSet();
    }

    /** The launch-scoped update check. */
    public static UpdateCheck updateCheck() {
        return updateCheck;
    }

    /**
     * The folder the running restore replaces, or null while no player restore runs (the launch sweep holds RESTORING
     * without one). Client main thread only.
     */
    public static @Nullable String restoringFolderName() {
        return restoreFolderName;
    }

    public static boolean lastRestoreChangedDisk() {
        return lastRestoreChangedDisk;
    }
}
