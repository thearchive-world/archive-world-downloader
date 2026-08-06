// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * The job-done toast copy is MC-free and unit-tested headless: the title and two-line body are wdl.toast translation
 * keys resolved here against the shipped en_us lang file, with the folder and zip destination variants, the
 * amber-over-default completion tint, the wholly-rust error tint, and the showToasts gate.
 */
class ToastCopyTest {
    private static final Map<String, String> LANG = Collections.unmodifiableMap(loadLang());

    @Test
    void completionComposesTheTwoLineFolderBody() {
        ToastCopy toast = ToastCopy.completion(true, 12345, 247_000, "base_20260702");

        assertNotNull(toast);
        assertEquals("Download complete", requireKey(toast.titleKey()));
        assertEquals("chunks 12345 / 4:07\nSaved to base_20260702/", resolve(toast));
    }

    @Test
    void completionTintsOnlyTheInsertedArgumentsAmber() {
        ToastCopy toast = ToastCopy.completion(true, 12345, 247_000, "base_20260702");

        assertNotNull(toast);
        assertEquals(List.of("12345", "4:07", "base_20260702"), tintedTexts(toast, BrandColors.AMBER));
        assertEquals(3, tintedCount(toast), "the template text around the arguments stays default");
    }

    @Test
    void completionChunkCountReadsAsLabelThenNumber() {
        assertEquals("chunks 1 / 0:07\nSaved to w/", resolve(requireCompletion(1, 7_000)));
        assertEquals("chunks 2 / 0:07\nSaved to w/", resolve(requireCompletion(2, 7_000)));
        assertEquals("chunks 12345 / 0:07\nSaved to w/", resolve(requireCompletion(12345, 7_000)));
    }

    @Test
    void completionElapsedClampsNegativeToZero() {
        assertEquals("chunks 1 / 0:00\nSaved to w/", resolve(requireCompletion(1, -1)));
    }

    @Test
    void completionZipComposesSavedAsWithoutTheFolderSlash() {
        ToastCopy toast = ToastCopy.completionZip(true, 1, 7_000, "base.zip");

        assertNotNull(toast);
        assertEquals("Download complete", requireKey(toast.titleKey()));
        assertEquals("chunks 1 / 0:07\nSaved as base.zip", resolve(toast));
        assertEquals(List.of("1", "0:07", "base.zip"), tintedTexts(toast, BrandColors.AMBER));
    }

    @Test
    void partialComposesTheAmberIncompleteFolderToast() {
        ToastCopy toast = ToastCopy.partial(true, 343, 125_000, "base_20260702", false);

        assertNotNull(toast);
        assertEquals("Download incomplete", requireKey(toast.titleKey()));
        assertEquals("chunks 343 / 2:05\nSaved to base_20260702/", resolve(toast));
        assertEquals(OptionalInt.of(BrandColors.AMBER), toast.bodyColor());
    }

    @Test
    void partialUsesTheZipBodyWhenTheDownloadLandedAsZip() {
        ToastCopy toast = ToastCopy.partial(true, 343, 125_000, "base.zip", true);

        assertNotNull(toast);
        assertEquals("chunks 343 / 2:05\nSaved as base.zip", resolve(toast));
        assertEquals(OptionalInt.of(BrandColors.AMBER), toast.bodyColor());
    }

    @Test
    void partialGatedOffByShowToasts() {
        assertNull(ToastCopy.partial(false, 1, 7_000, "w", false));
    }

    @Test
    void errorBodyIsTheReasonRecoloredRustWhole() {
        ToastCopy toast = ToastCopy.downloadError(true, SaveFailureReason.literal("disk full"));

        assertNotNull(toast);
        assertEquals("Download error", requireKey(toast.titleKey()));
        assertEquals("Save failed: disk full", resolve(toast));
        assertEquals(OptionalInt.of(BrandColors.RUST), toast.bodyColor());
        assertEquals(1, toast.arguments().size(), "the error body carries the reason and nothing else");
        assertEquals(OptionalInt.empty(), toast.arguments().get(0).color(), "the reason inherits the rust");
    }

    @Test
    void errorBodyKeysTheFilesystemCategoryReason() {
        ToastCopy toast = ToastCopy.downloadError(true, SaveFailureReason.keyed("wdl.reason.access_denied"));

        assertNotNull(toast);
        assertEquals("Save failed: access denied", resolve(toast));
    }

    @Test
    void settingsErrorWearsTheSettingsTitleNotTheDownloadOne() {
        ToastCopy toast = ToastCopy.settingsError(true, SaveFailureReason.keyed("wdl.reason.access_denied"));

        assertNotNull(toast);
        assertEquals("Settings error", requireKey(toast.titleKey()));
        assertEquals("Save failed: access denied", resolve(toast));
        assertEquals(OptionalInt.of(BrandColors.RUST), toast.bodyColor());
        assertFalse(toast.refusal());
    }

    @Test
    void refuseTaintedResolvesTitleAndWhollyRustBody() {
        ToastCopy toast = ToastCopy.refuseTainted();

        assertNotNull(toast);
        assertEquals("Can't resume", requireKey(toast.titleKey()));
        assertEquals("This world was opened in singleplayer and may contain generated (non-server) chunks. "
                + "Start a fresh download instead of resuming.", resolve(toast));
        assertEquals(OptionalInt.of(BrandColors.RUST), toast.bodyColor());
        assertEquals(List.of(), toast.arguments());
        assertTrue(toast.refusal());
    }

    @Test
    void refusalFactoriesShareTheNeutralTitleAndRustBody() {
        List<ToastCopy> refusals = List.of(ToastCopy.refuseLoaded(), ToastCopy.alreadyDownloading(),
                ToastCopy.joinMultiplayer());

        assertEquals("That world is currently open.", resolve(refusals.get(0)));
        assertEquals("Already downloading.", resolve(refusals.get(1)));
        assertEquals("Join a multiplayer server to download its world.", resolve(refusals.get(2)));
        for (ToastCopy refusal : refusals) {
            assertEquals("Download blocked", requireKey(refusal.titleKey()));
            assertEquals(OptionalInt.of(BrandColors.RUST), refusal.bodyColor());
            assertTrue(refusal.refusal(), refusal.bodyKey());
        }
    }

    @Test
    void savingInProgressWearsTheNeutralTitleAndRustBody() {
        ToastCopy toast = ToastCopy.savingInProgress();

        assertEquals("Download blocked", requireKey(toast.titleKey()));
        assertEquals("Still saving the download. Try again when it's done.", resolve(toast));
        assertEquals(OptionalInt.of(BrandColors.RUST), toast.bodyColor());
        assertTrue(toast.refusal());
    }

    @Test
    void busyPicksTheRunningSavingOrRestoringRefusalByState() {
        assertEquals("Already downloading.", resolve(ToastCopy.busy(CaptureState.RECORDING, false)));
        assertEquals("Still saving the download. Try again when it's done.",
                resolve(ToastCopy.busy(CaptureState.SAVING, false)));
        assertEquals("Still restoring the download. Try again when it's done.",
                resolve(ToastCopy.busy(CaptureState.RESTORING, false)));
        assertEquals("Still cleaning up an earlier restore. Try again when it's done.",
                resolve(ToastCopy.busy(CaptureState.RESTORING, true)));
    }

    @Test
    void busyRestoringToastsWearTheirPinnedTitlesAndRustBodies() {
        ToastCopy restore = ToastCopy.busy(CaptureState.RESTORING, false);
        ToastCopy sweep = ToastCopy.busy(CaptureState.RESTORING, true);

        assertEquals("wdl.toast.busy_restoring.title", restore.titleKey());
        assertEquals("wdl.toast.busy_restoring_sweep.title", sweep.titleKey());
        assertEquals("Download blocked", requireKey(restore.titleKey()));
        assertEquals("Download blocked", requireKey(sweep.titleKey()));
        assertEquals(OptionalInt.of(BrandColors.RUST), restore.bodyColor());
        assertEquals(OptionalInt.of(BrandColors.RUST), sweep.bodyColor());
        assertTrue(restore.refusal());
        assertTrue(sweep.refusal());
        assertEquals(List.of(), restore.arguments());
        assertEquals(List.of(), sweep.arguments());
    }

    @Test
    void restoredResolvesWithTheAmberFolderInsert() {
        ToastCopy toast = ToastCopy.restored("base_2026-07-01");

        assertEquals("Restore complete", requireKey(toast.titleKey()));
        assertEquals("Restored base_2026-07-01 from its backup.", resolve(toast));
        assertEquals(List.of("base_2026-07-01"), tintedTexts(toast, BrandColors.AMBER));
        assertFalse(toast.refusal());
    }

    @Test
    void restoreRefusedCausesShareTheTitleAndResolvePerCauseBodies() {
        Map<ToastCopy, String> expected = Map.of(
                ToastCopy.restoreRefusedNotTainted(),
                "This download is no longer marked as opened in singleplayer, so there is nothing to restore.",
                ToastCopy.restoreRefusedTaintUnknown(),
                "Couldn't tell whether this download was opened in singleplayer, so nothing was changed.",
                ToastCopy.restoreRefusedSourceChanged(),
                "The backup file changed on disk. Try the restore again.",
                ToastCopy.restoreRefusedWorldInUse(),
                "That world is currently open. Close it and try again.",
                ToastCopy.restoreRefusedSnapshotFailed(),
                "The safety backup could not be written, so nothing was changed.",
                ToastCopy.restoreRefusedDiskFull(),
                "Not enough disk space to restore.",
                ToastCopy.restoreRefusedExtractRefused(),
                "The backup could not be unpacked, so nothing was changed.");
        for (Map.Entry<ToastCopy, String> entry : expected.entrySet()) {
            ToastCopy toast = entry.getKey();
            assertEquals("Restore blocked", requireKey(toast.titleKey()));
            assertEquals(entry.getValue(), resolve(toast));
            assertEquals(OptionalInt.of(BrandColors.RUST), toast.bodyColor());
            assertTrue(toast.refusal(), toast.bodyKey());
        }
    }

    @Test
    void restoreRefusedSwapFailedNamesTheSurvivingPaths() {
        ToastCopy toast = ToastCopy.restoreRefusedSwapFailed(".wdl-restore-work/base-1/aside/base");

        assertEquals("Restore blocked", requireKey(toast.titleKey()));
        assertEquals("The restore could not finish. Kept files: .wdl-restore-work/base-1/aside/base",
                resolve(toast));
        assertEquals(OptionalInt.of(BrandColors.RUST), toast.bodyColor());
        assertEquals(OptionalInt.empty(), toast.arguments().get(0).color(), "the paths inherit the rust");
        assertTrue(toast.refusal());
    }

    @Test
    void restoreRefusedRelocatedNamesTheKeptSiblingUnderTheBlockedTitle() {
        ToastCopy toast = ToastCopy.restoreRefusedRelocated("base_(2)");

        assertEquals("Restore blocked", requireKey(toast.titleKey()));
        assertEquals("The restore could not finish because that name is now in use. "
                + "Kept your previous download as base_(2).", resolve(toast));
        assertEquals(OptionalInt.of(BrandColors.RUST), toast.bodyColor());
        assertEquals("base_(2)", toast.arguments().get(0).text());
        assertEquals(OptionalInt.empty(), toast.arguments().get(0).color(), "the sibling inherits the rust");
        assertTrue(toast.refusal());
    }

    @Test
    void sweepNoticesResolveAmberWithTheirNameInserts() {
        ToastCopy movedBack = ToastCopy.sweepMovedBack("base");
        ToastCopy relocated = ToastCopy.sweepRelocated("base_(2)");
        ToastCopy missingDeferred = ToastCopy.sweepMissingDeferred("base");

        assertEquals("Put base back in place.", resolve(movedBack));
        assertEquals("Kept the previous download as base_(2).", resolve(relocated));
        assertEquals("base is still missing; it will be put back once it is no longer in use.",
                resolve(missingDeferred));
        for (ToastCopy notice : List.of(movedBack, relocated, missingDeferred)) {
            assertEquals("Restore cleanup", requireKey(notice.titleKey()));
            assertEquals(OptionalInt.of(BrandColors.AMBER), notice.bodyColor());
            assertFalse(notice.refusal(), notice.bodyKey());
        }
    }

    @Test
    void occupantMissingAndTornAttemptRefusalsWearTheirOwnTitles() {
        ToastCopy occupant = ToastCopy.refuseOccupant("base", false);
        ToastCopy occupantAdvice = ToastCopy.refuseOccupant("base", true);
        ToastCopy folderMissing = ToastCopy.refuseFolderMissing("base");
        ToastCopy tornAttempt = ToastCopy.refuseTornAttempt();

        assertEquals("base", occupant.arguments().get(0).text());
        assertEquals(OptionalInt.of(BrandColors.AMBER), occupant.arguments().get(0).color());
        assertEquals("base", occupantAdvice.arguments().get(0).text());
        assertEquals(OptionalInt.of(BrandColors.AMBER), occupantAdvice.arguments().get(0).color());
        assertEquals("base", folderMissing.arguments().get(0).text());
        assertEquals(OptionalInt.of(BrandColors.AMBER), folderMissing.arguments().get(0).color());

        assertEquals("Name in use", requireKey(occupant.titleKey()));
        assertEquals("\"base\" is taken by something that isn't a download.", resolve(occupant));
        assertEquals("wdl.refuse.occupant.body_named_advice", occupantAdvice.bodyKey());
        assertEquals("Name in use", requireKey(occupantAdvice.titleKey()));
        assertEquals("\"base\" is taken by something that isn't a download. "
                + "Choose another name, or move the folder aside.", resolve(occupantAdvice));
        assertEquals("Download missing", requireKey(folderMissing.titleKey()));
        assertEquals("The download folder \"base\" no longer exists.", resolve(folderMissing));
        assertEquals("Cleanup pending", requireKey(tornAttempt.titleKey()));
        assertEquals("An earlier restore left unfinished cleanup for that name. "
                + "Open the Downloads screen to finish it, or wait for the cleanup to complete.",
                resolve(tornAttempt));
        for (ToastCopy refusal : List.of(occupant, occupantAdvice, folderMissing, tornAttempt)) {
            assertEquals(OptionalInt.of(BrandColors.RUST), refusal.bodyColor());
            assertTrue(refusal.refusal(), refusal.bodyKey());
        }
    }

    @Test
    void jobDoneToastsAreNotRefusals() {
        ToastCopy completion = ToastCopy.completion(true, 1, 7_000, "w");

        assertNotNull(completion);
        assertFalse(completion.refusal());
    }

    @Test
    void showToastsOffYieldsNothingToEnqueue() {
        assertNull(ToastCopy.completion(false, 1, 7_000, "w"));
        assertNull(ToastCopy.completionZip(false, 1, 7_000, "w.zip"));
        assertNull(ToastCopy.downloadError(false, SaveFailureReason.literal("disk full")));
    }

    private static ToastCopy requireCompletion(int chunks, long elapsedMillis) {
        ToastCopy toast = ToastCopy.completion(true, chunks, elapsedMillis, "w");
        assertNotNull(toast);
        return toast;
    }

    /** Resolves a composed toast body to its en_us copy, failing on any key the lang file does not carry. */
    private static String resolve(ToastCopy toast) {
        Object[] resolvedArguments = new Object[toast.arguments().size()];
        int slot = 0;
        for (ToastCopy.Argument argument : toast.arguments()) {
            if (argument.translationKey() == null) {
                resolvedArguments[slot++] = argument.text();
            } else {
                String pattern = requireKey(argument.translationKey());
                resolvedArguments[slot++] = argument.text().isEmpty()
                        ? pattern
                        : String.format(Locale.ROOT, pattern, argument.text());
            }
        }
        return String.format(Locale.ROOT, requireKey(toast.bodyKey()), resolvedArguments);
    }

    private static String requireKey(String key) {
        String pattern = LANG.get(key);
        assertNotNull(pattern, "en_us.json is missing " + key);
        return pattern;
    }

    private static Map<String, String> loadLang() {
        InputStream stream = ToastCopyTest.class.getResourceAsStream("/assets/wdl/lang/en_us.json");
        if (stream == null) {
            throw new IllegalStateException("en_us.json not on the test classpath");
        }
        return new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8),
                new TypeToken<Map<String, String>>() {}.getType());
    }

    private static List<String> tintedTexts(ToastCopy toast, int colorRgb) {
        List<String> texts = new ArrayList<>();
        for (ToastCopy.Argument argument : toast.arguments()) {
            if (argument.color().isPresent() && argument.color().getAsInt() == colorRgb) {
                texts.add(argument.text());
            }
        }
        return texts;
    }

    private static int tintedCount(ToastCopy toast) {
        int count = 0;
        for (ToastCopy.Argument argument : toast.arguments()) {
            if (argument.color().isPresent()) {
                count++;
            }
        }
        return count;
    }
}
