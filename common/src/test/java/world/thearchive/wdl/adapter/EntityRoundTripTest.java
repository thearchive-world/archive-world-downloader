// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.EntitySinkImpl;
import world.thearchive.wdl.adapter.impl.WorldPathsImpl;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

class EntityRoundTripTest {
    private final EntitySink sink = new EntitySinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static IOWorker regionStorage(WorldPaths paths) {
        return paths.openRegionStorage(DimensionType.OVERWORLD);
    }

    /** A host region chunk with an empty {@code Level} compound, the terrain an entity fold lands inside. */
    private static CompoundTag hostChunk() {
        CompoundTag host = new CompoundTag();
        host.put("Level", new CompoundTag());
        return host;
    }

    private static CompoundTag entityTag(String id, int marker) {
        CompoundTag tag = EntityFixtures.entityTag(id);
        tag.putInt("wdlMarker", marker);
        return tag;
    }

    @Test
    void encodeChunkBuildsTheEntityCarrier() {
        ChunkPos pos = new ChunkPos(3, -7);

        CompoundTag tag = sink
                .encodeChunk(ImmutableList.of(entityTag("minecraft:pig", 1), entityTag("minecraft:cow", 2)), pos);

        ListTag entities = tag.getList("Entities", 10);
        assertEquals(2, entities.size(), "both entity tags must be retained in the Entities list");
        assertEquals("minecraft:pig", entities.getCompound(0).getString("id"));
        assertEquals("minecraft:cow", entities.getCompound(1).getString("id"));
    }

    @Test
    void encodeChunkReturnsNullWhenNoSaveableEntities() {
        assertNull(sink.encodeChunk(ImmutableList.of(), new ChunkPos(0, 0)),
                "an entity-chunk with nothing saveable is skipped, not written empty");
    }

    @Test
    void entityCarriersFoldIntoRegionChunksAcrossBoundaries(@TempDir Path save) throws IOException {
        WorldPaths paths = new WorldPathsImpl(save);
        Path regionDirectory = paths.regionDirectory(DimensionType.OVERWORLD);

        // (0,0)+(31,31) share r.0.0; the others cross into r.1.0 / r.0.1 / r.-1.-1.
        List<ChunkPos> positions = ImmutableList.of(
                new ChunkPos(0, 0), new ChunkPos(31, 31), new ChunkPos(32, 0),
                new ChunkPos(0, 32), new ChunkPos(-1, -1));

        try (IOWorker out = regionStorage(paths)) {
            for (int i = 0; i < positions.size(); i++) {
                ChunkPos pos = positions.get(i);
                out.store(pos, hostChunk()).join();
                CompoundTag carrier = sink.encodeChunk(ImmutableList.of(entityTag("minecraft:item", i)), pos);
                RegionChunkWriter.MergeWriteResult folded = RegionChunkWriter.foldEntitiesIntoRegion(out, pos, carrier,
                        null);
                assertEquals(RegionChunkWriter.MergeOutcome.WRITTEN_NEW, folded.outcome(),
                        "a first fold into a host chunk with no prior entities is a new write");
            }
            out.synchronize().join();
        }

        try (IOWorker in = regionStorage(paths)) {
            for (int i = 0; i < positions.size(); i++) {
                CompoundTag back = Optional.ofNullable(in.load(positions.get(i)))
                        .orElseThrow(() -> new AssertionError("missing chunk"));
                ListTag entities = back.getCompound("Level").getList("Entities", 10);
                assertEquals(1, entities.size());
                assertEquals(i,
                        entities.getCompound(0).contains("wdlMarker") ? entities.getCompound(0).getInt("wdlMarker")
                                : -1,
                        "the right entity must land in the chunk at " + positions.get(i));
                assertEquals("minecraft:item", entities.getCompound(0).getString("id"));
            }
        }

        assertTrue(Files.exists(regionDirectory.resolve("r.0.0.mca")), "r.0.0 holds (0,0) and (31,31)");
        assertTrue(Files.exists(regionDirectory.resolve("r.1.0.mca")), "(32,0) -> region 1,0");
        assertTrue(Files.exists(regionDirectory.resolve("r.0.1.mca")), "(0,32) -> region 0,1");
        assertTrue(Files.exists(regionDirectory.resolve("r.-1.-1.mca")), "(-1,-1) -> region -1,-1");
        assertFalse(Files.isDirectory(save.resolve("entities")), "no separate entities/ region is written at 1.16.5");
    }
}
