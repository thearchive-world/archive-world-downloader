// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.EntitySinkImpl;
import world.thearchive.wdl.adapter.impl.WorldPathsImpl;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for entity capture: the {@link EntitySink} pure envelope slice
 * ({@link EntitySink#encodeChunk(List, ChunkPos)} over already-serialized entity tags) plus the {@code entities/}
 * {@link SimpleRegionStorage} ({@link DataFixTypes#ENTITY_CHUNK}) write/read is a self-consistent, vanilla-valid Anvil
 * round-trip: {@code Position} decodes via {@link ChunkPos#CODEC}, the {@code Entities} list survives with each
 * entity's {@code id}, and {@code DataVersion} is stamped.
 *
 * <p>Server-free by construction: hand-built entity tags drive the envelope, so neither a live {@code Entity} nor a
 * {@code Level} is needed. The two client/level-coupled steps are not exercised headless, exactly as for chunks
 * ({@link ChunkRoundTripTest}):
 * <ul>
 * <li>the live {@code entity.save(ValueOutput)} serialization needs a real {@code Entity}; and</li>
 * <li>{@code EntityType.loadEntitiesRecursive} parse-back needs a {@code Level}.</li>
 * </ul>
 * This test proves the on-disk envelope + region IO self-consistency.
 */
class EntityRoundTripTest {
    private final EntitySink sink = new EntitySinkImpl();

    /**
     * Initialize vanilla's static state (versioned constants + built-in registries) so {@code ChunkPos},
     * {@code DataFixers}, and the region pipeline are usable headless. The pure envelope is itself registry-free; we
     * reuse the project's memoized bootstrap (not its {@code RegistryAccess}) only so this test is self-sufficient when
     * run in isolation.
     */
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    private static SimpleRegionStorage entityStorage(WorldPaths paths, Path entitiesDirectory) {
        return new SimpleRegionStorage(
                paths.entitiesStorageInfo(Level.OVERWORLD), entitiesDirectory,
                DataFixers.getDataFixer(), false, DataFixTypes.ENTITY_CHUNK);
    }

    /** A minimal stand-in for a serialized entity tag: an {@code id} (its type) plus a placement marker. */
    private static CompoundTag entityTag(String id, int marker) {
        CompoundTag tag = EntityFixtures.entityTag(id);
        tag.putInt("wdlMarker", marker);
        return tag;
    }

    @Test
    void entitiesStorageInfoIsTypedEntitiesNotChunk() {
        WorldPaths paths = new WorldPathsImpl(Path.of("unused"));

        RegionStorageInfo entities = paths.entitiesStorageInfo(Level.OVERWORLD);
        assertEquals("entities", entities.type(), "the entities region must be typed \"entities\", not \"chunk\"");
        assertEquals(Level.OVERWORLD, entities.dimension());
        assertEquals("chunk", paths.regionStorageInfo(Level.OVERWORLD).type(),
                "the chunk region info must stay byte-compatible (type \"chunk\")");
    }

    @Test
    void encodeChunkBuildsVanillaEntityEnvelope() {
        ChunkPos pos = new ChunkPos(3, -7);

        CompoundTag tag = sink.encodeChunk(List.of(entityTag("minecraft:pig", 1), entityTag("minecraft:cow", 2)), pos);

        assertTrue((tag.contains("DataVersion") ? tag.getInt("DataVersion") : -1) > 0,
                "must stamp a current DataVersion");
        assertEquals(pos, ChunkPos.CODEC.parse(NbtOps.INSTANCE, tag.get("Position")).result().orElseThrow(),
                "Position must encode the chunk pos via ChunkPos.CODEC");

        ListTag entities = tag.getList("Entities", Tag.TAG_COMPOUND);
        assertEquals(2, entities.size(), "both entity tags must be retained in the Entities list");
        assertEquals("minecraft:pig", entities.getCompound(0).getString("id"));
        assertEquals("minecraft:cow", entities.getCompound(1).getString("id"));
    }

    @Test
    void encodeChunkReturnsNullWhenNoSaveableEntities() {
        assertNull(sink.encodeChunk(List.of(), new ChunkPos(0, 0)),
                "an entity-chunk with nothing saveable is skipped, not written empty");
    }

    @Test
    void entityEnvelopesRoundTripThroughRegionStorageAcrossBoundaries(@TempDir Path save) throws IOException {
        WorldPaths paths = new WorldPathsImpl(save);
        Path entitiesDirectory = paths.entitiesDirectory(Level.OVERWORLD);

        // Same boundary set the chunk path covers: (0,0)+(31,31) share r.0.0; the others cross into
        // r.1.0 / r.0.1 / r.-1.-1. A distinct marker per entity-chunk proves each lands at its own pos.
        List<ChunkPos> positions = List.of(
                new ChunkPos(0, 0), new ChunkPos(31, 31), new ChunkPos(32, 0),
                new ChunkPos(0, 32), new ChunkPos(-1, -1));

        try (SimpleRegionStorage out = entityStorage(paths, entitiesDirectory)) {
            for (int i = 0; i < positions.size(); i++) {
                out.write(positions.get(i), sink.encodeChunk(List.of(entityTag("minecraft:item", i)), positions.get(i)))
                        .join();
            }
            out.synchronize(true).join();
        }

        // Reopen with a FRESH storage and read every entity-chunk back at its position.
        try (SimpleRegionStorage in = entityStorage(paths, entitiesDirectory)) {
            for (int i = 0; i < positions.size(); i++) {
                CompoundTag back = in.read(positions.get(i)).join()
                        .orElseThrow(() -> new AssertionError("missing entity-chunk"));
                assertEquals(positions.get(i),
                        ChunkPos.CODEC.parse(NbtOps.INSTANCE, back.get("Position")).result().orElseThrow(),
                        "Position must survive the region round-trip");
                ListTag entities = back.getList("Entities", Tag.TAG_COMPOUND);
                assertEquals(1, entities.size());
                assertEquals(i,
                        entities.getCompound(0).contains("wdlMarker") ? entities.getCompound(0).getInt("wdlMarker")
                                : -1,
                        "the right entity-chunk must land at " + positions.get(i));
                assertEquals("minecraft:item", entities.getCompound(0).getString("id"));
            }
        }

        assertTrue(Files.exists(entitiesDirectory.resolve("r.0.0.mca")), "r.0.0 holds (0,0) and (31,31)");
        assertTrue(Files.exists(entitiesDirectory.resolve("r.1.0.mca")), "(32,0) -> region 1,0");
        assertTrue(Files.exists(entitiesDirectory.resolve("r.0.1.mca")), "(0,32) -> region 0,1");
        assertTrue(Files.exists(entitiesDirectory.resolve("r.-1.-1.mca")), "(-1,-1) -> region -1,-1");
    }
}
