// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

public final class ToastCopy {
    /**
     * One argument of the body pattern: literal text when {@code translationKey} is null, else a keyed sub-pattern with
     * {@code text} as its single insert (empty when the sub-pattern has no slot).
     */
    public static final class Argument {
        private final @Nullable String translationKey;
        private final String text;
        private final OptionalInt color;

        private Argument(@Nullable String translationKey, String text, OptionalInt color) {
            this.translationKey = translationKey;
            this.text = text;
            this.color = color;
        }

        public @Nullable String translationKey() {
            return translationKey;
        }

        public String text() {
            return text;
        }

        public OptionalInt color() {
            return color;
        }
    }

    private final String titleKey;
    private final String bodyKey;
    private final OptionalInt bodyColor;
    private final List<Argument> arguments;
    private final boolean refusal;

    private ToastCopy(String titleKey, String bodyKey, OptionalInt bodyColor, List<Argument> arguments,
            boolean refusal) {
        this.titleKey = titleKey;
        this.bodyKey = bodyKey;
        this.bodyColor = bodyColor;
        this.arguments = Collections.unmodifiableList(arguments);
        this.refusal = refusal;
    }

    public String titleKey() {
        return titleKey;
    }

    public String bodyKey() {
        return bodyKey;
    }

    public OptionalInt bodyColor() {
        return bodyColor;
    }

    public List<Argument> arguments() {
        return arguments;
    }

    public boolean refusal() {
        return refusal;
    }

    public static @Nullable ToastCopy completion(boolean showToasts, int chunks, long elapsedMillis,
            String worldFolderName) {
        if (!showToasts) {
            return null;
        }
        return new ToastCopy("wdl.toast.complete.title", "wdl.toast.complete.body_folder", OptionalInt.empty(),
                statsArguments(chunks, elapsedMillis, worldFolderName), false);
    }

    public static @Nullable ToastCopy completionZip(boolean showToasts, int chunks, long elapsedMillis,
            String zipFileName) {
        if (!showToasts) {
            return null;
        }
        return new ToastCopy("wdl.toast.complete.title", "wdl.toast.complete.body_zip", OptionalInt.empty(),
                statsArguments(chunks, elapsedMillis, zipFileName), false);
    }

    public static @Nullable ToastCopy partial(boolean showToasts, int chunks, long elapsedMillis,
            String destination, boolean zip) {
        if (!showToasts) {
            return null;
        }
        String bodyKey = zip ? "wdl.toast.complete.body_zip" : "wdl.toast.complete.body_folder";
        return new ToastCopy("wdl.toast.partial.title", bodyKey, OptionalInt.of(BrandColors.AMBER),
                statsArguments(chunks, elapsedMillis, destination), false);
    }

    public static ToastCopy refuseTainted() {
        return new ToastCopy("wdl.refuse.tainted.title", "wdl.refuse.tainted.body",
                OptionalInt.of(BrandColors.RUST), new ArrayList<>(), true);
    }

    public static ToastCopy refuseLoaded() {
        return refusal("wdl.refuse.loaded_world.body");
    }

    static ToastCopy alreadyDownloading() {
        return refusal("wdl.refuse.already_downloading.body");
    }

    static ToastCopy savingInProgress() {
        return refusal("wdl.refuse.saving_in_progress.body");
    }

    static ToastCopy restoringInProgress() {
        return new ToastCopy("wdl.toast.busy_restoring.title", "wdl.toast.busy_restoring.body",
                OptionalInt.of(BrandColors.RUST), new ArrayList<>(), true);
    }

    static ToastCopy restoreSweepInProgress() {
        return new ToastCopy("wdl.toast.busy_restoring_sweep.title", "wdl.toast.busy_restoring_sweep.body",
                OptionalInt.of(BrandColors.RUST), new ArrayList<>(), true);
    }

    public static ToastCopy busy(CaptureState state, boolean sweep) {
        if (state == CaptureState.RESTORING) {
            return sweep ? restoreSweepInProgress() : restoringInProgress();
        }
        return state == CaptureState.SAVING ? savingInProgress() : alreadyDownloading();
    }

    public static ToastCopy joinMultiplayer() {
        return refusal("wdl.refuse.join_multiplayer.body");
    }

    public static ToastCopy restored(String folderName) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(amber(folderName));
        return new ToastCopy("wdl.toast.restored.title", "wdl.toast.restored.body", OptionalInt.empty(),
                arguments, false);
    }

    public static ToastCopy restoreRefusedNotTainted() {
        return restoreRefusal("wdl.toast.restore_refused.body_not_tainted", new ArrayList<>());
    }

    public static ToastCopy restoreRefusedTaintUnknown() {
        return restoreRefusal("wdl.toast.restore_refused.body_taint_unknown", new ArrayList<>());
    }

    public static ToastCopy restoreRefusedSourceChanged() {
        return restoreRefusal("wdl.toast.restore_refused.body_source_changed", new ArrayList<>());
    }

    public static ToastCopy restoreRefusedWorldInUse() {
        return restoreRefusal("wdl.toast.restore_refused.body_world_in_use", new ArrayList<>());
    }

    public static ToastCopy restoreRefusedSnapshotFailed() {
        return restoreRefusal("wdl.toast.restore_refused.body_snapshot_failed", new ArrayList<>());
    }

    public static ToastCopy restoreRefusedDiskFull() {
        return restoreRefusal("wdl.toast.restore_refused.body_disk_full", new ArrayList<>());
    }

    public static ToastCopy restoreRefusedExtractRefused() {
        return restoreRefusal("wdl.toast.restore_refused.body_extract_refused", new ArrayList<>());
    }

    public static ToastCopy restoreRefusedSwapFailed(String survivingPaths) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(new Argument(null, survivingPaths, OptionalInt.empty()));
        return restoreRefusal("wdl.toast.restore_refused.body_swap_failed", arguments);
    }

    public static ToastCopy restoreRefusedRelocated(String siblingName) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(new Argument(null, siblingName, OptionalInt.empty()));
        return restoreRefusal("wdl.toast.restore_refused.body_relocated", arguments);
    }

    public static ToastCopy sweepMovedBack(String folderName) {
        return sweepNotice("wdl.toast.sweep_moved_back", folderName);
    }

    public static ToastCopy sweepRelocated(String siblingName) {
        return sweepNotice("wdl.toast.sweep_relocated", siblingName);
    }

    public static ToastCopy sweepMissingDeferred(String folderName) {
        return sweepNotice("wdl.toast.sweep_missing_deferred", folderName);
    }

    public static ToastCopy refuseOccupant(String folderName, boolean suggestRename) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(amber(folderName));
        String bodyKey = suggestRename ? "wdl.refuse.occupant.body_named_advice" : "wdl.refuse.occupant.body";
        return new ToastCopy("wdl.refuse.occupant.title", bodyKey,
                OptionalInt.of(BrandColors.RUST), arguments, true);
    }

    public static ToastCopy refuseFolderMissing(String folderName) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(amber(folderName));
        return new ToastCopy("wdl.refuse.folder_missing.title", "wdl.refuse.folder_missing.body",
                OptionalInt.of(BrandColors.RUST), arguments, true);
    }

    public static ToastCopy refuseTornAttempt() {
        return new ToastCopy("wdl.refuse.torn_attempt.title", "wdl.refuse.torn_attempt.body",
                OptionalInt.of(BrandColors.RUST), new ArrayList<>(), true);
    }

    private static ToastCopy restoreRefusal(String bodyKey, List<Argument> arguments) {
        return new ToastCopy("wdl.toast.restore_refused.title", bodyKey, OptionalInt.of(BrandColors.RUST),
                arguments, true);
    }

    private static ToastCopy sweepNotice(String keyBase, String name) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(new Argument(null, name, OptionalInt.empty()));
        return new ToastCopy(keyBase + ".title", keyBase + ".body", OptionalInt.of(BrandColors.AMBER),
                arguments, false);
    }

    private static ToastCopy refusal(String bodyKey) {
        return new ToastCopy("wdl.refuse.title", bodyKey, OptionalInt.of(BrandColors.RUST),
                new ArrayList<>(), true);
    }

    public static @Nullable ToastCopy downloadError(boolean showToasts, SaveFailureReason reason) {
        return failure("wdl.toast.error.title", showToasts, reason);
    }

    public static @Nullable ToastCopy settingsError(boolean showToasts, SaveFailureReason reason) {
        return failure("wdl.toast.settings_error.title", showToasts, reason);
    }

    private static @Nullable ToastCopy failure(String titleKey, boolean showToasts, SaveFailureReason reason) {
        if (!showToasts) {
            return null;
        }
        List<Argument> arguments = new ArrayList<>();
        arguments.add(new Argument(reason.translationKey(), reason.text(), OptionalInt.empty()));
        return new ToastCopy(titleKey, "wdl.toast.error.body", OptionalInt.of(BrandColors.RUST),
                arguments, false);
    }

    private static List<Argument> statsArguments(int chunks, long elapsedMillis, String destination) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(amberCount("wdl.toast.chunks", chunks));
        arguments.add(amber(CaptureStatus.completionElapsed(elapsedMillis)));
        arguments.add(amber(destination));
        return arguments;
    }

    private static Argument amber(String text) {
        return new Argument(null, text, OptionalInt.of(BrandColors.AMBER));
    }

    private static Argument amberCount(String keyBase, int count) {
        return new Argument(keyBase, Integer.toString(count), OptionalInt.of(BrandColors.AMBER));
    }
}
