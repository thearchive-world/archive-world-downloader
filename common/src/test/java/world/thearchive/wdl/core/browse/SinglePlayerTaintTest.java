// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SinglePlayerTaintTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void nonEmptyPlayerdataDirIsTainted(@TempDir Path folder) throws IOException {
        Files.createDirectories(folder.resolve("playerdata"));
        Files.createFile(folder.resolve("playerdata").resolve("00000000-0000-0000-0000-000000000000.dat"));
        assertTrue(SinglePlayerTaint.isTainted(folder));
    }

    @Test
    void playersDataIsTheModsOwnOutputNotTaint(@TempDir Path folder) throws IOException {
        // The 26.x player file, which WDL writes itself because createTag carries no player compound there.
        Files.createDirectories(folder.resolve("players").resolve("data"));
        Files.createFile(folder.resolve("players").resolve("data").resolve("host.dat"));
        assertEquals(SinglePlayerTaint.TaintState.CLEAN, SinglePlayerTaint.classify(folder));
    }

    @Test
    void theDeepBandsStillRestOnPlayerData(@TempDir Path folder) throws IOException {
        // Point-of-interest does not exist before 1.14, so playerdata is the only member covering those bands.
        Files.createDirectories(folder.resolve("playerdata"));
        Files.createFile(folder.resolve("playerdata").resolve("host.dat"));
        assertEquals(SinglePlayerTaint.TaintState.TAINTED, SinglePlayerTaint.classify(folder));
    }

    @Test
    void anyEntryTaintsNotJustDat(@TempDir Path folder) throws IOException {
        Files.createDirectories(folder.resolve("playerdata"));
        Files.createFile(folder.resolve("playerdata").resolve("stray.tmp"));
        assertTrue(SinglePlayerTaint.isTainted(folder));
    }

    @Test
    void emptyPlayerdataDirIsNotTainted(@TempDir Path folder) throws IOException {
        Files.createDirectories(folder.resolve("playerdata"));
        assertFalse(SinglePlayerTaint.isTainted(folder));
    }

    @Test
    void noPlayerDataDirIsNotTainted(@TempDir Path folder) throws IOException {
        Files.createDirectories(folder.resolve("region"));
        assertFalse(SinglePlayerTaint.isTainted(folder));
    }

    @Test
    void decideMapsTheThreeStates() {
        assertEquals(SinglePlayerTaint.Decision.ALLOW,
                SinglePlayerTaint.decide(SinglePlayerTaint.TaintState.CLEAN, true));
        assertEquals(SinglePlayerTaint.Decision.ALLOW,
                SinglePlayerTaint.decide(SinglePlayerTaint.TaintState.CLEAN, false));
        assertEquals(SinglePlayerTaint.Decision.REFUSE,
                SinglePlayerTaint.decide(SinglePlayerTaint.TaintState.TAINTED, true));
        assertEquals(SinglePlayerTaint.Decision.CONFIRM,
                SinglePlayerTaint.decide(SinglePlayerTaint.TaintState.TAINTED, false));
    }

    @Test
    void anUnreadablePlayerDataDirectoryConfirmsRatherThanAllowing(@TempDir Path folder) {
        SinglePlayerTaint.DirectoryProbe unreadable = directory -> SinglePlayerTaint.Presence.UNREADABLE;
        assertEquals(SinglePlayerTaint.TaintState.UNKNOWN, SinglePlayerTaint.classify(folder, unreadable),
                "a present but unlistable player-data directory is unknown, not clean");
        assertEquals(SinglePlayerTaint.Decision.CONFIRM,
                SinglePlayerTaint.decide(SinglePlayerTaint.classify(folder, unreadable), false),
                "the unreadable case funnels to CONFIRM, not the fail-open ALLOW");
        assertEquals(SinglePlayerTaint.Decision.CONFIRM,
                SinglePlayerTaint.decide(SinglePlayerTaint.TaintState.UNKNOWN, true),
                "an unverifiable folder confirms even when blocking, never a silent allow");
    }

    @Test
    void aDefiniteEntryOutweighsAnUnreadableSiblingRegardlessOfOrder() {
        SinglePlayerTaint.DirectoryProbe playerdataUnreadable = directory -> directory.endsWith("playerdata")
                ? SinglePlayerTaint.Presence.UNREADABLE
                : SinglePlayerTaint.Presence.HAS_ENTRY;
        SinglePlayerTaint.DirectoryProbe playerdataTainted = directory -> directory.endsWith("playerdata")
                ? SinglePlayerTaint.Presence.HAS_ENTRY
                : SinglePlayerTaint.Presence.UNREADABLE;
        assertEquals(SinglePlayerTaint.TaintState.TAINTED,
                SinglePlayerTaint.classify(Paths.get("save"), playerdataUnreadable),
                "a definite entry after an unreadable directory still classifies as tainted");
        assertEquals(SinglePlayerTaint.TaintState.TAINTED,
                SinglePlayerTaint.classify(Paths.get("save"), playerdataTainted),
                "a definite entry short-circuits before an unreadable directory is reached");
    }

    @Test
    void poiAtDimensionRootsMeansTainted() throws IOException {
        // Overworld root poi.
        Path save = folderWith("poi/r.0.0.mca");
        assertEquals(SinglePlayerTaint.TaintState.TAINTED, SinglePlayerTaint.classify(save));
        // Vanilla nether and end roots.
        assertEquals(SinglePlayerTaint.TaintState.TAINTED,
                SinglePlayerTaint.classify(folderWith("DIM-1/poi/r.0.0.mca")));
        assertEquals(SinglePlayerTaint.TaintState.TAINTED,
                SinglePlayerTaint.classify(folderWith("DIM1/poi/r.0.0.mca")));
        // Datapack dimension root (two-level enumeration under dimensions/).
        assertEquals(SinglePlayerTaint.TaintState.TAINTED,
                SinglePlayerTaint.classify(folderWith("dimensions/mypack/myworld/poi/r.0.0.mca")));
    }

    @Test
    void poiOnlyCountsAtDimensionRoots() throws IOException {
        // A bare data segment or a nested poi never matches: WDL itself writes data/ (captured maps).
        assertEquals(SinglePlayerTaint.TaintState.CLEAN, SinglePlayerTaint.classify(folderWith("data/map_0.dat")));
        assertEquals(SinglePlayerTaint.TaintState.CLEAN,
                SinglePlayerTaint.classify(folderWith("datapacks/pack/data/ns/poi/thing.json")));
    }

    @Test
    void emptyPoiDirectoryStaysClean() throws IOException {
        // Mirrors the shipped playerdata rule: presence needs an entry.
        Path save = temporaryDirectory.resolve("save-empty-poi");
        Files.createDirectories(save.resolve("poi"));
        assertEquals(SinglePlayerTaint.TaintState.CLEAN, SinglePlayerTaint.classify(save));
    }

    @Test
    void entryMatcherPinsPositionsAndCase() {
        // Save-root player data, both band layouts, case-insensitive.
        assertTrue(SinglePlayerTaint.entryPathIsServerArtifact("playerdata/uuid.dat"));
        assertTrue(SinglePlayerTaint.entryPathIsServerArtifact("Playerdata/uuid.dat"));
        // Every export and pre-resume backup carries the 26.x player file, so matching it would exclude WDL's
        // own archives from its own restore.
        assertFalse(SinglePlayerTaint.entryPathIsServerArtifact("players/data/uuid.dat"));
        // POI at each dimension root.
        assertTrue(SinglePlayerTaint.entryPathIsServerArtifact("poi/r.0.0.mca"));
        assertTrue(SinglePlayerTaint.entryPathIsServerArtifact("DIM-1/poi/r.0.0.mca"));
        assertTrue(SinglePlayerTaint.entryPathIsServerArtifact("DIM1/poi/r.0.0.mca"));
        assertTrue(SinglePlayerTaint.entryPathIsServerArtifact("dimensions/ns/id/poi/r.0.0.mca"));
        assertTrue(SinglePlayerTaint.entryPathIsServerArtifact("POI/r.0.0.mca"));
        // The negative cases: arbitrary depth, nested player data, bare data, and the segment alone as a
        // file name (a file named poi is not a directory member).
        assertFalse(SinglePlayerTaint.entryPathIsServerArtifact("region/r.0.0.mca"));
        assertFalse(SinglePlayerTaint.entryPathIsServerArtifact("data/map_0.dat"));
        assertFalse(SinglePlayerTaint.entryPathIsServerArtifact("backup/playerdata/uuid.dat"));
        assertFalse(SinglePlayerTaint.entryPathIsServerArtifact("dimensions/ns/id/deeper/poi/x"));
        assertFalse(SinglePlayerTaint.entryPathIsServerArtifact("poi"));
        assertFalse(SinglePlayerTaint.entryPathIsServerArtifact("myplayers/database/x"));
    }

    private Path folderWith(String relativeFile) throws IOException {
        Path save = Files.createTempDirectory(temporaryDirectory, "save");
        Path file = save.resolve(relativeFile);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] { 1 });
        return save;
    }

    @Test
    void unlistableDimensionsTreeMeansUnknown() throws IOException {
        Path save = Files.createTempDirectory(temporaryDirectory, "save");
        Path dimensions = Files.createDirectory(save.resolve("dimensions"));
        Set<PosixFilePermission> original;
        try {
            original = Files.getPosixFilePermissions(dimensions);
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("POSIX permissions are unavailable on this filesystem");
            return;
        }
        Files.setPosixFilePermissions(dimensions,
                EnumSet.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        try {
            Assumptions.assumeTrue(!isListable(dimensions),
                    "the dimensions directory is still listable (running as root?); cannot force enumeration to fail");
            assertEquals(SinglePlayerTaint.TaintState.UNKNOWN, SinglePlayerTaint.classify(save),
                    "an unlistable dimensions tree cannot be verified clean, so classify reports UNKNOWN");
        } finally {
            Files.setPosixFilePermissions(dimensions, original);
        }
    }

    private static boolean isListable(Path directory) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
