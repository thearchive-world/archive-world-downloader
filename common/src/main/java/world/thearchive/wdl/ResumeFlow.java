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

/**
 * The resume/confirm state machine behind the command and keybind download-start surfaces: the toggle keybind,
 * auto-download-on-join, and the {@code /wdl start} and {@code /wdl resume} commands (the downloads screen runs its own
 * parallel cascade and does not route here). It classifies a start name against the saves directory: the quick-start
 * flow routes a fresh name to a NEW start and an existing folder through the tainted to map-id-mismatch to merge
 * confirm cascade, while {@code /wdl start <name>} refuses an existing folder outright, building the three
 * {@link ResumeConfirm} screens. Constructed once by {@link Wdl#initialize}: it reads the shared controller for the
 * idle guard, sends chat through the bridge, loads config fresh per decision, defers each screen open onto Wdl's
 * client-tick slot, and invokes Wdl's terminal start action. It builds screens and reads paths, so it is shared-SPI,
 * not core.
 */
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
     * The smart quick-start flow behind the keybind and auto-download, whose callers pass a concrete default name and
     * whether the start is a deliberate player act (the keybind) or the automatic join hook, the split that decides the
     * origin tag and with it whether a blocked resume may offer a restore: an existing wdl-managed folder routes
     * through the merge confirm and resumes it (a RESUME-mode target, so the existing level.dat name is kept), or
     * continues silently when {@code confirmResume} is off; an existing folder that is not a wdl download refuses with
     * the occupant chat before any confirm; a fresh name starts a NEW folder, dated per {@code appendDateSuffix}.
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
            Path savesDirectory = Minecraft.getMinecraft().gameDir.toPath().resolve("saves");
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
            Path savesDirectory = Minecraft.getMinecraft().gameDir.toPath().resolve("saves");
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
     * {@code /wdl resume <name>}: validate the name is an existing wdl-managed download, then route through the shared
     * merge confirm before resuming. The guards (already-running, remote-world, currently-open world) run before the
     * confirm, so a resume that will be refused never flashes the backup warning first; a name that is not a
     * wdl-managed folder, or that is empty after containment, is rejected in chat and starts nothing.
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
        Path savesDirectory = Minecraft.getMinecraft().gameDir.toPath().resolve("saves");
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
     * Guard (already-running, remote-world) then resolve the name into a NEW target and classify it against the saves
     * directory, or null when a guard rejected it (the chat notice is already sent). The guards run up front so a
     * resume that will be refused never flashes the backup warning first.
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
        Path savesDirectory = minecraft.gameDir.toPath().resolve("saves");
        DownloadTarget target = TargetResolver.resolveNew(name, LocalDate.now(),
                configSupplier.get().appendDateSuffix())
                .withOrigin(deliberate ? DownloadTarget.Origin.FLOW_DELIBERATE : DownloadTarget.Origin.FLOW_AUTO);
        return new NewStart(target, TargetResolver.classifyTarget(target.folderName(), savesDirectory,
                Wdl.loadedWorldPath(minecraft)));
    }

    /** A resolved NEW target paired with its on-disk classification, the shared result of {@link #classifyNewStart}. */
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
     * The command, keybind, and auto-download resume junction: the tainted decision (fresh disk read) is the outermost
     * gate, so a hard refusal comes before any advisory warn. A REFUSE stops with the refusal chat, unless the resume
     * was deliberate (the origin tag) and a fresh source discovery finds a clean backup, in which case the refusal
     * becomes the blocked-offer screen whose Restore dispatches the replace; the auto join never opens it, so an
     * unattended start still refuses silently. A CONFIRM shows the tainted confirm whose Continue runs the map-id
     * mismatch gate then a direct start (the tainted confirm stands in for the merge confirm, one prompt not two),
     * picking the restorable tip variant when the folder is tainted and a source exists (an unknown taint keeps the
     * plain tainted copy); an ALLOW runs the map-id mismatch gate then the normal merge confirm. The
     * tainted-and-blocked case never flashes the mismatch confirm. The pre-resume backup reassurance rides on the
     * terminal prompt only (the one whose Continue starts the download), so a two-screen cascade shows it once.
     */
    private void confirmThenResume(DownloadTarget target, String folderName) {
        WdlConfig config = configSupplier.get();
        Path savesDirectory = Minecraft.getMinecraft().gameDir.toPath().resolve("saves");
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

    /**
     * The map-id mismatch pre-flight for a resume: when {@code mismatch} is set, show the mismatch confirm (deferred to
     * the next client tick) whose Continue runs {@code proceed} and whose Cancel returns to the game with a chat note;
     * otherwise run {@code proceed} now. {@code backupHere} is whether this confirm is the terminal prompt that commits
     * the resume, so it alone carries the backup reassurance while the earlier gates pass false, showing the line once.
     * The reusable middle layer between the tainted gate and the resume tail.
     */
    private void gateMapIdMismatch(String folderName, boolean mismatch, boolean backupHere, Runnable proceed) {
        if (!mismatch) {
            proceed.run();
            return;
        }
        deferScreen.accept(() -> {
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.displayGuiScreen(ResumeConfirm.create("wdl.screen.downloads.confirm_map_id_mismatch",
                    folderName,
                    FinalizeOutputs.nextBackupName(minecraft.gameDir.toPath().resolve("saves"), folderName),
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

    /**
     * Show the shared merge confirm for a command-driven resume, deferred to the next client tick: the target is always
     * a RESUME, so the existing level.dat name is kept. Continue starts the download and returns to the game; Cancel
     * returns to the game with a chat note. When {@code confirmResume} is off the prompt is skipped and the resume
     * starts directly; the pre-merge backup is independent, governed by {@code zipOnResume}.
     */
    private void mergeConfirmThenResume(DownloadTarget target, String folderName, WdlConfig config) {
        if (!config.confirmResume()) {
            deferScreen.accept(() -> startDownload.accept(target));
            return;
        }
        deferScreen.accept(() -> {
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.displayGuiScreen(ResumeConfirm.create("wdl.screen.downloads.merge",
                    folderName,
                    FinalizeOutputs.nextBackupName(minecraft.gameDir.toPath().resolve("saves"), folderName),
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
