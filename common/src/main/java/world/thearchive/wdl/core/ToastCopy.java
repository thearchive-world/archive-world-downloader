// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * The composed copy of one job-done toast (download complete, download error, settings error): a title key plus a
 * two-line body given as a wdl translation key whose en_us pattern is the template, filled by ordered arguments. Each
 * argument is a literal value or a keyed sub-pattern with one insert, and carries its {@link BrandColors} tint or empty
 * to inherit the body's. The completion body tints its inserted values amber over the default text color; the error
 * body is wholly rust. MC-free so every decision is headless-testable, and the {@code showToasts} gate returns null
 * when nothing is to be enqueued.
 */
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

        /** The RGB tint from {@link BrandColors}, or empty to inherit the body's template tint. */
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

    /** The tint of the body's template text, or empty for the vanilla default toast text color. */
    public OptionalInt bodyColor() {
        return bodyColor;
    }

    public List<Argument> arguments() {
        return arguments;
    }

    /** Whether this is a refusal (mandatory action feedback the bridge dedupes), not a job-done event. */
    public boolean refusal() {
        return refusal;
    }

    /** The completion toast for a download saved as a world folder, or null when {@code showToasts} is off. */
    public static @Nullable ToastCopy completion(boolean showToasts, int chunks, long elapsedMillis,
            String worldFolderName) {
        if (!showToasts) {
            return null;
        }
        return new ToastCopy("wdl.toast.complete.title", "wdl.toast.complete.body_folder", OptionalInt.empty(),
                statsArguments(chunks, elapsedMillis, worldFolderName), false);
    }

    /** The completion toast for a download landing as a zip file, or null when {@code showToasts} is off. */
    public static @Nullable ToastCopy completionZip(boolean showToasts, int chunks, long elapsedMillis,
            String zipFileName) {
        if (!showToasts) {
            return null;
        }
        return new ToastCopy("wdl.toast.complete.title", "wdl.toast.complete.body_zip", OptionalInt.empty(),
                statsArguments(chunks, elapsedMillis, zipFileName), false);
    }

    /**
     * The partial-finish toast for a download saved with some writes lost, wholly amber (the recoverable-state tint,
     * not the rust error tint), or null when {@code showToasts} is off. Reuses the completion body so the stats still
     * show what landed; {@code zip} selects the zip or folder destination line.
     */
    public static @Nullable ToastCopy partial(boolean showToasts, int chunks, long elapsedMillis,
            String destination, boolean zip) {
        if (!showToasts) {
            return null;
        }
        String bodyKey = zip ? "wdl.toast.complete.body_zip" : "wdl.toast.complete.body_folder";
        return new ToastCopy("wdl.toast.partial.title", bodyKey, OptionalInt.of(BrandColors.AMBER),
                statsArguments(chunks, elapsedMillis, destination), false);
    }

    /**
     * The refusal toast for a resume into a singleplayer-opened download, wholly rust. Unlike the job-done toasts a
     * refusal is not gated by {@code showToasts}: it is mandatory action feedback, not an optional notification, so it
     * always shows.
     */
    public static ToastCopy refuseTainted() {
        return new ToastCopy("wdl.refuse.tainted.title", "wdl.refuse.tainted.body",
                OptionalInt.of(BrandColors.RUST), new ArrayList<>(), true);
    }

    /** The refusal toast for a download or resume targeting the currently-open world. */
    public static ToastCopy refuseLoaded() {
        return refusal("wdl.refuse.loaded_world.body");
    }

    /** The refusal toast for a start while a download is already running. */
    static ToastCopy alreadyDownloading() {
        return refusal("wdl.refuse.already_downloading.body");
    }

    /** The refusal toast for a start while the previous download is still saving to disk. */
    static ToastCopy savingInProgress() {
        return refusal("wdl.refuse.saving_in_progress.body");
    }

    /** The refusal toast for a start while a restore is replacing a download folder; carries its own title. */
    static ToastCopy restoringInProgress() {
        return new ToastCopy("wdl.toast.busy_restoring.title", "wdl.toast.busy_restoring.body",
                OptionalInt.of(BrandColors.RUST), new ArrayList<>(), true);
    }

    /** The refusal toast for a start while the launch sweep is still cleaning up after an earlier restore. */
    static ToastCopy restoreSweepInProgress() {
        return new ToastCopy("wdl.toast.busy_restoring_sweep.title", "wdl.toast.busy_restoring_sweep.body",
                OptionalInt.of(BrandColors.RUST), new ArrayList<>(), true);
    }

    /**
     * The start refusal for a capture that is not idle: the running case ({@link #alreadyDownloading}), the
     * still-saving case ({@link #savingInProgress}), or the restore cases, where {@code sweep} picks the launch-cleanup
     * flavor over the player-invoked restore (the caller knows which it dispatched); the idle state never reaches here.
     */
    public static ToastCopy busy(CaptureState state, boolean sweep) {
        if (state == CaptureState.RESTORING) {
            return sweep ? restoreSweepInProgress() : restoringInProgress();
        }
        return state == CaptureState.SAVING ? savingInProgress() : alreadyDownloading();
    }

    /** The refusal toast for a start outside a remote world. */
    public static ToastCopy joinMultiplayer() {
        return refusal("wdl.refuse.join_multiplayer.body");
    }

    /**
     * The completion toast for a restored download folder. Not gated by {@code showToasts}: the replace ran off-screen
     * with no HUD, so this toast is its sole completion feedback.
     */
    public static ToastCopy restored(String folderName) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(amber(folderName));
        return new ToastCopy("wdl.toast.restored.title", "wdl.toast.restored.body", OptionalInt.empty(),
                arguments, false);
    }

    /** The restore refusal for a folder found clean again at the re-check. */
    public static ToastCopy restoreRefusedNotTainted() {
        return restoreRefusal("wdl.toast.restore_refused.body_not_tainted", new ArrayList<>());
    }

    /** The restore refusal for a folder whose singleplayer-opened state could not be read. */
    public static ToastCopy restoreRefusedTaintUnknown() {
        return restoreRefusal("wdl.toast.restore_refused.body_taint_unknown", new ArrayList<>());
    }

    /** The restore refusal for a pinned source zip that changed on disk since it was picked. */
    public static ToastCopy restoreRefusedSourceChanged() {
        return restoreRefusal("wdl.toast.restore_refused.body_source_changed", new ArrayList<>());
    }

    /** The restore refusal for a folder that is open in a running game. */
    public static ToastCopy restoreRefusedWorldInUse() {
        return restoreRefusal("wdl.toast.restore_refused.body_world_in_use", new ArrayList<>());
    }

    /** The restore refusal for a pre-replace safety backup that could not be written. */
    public static ToastCopy restoreRefusedSnapshotFailed() {
        return restoreRefusal("wdl.toast.restore_refused.body_snapshot_failed", new ArrayList<>());
    }

    /** The restore refusal for a staging filesystem too full to extract into. */
    public static ToastCopy restoreRefusedDiskFull() {
        return restoreRefusal("wdl.toast.restore_refused.body_disk_full", new ArrayList<>());
    }

    /** The restore refusal for a source zip the extractor declined to unpack. */
    public static ToastCopy restoreRefusedExtractRefused() {
        return restoreRefusal("wdl.toast.restore_refused.body_extract_refused", new ArrayList<>());
    }

    /** The restore failure whose swap did not complete; {@code survivingPaths} names what was kept where. */
    public static ToastCopy restoreRefusedSwapFailed(String survivingPaths) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(new Argument(null, survivingPaths, OptionalInt.empty()));
        return restoreRefusal("wdl.toast.restore_refused.body_swap_failed", arguments);
    }

    /**
     * The restore failure whose rollback could not reinstate the original because the folder name was reoccupied
     * mid-restore, so the original was kept at {@code siblingName}. Distinct from the sweep's amber
     * {@link #sweepRelocated} success notice: a player restore that relocates has failed, so this wears the rust
     * restore-blocked framing.
     */
    public static ToastCopy restoreRefusedRelocated(String siblingName) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(new Argument(null, siblingName, OptionalInt.empty()));
        return restoreRefusal("wdl.toast.restore_refused.body_relocated", arguments);
    }

    /** The sweep notice for a kept-aside folder moved back to its original name, wholly amber. */
    public static ToastCopy sweepMovedBack(String folderName) {
        return sweepNotice("wdl.toast.sweep_moved_back", folderName);
    }

    /** The notice for a kept-aside folder relocated to a visible sibling because its name was reoccupied. */
    public static ToastCopy sweepRelocated(String siblingName) {
        return sweepNotice("wdl.toast.sweep_relocated", siblingName);
    }

    /** The sweep notice for a still-absent folder whose recovery stays deferred behind a live session. */
    public static ToastCopy sweepMissingDeferred(String folderName) {
        return sweepNotice("wdl.toast.sweep_missing_deferred", folderName);
    }

    /**
     * The refusal for a name occupied by a file or a folder that is not a wdl download; {@code folderName} is the
     * filesystem-reported spelling, carried for the copy to name. {@code suggestRename} selects the variant that adds
     * the name-choosing advice, shown on the name-entry surfaces (a typed name or quick start, where the player has
     * something to change); the row and chip surfaces pass false, since nothing there involves choosing a name.
     */
    public static ToastCopy refuseOccupant(String folderName, boolean suggestRename) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(amber(folderName));
        String bodyKey = suggestRename ? "wdl.refuse.occupant.body_named_advice" : "wdl.refuse.occupant.body";
        return new ToastCopy("wdl.refuse.occupant.title", bodyKey,
                OptionalInt.of(BrandColors.RUST), arguments, true);
    }

    /** The refusal for a download folder that no longer exists at its name, carried for the copy to name. */
    public static ToastCopy refuseFolderMissing(String folderName) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(amber(folderName));
        return new ToastCopy("wdl.refuse.folder_missing.title", "wdl.refuse.folder_missing.body",
                OptionalInt.of(BrandColors.RUST), arguments, true);
    }

    /** The refusal for a NEW name a torn restore attempt still stages under the temporary root. */
    public static ToastCopy refuseTornAttempt() {
        return new ToastCopy("wdl.refuse.torn_attempt.title", "wdl.refuse.torn_attempt.body",
                OptionalInt.of(BrandColors.RUST), new ArrayList<>(), true);
    }

    /** A per-cause restore refusal body under the shared restore-blocked title, wholly rust, never gated. */
    private static ToastCopy restoreRefusal(String bodyKey, List<Argument> arguments) {
        return new ToastCopy("wdl.toast.restore_refused.title", bodyKey, OptionalInt.of(BrandColors.RUST),
                arguments, true);
    }

    /** A restore-cleanup notice naming one folder, wholly amber (the recoverable-state tint), never gated. */
    private static ToastCopy sweepNotice(String keyBase, String name) {
        List<Argument> arguments = new ArrayList<>();
        arguments.add(new Argument(null, name, OptionalInt.empty()));
        return new ToastCopy(keyBase + ".title", keyBase + ".body", OptionalInt.of(BrandColors.AMBER),
                arguments, false);
    }

    /** A no-argument refusal body under the shared verb-neutral title, wholly rust and never gated. */
    private static ToastCopy refusal(String bodyKey) {
        return new ToastCopy("wdl.refuse.title", bodyKey, OptionalInt.of(BrandColors.RUST),
                new ArrayList<>(), true);
    }

    /** The error toast for a download that could not be saved, or null when {@code showToasts} is off. */
    public static @Nullable ToastCopy downloadError(boolean showToasts, SaveFailureReason reason) {
        return failure("wdl.toast.error.title", showToasts, reason);
    }

    /** The error toast for a settings file that could not be written, or null when {@code showToasts} is off. */
    public static @Nullable ToastCopy settingsError(boolean showToasts, SaveFailureReason reason) {
        return failure("wdl.toast.settings_error.title", showToasts, reason);
    }

    /** The shared failure body carrying the reason under {@code titleKey}, wholly rust. */
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
