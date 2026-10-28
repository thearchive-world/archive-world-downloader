// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

final class ContainerMerge {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ContainerMerge() {}

    static MergeTally mergeChunkStash(ContainerSink sink, CompoundTag chunkTag, ChunkPos pos,
            Map<BlockPos, CompoundTag> stash) {
        return mergeStashWith(
                (blockEntityTag, holder) -> mergeStateKeys(sink.merge(blockEntityTag, holder), holder),
                chunkTag, pos, stash);
    }

    /**
     * Still a whitelist, and still fails closed, so an internal holder key ({@code wdl_block_entity_id}) can never leak
     * to disk.
     */
    private static CompoundTag mergeStateKeys(CompoundTag merged, CompoundTag holder) {
        for (CapturedBlockField field : CapturedBlockField.fields()) {
            if (!field.openTimeState()) {
                continue;
            }
            Tag value = holder.get(field.key());
            if (value != null) {
                merged.put(field.key(), value);
            }
        }
        return merged;
    }

    static MergeTally mergeLecternChunkStash(LecternSink sink, CompoundTag chunkTag, ChunkPos pos,
            Map<BlockPos, CompoundTag> stash) {
        return mergeStashWith(sink::merge, chunkTag, pos, stash);
    }

    static MergeTally mergeHolderChunkStash(CompoundTag chunkTag, ChunkPos pos, Map<BlockPos, CompoundTag> stash) {
        return mergeStashWith(ContainerMerge::mergeHolderFields, chunkTag, pos, stash);
    }

    /**
     * An opened menu is ground truth and supersedes a place-time snapshot for the same pos, which can have gone stale
     * after the placement was opened and edited.
     */
    static Map<BlockPos, CompoundTag> mergePlaceCandidates(Map<BlockPos, CompoundTag> openTimeBundle,
            Map<BlockPos, CompoundTag> confirmedPlace) {
        Map<BlockPos, CompoundTag> surviving = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, CompoundTag> entry : confirmedPlace.entrySet()) {
            if (!openTimeBundle.containsKey(entry.getKey())) {
                surviving.put(entry.getKey(), entry.getValue());
            }
        }
        return surviving;
    }

    private static CompoundTag mergeHolderFields(CompoundTag blockEntityTag, CompoundTag holder) {
        CompoundTag merged = blockEntityTag.copy();
        for (String key : holder.keySet()) {
            Tag value = holder.get(key);
            if (value != null) {
                merged.put(key, value);
            }
        }
        return merged;
    }

    private static MergeTally mergeStashWith(BiFunction<CompoundTag, CompoundTag, CompoundTag> merge,
            CompoundTag chunkTag, ChunkPos pos, Map<BlockPos, CompoundTag> stash) {
        int merged = 0;
        int failed = 0;
        Iterator<Map.Entry<BlockPos, CompoundTag>> entries = stash.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<BlockPos, CompoundTag> entry = entries.next();
            if (!new ChunkPos(entry.getKey()).equals(pos)) {
                continue;
            }
            entries.remove();
            try {
                if (mergeOne(merge, chunkTag, entry.getKey(), entry.getValue())) {
                    merged++;
                }
            } catch (RuntimeException e) {
                failed++;
                LOGGER.warn("skipping block entity {}: merge failed", entry.getKey(), e);
            }
        }
        return new MergeTally(merged, failed);
    }

    private static boolean mergeOne(BiFunction<CompoundTag, CompoundTag, CompoundTag> merge,
            CompoundTag chunkTag, BlockPos pos, CompoundTag holder) {
        if (!(chunkTag.get("block_entities") instanceof ListTag blockEntities)) {
            return false;
        }
        for (int i = 0; i < blockEntities.size(); i++) {
            if (blockEntities.get(i) instanceof CompoundTag blockEntityTag
                    && NbtMerge.isBlockEntityAt(blockEntityTag, pos)) {
                if (!recordedTypeMatches(holder, blockEntityTag)) {
                    return false;
                }
                blockEntities.set(i, merge.apply(blockEntityTag, holder));
                return true;
            }
        }
        return false;
    }

    /**
     * The registry-key strings compare like for like ({@code "id"} is written with
     * {@code BLOCK_ENTITY_TYPE.byNameCodec()}, the same key a live {@code getType()} records), so a mismatch means the
     * position was replaced by a different block entity after capture and the stale items must not be written.
     */
    static boolean recordedTypeMatches(CompoundTag holder, CompoundTag blockEntityTag) {
        Tag recorded = holder.get("wdl_block_entity_id");
        return recorded == null || recorded.equals(blockEntityTag.get("id"));
    }
}
