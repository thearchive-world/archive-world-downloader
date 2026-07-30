// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
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
import world.thearchive.wdl.core.AtomicFileWrite;
import world.thearchive.wdl.core.CaptureController;
import world.thearchive.wdl.core.CaptureCounts;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.CaptureToggleGuard;
import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.ConfigSchema;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
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
import world.thearchive.wdl.platform.PlatformBridge;

/**
 * The mod's loader-agnostic entry point. Each loader's own entrypoint constructs its {@link PlatformBridge} and hands
 * it here, so everything above this line is written once and knows nothing about which loader is running; the per-band
 * {@link VersionAdapter} is the one service genuinely discovered at runtime, so it stays on {@link ServiceLoader}.
 *
 * <p>From there it owns the {@link CaptureController}: it wires the controller's tick to the client tick and its
 * auto-save to the disconnect hook, and {@link #startDownload} is the one place a {@link LiveCaptureSession} is built.
 */
public final class Wdl {
    public static final String MOD_ID = "wdl";

    private static final Logger LOGGER = LogUtils.getLogger();

    // Set once by initialize() from the loader entrypoint before any hook can fire; never null in operation,
    // a lifecycle NullAway cannot model, so its uninitialized-field check is suppressed on these two.
    @SuppressWarnings("NullAway.Init")
    private static VersionAdapter adapter;

    @SuppressWarnings("NullAway.Init")
    private static PlatformBridge bridge;

    private static final CaptureController controller = new CaptureController();

    // One-shot daemon for the pre-download worldgen warmup, so the reconstruction decode never blocks the client
    // tick or JVM exit. Idempotent through the reconstruction memo; a repeat trigger just spawns a thread that
    // finds the registries already built and exits.
    private static final Executor warmupWorker = daemonWorker("wdl-worldgen-warmup");

    // The in-world unsaved-container outline: the tick maintains its draw-set, the per-loader render
    // registrar reads it. The recovered coverage it classifies against is the session's, published by the
    // off-thread resume scan and read through the controller.
    private static final OutlineTracker outlineTracker = new OutlineTracker();

    private static volatile WdlConfig currentConfig = WdlConfig.DEFAULTS;

    // The display name of the download currently running, set when a capture begins; meaningful only while the
    // controller is non-idle (it is left stale once the capture finishes and overwritten by the next start).
    private static @Nullable String activeDownloadName;

    // A screen opened synchronously from a chat command is clobbered on Fabric by ChatScreen's post-dispatch
    // setScreen(null) (NeoForge patches that close to guard it). Open it on the next client tick instead, after
    // that close has run. One-shot, last-write-wins; consumed by onClientTick and discarded by the inline
    // pause-menu open (openDownloadsScreenNow), both on the client main thread.
    private static @Nullable Runnable pendingScreenOpen;

    // The download-start flow, constructed by initialize() once the bridge is live; never null in operation,
    // so its uninitialized-field check is suppressed here.
    @SuppressWarnings("NullAway.Init")
    private static ResumeFlow resumeFlow;

    // The live restore worker's state, volatile because the permanent shutdown hook reads it from its own
    // thread: the operation for abort and the swap-window read, the worker for the bounded join, the parked
    // future for the completion poll. Set together by the dispatch and cleared together by the poll, both on
    // the client main thread.
    private static volatile @Nullable RestoreOperation activeRestore;
    private static volatile @Nullable Thread restoreWorker;
    private static volatile @Nullable CompletableFuture<RestoreOperation.Result> restoreResult;

    // The folder the running restore replaces, for the completion toast; main-thread only.
    private static @Nullable String restoreFolderName;

    // The launch sweep's parked result; its non-null read doubles as the owner-routed sweep flag that picks
    // the cleanup flavor of the busy and status copy.
    private static volatile @Nullable CompletableFuture<RestoreOperation.RestoreSweep.SweepResult> sweepResult;

    // Whether the restore or sweep that most recently returned the controller to idle changed the disk;
    // the downloads screen gates its entries re-pull on it. Main-thread only.
    private static boolean lastRestoreChangedDisk = true;

    private Wdl() {}

    /** The current MC version via its Mojmap name. */
    public static String mcVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    /** Called once by the running loader's client entrypoint, with that loader's bridge. */
    public static void initialize(PlatformBridge platformBridge) {
        // Route core's java.util.logging into latest.log first, so a fail-soft warning from config load or any
        // later core step is visible (the MC runtime does not forward JUL to the log on its own).
        CoreLogHandler.install();
        bridge = platformBridge;
        adapter = ServiceLoader.load(VersionAdapter.class, Wdl.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No VersionAdapter service is registered"));
        LOGGER.info("Archive World Downloader {} on {} {}", platformBridge.modVersion(),
                platformBridge.loaderName(), platformBridge.loaderVersion());
        currentConfig = WdlConfig.load(configPath()); // materialize the default config file on first run
        LOGGER.info("config file: {}", configPath());
        resumeFlow = new ResumeFlow(bridge, () -> WdlConfig.load(configPath()), Wdl::startDownload);

        bridge.registerToggleKeybind(Wdl::onToggle);
        bridge.registerDownloadsKeybind(Wdl::openDownloadsScreen);
        bridge.addPauseMenuButtons(Wdl::pausePrimaryLabelKey, Wdl::isPausePrimaryEnabled, Wdl::onPausePrimary,
                Wdl::openSettingsScreen);
        bridge.onClientTickEnd(Wdl::onClientTick);
        bridge.onDisconnect(controller::onDisconnect);
        // A backend transfer (play-to-configuration re-entry) fires no disconnect hook on either loader, so the
        // tee raises its own signal and the controller polls it each tick, stopping the download the same way.
        controller.setTransferStopPoll(ConnectionTee::consumeTransferSignal);
        // One permanent hook for the JVM's life rather than one per operation, matching the bounded halt
        // the vanilla client shutdown hook gives the integrated server.
        Runtime.getRuntime().addShutdownHook(new Thread(Wdl::abortRestoreOnShutdown, "wdl-restore-shutdown"));
    }

    /** The current capture state. */
    public static CaptureState state() {
        return controller.state();
    }

    /** Live counts while recording, the stop-time frozen snapshot through saving and the done linger. */
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

    /**
     * The restore and sweep completion poll, the counterpart of the controller's save poll, reached two ways: the
     * per-tick call, which is what republishes the loaded world each tick while a restore runs (the worker re-probes it
     * before the swap, so a world opened mid-restore is seen), and the marshaled completion poke, which can drain a
     * parked future off the tick. Once a parked future completes, return the controller to idle, surface the outcome
     * copy, and clear the fields. The screen refresh rides the state flip: the downloads screen rebuilds and re-pulls
     * its entries when RESTORING returns to IDLE.
     */
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

    /** The outcome-to-copy table for a finished restore; every cause surfaces as a toast or a log line. */
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

    /** One toast per swept folder, converting the three SweepResult lists to their cleanup notices. */
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

    /** The kept-aside paths a swap failure names, shortened to saves-relative form where possible. */
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
     * captured-set. Rebuilds it around the player while recording, and empties it otherwise so no rim lingers once the
     * capture ends. The recovered-coverage set is the latest the off-thread scan has published.
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
        outlineTracker.tick(level, minecraft.gameRenderer.getMainCamera().position(), currentConfig.outline(),
                currentConfig.captureContainers(), currentConfig.captureEntities(),
                currentConfig.recaptureChunks().refreshesHotChunks(),
                controller.capturedContainers(), controller.recoveredCoverage());
    }

    /** Stash a screen open to run on the next client tick; see {@link #onClientTick} for why. */
    private static void deferScreen(Runnable open) {
        pendingScreenOpen = open;
    }

    /** Open the in-mod settings screen from the pause-menu config button; edits commit on close. */
    private static void openSettingsScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(createSettingsScreen(minecraft.screen));
    }

    /**
     * Build the in-mod settings screen with {@code parent} as its back target, so a loader mod-config entry point (the
     * Fabric mod-list hook, the NeoForge config button) opens the same screen as the pause-menu button. Safe before a
     * world loads: the curated game-rule set reads the band's default rules, not a live level.
     */
    public static Screen createSettingsScreen(@Nullable Screen parent) {
        return new WdlSettingsScreen(parent, WdlConfig.load(configPath()), Wdl::saveConfig,
                adapter.levelDataWriter().curatedGameRules(), bridge::isModLoaded);
    }

    /**
     * Persist an edited config from the settings menu and refresh the in-memory snapshot the per-frame consumers (the
     * HUD, the outline, the coverage overlay) read, so an edit takes effect without waiting for the next download
     * start. Writes the documented file atomically, then reloads from disk so the snapshot is the parsed, self-healed
     * result. A write failure is surfaced to the player on the same chat and toast channel a failed download uses, and
     * logged; the reload still runs, so the snapshot stays consistent with what is actually on disk (the prior file,
     * since the write did not land).
     */
    private static void saveConfig(WdlConfig config) {
        Path file = configPath();
        try {
            AtomicFileWrite.write(file, ConfigSchema.renderConfigFile(config).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.warn("failed to write wdl.properties from the settings menu", e);
            SaveFailureReason reason = SaveFailureComposer.describe(e);
            bridge.sendChat(ChatCopy.saveFailed(reason)); // an error is never suppressed by showChatMessages
            ToastCopy toast = ToastCopy.error(config.showToasts(), reason);
            if (toast != null) {
                bridge.sendToast(toast);
            }
        }
        currentConfig = WdlConfig.load(file);
    }

    // The pause-menu primary button's label and enabled-state are resolved once when the pause menu opens, since
    // PauseScreen is vanilla and we do not own its per-frame tick to refresh them; they can go one open stale
    // during a drain. onPausePrimary re-reads the state at click, so the action stays correct even when the label
    // does not.

    /**
     * The pause-menu primary button's label key for the current state (idle and restoring open / recording stop /
     * saving): the button stays an open-screen affordance during a restore.
     */
    private static String pausePrimaryLabelKey() {
        return switch (controller.state()) {
            case IDLE -> "wdl.screen.downloads.open";
            case RECORDING -> "wdl.screen.downloads.stop_download";
            case SAVING -> "wdl.screen.downloads.saving";
            case RESTORING -> "wdl.screen.downloads.open";
        };
    }

    /** The primary button is inert only while a save drains; recording offers Stop, idle and restoring open. */
    private static boolean isPausePrimaryEnabled() {
        return controller.state() != CaptureState.SAVING;
    }

    /**
     * Pause-menu primary press: idle and restoring open the screen, recording stops and returns to the world, saving
     * no-ops.
     */
    private static void onPausePrimary() {
        switch (controller.state()) {
            case RECORDING -> {
                if (currentConfig.showChatMessages()) {
                    bridge.sendChat(ChatCopy.saving());
                }
                controller.stop();
                Minecraft.getInstance().setScreen(null);
            }
            case SAVING -> {} // active == false makes this unreachable by click; the branch is defense in depth
            case RESTORING -> openDownloadsScreenNow();
            default -> openDownloadsScreenNow();
        }
    }

    /**
     * Open the download screen with the existing-worlds list collapsed. Deferred to the next client tick; the parent
     * screen is captured at tick time.
     */
    private static void openDownloadsScreen() {
        deferScreen(() -> showDownloadsScreen(false));
    }

    /**
     * Open the download screen directly on the click, for the pause-menu button. The pause button is not
     * chat-originated, so it needs none of the tick deferral openDownloadsScreen uses to survive Fabric's post-dispatch
     * chat close; opening inline is what lets it work while a replay's paused timer has suspended the game tick.
     */
    private static void openDownloadsScreenNow() {
        // The inline open consumes the deferred slot the same way a deferred open would. The slot holds any
        // parked action, a resume start or a confirm as much as a plain open, so this can discard one. The
        // slot is last-write-wins on every path that writes it.
        pendingScreenOpen = null;
        showDownloadsScreen(false);
    }

    /** Build the MC-free browse model and show the screen; run from the deferral on the client main thread. */
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
        // Manual path: opening the download screen is the player's download intent and precedes the first flush
        // by seconds, so warm the worldgen reconstruction now when the chosen generator needs it.
        WorldgenWarmup.dispatchForScreenOpen(config.worldOutput().worldType(),
                adapter.levelDataWriter()::warmWorldgen, warmupWorker);
        minecraft.setScreen(new WdlDownloadsScreen(minecraft.screen, savesDirectory, loadedWorld, entries,
                expandExistingList, defaultDownloadName(minecraft), config.appendDateSuffix(), config.confirmResume(),
                config.blockTaintedResume(), config.zipOnResume(), config.remapMapIds(),
                CaptureToggleGuard.isCapturePartiallyDisabled(config), bridge.modVersion(), mcVersion(),
                Wdl::startDownload, Wdl::state, controller::stop,
                bridge::sendToast, bridge::isRemoteWorld, activeDownloadName));
    }

    private static Executor daemonWorker(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.start();
        };
    }

    /**
     * The shutdown hook body: abort any live restore so the worker backs out at its next phase check, and only while
     * the worker is inside the two-rename swap window wait for it, bounded to three seconds, so the folder is never
     * abandoned mid-swap by a quitting client.
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

    /** Keybind handler (client main thread): start a download, or stop and save the running one. */
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
        // The isRemoteWorld conjunct is load-bearing here, unlike the guard in onServerJoin: no activation gate
        // precedes this one, so without it a keybind press in a genuine singleplayer world would answer with
        // the needs-a-name refusal instead of falling through to the join-a-server refusal.
        if (bridge.isRemoteWorld() && !hasSourceIdentity()) {
            bridge.sendChat(ChatCopy.startNeedsName());
            return;
        }
        resumeFlow.begin(defaultBaseName(), true);
    }

    /**
     * Begin a download for {@code target}, the single entry point: a {@link DownloadMode#NEW} target writes to its
     * (already-disambiguated) folder, a {@link DownloadMode#RESUME} re-runs into an existing wdl-managed folder
     * verbatim and adds to it. Guards a double-start and a local world, re-loads the config so hand-edits apply on the
     * next download, then begins a session for the target.
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
            return; // no live start path reaches here without a loaded world; a silent guard before level is used
        }
        WdlConfig config = WdlConfig.load(configPath());
        currentConfig = config;
        Path savesDirectory = minecraft.getLevelSource().getBaseDir();
        if (target.mode() == DownloadMode.NEW
                && RestoreOperation.attemptReferences(savesDirectory, target.folderName())) {
            // A torn restore attempt still stages this name under the temporary root; a NEW download landing on
            // it would race the next sweep's roll-back. The predicate fails open on an unreadable scan.
            sendRefusal(target.origin(), ToastCopy.refuseTornAttempt(), ChatCopy.refuseTornAttempt());
            return;
        }
        if (target.mode() == DownloadMode.RESUME) {
            Path saveFolder = savesDirectory.resolve(target.folderName());
            if (!DownloadFolders.isWdlManaged(saveFolder)) {
                // The managed re-test ahead of the taint re-test: the folder may have changed since the
                // confirm cascade classified it. A vanished folder is the missing cause; a file or an
                // unmanaged directory at the name is a foreign occupant either way.
                if (Files.exists(saveFolder)) {
                    // The flow origins came from a typed name or quick start, so they earn the name-choosing
                    // advice; the screen already surfaced its own advice at the junction, so SCREEN drops it.
                    boolean suggestRename = target.origin() != DownloadTarget.Origin.SCREEN;
                    sendRefusal(target.origin(), ToastCopy.refuseOccupant(target.folderName(), suggestRename),
                            ChatCopy.refuseOccupant(target.folderName(), suggestRename));
                } else {
                    sendRefusal(target.origin(), ToastCopy.refuseFolderMissing(target.folderName()),
                            ChatCopy.refuseFolderMissing(target.folderName()));
                }
                return;
            }
            if (SinglePlayerTaint.isTainted(saveFolder) && config.blockTaintedResume()) {
                sendRefusal(target.origin(), ToastCopy.refuseTainted(), ChatCopy.refuseTainted());
                return;
            }
        }
        activeDownloadName = target.worldName() != null ? target.worldName() : target.folderName();
        // State-independent of captureEntities, so a signal raised between downloads is discarded for every
        // download kind, not only when an entity capture activates.
        ConnectionTee.clearTransferSignal();
        controller.start(() -> new LiveCaptureSession(adapter, bridge, config, level, target,
                controller.sendRange(), minecraft.getCameraEntity() != minecraft.player, controller::tick));
        if (config.showChatMessages()) {
            bridge.sendChat(target.mode() == DownloadMode.RESUME
                    ? ChatCopy.resuming(target.folderName())
                    : ChatCopy.downloading(target.folderName()));
            if (CaptureToggleGuard.isCapturePartiallyDisabled(config)) {
                bridge.sendChat(ChatCopy.capturePartiallyDisabled()); // passive indicator at the start action
            }
        }
    }

    /** Route a start refusal to its origin's channel: the screen's toast, the command/keybind flows' chat. */
    private static void sendRefusal(DownloadTarget.Origin origin, ToastCopy toast, ChatCopy chat) {
        if (DownloadTarget.refusalUsesToast(origin)) {
            bridge.sendToast(toast);
        } else {
            bridge.sendChat(chat);
        }
    }

    /** Whether the current RESTORING state belongs to the launch sweep rather than a player restore. */
    static boolean isSweepActive() {
        return sweepResult != null;
    }

    /**
     * The one restore dispatch (client main thread): flip the idle controller into RESTORING, seed the operation's
     * loaded-world volatile from this thread, and run the guarded replace of {@code folderName} from
     * {@code pinnedSource} on a named daemon worker, parking the future for the completion poll, which is marshaled
     * back to the client thread before the worker starts and backed by the per-tick poll. A controller that is not idle
     * refuses with the busy toast; the pre-replace snapshot follows {@code zipOnResume}. Public for the downloads
     * screen's restore confirm and blocked offer, the client-package dispatchers.
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
        // RestoreOperation.run is documented never-throws, but an Error escaping its own catch (thrown
        // while logging or building the failure Result) must still complete the future or the controller
        // stays RESTORING forever.
        Thread worker = new Thread(() -> {
            try {
                result.complete(operation.run());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        }, "wdl-restore");
        worker.setDaemon(true);
        restoreWorker = worker;
        // Attach the marshal before starting the worker: attached afterwards it could meet an already-finished
        // worker and run the poke inline on the dispatching stack, rather than off the game tick where every
        // other completion lands. The dispatch-failure catch below never completes the future, so the marshal
        // never fires there; that path self-heals inline.
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

    /**
     * The launch-sweep dispatch (client main thread): the identical flip, daemon worker, marshal-before-start and
     * parked-future machinery as {@link #launchRestore}, with the sweep's future doubling as the flag that routes the
     * busy and status copy to the cleanup flavor. Public for the downloads screen's open and TTL sweep triggers.
     */
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

    /** The currently-loaded local world folder (refused as a target), or null when the world is remote. */
    static @Nullable Path loadedWorldPath(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        return server != null ? server.getWorldPath(LevelResource.ROOT) : null;
    }

    /** The in-capture screen label's fallback name: the current server's name, else a generic default. */
    private static String defaultDownloadName(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        return server != null && server.name != null && !server.name.isBlank() ? server.name : "download";
    }

    /**
     * The default base name for a keybind start, before any date suffix: the current server's name when it sanitizes to
     * a usable folder name, else a generic default, so the implicit path always carries a usable name into
     * {@link ResumeFlow#begin}.
     */
    private static String defaultBaseName() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        String name = server != null ? server.name : null;
        return name != null && TargetResolver.hasUsableName(name) ? name : "world";
    }

    /**
     * Whether this session has a server identity at all. False only when nothing is being connected to as a server,
     * which is genuine singleplayer. Deliberately does not inspect the name: a server whose name sanitizes to nothing
     * still has an identity, and refusing it would take away an implicit start that works.
     */
    private static boolean hasSourceIdentity() {
        return Minecraft.getInstance().getCurrentServer() != null;
    }

    private static Path configPath() {
        return bridge.configDirectory().resolve("wdl.properties");
    }

    /** The unsaved-container outline draw-set, read by the per-loader render registrar each frame. */
    public static OutlineDrawSet outlineDrawSet() {
        return outlineTracker.drawSet();
    }

    /**
     * The folder the running restore replaces, or null while no player restore runs (the launch sweep holds RESTORING
     * without one); the downloads screen picks its busy label by it. Client main thread only.
     */
    public static @Nullable String restoringFolderName() {
        return restoreFolderName;
    }

    /**
     * Whether the restore or sweep that most recently returned the controller to idle changed the disk; the downloads
     * screen skips its entries re-pull when a completed sweep never touched it.
     */
    public static boolean lastRestoreChangedDisk() {
        return lastRestoreChangedDisk;
    }
}
