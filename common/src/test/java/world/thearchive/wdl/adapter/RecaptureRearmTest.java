// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkBiomeContainer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.HeadlessLevel;

/**
 * What {@link LiveCaptureSession#rearmUnsaved} leaves behind, read back through {@link LevelChunk#isUnsaved}: the
 * should-save query the change-driven dirty poll reads, whose entity term is permanently satisfied on a client chunk
 * because nothing writes a client chunk's last-save time.
 */
class RecaptureRearmTest {
    /** Any clock past zero, which every world is after its first tick, leaves the chunk's unwritten time behind. */
    private static final long WORLD_AGE_TICKS = 10_000L;

    private final HeadlessLevel level = HeadlessLevel.get();

    private LevelChunk entityBearingChunk() {
        level.setGameTime(WORLD_AGE_TICKS);
        LevelChunk chunk = new LevelChunk(level, new ChunkPos(0, 0),
                new ChunkBiomeContainer(new Biome[ChunkBiomeContainer.BIOMES_SIZE]));
        // Having held an entity at the last save is what arms the entity term; LevelChunk.addEntity sets it too.
        chunk.setLastSaveHadEntities(true);
        return chunk;
    }

    @Test
    void clearingTheUnsavedFlagAloneLeavesTheShouldSaveQueryTrue() {
        LevelChunk chunk = entityBearingChunk();

        chunk.setUnsaved(false);

        assertTrue(chunk.isUnsaved(),
                "nothing writes a client chunk's last-save time, so the entity term alone holds the query true");
    }

    @Test
    void theRearmLeavesTheQueryReadingTheUnsavedFlagAlone() {
        LevelChunk chunk = entityBearingChunk();

        LiveCaptureSession.rearmUnsaved(chunk);

        assertFalse(chunk.isUnsaved(), "the cleared entity flag silences the entity term");
        chunk.setUnsaved(true);
        assertTrue(chunk.isUnsaved(), "and a real change still reaches the poll through the unsaved flag");
    }

    @Test
    void anEntityEnteringTheChunkAfterTheRearmReflagsIt() {
        LevelChunk chunk = entityBearingChunk();
        LiveCaptureSession.rearmUnsaved(chunk);

        assertFalse(chunk.isUnsaved(), "a re-armed chunk stays quiet while nothing enters it");

        chunk.addEntity(new ItemEntity(EntityType.ITEM, level));
        assertTrue(chunk.isUnsaved(),
                "an entity entering re-arms the entity term, the bounded residual the poll's contract states");
    }
}
