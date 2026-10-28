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
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.DimensionType;
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

    private static WdlRegionStorage regionStorage(WorldPaths paths) {
        return paths.openRegionStorage(DimensionType.OVERWORLD);
    }

    /** A host region chunk with an empty {@code Level} compound, the terrain an entity fold lands inside. */
    private static NBTTagCompound hostChunk() {
        NBTTagCompound host = new NBTTagCompound();
        host.setTag("Level", new NBTTagCompound());
        return host;
    }

    private static NBTTagCompound entityTag(String id, int marker) {
        NBTTagCompound tag = EntityFixtures.entityTag(id);
        tag.setInteger("wdlMarker", marker);
        return tag;
    }

    @Test
    void encodeChunkBuildsTheEntityCarrier() {
        ChunkPos pos = new ChunkPos(3, -7);

        NBTTagCompound tag = sink
                .encodeChunk(ImmutableList.of(entityTag("Pig", 1), entityTag("Cow", 2)), pos);

        NBTTagList entities = tag.getTagList("Entities", 10);
        assertEquals(2, entities.tagCount(), "both entity tags must be retained in the Entities list");
        assertEquals("Pig", entities.getCompoundTagAt(0).getString("id"));
        assertEquals("Cow", entities.getCompoundTagAt(1).getString("id"));
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

        try (WdlRegionStorage out = regionStorage(paths)) {
            for (int i = 0; i < positions.size(); i++) {
                ChunkPos pos = positions.get(i);
                out.write(pos, hostChunk());
                NBTTagCompound carrier = sink.encodeChunk(ImmutableList.of(entityTag("Item", i)), pos);
                RegionChunkWriter.MergeWriteResult folded = RegionChunkWriter.foldEntitiesIntoRegion(out, pos, carrier,
                        null);
                assertEquals(RegionChunkWriter.MergeOutcome.WRITTEN_NEW, folded.outcome(),
                        "a first fold into a host chunk with no prior entities is a new write");
            }
        }

        try (WdlRegionStorage in = regionStorage(paths)) {
            for (int i = 0; i < positions.size(); i++) {
                NBTTagCompound back = Optional.ofNullable(in.read(positions.get(i)))
                        .orElseThrow(() -> new AssertionError("missing chunk"));
                NBTTagList entities = back.getCompoundTag("Level").getTagList("Entities", 10);
                assertEquals(1, entities.tagCount());
                assertEquals(i,
                        entities.getCompoundTagAt(0).hasKey("wdlMarker")
                                ? entities.getCompoundTagAt(0).getInteger("wdlMarker")
                                : -1,
                        "the right entity must land in the chunk at " + positions.get(i));
                assertEquals("Item", entities.getCompoundTagAt(0).getString("id"));
            }
        }

        assertTrue(Files.exists(regionDirectory.resolve("r.0.0.mca")), "r.0.0 holds (0,0) and (31,31)");
        assertTrue(Files.exists(regionDirectory.resolve("r.1.0.mca")), "(32,0) -> region 1,0");
        assertTrue(Files.exists(regionDirectory.resolve("r.0.1.mca")), "(0,32) -> region 0,1");
        assertTrue(Files.exists(regionDirectory.resolve("r.-1.-1.mca")), "(-1,-1) -> region -1,-1");
        assertFalse(Files.isDirectory(save.resolve("entities")), "no separate entities/ region is written at 1.10.2");
    }
}
