// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import com.google.common.collect.ImmutableList;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
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
import net.minecraft.client.multiplayer.MultiPlayerLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.CompletionMarshal;
import world.thearchive.wdl.adapter.ConnectionTee;
import world.thearchive.wdl.adapter.LiveCaptureSession;
import world.thearchive.wdl.adapter.OutlineDrawSet;
import world.thearchive.wdl.adapter.OutlineTracker;
import world.thearchive.wdl.adapter.VersionAdapter;
import world.thearchive.wdl.client.WdlDownloadsScreen;
import world.thearchive.wdl.client.WdlSettingsScreen;
import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
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

/**
 * Entry point. Resolves the per-band {@link VersionAdapter} via {@link ServiceLoader} and takes the per-loader
 * {@link PlatformBridge} from the loader entrypoint, then drives the {@link CaptureController}: the toggle keybind
 * starts/stops a {@link LiveCaptureSession}, the client tick advances capture, and disconnect auto-saves.
 */
public final class Wdl {
    private static final Logger LOGGER = LogManager.getLogger(Wdl.class);

    // Set once by initialize() from the loader entrypoint before any hook can fire; never null in operation,
    // a lifecycle NullAway cannot model, so its uninitialized-field check is suppressed on these two.
    @SuppressWarnings("NullAway.Init")
    private static VersionAdapter adapter;

    @SuppressWarnings("NullAway.Init")
    private static PlatformBridge bridge;

    private static final ReadyLatch READY = new ReadyLatch();

    private static final CaptureController controller = new CaptureController();

    // The launch-scoped newer-release check: dispatched once at initialize, its held result flushed to
    // chat by the second join hook and shown as the downloads-screen banner.
    private static final UpdateCheck updateCheck = new UpdateCheck();

    // One-shot daemon for the pre-download worldgen warmup, so the reconstruction decode never blocks the client
    // tick or JVM exit. Idempotent through the reconstruction memo; a repeat trigger just spawns a thread that
    // finds the registries already built and exits.
    private static final Executor warmupWorker = daemonWorker("wdl-worldgen-warmup");

    // The in-world unsaved-container outline: the tick maintains its draw-set, the per-loader render
    // registrar reads it. The recovered coverage it classifies against is the session's, published by the
    // off-thread resume scan and read through the controller.
    private static final OutlineTracker outlineTracker = new OutlineTracker();

    private static BobbyChunkFilter bobbyFilter = BobbyChunkFilter.INACTIVE;

    // The most recently loaded config, cached so the per-frame HUD overlay reads it without disk IO. Refreshed
    // at initialize and on each download start (the existing "applies on the next download" model). Volatile
    // because the client thread writes it while the render thread reads it; the config objects are deeply
    // immutable, so publishing the reference is all this needs.
    private static volatile WdlConfig currentConfig = WdlConfig.DEFAULTS;

    // The display name of the download currently running, set when a capture begins; meaningful only while the
    // controller is non-idle (it is left stale once the capture finishes and overwritten by the next start).
    private static @Nullable String activeDownloadName;

    // A screen opened synchronously from a chat command is clobbered on Fabric by ChatScreen's post-dispatch
    // setScreen(null) (NeoForge patches that close to guard it). Open it on the next client tick instead, after
    // that close has run. One-shot, last-write-wins; consumed by onClientTick and discarded by the inline
    // pause-menu open (openDownloadsScreenNow), both on the client main thread.
    private static @Nullable Runnable pendingScreenOpen;

    // The resume/confirm state machine, constructed by initialize() once the controller and bridge are live.
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
        return SharedConstants.getCurrentVersion().getName();
    }

    /**
     * Run {@code runnable} once the platform bridge is initialized (immediately if it already is). Touches no
     * {@code journeymap.*} type, so an optional integration can wire itself order-independently.
     */
    public static void runWhenReady(Runnable runnable) {
        READY.runWhenReady(runnable);
    }

    /** The loader platform bridge, for an optional integration that must reach it (no journeymap.* type here). */
    public static PlatformBridge platformBridge() {
        return bridge;
    }

    /**
     * Resolve the per-band {@link VersionAdapter}. ServiceLoader is the primary path, but Forge below 1.17 does not
     * enumerate META-INF/services through the mod classloader's getResources, which is what ServiceLoader scans, so on
     * an empty result the single provider file is read directly (getResourceAsStream reads the same jar this class
     * loaded from) and its named adapter instantiated.
     */
    private static VersionAdapter resolveVersionAdapter() {
        Iterator<VersionAdapter> discovered = ServiceLoader.load(VersionAdapter.class, Wdl.class.getClassLoader())
                .iterator();
        if (discovered.hasNext()) {
            return discovered.next();
        }
        InputStream in = Wdl.class.getClassLoader()
                .getResourceAsStream("META-INF/services/" + VersionAdapter.class.getName());
        if (in != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    int comment = line.indexOf('#');
                    String name = (comment >= 0 ? line.substring(0, comment) : line).trim();
                    if (!name.isEmpty()) {
                        return Class.forName(name, true, Wdl.class.getClassLoader())
                                .asSubclass(VersionAdapter.class).getDeclaredConstructor().newInstance();
                    }
                }
            } catch (IOException | ReflectiveOperationException e) {
                throw new IllegalStateException("failed to load the VersionAdapter service", e);
            }
        }
        throw new IllegalStateException("No VersionAdapter service is registered");
    }

    /**
     * Wire the capture controller to the loader hooks. Called once from the loader entrypoint, which supplies its own
     * {@link PlatformBridge} (the loader is known at that point); the per-band {@link VersionAdapter} is discovered at
     * runtime via {@link #resolveVersionAdapter()}.
     */
    public static void initialize(PlatformBridge platformBridge) {
        // Route core's java.util.logging into latest.log first, so a fail-soft warning from config load or any
        // later core step is visible (the MC runtime does not forward JUL to the log on its own).
        CoreLogHandler.install();
        bridge = platformBridge;
        bobbyFilter = BobbyChunkFilter.resolve(bridge);
        outlineTracker.useBobbyFilter(bobbyFilter);
        adapter = resolveVersionAdapter();

        LOGGER.info("loaded on Minecraft {}: adapter={}, bridge={}",
                mcVersion(), adapter.getClass().getSimpleName(), bridge.getClass().getSimpleName());

        currentConfig = WdlConfig.load(configPath()); // materialize the default config file on first run
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
        // A backend transfer (play-to-configuration re-entry) fires no disconnect hook on either loader, so the
        // tee raises its own signal and the controller polls it each tick, stopping the download the same way.
        controller.setTransferStopPoll(ConnectionTee::consumeTransferSignal);
        bridge.onServerJoin(Wdl::onServerJoin);
        bridge.onServerJoin(Wdl::onUpdateAvailableJoin);
        bridge.registerCommands(new WdlCommands(Wdl::onStart, Wdl::onStartNamed, Wdl::onStop, Wdl::onStatus,
                Wdl::onConfig, Wdl::onResume, Wdl::openDownloadsFromCommand));
        // One permanent hook for the JVM's life rather than one per operation, matching the bounded halt
        // the vanilla client shutdown hook gives the integrated server.
        Runtime.getRuntime().addShutdownHook(new Thread(Wdl::abortRestoreOnShutdown, "wdl-restore-shutdown"));
        READY.markReadyAndDrain();
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
     * Server-join hook: when {@code autoDownload} is enabled, begin a download on joining a remote world, only while
     * idle, through the same smart flow as the keybind. A folder that already exists (a same-day rejoin, or any rejoin
     * with the date suffix off) resumes it via the merge confirm, so Cancel on join aborts the auto-download; with
     * confirmResume off it continues silently. The config is re-read so a hand-edit applies, and the idle and
     * activation gates are checked here, so a join into the user's own local world stays a silent no-op. Two skips are
     * spoken: a restore in progress, and a world served from elsewhere with no server identity behind it, which is
     * refused aloud rather than started under a generic name. Every other skip cause stays silent.
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
        // Must stay below the gate above: this hook also fires for the integrated server, so hoisting it would
        // speak on every genuine singleplayer world load.
        if (!hasSourceIdentity()) {
            bridge.sendChat(ChatCopy.startNeedsName());
            return;
        }
        resumeFlow.begin(defaultBaseName(), false);
    }

    /**
     * Dispatch the once-per-launch newer-release check off-thread, on a named daemon worker so it can never block
     * startup or JVM exit. The boundary declines quietly when the running version does not parse or no project id was
     * baked in (development shapes with nothing comparable to check); degrading to silence is the feature's contract.
     */
    private static void dispatchUpdateCheck(WdlConfig config) {
        String runningRaw = bridge.modVersion();
        Optional<SemVer> running = SemVer.parse(runningRaw);
        String modrinthId = modrinthId();
        if (!running.isPresent() || modrinthId.isEmpty()) {
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

    /** The Modrinth project id expanded into the jar from gradle.properties at build, or empty if unread. */
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
     * {@code autoDownload} is off): the held update result is flushed to chat on the first join after it arrives, at
     * most once per launch. The chat line respects {@code showChatMessages}; the banner on the downloads screen ignores
     * that toggle. The config is re-read so a hand-edit applies.
     */
    private static void onUpdateAvailableJoin() {
        Optional<UpdateAvailable> available = updateCheck.available();
        if (!available.isPresent()) {
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
        MultiPlayerLevel level = minecraft.level;
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

    /**
     * Open the download screen with the existing-worlds list collapsed. Deferred to the next client tick, the same
     * deferral the command path needs to survive Fabric's post-dispatch chat close; the parent screen is captured at
     * tick time.
     */
    private static void openDownloadsScreen() {
        deferScreen(() -> showDownloadsScreen(false));
    }

    /** Open the download screen from {@code /wdl downloads}, with the existing-worlds list expanded. */
    private static void openDownloadsFromCommand() {
        deferScreen(() -> showDownloadsScreen(true));
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
                return ImmutableList.of();
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
            case RESTORED:
                bridge.sendToast(ToastCopy.restored(folderName));
                break;
            case RESTORED_WITH_REMNANTS:
                LOGGER.warn("restore of {} left remnants under {}; the next sweep cleans them up",
                        folderName, RestoreOperation.TEMPORARY_ROOT);
                bridge.sendToast(ToastCopy.restored(folderName));
                break;
            case RELOCATED: {
                Path sibling = Objects.requireNonNull(result.relocatedTo(), "RELOCATED names its sibling");
                bridge.sendToast(ToastCopy.restoreRefusedRelocated(folderLabel(sibling)));
                break;
            }
            case ABORTED:
                LOGGER.info("restore of {} aborted by shutdown", folderName);
                break;
            case NOT_MANAGED:
            case FILE_OCCUPANT:
                bridge.sendToast(ToastCopy.refuseOccupant(folderName, false));
                break;
            case FOLDER_MISSING:
                bridge.sendToast(ToastCopy.refuseFolderMissing(folderName));
                break;
            case NOT_TAINTED:
                bridge.sendToast(ToastCopy.restoreRefusedNotTainted());
                break;
            case TAINT_UNKNOWN:
                bridge.sendToast(ToastCopy.restoreRefusedTaintUnknown());
                break;
            case SOURCE_CHANGED:
                bridge.sendToast(ToastCopy.restoreRefusedSourceChanged());
                break;
            case WORLD_IN_USE:
                bridge.sendToast(ToastCopy.restoreRefusedWorldInUse());
                break;
            case SNAPSHOT_FAILED:
                bridge.sendToast(ToastCopy.restoreRefusedSnapshotFailed());
                break;
            case DISK_FULL:
                bridge.sendToast(ToastCopy.restoreRefusedDiskFull());
                break;
            case EXTRACT_REFUSED:
                bridge.sendToast(ToastCopy.restoreRefusedExtractRefused());
                break;
            case SWAP_FAILED:
                bridge.sendToast(ToastCopy.restoreRefusedSwapFailed(
                        describeSurvivingPaths(result.survivingPaths())));
                break;
            default:
                LOGGER.error("unexpected restore outcome {} for {}", result.outcome(), folderName);
                break;
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
        MultiPlayerLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            outlineTracker.clear();
            return;
        }
        outlineTracker.tick(level, minecraft.gameRenderer.getMainCamera().getPosition(), currentConfig.outline(),
                controller.aidToggles(currentConfig), controller.capturedContainers(),
                controller.recoveredCoverage());
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
     * result. A write failure is surfaced to the player in chat and as a toast, and logged; the reload still runs, so
     * the snapshot stays consistent with what is actually on disk (the prior file, since the write did not land).
     */
    private static void saveConfig(WdlConfig config) {
        Path file = configPath();
        try {
            AtomicFileWrite.write(file, ConfigSchema.renderConfigFile(config).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.warn("failed to write wdl.properties from the settings menu", e);
            SaveFailureReason reason = SaveFailureComposer.describe(e);
            bridge.sendChat(ChatCopy.saveFailed(reason)); // an error is never suppressed by showChatMessages
            ToastCopy toast = ToastCopy.settingsError(config.showToasts(), reason);
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
        switch (controller.state()) {
            case IDLE:
                return "wdl.screen.downloads.open";
            case RECORDING:
                return "wdl.screen.downloads.stop_download";
            case SAVING:
                return "wdl.screen.downloads.saving";
            case RESTORING:
                return "wdl.screen.downloads.open";
            default:
                throw new IncompatibleClassChangeError();
        }
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
            case RECORDING:
                if (currentConfig.showChatMessages()) {
                    bridge.sendChat(ChatCopy.saving());
                }
                controller.stop();
                Minecraft.getInstance().setScreen(null);
                break;
            case SAVING:
                break; // active == false makes this unreachable by click; the branch is defense in depth
            case RESTORING:
                openDownloadsScreenNow();
                break;
            default:
                openDownloadsScreenNow();
                break;
        }
    }

    /** The currently-loaded local world folder (refused as a target), or null when the world is remote. */
    static @Nullable Path loadedWorldPath(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        return server != null
                ? server.getStorageSource().getBaseDir().resolve(server.getLevelIdName())
                : null;
    }

    /** The in-capture screen label's fallback name: the current server's name, else a generic default. */
    private static String defaultDownloadName(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        return server != null && server.name != null && !server.name.trim().isEmpty() ? server.name : "download";
    }

    /**
     * The default base name for a keybind or auto-download start, before any date suffix: the current server's name
     * when it sanitizes to a usable folder name, else a generic default, so the implicit paths always carry a usable
     * name into {@link ResumeFlow#begin}.
     */
    private static String defaultBaseName() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        String name = server != null ? server.name : null;
        return name != null && TargetResolver.hasUsableName(name) ? name : "world";
    }

    /**
     * Whether this session has a server identity at all. False only when nothing is being connected to as a server:
     * during replay playback of either replay mod, and in genuine singleplayer. Deliberately does not inspect the name:
     * a server whose name sanitizes to nothing still has an identity, and refusing it would take away an implicit start
     * that works today.
     */
    private static boolean hasSourceIdentity() {
        return Minecraft.getInstance().getCurrentServer() != null;
    }

    private static Path configPath() {
        return bridge.configDirectory().resolve("wdl.properties");
    }

    /** Bare {@code /wdl start}: refused; a download needs a name, so the reply points at {@code /wdl start <name>}. */
    private static void onStart() {
        bridge.sendChat(ChatCopy.startNeedsName());
    }

    /** {@code /wdl start <name>}: start a NEW download of that name, refusing a name that already exists. */
    private static void onStartNamed(String name) {
        resumeFlow.startNamed(name);
    }

    /**
     * {@code /wdl stop}: stop and save the running download, replying in chat that none is running, or, when chat
     * messages are on, echoing the saving or busy line for the one it stops.
     */
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

    /** {@code /wdl resume <name>}: resume an existing wdl-managed download through the merge confirm. */
    private static void onResume(String name) {
        resumeFlow.resume(name);
    }

    /** {@code /wdl status} (and bare {@code /wdl}): the live state and counts, in chat. */
    private static void onStatus() {
        bridge.sendChat(ChatCopy.status(controller.state(), controller.counts(), isSweepActive()));
    }

    /** {@code /wdl config}: the config file path and current values, in chat. */
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

    /** The saved chunk-position longs for a dimension; see {@link CaptureController#overlaySavedChunks}. */
    public static long[] overlaySavedChunks(String dimensionId) {
        return controller.overlaySavedChunks(currentConfig, dimensionId);
    }

    /** The covered chunk-position longs for a dimension; see {@link CaptureController#overlayCoveredChunks}. */
    public static long[] overlayCoveredChunks(String dimensionId) {
        return controller.overlayCoveredChunks(currentConfig, dimensionId);
    }

    /** A monotonic overlay-data generation, bumped whenever any saved/covered set changes; poll to detect edits. */
    public static long overlayGeneration() {
        return controller.savedChunks().version() + controller.coveredChunks().version();
    }

    /** The unsaved-container outline draw-set, read by the per-loader render registrar each frame. */
    public static OutlineDrawSet outlineDrawSet() {
        return outlineTracker.drawSet();
    }

    /** The launch-scoped update check, read by the downloads screen for its update banner. */
    public static UpdateCheck updateCheck() {
        return updateCheck;
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
