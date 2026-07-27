// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.browse.DownloadFolders;
import world.thearchive.wdl.core.browse.TargetClassification;
import world.thearchive.wdl.core.browse.TargetResolver;
import world.thearchive.wdl.platform.PlatformBridge;

/**
 * The download-start flow behind the toggle keybind and the explicit named start: it turns a start name into a
 * {@link DownloadTarget}, classifies it against what is already in the saves directory, and either hands that to Wdl's
 * terminal start action or refuses in chat. Constructed once by {@link Wdl#initialize}, which supplies the bridge it
 * asks about the world and speaks through, a config supplier read fresh per decision so a hand-edit applies to the next
 * download, and the start action itself.
 */
final class ResumeFlow {
    private final PlatformBridge bridge;
    private final Supplier<WdlConfig> configSupplier;
    private final Consumer<DownloadTarget> startDownload;

    ResumeFlow(PlatformBridge bridge, Supplier<WdlConfig> configSupplier,
            Consumer<DownloadTarget> startDownload) {
        this.bridge = bridge;
        this.configSupplier = configSupplier;
        this.startDownload = startDownload;
    }

    /**
     * The quick-start flow behind the keybind, whose caller passes a concrete default name and whether the start is a
     * deliberate player act, the split that decides the origin tag: the currently-loaded world is refused, and any
     * other name starts a NEW folder, dated per {@code appendDateSuffix}.
     */
    void begin(String defaultName, boolean deliberate) {
        NewStart start = classifyNewStart(defaultName, deliberate);
        if (start == null) {
            return;
        }
        if (start.classification() == TargetClassification.REFUSE_LOADED) {
            bridge.sendChat(ChatCopy.refuseLoaded());
            return;
        }
        startDownload.accept(start.target());
    }

    /**
     * {@code /wdl start <name>}: start a NEW download of that name. An explicit start is an intentional, named act, so
     * a name that sanitizes to nothing is refused in chat; only this command path validates, since {@link #begin}'s
     * default name is trusted usable. Keeps the {@code start}/{@code resume} verbs domain-specific: a name that is
     * already a download is refused here, pointing the player at {@code /wdl resume}, rather than silently continuing
     * it.
     */
    void startNamed(String name) {
        if (!TargetResolver.hasUsableName(name)) {
            bridge.sendChat(ChatCopy.startNeedsName());
            return;
        }
        NewStart start = classifyNewStart(name, true);
        if (start == null) {
            return;
        }
        if (start.classification() == TargetClassification.REFUSE_LOADED) {
            bridge.sendChat(ChatCopy.refuseLoaded());
            return;
        }
        if (start.classification() == TargetClassification.RESUME_EXISTING) {
            // The managed gate ahead of the already-a-download reply: pointing the player at
            // /wdl resume for a folder that is not a wdl download would send them into a dead end.
            Path savesDirectory = Minecraft.getInstance().getLevelSource().getBaseDir();
            if (!DownloadFolders.isWdlManaged(savesDirectory.resolve(start.target().folderName()))) {
                bridge.sendChat(ChatCopy.refuseOccupant(start.target().folderName(), true));
            } else {
                bridge.sendChat(ChatCopy.downloadExists(start.target().folderName()));
            }
        } else {
            startDownload.accept(start.target());
        }
    }

    /**
     * Guard, then resolve {@code name} into a NEW target tagged with the origin its caller reports and classify it
     * against the saves directory, or null when the guard rejected it (the chat notice is already sent): a world the
     * client is not connected to as a server has nothing to download.
     */
    private @Nullable NewStart classifyNewStart(String name, boolean deliberate) {
        if (!bridge.isRemoteWorld()) {
            bridge.sendChat(ChatCopy.joinMultiplayer());
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Path savesDirectory = minecraft.getLevelSource().getBaseDir();
        DownloadTarget target = TargetResolver.resolveNew(name, LocalDate.now(),
                configSupplier.get().appendDateSuffix())
                .withOrigin(deliberate ? DownloadTarget.Origin.FLOW_DELIBERATE : DownloadTarget.Origin.FLOW_AUTO);
        return new NewStart(target, TargetResolver.classifyTarget(target.folderName(), savesDirectory,
                Wdl.loadedWorldPath(minecraft)));
    }

    /** A resolved NEW target paired with its on-disk classification, the shared result of {@link #classifyNewStart}. */
    private record NewStart(DownloadTarget target, TargetClassification classification) {}
}
