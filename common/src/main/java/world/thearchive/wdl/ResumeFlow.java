// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.client.ResumeConfirm;
import world.thearchive.wdl.core.CaptureController;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.browse.DownloadFolders;
import world.thearchive.wdl.core.browse.SinglePlayerTaint;
import world.thearchive.wdl.core.browse.TargetClassification;
import world.thearchive.wdl.core.browse.TargetResolver;
import world.thearchive.wdl.core.export.FinalizeOutputs;
import world.thearchive.wdl.core.export.RestoreOperation;
import world.thearchive.wdl.core.export.RestoreSource;
import world.thearchive.wdl.platform.PlatformBridge;

final class ResumeFlow {
    private final CaptureController controller;
    private final PlatformBridge bridge;
    private final Supplier<WdlConfig> configSupplier;
    private final Consumer<Runnable> deferScreen;
    private final Consumer<DownloadTarget> startDownload;

    ResumeFlow(CaptureController controller, PlatformBridge bridge, Supplier<WdlConfig> configSupplier,
            Consumer<Runnable> deferScreen, Consumer<DownloadTarget> startDownload) {
        this.controller = controller;
        this.bridge = bridge;
        this.configSupplier = configSupplier;
        this.deferScreen = deferScreen;
        this.startDownload = startDownload;
    }

    /**
     * An existing wdl-managed folder takes a RESUME-mode target, so the existing level.dat name is kept. An existing
     * folder that is not a wdl download refuses with the occupant chat before any confirm.
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
        if (start.classification() == TargetClassification.RESUME_EXISTING) {
            Path savesDirectory = Minecraft.getMinecraft().mcDataDir.toPath().resolve("saves");
            DownloadTarget target = TargetResolver.resolveResume(start.target().folderName(), savesDirectory)
                    .withOrigin(start.target().origin());
            if (!DownloadFolders.isWdlManaged(savesDirectory.resolve(target.folderName()))) {
                bridge.sendChat(ChatCopy.refuseOccupant(target.folderName(), true));
                return;
            }
            confirmThenResume(target, target.folderName());
        } else {
            startDownload.accept(start.target());
        }
    }

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
            Path savesDirectory = Minecraft.getMinecraft().mcDataDir.toPath().resolve("saves");
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
     * The guards (already-running, remote-world, currently-open world) run before the confirm, so a resume they refuse
     * never flashes the backup warning first.
     */
    void resume(String name) {
        CaptureState state = controller.state();
        if (state != CaptureState.IDLE) {
            bridge.sendChat(ChatCopy.busy(state, Wdl.isSweepActive()));
            return;
        }
        if (!bridge.isRemoteWorld()) {
            bridge.sendChat(ChatCopy.joinMultiplayer());
            return;
        }
        Path savesDirectory = Minecraft.getMinecraft().mcDataDir.toPath().resolve("saves");
        DownloadTarget target = DownloadFolders.resolveManagedResume(name, savesDirectory);
        if (target == null) {
            bridge.sendChat(ChatCopy.noSuchDownload(name));
            return;
        }
        if (TargetResolver.classifyTarget(target.folderName(), savesDirectory,
                Wdl.loadedWorldPath(Minecraft.getMinecraft())) == TargetClassification.REFUSE_LOADED) {
            bridge.sendChat(ChatCopy.refuseLoaded());
            return;
        }
        confirmThenResume(target.withOrigin(DownloadTarget.Origin.FLOW_DELIBERATE), target.folderName());
    }

    /**
     * The guards run up front so a resume they refuse never flashes the backup warning first.
     */
    private @Nullable NewStart classifyNewStart(String name, boolean deliberate) {
        CaptureState state = controller.state();
        if (state != CaptureState.IDLE) {
            bridge.sendChat(ChatCopy.busy(state, Wdl.isSweepActive()));
            return null;
        }
        if (!bridge.isRemoteWorld()) {
            bridge.sendChat(ChatCopy.joinMultiplayer());
            return null;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        Path savesDirectory = minecraft.mcDataDir.toPath().resolve("saves");
        DownloadTarget target = TargetResolver.resolveNew(name, LocalDate.now(),
                configSupplier.get().appendDateSuffix())
                .withOrigin(deliberate ? DownloadTarget.Origin.FLOW_DELIBERATE : DownloadTarget.Origin.FLOW_AUTO);
        return new NewStart(target, TargetResolver.classifyTarget(target.folderName(), savesDirectory,
                Wdl.loadedWorldPath(minecraft)));
    }

    private static final class NewStart {
        private final DownloadTarget target;
        private final TargetClassification classification;

        NewStart(DownloadTarget target, TargetClassification classification) {
            this.target = target;
            this.classification = classification;
        }

        DownloadTarget target() {
            return target;
        }

        TargetClassification classification() {
            return classification;
        }
    }

    /**
     * The tainted decision (fresh disk read) is the outermost gate, so a hard refusal comes before any advisory warn. A
     * REFUSE stops with the refusal chat, unless the resume was deliberate (the origin tag) and a fresh source
     * discovery finds a clean backup, in which case the refusal becomes the blocked-offer screen; the auto join never
     * opens it. The pre-resume backup reassurance rides on the terminal prompt only (the one whose Continue starts the
     * download), so a two-screen cascade shows it once.
     */
    private void confirmThenResume(DownloadTarget target, String folderName) {
        WdlConfig config = configSupplier.get();
        Path savesDirectory = Minecraft.getMinecraft().mcDataDir.toPath().resolve("saves");
        Path saveFolder = savesDirectory.resolve(folderName);
        SinglePlayerTaint.TaintState taint = SinglePlayerTaint.classify(saveFolder);
        SinglePlayerTaint.Decision decision = SinglePlayerTaint.decide(taint, config.blockTaintedResume());
        if (decision == SinglePlayerTaint.Decision.REFUSE) {
            if (target.origin() == DownloadTarget.Origin.FLOW_DELIBERATE) {
                Optional<RestoreSource> source = RestoreSource.find(savesDirectory, folderName);
                if (source.isPresent()) {
                    RestoreSource pinned = source.get();
                    deferScreen.accept(() -> {
                        Minecraft minecraft = Minecraft.getMinecraft();
                        minecraft.displayGuiScreen(ResumeConfirm.createRestore(
                                "wdl.screen.downloads.confirm_restore_blocked",
                                folderName, sourceZipName(pinned),
                                RestoreOperation.nextSnapshotName(savesDirectory, folderName), config.zipOnResume(),
                                () -> {
                                    minecraft.displayGuiScreen(null);
                                    Wdl.launchRestore(savesDirectory, folderName, pinned);
                                },
                                () -> {
                                    minecraft.displayGuiScreen(null);
                                    bridge.sendChat(ChatCopy.resumeCancelled());
                                }));
                    });
                    return;
                }
            }
            bridge.sendChat(ChatCopy.refuseTainted());
            return;
        }
        boolean mismatch = MapManifest.schemeMismatch(saveFolder, config.remapMapIds());
        if (decision == SinglePlayerTaint.Decision.CONFIRM) {
            Optional<RestoreSource> source = taint == SinglePlayerTaint.TaintState.TAINTED
                    ? RestoreSource.find(savesDirectory, folderName)
                    : Optional.empty();
            deferScreen.accept(() -> {
                Minecraft minecraft = Minecraft.getMinecraft();
                boolean backupHere = config.zipOnResume() && !mismatch;
                Runnable onContinue = () -> gateMapIdMismatch(folderName, mismatch, config.zipOnResume(), () -> {
                    startDownload.accept(target);
                    Minecraft.getMinecraft().displayGuiScreen(null);
                });
                Runnable onCancel = () -> {
                    minecraft.displayGuiScreen(null);
                    bridge.sendChat(ChatCopy.resumeCancelled());
                };
                minecraft.displayGuiScreen(source.isPresent()
                        ? ResumeConfirm.createTaintedRestorable(folderName, sourceZipName(source.get()),
                                FinalizeOutputs.nextBackupName(savesDirectory, folderName), backupHere,
                                onContinue, onCancel)
                        : ResumeConfirm.create("wdl.screen.downloads.confirm_tainted", folderName,
                                FinalizeOutputs.nextBackupName(savesDirectory, folderName), backupHere,
                                onContinue, onCancel));
            });
            return;
        }
        gateMapIdMismatch(folderName, mismatch, config.zipOnResume() && !config.confirmResume(),
                () -> mergeConfirmThenResume(target, folderName, config));
    }

    private static String sourceZipName(RestoreSource source) {
        return source.zip().getFileName().toString();
    }

    private void gateMapIdMismatch(String folderName, boolean mismatch, boolean backupHere, Runnable proceed) {
        if (!mismatch) {
            proceed.run();
            return;
        }
        deferScreen.accept(() -> {
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.displayGuiScreen(ResumeConfirm.create("wdl.screen.downloads.confirm_map_id_mismatch",
                    folderName,
                    FinalizeOutputs.nextBackupName(minecraft.mcDataDir.toPath().resolve("saves"), folderName),
                    backupHere,
                    () -> {
                        minecraft.displayGuiScreen(null);
                        proceed.run();
                    },
                    () -> {
                        minecraft.displayGuiScreen(null);
                        bridge.sendChat(ChatCopy.resumeCancelled());
                    }));
        });
    }

    private void mergeConfirmThenResume(DownloadTarget target, String folderName, WdlConfig config) {
        if (!config.confirmResume()) {
            deferScreen.accept(() -> startDownload.accept(target));
            return;
        }
        deferScreen.accept(() -> {
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.displayGuiScreen(ResumeConfirm.create("wdl.screen.downloads.merge",
                    folderName,
                    FinalizeOutputs.nextBackupName(minecraft.mcDataDir.toPath().resolve("saves"), folderName),
                    config.zipOnResume(),
                    () -> {
                        startDownload.accept(target);
                        minecraft.displayGuiScreen(null);
                    },
                    () -> {
                        minecraft.displayGuiScreen(null);
                        bridge.sendChat(ChatCopy.resumeCancelled());
                    }));
        });
    }
}
