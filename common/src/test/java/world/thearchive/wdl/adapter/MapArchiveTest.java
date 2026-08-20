// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.holderReferencing;

import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless guard for the on-sight map archive: the live remap table ({@link MapManifest} plus the memoized
 * session-to-archive resolution and the on-sight data stream). The renumbering-server idempotency gate is driven
 * entirely here with an injected image resolver, so neither a live {@code Level} nor the {@code getMapData} resolution
 * is needed.
 */
class MapArchiveTest {
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /**
     * A synthetic serialized inner map data tag: the three hashed fields, plus the deliberately-ignored
     * {@code "locked"} and the {@code "xCenter"}/{@code "zCenter"} vanilla always writes, so a fixture that varies
     * either one is comparing realistic tags.
     */
    private static CompoundTag mapData(int colorFill, int scale, DimensionType dimension, boolean locked) {
        byte[] colors = new byte[16384];
        Arrays.fill(colors, (byte) colorFill);
        CompoundTag data = new CompoundTag();
        data.put("colors", new ByteArrayTag(colors));
        data.putByte("scale", (byte) scale);
        data.putInt("dimension", dimension.getId()); // at 1.15.2 vanilla writes the map dimension as an int id
        data.putBoolean("locked", locked);
        data.putInt("xCenter", 0);
        data.putInt("zCenter", 0);
        return data;
    }

    private static CompoundTag picture(int colorFill) {
        return mapData(colorFill, 0, DimensionType.OVERWORLD, true);
    }

    private static Set<Integer> collect(CompoundTag holder) {
        Set<Integer> ids = new LinkedHashSet<>();
        MapIdCollector.collectFromItemList(holder, "Items", ids);
        return ids;
    }

    /** A collecting sink: records each streamed (archiveId, dataTag), in order, and counts fires. */
    private static final class CollectingSink implements MapArchive.MapDataSink {
        final Map<Integer, Tag> streamed = new LinkedHashMap<>();
        int fires;

        @Override
        public void accept(int archiveId, Tag dataTag) {
            fires++;
            streamed.put(archiveId, dataTag);
        }
    }

    /** Write what the archive streamed plus its finalize idcounts, the on-disk shape a session leaves. */
    private static void writeDataFiles(Path dataDirectory, CollectingSink stream, MapArchive archive) {
        stream.streamed.forEach((id, tag) -> {
            try {
                MapDataWriter.write(dataDirectory, "map_" + id, tag);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Tag idCounts = archive.idCountsTag();
        if (idCounts != null) {
            try {
                MapDataWriter.write(dataDirectory, "idcounts", idCounts);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    void hashIgnoresLockedAndCentersButTracksColorsScaleDimension() {
        String base = MapArchive.hashOf(mapData(7, 1, DimensionType.OVERWORLD, true));
        assertEquals(base, MapArchive.hashOf(mapData(7, 1, DimensionType.OVERWORLD, false)),
                "a lock-state flip resolves to the same id");
        CompoundTag recentered = mapData(7, 1, DimensionType.OVERWORLD, true);
        recentered.putInt("xCenter", 512);
        recentered.putInt("zCenter", -512);
        assertEquals(base, MapArchive.hashOf(recentered), "a recentered map resolves to the same id");
        assertNotEquals(base, MapArchive.hashOf(mapData(8, 1, DimensionType.OVERWORLD, true)), "colors matter");
        assertNotEquals(base, MapArchive.hashOf(mapData(7, 2, DimensionType.OVERWORLD, true)), "scale matters");
        assertNotEquals(base, MapArchive.hashOf(mapData(7, 1, DimensionType.NETHER, true)), "dimension matters");
    }

    @Test
    void anImagedMapDedupsByContentAndStreamsOnce() {
        boolean[] hasColors = { true };
        CollectingSink stream = new CollectingSink();
        MapArchive archive = new MapArchive(MapManifest.empty(), id -> hasColors[0] ? picture(1) : null, stream);
        int first = archive.archiveIdFor(100);
        int again = archive.archiveIdFor(200); // a different session id, same picture content
        assertEquals(first, again, "identical content dedups to one archive id");
        assertEquals(1, stream.fires, "one content, one streamed write: the add-gate guard");
        assertTrue(stream.streamed.containsKey(first), "streamed under the archive id");
        hasColors[0] = false;
        assertEquals(first, archive.archiveIdFor(200),
                "the dedup hit still memoizes the session id: a later imageless sighting keeps the archive id");
    }

    @Test
    void aRepeatOfTheSameSessionIdDoesNotRefire() {
        int[] fill = { 1 };
        CollectingSink stream = new CollectingSink();
        MapArchive archive = new MapArchive(MapManifest.empty(), id -> picture(fill[0]), stream);
        int first = archive.archiveIdFor(100);
        fill[0] = 9; // the repeat resolves to changed content; imaged stickiness must win over a re-hash
        assertEquals(first, archive.archiveIdFor(100), "sticky");
        assertEquals(1, stream.fires, "the second sighting of the same id re-fires nothing");
    }

    @Test
    void anImagelessMapTakesFreshIdAndStreamsNothing() {
        CollectingSink stream = new CollectingSink();
        MapArchive archive = new MapArchive(MapManifest.empty(), id -> null, stream);
        int imageless = archive.archiveIdFor(500);
        assertEquals(0, imageless);
        assertEquals(0, stream.fires, "no colors, no streamed write");
        assertEquals(MapDataWriter.serializeIdCounts(0), archive.idCountsTag(),
                "idcounts still floors above the imageless id");
    }

    @Test
    void aLaterImagedSourceUpgradesAnEarlierImagelessResolution() {
        // The map is first seen imageless (in a chest, no colors), then carried so colors arrive.
        boolean[] hasColors = { false };
        CollectingSink stream = new CollectingSink();
        MapArchive archive = new MapArchive(MapManifest.empty(), id -> hasColors[0] ? picture(3) : null, stream);
        int imageless = archive.archiveIdFor(42);
        assertEquals(0, stream.fires, "imageless streams nothing");
        hasColors[0] = true;
        int imaged = archive.archiveIdFor(42);
        assertNotEquals(imageless, imaged, "the carried copy resolves to a real, imaged archive id");
        assertEquals(1, stream.fires, "the upgrade streams exactly once, with the imaged id");
        assertTrue(stream.streamed.containsKey(imaged), "streamed under the imaged id, never the imageless one");
        assertEquals(imaged, archive.archiveIdFor(42), "imaged is then sticky");
    }

    @Test
    void remapRewritesHolderItemsToArchiveIds() {
        CollectingSink stream = new CollectingSink();
        MapArchive archive = new MapArchive(MapManifest.empty(), id -> id == 1988 ? picture(1) : picture(2), stream);
        CompoundTag holder = holderReferencing(sink, 1988, 1993);

        archive.remap(holder, "Items");

        assertEquals(ImmutableSet.of(archive.archiveIdFor(1988), archive.archiveIdFor(1993)), collect(holder));
    }

    @Test
    void renumberingServerResumeReResolvesToTheSameArchiveIds(@TempDir Path directory) throws IOException {
        CompoundTag pictureP = picture(1);
        CompoundTag pictureQ = picture(2);
        Path manifestA = directory.resolve("a").resolve("wdl").resolve("map-ids");
        Path dataA = directory.resolve("a").resolve("data");
        Path manifestB = directory.resolve("b").resolve("wdl").resolve("map-ids");
        Path dataB = directory.resolve("b").resolve("data");

        // Session A: pictures P and Q under one server's session ids 1988, 1993.
        CollectingSink sinkA = new CollectingSink();
        MapArchive archiveA = new MapArchive(MapManifest.empty(),
                id -> id == 1988 ? pictureP : id == 1993 ? pictureQ : null, sinkA);
        CompoundTag holderA = holderReferencing(sink, 1988, 1993);
        archiveA.remap(holderA, "Items");
        writeDataFiles(dataA, sinkA, archiveA);
        archiveA.manifest().save(manifestA);
        int idOfP = archiveA.archiveIdFor(1988);
        int idOfQ = archiveA.archiveIdFor(1993);

        // Session B: resume, seeded from A's manifest; the same pictures, session ids reshuffled to 0, 1.
        MapManifest resumed = MapManifest.load(manifestA);
        resumed.raiseCounterAbove(MapManifest.highestDataFileId(dataA));
        CollectingSink sinkB = new CollectingSink();
        MapArchive archiveB = new MapArchive(resumed, id -> id == 0 ? pictureQ : id == 1 ? pictureP : null, sinkB);
        CompoundTag holderB = holderReferencing(sink, 0, 1);
        archiveB.remap(holderB, "Items");
        writeDataFiles(dataB, sinkB, archiveB);
        archiveB.manifest().save(manifestB);

        assertEquals(idOfP, archiveB.archiveIdFor(1), "B's id 1 is picture P, re-resolved to A's archive id");
        assertEquals(idOfQ, archiveB.archiveIdFor(0), "B's id 0 is picture Q, re-resolved to A's archive id");
        assertArrayEquals(Files.readAllBytes(dataA.resolve("map_" + idOfP + ".dat")),
                Files.readAllBytes(dataB.resolve("map_" + idOfP + ".dat")), "picture P data byte-identical");
        assertArrayEquals(Files.readAllBytes(dataA.resolve("map_" + idOfQ + ".dat")),
                Files.readAllBytes(dataB.resolve("map_" + idOfQ + ".dat")), "picture Q data byte-identical");
        assertArrayEquals(Files.readAllBytes(manifestA), Files.readAllBytes(manifestB),
                "the manifest grows the id space by zero across the resume");
        assertEquals(ImmutableSet.of(idOfP, idOfQ), collect(holderB), "B's remapped items carry A's archive ids");
    }

    @Test
    void offModeKeepsOriginalIdsAndSkipsDedup() {
        CollectingSink stream = new CollectingSink();
        MapArchive archive = new MapArchive(MapManifest.empty(), id -> picture(1), stream, false, -1);
        assertEquals(700, archive.archiveIdFor(700), "off-mode returns the original id unchanged");
        assertEquals(900, archive.archiveIdFor(900), "a second identical picture keeps its own original id");
        assertTrue(stream.streamed.containsKey(700), "streamed keyed by the original id");
        assertTrue(stream.streamed.containsKey(900), "no content dedup in off-mode: both ids stream");
        assertEquals(MapDataWriter.serializeIdCounts(900), archive.idCountsTag(),
                "idcounts floors at the highest referenced original id");
    }

    @Test
    void offModeImagelessKeepsIdStreamsNothingButRaisesIdcounts() {
        CollectingSink stream = new CollectingSink();
        MapArchive archive = new MapArchive(MapManifest.empty(), id -> null, stream, false, -1);
        assertEquals(900, archive.archiveIdFor(900), "an imageless map keeps its original id");
        assertEquals(0, stream.fires, "imageless streams no data file");
        assertEquals(MapDataWriter.serializeIdCounts(900), archive.idCountsTag(),
                "the imageless id still raises the floor so it is never reused");
    }

    @Test
    void offModeFloorSeedsIdcountsAbovePriorSessionsHigherId() {
        CollectingSink stream = new CollectingSink();
        MapArchive archive = new MapArchive(MapManifest.empty(), id -> picture(1), stream, false, 50);
        archive.archiveIdFor(5); // a low imaged id this session
        assertEquals(MapDataWriter.serializeIdCounts(50), archive.idCountsTag(),
                "the seeded floor keeps idcounts above a prior session's higher id");
    }

    @Test
    void offModeImagelessThenImagedUpgradesAndStreamsOnce() {
        // The imageless-add trap: a null-resolve sighting must not mark the id imaged, or this upgrade dies.
        boolean[] hasColors = { false };
        CollectingSink stream = new CollectingSink();
        MapArchive archive = new MapArchive(MapManifest.empty(), id -> hasColors[0] ? picture(4) : null, stream,
                false, -1);
        assertEquals(300, archive.archiveIdFor(300), "imageless keeps the original id");
        assertEquals(0, stream.fires, "not yet imaged, nothing streamed");
        hasColors[0] = true;
        assertEquals(300, archive.archiveIdFor(300), "same original id once imaged");
        assertEquals(1, stream.fires, "the later imaged sighting streams exactly once");
        assertTrue(stream.streamed.containsKey(300));
    }

    @Test
    void offModeRepeatImagedSightingDoesNotRefire() {
        CollectingSink stream = new CollectingSink();
        MapArchive archive = new MapArchive(MapManifest.empty(), id -> picture(8), stream, false, -1);
        assertEquals(20, archive.archiveIdFor(20), "the frame images 20");
        assertEquals(20, archive.archiveIdFor(20), "a later imaged re-reference reuses id 20");
        assertEquals(1, stream.fires, "the write-once gate: an imaged repeat re-fires nothing");
    }

    @Test
    void offModeImagedThenImagelessReferenceDoesNotRefire() {
        // The clobber walk: once imaged, a later reference short-circuits before the resolver runs.
        boolean[] hasColors = { true };
        CollectingSink stream = new CollectingSink();
        MapArchive archive = new MapArchive(MapManifest.empty(), id -> hasColors[0] ? picture(5) : null, stream,
                false, -1);
        assertEquals(15, archive.archiveIdFor(15), "the frame images 15");
        hasColors[0] = false; // the chest re-reference would resolve imageless, but the gate short-circuits first
        assertEquals(15, archive.archiveIdFor(15), "the chest re-reference reuses id 15");
        assertEquals(1, stream.fires, "no re-fire, no re-write, no clobber");
    }

    @Test
    void offModeResumeRewritesImagedIdempotentlyAndLeavesImagelessPrior(@TempDir Path directory) throws IOException {
        Path data = directory.resolve("data");

        // Session A, off-mode: image server map 700 and land it on disk.
        CollectingSink sinkA = new CollectingSink();
        MapArchive archiveA = new MapArchive(MapManifest.empty(), id -> picture(6), sinkA, false, -1);
        archiveA.archiveIdFor(700);
        writeDataFiles(data, sinkA, archiveA);
        byte[] prior = Files.readAllBytes(data.resolve("map_700.dat"));

        // Session B, off-mode resume (floor seeded past A): re-seeing 700 imaged re-streams the same id.
        CollectingSink sinkB = new CollectingSink();
        MapArchive archiveB = new MapArchive(MapManifest.empty(), id -> picture(6), sinkB, false, 700);
        archiveB.archiveIdFor(700);
        assertEquals(1, sinkB.fires, "the per-session imaged set re-streams a re-seen imaged id");
        writeDataFiles(data, sinkB, archiveB);
        assertArrayEquals(prior, Files.readAllBytes(data.resolve("map_700.dat")),
                "the re-write is idempotent: same server map, same bytes");

        // Session C, off-mode resume: an imageless re-reference streams nothing, the prior file is untouched.
        CollectingSink sinkC = new CollectingSink();
        MapArchive archiveC = new MapArchive(MapManifest.empty(), id -> null, sinkC, false, 700);
        archiveC.archiveIdFor(700);
        assertEquals(0, sinkC.fires, "imageless on resume writes nothing");
        assertArrayEquals(prior, Files.readAllBytes(data.resolve("map_700.dat")),
                "the prior capture is left untouched");
    }
}
