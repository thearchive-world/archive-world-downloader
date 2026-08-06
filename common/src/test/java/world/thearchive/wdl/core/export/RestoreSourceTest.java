// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The seven fail-closed discovery rules over real crafted zips, plus the never-throwing harness. */
class RestoreSourceTest {
    @TempDir
    Path saves;

    /** Writes a zip at saves/&lt;name&gt; whose entries are (path, bytes) pairs in order. */
    private Path zip(String name, Object... pathThenBytes) throws IOException {
        Path target = saves.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (int i = 0; i < pathThenBytes.length; i += 2) {
                out.putNextEntry(new ZipEntry((String) pathThenBytes[i]));
                out.write((byte[]) pathThenBytes[i + 1]);
                out.closeEntry();
            }
        }
        return target;
    }

    private static byte[] record(String finishedAt) {
        return ("{\"id\":\"x\",\"finishedAt\":\"" + finishedAt + "\"}\n").getBytes(StandardCharsets.UTF_8);
    }

    private static final byte[] LEVEL = { 10, 0 };

    /** A minimal clean candidate rooted at root/: level.dat + a record line. */
    private Path cleanZip(String zipName, String root, String finishedAt) throws IOException {
        return zip(zipName, root + "/level.dat", LEVEL,
                root + "/wdl/download.jsonl", record(finishedAt));
    }

    /** Exactly MAX_RECORD_BYTES of record content: a padded garbage first line, then the record line. */
    private static byte[] atCapRecord(String finishedAt) {
        byte[] content = new byte[(int) RestoreSource.MAX_RECORD_BYTES];
        Arrays.fill(content, (byte) 'a');
        byte[] recordLine = record(finishedAt);
        System.arraycopy(recordLine, 0, content, content.length - recordLine.length, recordLine.length);
        content[content.length - recordLine.length - 1] = '\n';
        return content;
    }

    /** A candidate whose record entry is STORED, so both central-directory sizes are the true byte count. */
    private Path storedRecordZip(String zipName, String root, byte[] recordBytes) throws IOException {
        Path target = saves.resolve(zipName);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            out.putNextEntry(new ZipEntry(root + "/level.dat"));
            out.write(LEVEL);
            out.closeEntry();
            ZipEntry stored = new ZipEntry(root + "/wdl/download.jsonl");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(recordBytes.length);
            stored.setCompressedSize(recordBytes.length);
            CRC32 crc = new CRC32();
            crc.update(recordBytes);
            stored.setCrc(crc.getValue());
            out.putNextEntry(stored);
            out.write(recordBytes);
            out.closeEntry();
        }
        return target;
    }

    @Test
    void findsTheOnlyCleanCandidate() throws IOException {
        cleanZip("World.zip", "World", "2026-03-01T10:00:00Z");
        Optional<RestoreSource> found = RestoreSource.find(saves, "World");
        assertTrue(found.isPresent());
        assertEquals("World.zip", found.get().zip().getFileName().toString());
        assertEquals(Instant.parse("2026-03-01T10:00:00Z"), found.get().finishedAt());
    }

    @Test
    void familyGrammarIsExactNeverPrefix() throws IOException {
        // A sibling download whose literal name extends World's grammar must not bleed in by name.
        cleanZip("World Extended.zip", "World Extended", "2026-03-01T10:00:00Z");
        // A family-named zip whose ROOT is another folder is excluded by rule 3 (root identity).
        cleanZip("World_(2).zip", "World_(2)", "2026-04-01T10:00:00Z");
        assertFalse(RestoreSource.find(saves, "World").isPresent());
    }

    @Test
    void rootIdentityRejectsFlatMultiRootAndMismatched() throws IOException {
        zip("World.zip", "level.dat", LEVEL, "wdl/download.jsonl", record("2026-03-01T10:00:00Z")); // flat
        assertFalse(RestoreSource.find(saves, "World").isPresent());
        Files.delete(saves.resolve("World.zip"));
        cleanZip("World.zip", "Other", "2026-03-01T10:00:00Z"); // mismatched root
        assertFalse(RestoreSource.find(saves, "World").isPresent());
        Files.delete(saves.resolve("World.zip"));
        zip("World.zip", "World/level.dat", LEVEL, "Rogue/x", LEVEL,
                "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z")); // multi-root
        assertFalse(RestoreSource.find(saves, "World").isPresent());
    }

    @Test
    void saveShapeNeedsExactCaseFileLevelDat() throws IOException {
        zip("World.zip", "World/Level.dat", LEVEL,
                "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z")); // re-cased
        assertFalse(RestoreSource.find(saves, "World").isPresent());
        Files.delete(saves.resolve("World.zip"));
        zip("World.zip", "World/level.dat/", new byte[0],
                "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z")); // directory entry at the path
        assertFalse(RestoreSource.find(saves, "World").isPresent());
    }

    @Test
    void contentCleanlinessUsesTheSharedMatcher() throws IOException {
        // A map-carrying download's own data/ never matches; playerdata does, case-insensitively.
        zip("World.zip", "World/level.dat", LEVEL, "World/data/map_0.dat", LEVEL,
                "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z"));
        assertTrue(RestoreSource.find(saves, "World").isPresent());
        zip("World-pre-resume.zip", "World/level.dat", LEVEL, "World/Playerdata/u.dat", LEVEL,
                "World/wdl/download.jsonl", record("2026-04-01T10:00:00Z"));
        // The newer backup is content-tainted, so the older export still wins.
        assertEquals("World.zip",
                RestoreSource.find(saves, "World").get().zip().getFileName().toString());
    }

    @Test
    void downloadsNamedLikeArtifactsStayClean() throws IOException {
        cleanZip("players.zip", "players", "2026-03-01T10:00:00Z");
        // Root-stripped, its data/ path reads players-relative, never players/data.
        zip("players.zip", "players/level.dat", LEVEL, "players/data/map_0.dat", LEVEL,
                "players/wdl/download.jsonl", record("2026-03-01T10:00:00Z"));
        assertTrue(RestoreSource.find(saves, "players").isPresent());
        cleanZip("poi.zip", "poi", "2026-03-01T10:00:00Z");
        assertTrue(RestoreSource.find(saves, "poi").isPresent());
    }

    @Test
    void recordedFinishOrdersAndMtimeOnlyBreaksTies() throws IOException {
        Path older = cleanZip("World.zip", "World", "2026-01-01T10:00:00Z");
        cleanZip("World-pre-resume.zip", "World", "2026-03-01T10:00:00Z");
        // Freshen the OLD copy's mtime (the re-copied-from-cloud shape): recorded finish must still win.
        Files.setLastModifiedTime(older, FileTime.from(Instant.now()));
        assertEquals("World-pre-resume.zip",
                RestoreSource.find(saves, "World").get().zip().getFileName().toString());
    }

    @Test
    void strictRecordCapAndCorruptionExclude() throws IOException {
        // Unparseable finishedAt excludes (never epoch-orders).
        zip("World.zip", "World/level.dat", LEVEL, "World/wdl/download.jsonl",
                "{\"finishedAt\":\"garbage\"}".getBytes(StandardCharsets.UTF_8));
        assertFalse(RestoreSource.find(saves, "World").isPresent());
        // An over-cap record excludes; an at-cap record reads. Build a real deflate bomb: one line
        // whose UNCOMPRESSED size crosses the cap, compressed size tiny.
        byte[] bomb = new byte[(int) RestoreSource.MAX_RECORD_BYTES + 1];
        Arrays.fill(bomb, (byte) 'a');
        zip("World_(2).zip", "World/level.dat", LEVEL, "World/wdl/download.jsonl", bomb);
        assertFalse(RestoreSource.find(saves, "World").isPresent());
        // A zip that is not a zip is excluded at the central directory, harness survives.
        Files.write(saves.resolve("World-pre-resume.zip"), new byte[] { 1, 2, 3 });
        assertFalse(RestoreSource.find(saves, "World").isPresent());
    }

    @Test
    void duplicateAndPrefixCollisionsExcludeButLegitimateShapesSurvive() throws IOException {
        // Case-colliding duplicates (slash-normalized) exclude.
        zip("World.zip", "World/level.dat", LEVEL, "World/Thing", LEVEL, "World/thing", LEVEL,
                "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z"));
        assertFalse(RestoreSource.find(saves, "World").isPresent());
        Files.delete(saves.resolve("World.zip"));
        // A FILE that path-prefixes another entry excludes.
        zip("World.zip", "World/level.dat", LEVEL, "World/x", LEVEL, "World/x/y", LEVEL,
                "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z"));
        assertFalse(RestoreSource.find(saves, "World").isPresent());
        Files.delete(saves.resolve("World.zip"));
        // MUST-SURVIVE: level.dat beside level.dat_old (every resumed download's export), and a repack's
        // directory entries including the root directory (harmless prefixes of everything).
        zip("World.zip", "World/", new byte[0], "World/level.dat", LEVEL,
                "World/level.dat_old", LEVEL, "World/region/", new byte[0],
                "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z"));
        assertTrue(RestoreSource.find(saves, "World").isPresent());
    }

    @Test
    void selfSnapshotsExcludeByContentAndHarnessNeverThrows() throws IOException {
        zip("World-singleplayer.zip", "World/level.dat", LEVEL, "World/playerdata/u.dat", LEVEL,
                "World/wdl/download.jsonl", record("2026-05-01T10:00:00Z"));
        assertFalse(RestoreSource.find(saves, "World").isPresent());
        // Harness-level: a nonexistent saves directory returns empty, no throw.
        assertFalse(RestoreSource.find(saves.resolve("no-such-dir"), "World").isPresent());
    }

    @Test
    void stillIdenticalRejectsSizeOnlyChange() throws IOException {
        cleanZip("World.zip", "World", "2026-03-01T10:00:00Z");
        RestoreSource pinned = RestoreSource.find(saves, "World").get();
        assertTrue(RestoreSource.stillIdentical(pinned));
        // The pinned mtime goes back on after the rewrite, so only the size conjunct can see this.
        Files.write(pinned.zip(), new byte[] { 9, 9, 9, 9, 9, 9, 9, 9, 9 });
        Files.setLastModifiedTime(pinned.zip(), pinned.mtime());
        assertFalse(RestoreSource.stillIdentical(pinned), "a size change alone is not still identical");
    }

    @Test
    void stillIdenticalRejectsMtimeOnlyChange() throws IOException {
        cleanZip("World.zip", "World", "2026-03-01T10:00:00Z");
        RestoreSource pinned = RestoreSource.find(saves, "World").get();
        assertTrue(RestoreSource.stillIdentical(pinned));
        // The bytes are untouched, so only the mtime conjunct can see this.
        Files.setLastModifiedTime(pinned.zip(), FileTime.from(pinned.mtime().toInstant().plusSeconds(60)));
        assertFalse(RestoreSource.stillIdentical(pinned), "an mtime change alone is not still identical");
    }

    @Test
    void zeroEntryZipExcludes() throws IOException {
        zip("World.zip");
        assertFalse(RestoreSource.find(saves, "World").isPresent());
    }

    @Test
    void recordExactlyAtTheCapReads() throws IOException {
        zip("World.zip", "World/level.dat", LEVEL,
                "World/wdl/download.jsonl", atCapRecord("2026-03-01T10:00:00Z"));
        Optional<RestoreSource> found = RestoreSource.find(saves, "World");
        assertTrue(found.isPresent());
        assertEquals(Instant.parse("2026-03-01T10:00:00Z"), found.get().finishedAt());
    }

    @Test
    void storedRecordAtCapReadsAndOverCapExcludes() throws IOException {
        storedRecordZip("World.zip", "World", atCapRecord("2026-03-01T10:00:00Z"));
        assertTrue(RestoreSource.find(saves, "World").isPresent());
        Files.delete(saves.resolve("World.zip"));
        byte[] overCap = new byte[(int) RestoreSource.MAX_RECORD_BYTES + 1];
        Arrays.fill(overCap, (byte) 'a');
        storedRecordZip("World.zip", "World", overCap);
        assertFalse(RestoreSource.find(saves, "World").isPresent());
    }

    @Test
    void midInflateCorruptionExcludesAndTheHarnessSurvives() throws IOException {
        Path candidate = zip("World.zip", "World/level.dat", LEVEL,
                "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z"));
        byte[] bytes = Files.readAllBytes(candidate);
        // Zero the record entry's data bytes just past its local file header. The central directory at
        // the file's tail stays intact, so the zip still opens and enumerates; only the record entry's
        // stream fails while inflating.
        String entryName = "World/wdl/download.jsonl";
        int dataStart = new String(bytes, StandardCharsets.ISO_8859_1).indexOf(entryName) + entryName.length();
        Arrays.fill(bytes, dataStart, dataStart + 24, (byte) 0);
        Files.write(candidate, bytes);
        assertFalse(RestoreSource.find(saves, "World").isPresent());
    }

    @Test
    void judgeAppliesEveryRuleFromTheOneOpenZipSession() throws IOException {
        // The one-session pin, structural: the rules body takes the already-open ZipFile, so every rule
        // reads from that single session by construction; this call compiles only against that signature.
        Path candidate = cleanZip("World.zip", "World", "2026-03-01T10:00:00Z");
        try (ZipFile zipFile = new ZipFile(candidate.toFile())) {
            RestoreSource judged = RestoreSource.judge(zipFile, candidate, "World");
            assertNotNull(judged);
            assertEquals(Instant.parse("2026-03-01T10:00:00Z"), judged.finishedAt());
        }
    }

    @Test
    void entryCountOverTheCapExcludesModestCandidateSurvives() throws IOException {
        // A candidate whose entry count exceeds the cap is excluded before the per-entry work runs. The
        // production cap is generous, so the seam takes a tiny cap to prove the rule without a real bomb.
        Path candidate = cleanZip("World.zip", "World", "2026-03-01T10:00:00Z"); // two entries
        try (ZipFile zipFile = new ZipFile(candidate.toFile())) {
            assertNull(RestoreSource.judge(zipFile, candidate, "World", 1)); // two entries over a cap of one
            assertNotNull(RestoreSource.judge(zipFile, candidate, "World", RestoreSource.MAX_ENTRIES));
        }
        // MUST-SURVIVE: a normal clean candidate is still found under the generous production cap.
        assertTrue(RestoreSource.find(saves, "World").isPresent());
    }

    @Test
    void caseVariantRecordPathExcludesAtDiscovery() throws IOException {
        zip("World.zip", "World/level.dat", LEVEL,
                "World/WDL/download.jsonl", record("2026-03-01T10:00:00Z"));
        assertFalse(RestoreSource.find(saves, "World").isPresent());
    }

    @Test
    void equalRecordedFinishBreaksTheTieByNewerMtime() throws IOException {
        Path export = cleanZip("World.zip", "World", "2026-03-01T10:00:00Z");
        Path backup = cleanZip("World-pre-resume.zip", "World", "2026-03-01T10:00:00Z");
        Files.setLastModifiedTime(export, FileTime.from(Instant.parse("2026-03-01T10:00:00Z")));
        Files.setLastModifiedTime(backup, FileTime.from(Instant.parse("2026-03-02T10:00:00Z")));
        assertEquals("World-pre-resume.zip",
                RestoreSource.find(saves, "World").get().zip().getFileName().toString());
    }

    @Test
    void pathEscapingEntryExcludesButLiteralDotdotSubstringSurvives() throws IOException {
        // A zip-slip entry outside the root, hidden behind a root-identity-passing first segment, excludes.
        zip("World.zip", "World/level.dat", LEVEL, "World/../evil", LEVEL,
                "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z"));
        assertFalse(RestoreSource.find(saves, "World").isPresent());
        Files.delete(saves.resolve("World.zip"));
        // MUST-SURVIVE: the rule keys on path segments, never a raw substring match. level.dat_old has no
        // dotdot segment and a literal file named with a "..zip" substring is not a dotdot segment either.
        zip("World.zip", "World/level.dat", LEVEL, "World/level.dat_old", LEVEL, "World/backup..zip", LEVEL,
                "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z"));
        assertTrue(RestoreSource.find(saves, "World").isPresent());
    }

    @Test
    void backslashPathEscapingEntryExcludesButBackslashFilenameSurvives() throws IOException {
        // World/..\evil hides its dotdot from a slash-only split, yet Windows Path.normalize collapses
        // it to targetParent\evil and extract refuses it. Discovery must reject it lexically on every OS.
        zip("World.zip", "World/level.dat", LEVEL, "World/..\\evil", LEVEL,
                "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z"));
        assertFalse(RestoreSource.find(saves, "World").isPresent());
        Files.delete(saves.resolve("World.zip"));
        // A dotdot behind a backslash deeper in the path is caught the same way.
        zip("World.zip", "World/level.dat", LEVEL, "World/foo/..\\bar", LEVEL,
                "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z"));
        assertFalse(RestoreSource.find(saves, "World").isPresent());
        Files.delete(saves.resolve("World.zip"));
        // MUST-SURVIVE: a backslash in a FILENAME with no dotdot is a legal POSIX name, not a traversal,
        // so the rule must key on the escaping segment, never on the backslash character.
        zip("World.zip", "World/level.dat", LEVEL, "World/a\\b.txt", LEVEL,
                "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z"));
        assertTrue(RestoreSource.find(saves, "World").isPresent());
    }

    @Test
    void prefixCollisionIsCaughtAcrossAnInterveningSibling() throws IOException {
        // '-' sorts below '/', so x-old sits between x and x/y in sorted order; a sorted-adjacency scan
        // would miss the x versus x/y collision. The membership rule must still exclude.
        zip("World.zip", "World/level.dat", LEVEL, "World/x", LEVEL, "World/x-old", LEVEL,
                "World/x/y", LEVEL, "World/wdl/download.jsonl", record("2026-03-01T10:00:00Z"));
        assertFalse(RestoreSource.find(saves, "World").isPresent());
    }
}
