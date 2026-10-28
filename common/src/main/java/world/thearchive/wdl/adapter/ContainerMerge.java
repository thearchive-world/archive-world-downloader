// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class ContainerMerge {
    private static final Logger LOGGER = LogManager.getLogger(ContainerMerge.class);

    private ContainerMerge() {}

    static MergeTally mergeChunkStash(ContainerSink sink, NBTTagCompound chunkTag, ChunkPos pos,
            Map<BlockPos, NBTTagCompound> stash) {
        return mergeStashWith(
                (blockEntityTag, holder) -> mergeStateKeys(sink.merge(blockEntityTag, holder), holder),
                chunkTag, pos, stash);
    }

    /**
     * Still a whitelist, and still fails closed, so an internal holder key ({@code wdl_block_entity_id}) can never leak
     * to disk.
     */
    private static NBTTagCompound mergeStateKeys(NBTTagCompound merged, NBTTagCompound holder) {
        for (CapturedBlockField field : CapturedBlockField.fields()) {
            if (!field.openTimeState()) {
                continue;
            }
            NBTBase value = holder.getTag(field.key());
            if (value != null) {
                merged.setTag(field.key(), value);
            }
        }
        return merged;
    }

    static MergeTally mergeLecternChunkStash(LecternSink sink, NBTTagCompound chunkTag, ChunkPos pos,
            Map<BlockPos, NBTTagCompound> stash) {
        return mergeStashWith(sink::merge, chunkTag, pos, stash);
    }

    static MergeTally mergeHolderChunkStash(NBTTagCompound chunkTag, ChunkPos pos,
            Map<BlockPos, NBTTagCompound> stash) {
        return mergeStashWith(ContainerMerge::mergeHolderFields, chunkTag, pos, stash);
    }

    /**
     * An opened menu is ground truth and supersedes a place-time snapshot for the same pos, which can have gone stale
     * after the placement was opened and edited.
     */
    static Map<BlockPos, NBTTagCompound> mergePlaceCandidates(Map<BlockPos, NBTTagCompound> openTimeBundle,
            Map<BlockPos, NBTTagCompound> confirmedPlace) {
        Map<BlockPos, NBTTagCompound> surviving = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, NBTTagCompound> entry : confirmedPlace.entrySet()) {
            if (!openTimeBundle.containsKey(entry.getKey())) {
                surviving.put(entry.getKey(), entry.getValue());
            }
        }
        return surviving;
    }

    private static NBTTagCompound mergeHolderFields(NBTTagCompound blockEntityTag, NBTTagCompound holder) {
        NBTTagCompound merged = blockEntityTag.copy();
        for (String key : holder.getKeySet()) {
            NBTBase value = holder.getTag(key);
            if (value != null) {
                merged.setTag(key, value);
            }
        }
        return merged;
    }

    private static MergeTally mergeStashWith(BiFunction<NBTTagCompound, NBTTagCompound, NBTTagCompound> merge,
            NBTTagCompound chunkTag, ChunkPos pos, Map<BlockPos, NBTTagCompound> stash) {
        int merged = 0;
        int failed = 0;
        Iterator<Map.Entry<BlockPos, NBTTagCompound>> entries = stash.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<BlockPos, NBTTagCompound> entry = entries.next();
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

    private static boolean mergeOne(BiFunction<NBTTagCompound, NBTTagCompound, NBTTagCompound> merge,
            NBTTagCompound chunkTag, BlockPos pos, NBTTagCompound holder) {
        if (!(chunkTag.getCompoundTag("Level").getTag("TileEntities") instanceof NBTTagList)) {
            return false;
        }
        NBTTagList blockEntities = (NBTTagList) chunkTag.getCompoundTag("Level").getTag("TileEntities");
        for (int i = 0; i < blockEntities.tagCount(); i++) {
            NBTTagCompound blockEntityTag = blockEntities.get(i) instanceof NBTTagCompound
                    ? (NBTTagCompound) blockEntities.get(i)
                    : null;
            if (blockEntityTag != null && NbtMerge.isBlockEntityAt(blockEntityTag, pos)) {
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
     * The registry-key strings compare like for like, so a mismatch means the position was replaced by a different
     * block entity after capture and the stale items must not be written.
     */
    static boolean recordedTypeMatches(NBTTagCompound holder, NBTTagCompound blockEntityTag) {
        NBTBase recorded = holder.getTag("wdl_block_entity_id");
        return recorded == null || recorded.equals(blockEntityTag.getTag("id"));
    }
}
