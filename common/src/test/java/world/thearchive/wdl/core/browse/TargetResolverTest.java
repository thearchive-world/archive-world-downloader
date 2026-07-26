// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;

class TargetResolverTest {
    private static final LocalDate DATE = LocalDate.of(2026, 6, 22);

    @Test
    void aNewTargetDatesTheFolderAndUsesItAsTheLevelDatName() {
        DownloadTarget target = TargetResolver.resolveNew("download 1", DATE, true);
        assertEquals("download 1-2026-06-22", target.folderName());
        assertEquals("download 1-2026-06-22", target.worldName(), "level.dat carries the dated name");
        assertEquals(DownloadMode.NEW, target.mode());
    }

    @Test
    void theSuffixOffYieldsTheBareSanitizedNameForFolderAndLevelDatName() {
        DownloadTarget target = TargetResolver.resolveNew("download 1", DATE, false);
        assertEquals("download 1", target.folderName(), "no date on the folder when the suffix is off");
        assertEquals("download 1", target.worldName(), "no date on the level.dat name when the suffix is off");
        assertEquals(DownloadMode.NEW, target.mode());
    }

    @Test
    void anAlreadyDatedNameIsNotDatedTwice() {
        DownloadTarget target = TargetResolver.resolveNew("download 1-2026-06-22", DATE, true);
        assertEquals("download 1-2026-06-22", target.folderName());
    }

    @Test
    void aDateNotAtTheEndOfTheNameIsStillDated() {
        DownloadTarget target = TargetResolver.resolveNew("world-2026-06-22-wing", DATE, true);
        assertEquals("world-2026-06-22-wing-2026-06-22", target.folderName(),
                "the suffix is suppressed only when the date is the literal trailing token");
    }

    @Test
    void aReservedWindowsDeviceNameIsDefusedInAnyCase() {
        // Windows refuses CON/PRN/AUX/NUL/COM1-9/LPT1-9 as a folder basename, case-insensitively. Without the
        // defusing suffix the UI accepts the name (hasUsableName is true) and the download then dies at
        // createAccess with a generic save failure; with it the folder the user gets matches what they typed
        // as closely as the filesystem allows.
        assertEquals("CON_", TargetResolver.sanitize("CON"));
        assertEquals("PRN_", TargetResolver.sanitize("PRN"));
        assertEquals("AUX_", TargetResolver.sanitize("AUX"));
        assertEquals("nul_", TargetResolver.sanitize("nul"));
        assertEquals("Com5_", TargetResolver.sanitize("Com5"));
        assertEquals("LPT9_", TargetResolver.sanitize("LPT9"));
    }

    @Test
    void aReservedStemAheadOfTheFirstDotIsDefusedAndLookalikesAreNot() {
        // Windows reserves by the portion before the first dot, so NUL.txt is as unusable as NUL; the
        // underscore lands on the stem to lift the whole name out of the reserved set. Names merely starting
        // with a device word are legal and stay untouched.
        assertEquals("NUL_.txt", TargetResolver.sanitize("NUL.txt"));
        assertEquals("con_.backup.old", TargetResolver.sanitize("con.backup.old"));
        assertEquals("CONS", TargetResolver.sanitize("CONS"));
        assertEquals("COM10", TargetResolver.sanitize("COM10"), "only COM1 through COM9 are reserved");
        assertEquals("console.txt", TargetResolver.sanitize("console.txt"));
    }

    @Test
    void theSuperscriptDigitDeviceFormsAreDefusedToo() {
        // The Win32 naming rules also reserve the superscript-digit forms of the serial and printer devices;
        // they parse as the corresponding COM or LPT device, so they are as uncreatable as the plain digits.
        assertEquals("COM¹_", TargetResolver.sanitize("COM¹"));
        assertEquals("lpt³_.txt", TargetResolver.sanitize("lpt³.txt"));
    }

    @Test
    void theSameBaseOnTheSameDayResolvesToTheSameFolder() {
        String first = TargetResolver.resolveNew("base", DATE, true).folderName();
        String second = TargetResolver.resolveNew("base", DATE, true).folderName();
        assertEquals(first, second);
        assertEquals("base-2026-06-22", first);
    }

    @Test
    void anOrdinaryNameIsUsable() {
        assertTrue(TargetResolver.hasUsableName("museum"));
    }

    @Test
    void aNameThatSanitizesToSomethingIsUsable() {
        assertTrue(TargetResolver.hasUsableName("my/server"),
                "the separator is scrubbed to my_server, so something usable remains");
    }

    @Test
    void blankAndIllegalOnlyNamesAreNotUsable() {
        assertFalse(TargetResolver.hasUsableName(""));
        assertFalse(TargetResolver.hasUsableName("   "));
        assertFalse(TargetResolver.hasUsableName("<>"));
        assertFalse(TargetResolver.hasUsableName("..."));
    }

    @Test
    void dotDotRemovalRunsBeforeTheWhitespaceTrim() {
        assertFalse(TargetResolver.hasUsableName(".."));
        assertFalse(TargetResolver.hasUsableName(".. .."),
                "the dot-dot removal leaves whitespace that only the later trim collapses");
    }

    @Test
    void anIllegalCharacterInsideTheNameIsStripped() {
        assertEquals("Hypixel Skyblock",
                TargetResolver.resolveNew("Hypixel: Skyblock", DATE, false).folderName());
    }

    @Test
    void aLoneSeparatorSanitizesToAnUnderscore() {
        assertEquals("_", TargetResolver.resolveNew("/", DATE, false).folderName());
    }

    @Test
    void aMidNameDotDotCollapsesInsteadOfSurviving() {
        assertEquals("ab", TargetResolver.resolveNew("a..b", DATE, false).folderName());
    }

    @Test
    void serverNameWithSeparatorResolvesToOneSafeContainedComponent(@TempDir Path saves) {
        DownloadTarget target = TargetResolver.resolveNew("my/server", DATE, false);
        assertEquals("my_server", target.folderName(),
                "an unsafe server-derived name resolves to a single safe component so the download can complete");
        assertEquals(saves, saves.resolve(target.folderName()).getParent(),
                "the folder is a single child of the saves directory, not a nested path that fails the write gate");
    }

    @Test
    void pathTraversalIsContainedToTheSavesDirectory(@TempDir Path saves) {
        DownloadTarget target = TargetResolver.resolveNew("../../etc/passwd", DATE, true);
        Path resolved = saves.resolve(target.folderName()).normalize();
        assertTrue(resolved.startsWith(saves.normalize()),
                "the derived folder must stay inside the saves directory");
    }

    @Test
    void aResumeTargetsTheExistingFolderVerbatim(@TempDir Path saves) {
        DownloadTarget target = TargetResolver.resolveResume("foo-2026-06-22", saves);
        assertEquals("foo-2026-06-22", target.folderName());
        assertEquals("foo-2026-06-22", target.worldName());
        assertEquals(DownloadMode.RESUME, target.mode());
    }

    @Test
    void resumeTargetReadsBackTheOnDiskSpelling(@TempDir Path saves) throws IOException {
        Files.createDirectories(saves.resolve("World"));
        // Case-insensitive FS behavior cannot be forced on POSIX CI, so the readback contract is tested
        // via the exact path (identity readback) plus the miss path; the case-variant readback is covered
        // by the same toRealPath call and lands on the Windows manual gate.
        DownloadTarget target = TargetResolver.resolveResume("World", saves);
        assertEquals("World", target.folderName());
        // A typed name that resolves nowhere keeps the typed spelling (the caller already classified).
        assertEquals("Ghost", TargetResolver.resolveResume("Ghost", saves).folderName());
    }

    @Test
    void theLoadedWorldIsRecognizedByFilesystemIdentityNotPathString(@TempDir Path saves) throws IOException {
        Path world = Files.createDirectories(saves.resolve("loaded"));
        Path other = Files.createDirectories(saves.resolve("other"));
        Path sameFileDifferentString = saves.resolve("x").resolve("..").resolve("loaded");
        assertTrue(TargetResolver.isSameWorld(world, world));
        assertTrue(TargetResolver.isSameWorld(sameFileDifferentString, world),
                "a different path string for the same directory is still the same world");
        assertFalse(TargetResolver.isSameWorld(other, world));
    }

    @Test
    void noLoadedWorldMeansNothingIsRefused(@TempDir Path saves) throws IOException {
        Path world = Files.createDirectories(saves.resolve("a"));
        assertFalse(TargetResolver.isSameWorld(world, null));
    }

    @Test
    void classifyTargetReturnsNewForAnAbsentFolder(@TempDir Path saves) {
        assertEquals(TargetClassification.NEW,
                TargetResolver.classifyTarget("brand-new-2026-06-22", saves, null));
    }

    @Test
    void classifyTargetReturnsResumeExistingForAnExistingFolder(@TempDir Path saves) throws IOException {
        Files.createDirectories(saves.resolve("existing-2026-06-22"));
        assertEquals(TargetClassification.RESUME_EXISTING,
                TargetResolver.classifyTarget("existing-2026-06-22", saves, null));
    }

    @Test
    void aBareFolderFromTheSuffixOffPathStillClassifiesAsResumeExisting(@TempDir Path saves) throws IOException {
        DownloadTarget target = TargetResolver.resolveNew("museum", DATE, false);
        Files.createDirectories(saves.resolve(target.folderName()));
        assertEquals(TargetClassification.RESUME_EXISTING,
                TargetResolver.classifyTarget(target.folderName(), saves, null),
                "a same-name new download with the suffix off lands on the existing bare folder and routes to the "
                        + "resume/merge confirm, so overwrite protection is unchanged by the toggle");
    }

    @Test
    void classifyTargetRefusesAnExistingFolderThatIsTheLoadedWorld(@TempDir Path saves) throws IOException {
        Path loaded = Files.createDirectories(saves.resolve("loaded-2026-06-22"));
        assertEquals(TargetClassification.REFUSE_LOADED,
                TargetResolver.classifyTarget("loaded-2026-06-22", saves, loaded),
                "the loaded-world check wins over the exists check so a resume cannot target it");
    }
}
