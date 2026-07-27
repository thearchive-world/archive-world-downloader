// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import world.thearchive.wdl.adapter.ConnectionTee;
import world.thearchive.wdl.adapter.LiveCaptureSession;
import world.thearchive.wdl.adapter.VersionAdapter;
import world.thearchive.wdl.client.WdlDownloadsScreen;
import world.thearchive.wdl.core.CaptureController;
import world.thearchive.wdl.core.CaptureCounts;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.CaptureToggleGuard;
import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.SaveStage;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.browse.DownloadCatalog;
import world.thearchive.wdl.core.browse.DownloadEntry;
import world.thearchive.wdl.core.browse.TargetResolver;
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
    // a lifecycle NullAway cannot model, so its uninitialized-field check is suppressed here.
    @SuppressWarnings("NullAway.Init")
    private static VersionAdapter adapter;

    private static @Nullable PlatformBridge bridge;

    private static final CaptureController controller = new CaptureController();

    // One-shot daemon for the pre-download worldgen warmup, so the reconstruction decode never blocks the client
    // tick or JVM exit. Idempotent through the reconstruction memo; a repeat trigger just spawns a thread that
    // finds the registries already built and exits.
    private static final Executor warmupWorker = daemonWorker("wdl-worldgen-warmup");

    private static volatile WdlConfig currentConfig = WdlConfig.DEFAULTS;

    // The display name of the download currently running, set when a capture begins; meaningful only while the
    // controller is non-idle (it is left stale once the capture finishes and overwritten by the next start).
    private static @Nullable String activeDownloadName;

    // A screen opened synchronously from a chat command is clobbered on Fabric by ChatScreen's post-dispatch
    // setScreen(null) (NeoForge patches that close to guard it). Open it on the next client tick instead, after
    // that close has run. One-shot, last-write-wins; consumed by onClientTick on the client main thread.
    private static @Nullable Runnable pendingScreenOpen;

    // The download-start flow, constructed by initialize() once the bridge is live; never null in operation,
    // so its uninitialized-field check is suppressed here.
    @SuppressWarnings("NullAway.Init")
    private static ResumeFlow resumeFlow;

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
        resumeFlow = new ResumeFlow(platformBridge, () -> WdlConfig.load(configPath()), Wdl::startDownload);

        platformBridge.registerToggleKeybind(Wdl::onToggle);
        platformBridge.registerDownloadsKeybind(Wdl::openDownloadsScreen);
        platformBridge.onClientTickEnd(Wdl::onClientTick);
        platformBridge.onDisconnect(controller::onDisconnect);
        // A backend transfer (play-to-configuration re-entry) fires no disconnect hook on either loader, so the
        // tee raises its own signal and the controller polls it each tick, stopping the download the same way.
        controller.setTransferStopPoll(ConnectionTee::consumeTransferSignal);
    }

    /** The running loader's bridge. Throws if read before {@link #initialize}, which is a wiring bug. */
    public static PlatformBridge platform() {
        PlatformBridge current = bridge;
        if (current == null) {
            throw new IllegalStateException("Wdl.initialize has not run");
        }
        return current;
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
        Runnable open = pendingScreenOpen;
        if (open != null) {
            pendingScreenOpen = null;
            open.run();
        }
    }

    /** Stash a screen open to run on the next client tick; see {@link #onClientTick} for why. */
    private static void deferScreen(Runnable open) {
        pendingScreenOpen = open;
    }

    /**
     * Open the download screen with the existing-worlds list collapsed. Deferred to the next client tick; the parent
     * screen is captured at tick time.
     */
    private static void openDownloadsScreen() {
        deferScreen(() -> showDownloadsScreen(false));
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
                expandExistingList, defaultDownloadName(minecraft), config.appendDateSuffix(),
                CaptureToggleGuard.isCapturePartiallyDisabled(config), platform().modVersion(), mcVersion(),
                Wdl::startDownload, Wdl::state, controller::stop, activeDownloadName));
    }

    private static Executor daemonWorker(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.start();
        };
    }

    /** Keybind handler (client main thread): start a download, or stop and save the running one. */
    private static void onToggle() {
        if (controller.state() != CaptureState.IDLE) {
            controller.stop();
            return;
        }
        PlatformBridge platform = platform();
        if (platform.isRemoteWorld() && !hasSourceIdentity()) {
            platform.sendChat(ChatCopy.startNeedsName());
            return;
        }
        resumeFlow.begin(defaultBaseName(), true);
    }

    /**
     * Begin a download for {@code target}, the single entry point: a {@link DownloadMode#NEW} target writes to its
     * (already-disambiguated) folder, a {@link DownloadMode#RESUME} re-runs into an existing folder verbatim and adds
     * to it. Guards a double-start and a local world, re-loads the config so a hand-edit applies on the next download,
     * then begins a session for the target.
     */
    private static void startDownload(DownloadTarget target) {
        if (controller.state() != CaptureState.IDLE) {
            return;
        }
        PlatformBridge platform = platform();
        if (!platform.isRemoteWorld()) {
            platform.sendChat(ChatCopy.joinMultiplayer());
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return; // no live start path reaches here without a loaded world; a silent guard before level is used
        }
        WdlConfig config = WdlConfig.load(configPath());
        currentConfig = config;
        activeDownloadName = target.worldName() != null ? target.worldName() : target.folderName();
        // State-independent of captureEntities, so a signal raised between downloads is discarded for every
        // download kind, not only when an entity capture activates.
        ConnectionTee.clearTransferSignal();
        controller.start(() -> new LiveCaptureSession(adapter, platform, config, level, target,
                controller.sendRange(), minecraft.getCameraEntity() != minecraft.player, controller::tick));
        if (config.showChatMessages()) {
            platform.sendChat(target.mode() == DownloadMode.RESUME
                    ? ChatCopy.resuming(target.folderName())
                    : ChatCopy.downloading(target.folderName()));
            if (CaptureToggleGuard.isCapturePartiallyDisabled(config)) {
                platform.sendChat(ChatCopy.capturePartiallyDisabled()); // passive indicator at the start action
            }
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
        return platform().configDirectory().resolve("wdl.properties");
    }
}
