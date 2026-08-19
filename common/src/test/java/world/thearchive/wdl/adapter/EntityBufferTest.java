// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless guard for the per-tick entity accumulation buffer: entities are keyed by UUID so re-seeing one in a new
 * chunk re-homes it (the write-time dedup, within-session), and chunks drain independently so the buffer stays bounded
 * as the player roams (it is the entity analog of the keep-hot chunk buffer).
 *
 * <p>The chunk view is served from an index rather than a scan, so several tests here pin the invariant that the chunks
 * it reports partition the buffer exactly: an entity the chunk view fails to report is never written and never counted
 * as a drop, so it would leave a silently short save that reports itself clean.
 */
class EntityBufferTest {
    private static final UUID UUID_A = new UUID(0, 1);
    private static final UUID UUID_B = new UUID(0, 2);
    private static final UUID UUID_C = new UUID(0, 3);
    private static final UUID UUID_D = new UUID(0, 4);

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen(); // ChunkPos static init needs the vanilla bootstrap (SharedConstants)
    }

    private static CompoundTag tag(String marker) {
        return EntityFixtures.entityTag(marker);
    }

    private static List<String> markersOf(List<CompoundTag> tags) {
        List<String> markers = new ArrayList<>();
        for (CompoundTag tag : tags) {
            markers.add(tag.getString("id"));
        }
        return markers;
    }

    @Test
    void accumulatedEntitiesDrainByChunk() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("a"));
        buffer.accumulate(UUID_B, new ChunkPos(0, 0), tag("b"));

        List<CompoundTag> drained = buffer.drainChunk(new ChunkPos(0, 0));

        assertEquals(2, drained.size());
        assertTrue(buffer.isEmpty(), "the chunk drained completely");
    }

    @Test
    void reSeeingAnEntityInNewChunkReHomesItAndDropsTheStaleCopy() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("old"));
        buffer.accumulate(UUID_A, new ChunkPos(5, 5), tag("new")); // it walked into a new chunk

        assertTrue(buffer.drainChunk(new ChunkPos(0, 0)).isEmpty(), "the stale copy is gone from the old chunk");
        List<CompoundTag> current = buffer.drainChunk(new ChunkPos(5, 5));
        assertEquals(1, current.size(), "exactly one copy, in the newest chunk");
        assertEquals("new", current.get(0).getString("id"));
    }

    @Test
    void reHomingDropsTheEmptiedChunkFromBufferedChunks() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("old"));
        buffer.accumulate(UUID_A, new ChunkPos(5, 5), tag("new"));

        assertEquals(ImmutableSet.of(new ChunkPos(5, 5)), buffer.bufferedChunks(),
                "the chunk the entity left holds nothing, so it is not a buffered chunk");
    }

    @Test
    void reHomingLeavesChunkOfReportingTheNewChunk() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("old"));
        buffer.accumulate(UUID_A, new ChunkPos(5, 5), tag("new"));

        assertEquals(new ChunkPos(5, 5), buffer.chunkOf(UUID_A));
    }

    @Test
    void reHomingKeepsAnOldChunkThatStillHoldsAnotherEntity() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("a"));
        buffer.accumulate(UUID_B, new ChunkPos(0, 0), tag("b"));
        buffer.accumulate(UUID_A, new ChunkPos(5, 5), tag("a-moved"));

        assertEquals(ImmutableSet.of(new ChunkPos(0, 0), new ChunkPos(5, 5)), buffer.bufferedChunks());
        assertEquals(ImmutableList.of("b"), markersOf(buffer.drainChunk(new ChunkPos(0, 0))),
                "the entity that stayed behind is still buffered in the old chunk");
    }

    @Test
    void reSeeingAnEntityInTheSameChunkReplacesTheTag() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("old"));
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("new"));

        assertEquals(ImmutableList.of("new"), markersOf(buffer.drainChunk(new ChunkPos(0, 0))));
        assertTrue(buffer.isEmpty(), "the replaced copy left no residue");
    }

    @Test
    void chunkOfReportsTheCurrentChunkForTheNewOrMovedCheck() {
        EntityBuffer buffer = new EntityBuffer();
        assertNull(buffer.chunkOf(UUID_A), "an unseen entity has no chunk");
        buffer.accumulate(UUID_A, new ChunkPos(3, 4), tag("a"));
        assertEquals(new ChunkPos(3, 4), buffer.chunkOf(UUID_A));
    }

    @Test
    void chunkOfForgetsAnEntityWhoseChunkDrained() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("a"));
        buffer.drainChunk(new ChunkPos(0, 0));

        assertNull(buffer.chunkOf(UUID_A), "a flushed entity is unbuffered again, so the prime poll re-buffers it");
    }

    @Test
    void bufferedChunksListsEveryChunkHoldingEntities() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("a"));
        buffer.accumulate(UUID_B, new ChunkPos(1, 1), tag("b"));

        assertEquals(ImmutableSet.of(new ChunkPos(0, 0), new ChunkPos(1, 1)), buffer.bufferedChunks());
    }

    @Test
    void drainingOneChunkLeavesTheOtherChunkBuffered() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("a"));
        buffer.accumulate(UUID_B, new ChunkPos(1, 1), tag("b"));

        assertEquals(ImmutableList.of("a"), markersOf(buffer.drainChunk(new ChunkPos(0, 0))));

        assertEquals(ImmutableSet.of(new ChunkPos(1, 1)), buffer.bufferedChunks(),
                "the undrained chunk is still reported");
        assertEquals(new ChunkPos(1, 1), buffer.chunkOf(UUID_B));
        assertEquals(ImmutableList.of("b"), markersOf(buffer.drainChunk(new ChunkPos(1, 1))));
        assertTrue(buffer.isEmpty());
    }

    @Test
    void bufferedChunksSnapshotAllowsDrainingInsideTheLoop() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("a"));
        buffer.accumulate(UUID_B, new ChunkPos(1, 1), tag("b"));

        List<String> markers = new ArrayList<>();
        for (ChunkPos pos : buffer.bufferedChunks()) {
            markers.addAll(markersOf(buffer.drainChunk(pos))); // the flush loop drains inside the iteration
        }

        assertEquals(ImmutableSet.of("a", "b"), new HashSet<>(markers));
        assertTrue(buffer.isEmpty());
    }

    @Test
    void bufferedChunksPartitionEveryAccumulatedEntity() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("a"));
        buffer.accumulate(UUID_B, new ChunkPos(0, 0), tag("b"));
        buffer.accumulate(UUID_C, new ChunkPos(1, 1), tag("c"));
        buffer.accumulate(UUID_A, new ChunkPos(1, 1), tag("a-moved")); // re-home into an occupied chunk
        buffer.accumulate(UUID_B, new ChunkPos(2, 2), tag("b-moved")); // re-home, emptying (0, 0)
        buffer.accumulate(UUID_D, new ChunkPos(2, 2), tag("d"));

        List<String> markers = new ArrayList<>();
        for (ChunkPos pos : buffer.bufferedChunks()) {
            List<CompoundTag> drained = buffer.drainChunk(pos);
            assertFalse(drained.isEmpty(), "a reported chunk holds at least one entity: " + pos);
            markers.addAll(markersOf(drained));
        }

        assertEquals(ImmutableSet.of("a-moved", "b-moved", "c", "d"), new HashSet<>(markers));
        assertEquals(4, markers.size(), "no entity drains twice");
        assertTrue(buffer.isEmpty(), "every buffered entity was reachable through the reported chunks");
    }

    @Test
    void drainingAnEmptyChunkYieldsNothing() {
        EntityBuffer buffer = new EntityBuffer();
        assertTrue(buffer.drainChunk(new ChunkPos(9, 9)).isEmpty());
    }

    @Test
    void drainingAnAlreadyDrainedChunkYieldsNothing() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("a"));
        buffer.drainChunk(new ChunkPos(0, 0));

        assertTrue(buffer.drainChunk(new ChunkPos(0, 0)).isEmpty());
        assertEquals(ImmutableSet.of(), buffer.bufferedChunks());
    }

    @Test
    void drainYieldsMutableTagsTheCallerCanFilter() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.accumulate(UUID_A, new ChunkPos(0, 0), tag("a"));
        buffer.accumulate(UUID_B, new ChunkPos(0, 0), tag("b"));

        List<CompoundTag> drained = buffer.drainChunk(new ChunkPos(0, 0));
        drained.removeIf(tag -> "a".equals(tag.getString("id"))); // the caller filters the drain in place

        assertEquals(ImmutableList.of("b"), markersOf(drained));
    }
}
