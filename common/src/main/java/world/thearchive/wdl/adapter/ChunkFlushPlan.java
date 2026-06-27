// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * What a flushed chunk hands its writer thunks: the values the main thread reads off its own state and detaches for the
 * writer.
 *
 * <p>Every member is pure over its arguments, with no session, client or writer state, which is what lets a call site
 * be driven directly rather than only through a live capture session. That matters more here than the arithmetic each
 * member does: a member with good tests proves nothing about whether a call site still passes it the arguments that
 * make it chunk-specific, and dropping one is neither a deletion nor a compile error.
 */
final class ChunkFlushPlan {
    private ChunkFlushPlan() {}

    /**
     * The positions in {@code pos}'s chunk that a block placement landed in this session. The load-bearing property is
     * that the result is a DETACHED copy: the per-dimension set it reads is live main-thread state that the flush goes
     * on to drain, and what is returned crosses to the writer thread. Narrowing to the chunk is what makes that copy
     * cheap rather than what makes it correct, since a position in another chunk packs to a long no block entity in
     * this one can carry.
     */
    static LongSet replacedIn(ChunkPos pos, LongSet replacedBlockKeys) {
        LongOpenHashSet inChunk = new LongOpenHashSet();
        for (long posKey : replacedBlockKeys) {
            if (new ChunkPos(BlockPos.of(posKey)).equals(pos)) {
                inChunk.add(posKey);
            }
        }
        return inChunk;
    }
}
