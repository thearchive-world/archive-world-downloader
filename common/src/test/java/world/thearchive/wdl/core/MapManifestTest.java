// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapManifestTest {
    private static final String HASH_P = "00000000000000000000000000000000000000000000000000000000000000aa";
    private static final String HASH_Q = "00000000000000000000000000000000000000000000000000000000000000bb";

    @Test
    void anEmptyManifestStartsAtIdZero() {
        MapManifest manifest = MapManifest.empty();
        assertEquals(0, manifest.nextArchiveId());
        assertEquals(-1, manifest.highestAssignedId(), "no id assigned yet");
        assertEquals(0, manifest.size());
    }

    @Test
    void raisingTheCounterMovesItUpOnlyAndIssuesTheNextIdAboveTheFloor() {
        MapManifest seeded = MapManifest.empty();
        seeded.raiseCounterAbove(7);
        assertEquals(7, seeded.highestAssignedId(),
                "the floor reads as assigned, so the idcounts this manifest finalizes still covers map_7.dat");
        assertEquals(8, seeded.lookupOrInsert(HASH_P), "and the first id it hands out clears every used id");

        seeded.raiseCounterAbove(3);
        assertEquals(9, seeded.lookupOrInsert(HASH_Q), "a floor the counter already clears moves it nowhere");

        MapManifest unseeded = MapManifest.empty();
        unseeded.raiseCounterAbove(-1);
        assertEquals(0, unseeded.nextArchiveId(), "and no used id at all leaves the counter where empty() put it");
    }

    @Test
    void lookupOrInsertAssignsSequentialIdsAndDedups() {
        MapManifest manifest = MapManifest.empty();
        assertEquals(0, manifest.lookupOrInsert(HASH_P));
        assertEquals(1, manifest.lookupOrInsert(HASH_Q));
        assertEquals(0, manifest.lookupOrInsert(HASH_P), "a re-seen hash keeps its stable id (idempotent)");
        assertEquals(2, manifest.nextArchiveId());
        assertEquals(1, manifest.highestAssignedId());
        assertEquals(2, manifest.size());
    }

    @Test
    void anImagelessIdNeverAliasesAnImagedId() {
        MapManifest manifest = MapManifest.empty();
        int imaged = manifest.lookupOrInsert(HASH_P); // 0
        int imageless = manifest.allocateImageless(); // 1, no hash recorded
        int imagedAgain = manifest.lookupOrInsert(HASH_Q); // 2
        assertNotEquals(imaged, imageless);
        assertNotEquals(imageless, imagedAgain);
        assertEquals(1, imageless);
        assertEquals(2, imagedAgain, "the shared counter advanced past the imageless id");
        assertEquals(2, manifest.size(), "the imageless allocation recorded no hash entry");
    }

    @Test
    void savedManifestRoundTripsTheTableAndCounter(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl").resolve("map-ids");
        MapManifest written = MapManifest.empty();
        written.lookupOrInsert(HASH_P);
        written.lookupOrInsert(HASH_Q);
        written.allocateImageless(); // advances the counter to 3 with no entry
        written.save(file);

        MapManifest read = MapManifest.load(file);
        assertEquals(0, read.lookupOrInsert(HASH_P), "P resolves to its stored id without a new allocation");
        assertEquals(1, read.lookupOrInsert(HASH_Q));
        assertEquals(3, read.nextArchiveId(), "the imageless allocation's counter high-water survives the round trip");
        assertEquals(2, read.size());
    }

    @Test
    void reSavingTheReResolvedManifestIsByteIdentical(@TempDir Path directory) throws IOException {
        Path fileA = directory.resolve("a").resolve("map-ids");
        Path fileB = directory.resolve("b").resolve("map-ids");
        MapManifest sessionA = MapManifest.empty();
        sessionA.lookupOrInsert(HASH_P);
        sessionA.lookupOrInsert(HASH_Q);
        sessionA.save(fileA);

        MapManifest sessionB = MapManifest.load(fileA);
        sessionB.lookupOrInsert(HASH_Q); // re-seen in a different order, must not renumber
        sessionB.lookupOrInsert(HASH_P);
        sessionB.save(fileB);

        assertArrayEquals(Files.readAllBytes(fileA), Files.readAllBytes(fileB),
                "re-resolving the same content grows the id space by zero and writes identical bytes");
    }

    @Test
    void loadingAnAbsentFileGivesAnEmptyManifest(@TempDir Path directory) throws IOException {
        MapManifest manifest = MapManifest.load(directory.resolve("does-not-exist"));
        assertEquals(0, manifest.nextArchiveId());
        assertEquals(0, manifest.size());
    }

    @Test
    void aTornTrailingEntryIsSkipped(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("map-ids");
        MapManifest written = MapManifest.empty();
        written.lookupOrInsert(HASH_P);
        written.save(file);
        Files.write(file, ("\n" + HASH_Q + "\t").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        MapManifest read = MapManifest.load(file);
        assertEquals(0, read.lookupOrInsert(HASH_P), "the good entry survives");
        assertEquals(1, read.lookupOrInsert(HASH_Q), "the torn entry was skipped, so Q is new here");
    }

    @Test
    void highestDataFileIdScansTheDataDirectory(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.write(directory.resolve("map_3.dat"), new byte[0]);
        Files.write(directory.resolve("map_10.dat"), new byte[0]);
        Files.write(directory.resolve("idcounts.dat"), new byte[0]); // not a map file
        assertEquals(10, MapManifest.highestDataFileId(directory));
        assertEquals(-1, MapManifest.highestDataFileId(directory.resolve("absent")));
    }

    @Test
    void theCrashFloorTakesTheHigherOfTheManifestAndTheOnDiskData(@TempDir Path directory) throws IOException {
        Path data = directory.resolve("data");
        Files.createDirectories(data);
        Path file = directory.resolve("wdl").resolve("map-ids");

        MapManifest stale = MapManifest.empty();
        stale.lookupOrInsert(HASH_P); // counter -> 1
        stale.save(file);
        // A crash wrote data/map_7.dat after the (now stale) manifest's last rewrite.
        Files.write(data.resolve("map_7.dat"), new byte[0]);

        MapManifest resumed = MapManifest.load(file);
        resumed.raiseCounterAbove(MapManifest.highestDataFileId(data));
        assertEquals(8, resumed.allocateImageless(), "the next id clears every used on-disk id");
    }

    @Test
    void theCrashFloorKeepsTheManifestCounterWhenItLeadsTheOnDiskData(@TempDir Path directory) throws IOException {
        Path data = directory.resolve("data");
        Files.createDirectories(data);
        Path file = directory.resolve("wdl").resolve("map-ids");

        MapManifest manifest = MapManifest.empty();
        manifest.lookupOrInsert(HASH_P);
        manifest.allocateImageless(); // counter -> 2, but the imageless id wrote no data file
        manifest.save(file);
        Files.write(data.resolve("map_0.dat"), new byte[0]); // only the imaged id has a data file

        MapManifest resumed = MapManifest.load(file);
        resumed.raiseCounterAbove(MapManifest.highestDataFileId(data));
        assertEquals(2, resumed.allocateImageless(), "the manifest's counter protects the imageless id");
    }

    @Test
    void anEmptyFileGivesAnEmptyManifest(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("map-ids");
        Files.write(file, new byte[0]); // a zero-byte file, e.g. a torn write that got the create but no content
        MapManifest manifest = MapManifest.load(file);
        assertEquals(0, manifest.size());
        assertEquals(0, manifest.nextArchiveId());
    }

    @Test
    void aZeroCounterHeaderStillKeepsItsEntries(@TempDir Path directory) throws IOException {
        // 0 is a valid high-water (a fresh manifest saves "1\t0"); only a negative or unparseable counter
        // discards the file, so a well-formed zero-counter header must still surface any recorded entry.
        Path file = directory.resolve("map-ids");
        Files.write(file, ("1\t0\n" + HASH_P + "\t7").getBytes(StandardCharsets.UTF_8));
        MapManifest manifest = MapManifest.load(file);
        assertEquals(1, manifest.size(), "the zero-counter header is not treated as corrupt");
        assertEquals(7, manifest.lookupOrInsert(HASH_P), "the recorded entry survives with its stored id");
    }

    @Test
    void aHeaderWithNoSeparatorDiscardsTheFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("map-ids");
        Files.write(file, ("no-separator-here\n" + HASH_P + "\t2").getBytes(StandardCharsets.UTF_8));
        assertEquals(0, MapManifest.load(file).size(), "too few header fields is corrupt, so nothing is read");
    }

    @Test
    void aNonNumericSchemaFieldDiscardsTheFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("map-ids");
        Files.write(file, ("abc\t5\n" + HASH_P + "\t2").getBytes(StandardCharsets.UTF_8));
        MapManifest manifest = MapManifest.load(file);
        assertEquals(0, manifest.size(), "a non-integer schema field yields an empty manifest, not a partial read");
        assertEquals(0, manifest.nextArchiveId(), "and no counter is adopted from the corrupt header");
    }

    @Test
    void aNegativeSchemaFieldDiscardsTheFile(@TempDir Path directory) throws IOException {
        // The schema field must be non-negative; a negative value is corrupt (it collides with the parse-error
        // sentinel), so the file is discarded whole: neither the counter beside it nor its entries are read. The
        // entry line and the size assertion are what separate the discard from a counter of 0 read off a
        // well-formed header, which would leave nextArchiveId 0 too but keep the entry.
        Path file = directory.resolve("map-ids");
        Files.write(file, ("-1\t5\n" + HASH_P + "\t2").getBytes(StandardCharsets.UTF_8));
        MapManifest manifest = MapManifest.load(file);
        assertEquals(0, manifest.size(), "a negative schema field yields an empty manifest, not a partial read");
        assertEquals(0, manifest.nextArchiveId(), "and no counter is adopted from the corrupt header");
    }

    @Test
    void aZeroSchemaFieldIsWellFormedAndItsCounterIsRead(@TempDir Path directory) throws IOException {
        // Zero is a non-negative (well-formed) schema field, so the header's counter is read; only a negative or
        // unparseable schema marks the file corrupt. There is no schema-version equality check today, so any
        // non-negative schema is accepted (a version bump would add a migrator; see the SCHEMA_VERSION contract).
        Path file = directory.resolve("map-ids");
        Files.write(file, "0\t5".getBytes(StandardCharsets.UTF_8));
        assertEquals(5, MapManifest.load(file).nextArchiveId());
    }

    @Test
    void aNonNumericCounterFieldDiscardsTheFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("map-ids");
        Files.write(file, ("1\tnope\n" + HASH_P + "\t2").getBytes(StandardCharsets.UTF_8));
        assertEquals(0, MapManifest.load(file).size(), "a non-integer counter is corrupt, so nothing is read");
    }

    @Test
    void savingReordersEntriesByIdRegardlessOfLoadOrder(@TempDir Path directory) throws IOException {
        // Entries read out of id order (a hand-edited or differently-written file) are rewritten lowest-id-first,
        // so the on-disk form is canonical and a re-save stays byte-stable no matter the load order.
        Path in = directory.resolve("in").resolve("map-ids");
        Files.createDirectories(in.getParent());
        Files.write(in, ("1\t2\n" + HASH_Q + "\t1\n" + HASH_P + "\t0").getBytes(StandardCharsets.UTF_8));

        Path out = directory.resolve("out").resolve("map-ids");
        MapManifest.load(in).save(out);

        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
        assertEquals(HASH_P + "\t0", lines.get(1), "the lowest id is written first, not the file's load order");
        assertEquals(HASH_Q + "\t1", lines.get(2));
    }

    @Test
    void highestDataFileIdIgnoresNonMapAndNonNumericFiles(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.write(directory.resolve("idcounts.dat"), new byte[0]); // no map_ prefix
        Files.write(directory.resolve("map_notanumber.dat"), new byte[0]); // map_ prefix, non-numeric id
        Files.write(directory.resolve("scratch.txt"), new byte[0]);
        assertEquals(-1, MapManifest.highestDataFileId(directory),
                "a directory with no numeric map_<n>.dat file has no highest id");
    }

    @Test
    void pathInAndExistsInTrackTheManifestFile(@TempDir Path directory) throws IOException {
        Path saveFolder = directory.resolve("world");
        assertEquals(saveFolder.resolve("wdl").resolve("map-ids"), MapManifest.pathIn(saveFolder));
        assertFalse(MapManifest.existsIn(saveFolder), "absent before any download");
        Files.createDirectories(MapManifest.pathIn(saveFolder).getParent());
        MapManifest.empty().save(MapManifest.pathIn(saveFolder));
        assertTrue(MapManifest.existsIn(saveFolder), "present once a remap manifest is written");
    }

    @Test
    void schemeMismatchNeedsImagedDataAndModeDifference(@TempDir Path directory) throws IOException {
        Path saveFolder = directory.resolve("world");
        Path data = saveFolder.resolve("data");
        // No imaged map data yet: never a mismatch, whatever the knob says.
        assertFalse(MapManifest.schemeMismatch(saveFolder, true));
        assertFalse(MapManifest.schemeMismatch(saveFolder, false));
        // An imaged map data file with no manifest is original-id mode: matches off, mismatches on.
        Files.createDirectories(data);
        Files.createFile(data.resolve("map_0.dat"));
        assertFalse(MapManifest.schemeMismatch(saveFolder, false));
        assertTrue(MapManifest.schemeMismatch(saveFolder, true));
        // Adding a manifest makes it remapped mode: matches on, mismatches off.
        Files.createDirectories(MapManifest.pathIn(saveFolder).getParent());
        MapManifest.empty().save(MapManifest.pathIn(saveFolder));
        assertTrue(MapManifest.schemeMismatch(saveFolder, false));
        assertFalse(MapManifest.schemeMismatch(saveFolder, true));
    }
}
