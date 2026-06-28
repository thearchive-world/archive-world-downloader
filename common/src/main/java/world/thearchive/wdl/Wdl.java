// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.ServiceLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import world.thearchive.wdl.adapter.LiveCaptureSession;
import world.thearchive.wdl.adapter.VersionAdapter;
import world.thearchive.wdl.core.CaptureController;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;
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

    // The download-start flow, constructed by initialize() once the bridge is live; never null in operation,
    // so its uninitialized-field check is suppressed here.
    @SuppressWarnings("NullAway.Init")
    private static ResumeFlow resumeFlow;

    private Wdl() {}

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
        WdlConfig.load(configPath()); // materialize the documented default file on first run, so it can be edited
        LOGGER.info("config file: {}", configPath());
        resumeFlow = new ResumeFlow(platformBridge, () -> WdlConfig.load(configPath()), Wdl::startDownload);

        platformBridge.registerToggleKeybind(Wdl::onToggle);
        platformBridge.onClientTickEnd(Wdl::onClientTick);
        platformBridge.onDisconnect(controller::onDisconnect);
    }

    /** The running loader's bridge. Throws if read before {@link #initialize}, which is a wiring bug. */
    public static PlatformBridge platform() {
        PlatformBridge current = bridge;
        if (current == null) {
            throw new IllegalStateException("Wdl.initialize has not run");
        }
        return current;
    }

    /** The capture's elapsed wall-clock time, frozen at stop through saving and the done linger. */
    public static long elapsedMillis() {
        return controller.elapsedMillis();
    }

    private static void onClientTick() {
        controller.tick();
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
        controller.start(() -> new LiveCaptureSession(adapter, platform, config, level, target, controller::tick));
        if (config.showChatMessages()) {
            platform.sendChat(ChatCopy.downloading(target.folderName()));
        }
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
