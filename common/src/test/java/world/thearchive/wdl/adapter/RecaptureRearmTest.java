// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.HeadlessLevel;

/**
 * What {@link LiveCaptureSession#rearmUnsaved} leaves behind, read back through {@code method_3893(false)}: the
 * should-save query the change-driven dirty poll has to stand in for the unsaved reader this band's {@link LevelChunk}
 * does not expose.
 */
class RecaptureRearmTest {
    /** Vanilla's entity arm fires once the game time reaches the chunk's last save time plus this. */
    private static final long ENTITY_TIMER_TICKS = 600L;

    /** Far past the entity timer, as any world more than thirty seconds old is. */
    private static final long WORLD_AGE_TICKS = 10_000L;

    /** A column's worth of biome cells, which the chunk constructor takes and this gate never reads. */
    private static final int BIOME_CELLS = 256;

    private final Level level = HeadlessLevel.get();

    private LevelChunk entityBearingChunk() {
        level.setGameTime(WORLD_AGE_TICKS);
        LevelChunk chunk = new LevelChunk(level, 0, 0, new Biome[BIOME_CELLS]);
        // Having held an entity at the last save is what arms the entity timer; LevelChunk.addEntity sets it too.
        chunk.setLastSaveHadEntities(true);
        return chunk;
    }

    @Test
    void clearingTheUnsavedFlagAloneLeavesTheShouldSaveQueryTrue() {
        LevelChunk chunk = entityBearingChunk();

        chunk.setUnsaved(false);

        assertTrue(chunk.method_3893(false),
                "nothing writes a client chunk's last save time, so the entity arm alone holds the query true");
    }

    @Test
    void theRearmLeavesTheQueryReadingTheUnsavedFlagAlone() {
        LevelChunk chunk = entityBearingChunk();

        LiveCaptureSession.rearmUnsaved(chunk);

        assertFalse(chunk.method_3893(false), "the stamped last save time silences the entity arm");
    }

    @Test
    void anUnchangedChunkReentersTheQueryOnceTheEntityTimerExpires() {
        LevelChunk chunk = entityBearingChunk();
        LiveCaptureSession.rearmUnsaved(chunk);

        level.setGameTime(WORLD_AGE_TICKS + ENTITY_TIMER_TICKS - 1L);
        assertFalse(chunk.method_3893(false), "the entity arm stays quiet for the whole 600-tick window");

        level.setGameTime(WORLD_AGE_TICKS + ENTITY_TIMER_TICKS);
        assertTrue(chunk.method_3893(false), "at 600 ticks the chunk re-enters the dirty set for one re-encode");
    }
}
