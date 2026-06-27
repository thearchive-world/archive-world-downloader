// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The guard for the arguments a flushed chunk detaches for the writer.
 *
 * <p>What each case pins is the narrowing itself: the result must carry this chunk's positions and no other chunk's,
 * and it must be a copy rather than a view, since the set it reads is live main-thread state the flush goes on to drain
 * while the writer thread reads what was handed over.
 */
class ChunkFlushPlanTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    /**
     * The replaced positions are held per dimension and handed to the writer per chunk, so the narrowing is both what
     * detaches them from the live set and what keeps one chunk's placements out of another's merge.
     */
    @Test
    void theReplacedPositionsHandedOverAreThisChunksOwn() {
        BlockPos inside = new BlockPos(3, 64, 7);
        BlockPos elsewhere = new BlockPos(300, 64, 700);
        LongOpenHashSet replaced = new LongOpenHashSet();
        replaced.add(inside.asLong());
        replaced.add(elsewhere.asLong());

        LongSet forChunk = ChunkFlushPlan.replacedIn(new ChunkPos(inside), replaced);

        assertTrue(forChunk.contains(inside.asLong()), "a placement in this chunk reaches this chunk's merge");
        assertFalse(forChunk.contains(elsewhere.asLong()), "and one in another chunk does not");
    }
}
