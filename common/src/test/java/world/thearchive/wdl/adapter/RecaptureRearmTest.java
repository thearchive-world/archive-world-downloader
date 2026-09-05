// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.HeadlessLevel;

/**
 * What {@link LiveCaptureSession#rearmUnsaved} leaves behind, read back through {@code needsSaving(false)}: the
 * should-save query the change-driven dirty poll has to stand in for the unsaved reader this band's {@link Chunk} does
 * not expose.
 */
class RecaptureRearmTest {
    /** Vanilla's entity arm fires once the total world time reaches the chunk's last save time plus this. */
    private static final long ENTITY_TIMER_TICKS = 600L;

    /** Far past the entity timer, as any world more than thirty seconds old is. */
    private static final long WORLD_AGE_TICKS = 10_000L;

    private final World level = HeadlessLevel.get();

    private Chunk entityBearingChunk() {
        level.getWorldInfo().setWorldTotalTime(WORLD_AGE_TICKS);
        Chunk chunk = new Chunk(level, 0, 0);
        // Having ever held an entity is what arms the entity timer; Chunk.addEntity sets this same flag.
        chunk.setHasEntities(true);
        return chunk;
    }

    @Test
    void clearingTheUnsavedFlagAloneLeavesTheShouldSaveQueryTrue() {
        Chunk chunk = entityBearingChunk();

        chunk.setModified(false);

        assertTrue(chunk.needsSaving(false),
                "nothing writes a client chunk's last save time, so the entity arm alone holds the query true");
    }

    @Test
    void theRearmLeavesTheQueryReadingTheUnsavedFlagAlone() {
        Chunk chunk = entityBearingChunk();

        LiveCaptureSession.rearmUnsaved(chunk);

        assertFalse(chunk.needsSaving(false), "the stamped last save time silences the entity arm");
    }

    @Test
    void anUnchangedChunkReentersTheQueryOnceTheEntityTimerExpires() {
        Chunk chunk = entityBearingChunk();
        LiveCaptureSession.rearmUnsaved(chunk);

        level.getWorldInfo().setWorldTotalTime(WORLD_AGE_TICKS + ENTITY_TIMER_TICKS - 1L);
        assertFalse(chunk.needsSaving(false), "the entity arm stays quiet for the whole 600-tick window");

        level.getWorldInfo().setWorldTotalTime(WORLD_AGE_TICKS + ENTITY_TIMER_TICKS);
        assertTrue(chunk.needsSaving(false), "at 600 ticks the chunk re-enters the dirty set for one re-encode");
    }
}
