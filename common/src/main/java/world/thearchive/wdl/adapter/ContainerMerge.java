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

/**
 * Merges captured open-time block-entity data into the matching captured block-entity tag, keyed by {@link BlockPos},
 * with per-entry failure isolation that mirrors {@link RegionChunkWriter}: a merge that throws is logged and skipped so
 * one bad entry can never abort the whole save. Several axes share the {@code x/y/z} locator: {@link #mergeChunkStash}
 * writes container {@code "Items"} plus the whitelisted open-time state keys, {@link #mergeLecternChunkStash} writes
 * lectern {@code "Book"}/{@code "Page"}, and {@link #mergeHolderChunkStash} copies an interaction-predicted holder's
 * own keys onto its block entity. The streaming capture calls each per chunk, just before that chunk is flushed to
 * disk.
 *
 * <p>Portable across the <em>post-1.18</em> chunk format: the flat {@code "block_entities"} list and a block entity's
 * {@code x/y/z} metadata are vanilla-stable there. A pre-1.18 band would swap the key (block entities live under
 * {@code "TileEntities"} inside a {@code "Level"} wrapper). The block-entity tag is replaced in place inside the
 * captured chunk tag, so the merged contents are written by the regular chunk write that follows.
 */
final class ContainerMerge {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ContainerMerge() {}

    /**
     * Merge (and drain) just the stash entries located in {@code pos}'s chunk into {@code chunkTag}, in place. Returns
     * how many entries actually merged onto a captured block entity; an entry with no matching captured block entity is
     * still drained but not counted (its items are lost, the acceptable revisit edge: a container only opened after its
     * chunk was flushed).
     */
    static MergeTally mergeChunkStash(ContainerSink sink, CompoundTag chunkTag, ChunkPos pos,
            Map<BlockPos, CompoundTag> stash) {
        return mergeStashWith(
                (blockEntityTag, holder) -> mergeStateKeys(sink.merge(blockEntityTag, holder), holder),
                chunkTag, pos, stash);
    }

    /**
     * Copy the open-time state keys present on {@code holder} onto {@code merged} (band-stable NBT ops): the crafter's
     * {@code disabled_slots}/{@code triggered} and the brewing stand's {@code BrewTime}/{@code Fuel}, read off
     * {@link CapturedBlockField} so the same list drives the chunk carry-forward that has to preserve them across a
     * re-write. Still a whitelist, and still fails closed, so an internal holder key ({@code wdl_block_entity_id}) can
     * never leak to disk.
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

    /**
     * The lectern sibling of {@link #mergeChunkStash}: merge (and drain) just the lectern-book stash entries located in
     * {@code pos}'s chunk into {@code chunkTag}, setting {@code "Book"}/{@code "Page"} on the matching lectern block
     * entity. Same {@code x/y/z} locator, per-entry isolation, and drain semantics; a lectern BE and any container BE
     * are distinct entities, so the two merges never interfere.
     */
    static MergeTally mergeLecternChunkStash(LecternSink sink, CompoundTag chunkTag, ChunkPos pos,
            Map<BlockPos, CompoundTag> stash) {
        return mergeStashWith(sink::merge, chunkTag, pos, stash);
    }

    /**
     * The interaction-predicted sibling of {@link #mergeChunkStash}: copy each stash holder's own keys onto the
     * matching block entity in {@code pos}'s chunk. Band-stable: a plain copy of the keys the holder already carries
     * with no per-band sink. One method serves every content type.
     */
    static MergeTally mergeHolderChunkStash(CompoundTag chunkTag, ChunkPos pos, Map<BlockPos, CompoundTag> stash) {
        return mergeStashWith(ContainerMerge::mergeHolderFields, chunkTag, pos, stash);
    }

    /**
     * Apply the open-time-over-place precedence. An opened menu is ground truth and supersedes a place-time snapshot
     * for the same pos, which can have gone stale after the placement was opened and edited. The surviving entries join
     * the open-time bundle for the one per-chunk {@code "Items"} merge. Membership is tested against the
     * already-drained bundle, since the shared container stash is emptied into it before this runs.
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

    /**
     * Copy every key {@code holder} carries onto a copy of {@code blockEntityTag}, leaving its other fields intact (the
     * {@link ContainerSink#merge} no-clobber discipline). The interaction holder writes need no per-band sink.
     */
    private static CompoundTag mergeHolderFields(CompoundTag blockEntityTag, CompoundTag holder) {
        CompoundTag merged = blockEntityTag.copy();
        for (String key : holder.getAllKeys()) {
            Tag value = holder.get(key);
            if (value != null) {
                merged.put(key, value);
            }
        }
        return merged;
    }

    /**
     * The shared locator: walk {@code stash}, and for every entry in {@code pos}'s chunk, drain it and apply
     * {@code merge} to the matching captured block entity. Independent of <em>what</em> the merge writes.
     */
    private static MergeTally mergeStashWith(BiFunction<CompoundTag, CompoundTag, CompoundTag> merge,
            CompoundTag chunkTag, ChunkPos pos, Map<BlockPos, CompoundTag> stash) {
        int merged = 0;
        int failed = 0;
        Iterator<Map.Entry<BlockPos, CompoundTag>> entries = stash.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<BlockPos, CompoundTag> entry = entries.next();
            if (!new ChunkPos(entry.getKey()).equals(pos)) {
                continue; // a block entity in some other chunk; leave it for that chunk's flush
            }
            entries.remove();
            try {
                if (mergeOne(merge, chunkTag, entry.getKey(), entry.getValue())) {
                    merged++;
                }
            } catch (RuntimeException e) {
                failed++; // a thrown merge loses this block's captured contents; a no-match above is not a loss
                LOGGER.warn("skipping block entity {}: merge failed", entry.getKey(), e);
            }
        }
        return new MergeTally(merged, failed);
    }

    private static boolean mergeOne(BiFunction<CompoundTag, CompoundTag, CompoundTag> merge,
            CompoundTag chunkTag, BlockPos pos, CompoundTag holder) {
        // Use only NBT ops stable across the post-1.18 chunk format (get(String)/get(int)/instanceof/set plus
        // an IntTag value compare). The getXxxOr accessors are 1.21.5+ only; the ListTag/CompoundTag/IntTag
        // basics are older still. (The "block_entities" key itself is post-1.18: see the class Javadoc.)
        if (!(chunkTag.get("block_entities") instanceof ListTag blockEntities)) {
            return false; // no block entities captured for this chunk
        }
        for (int i = 0; i < blockEntities.size(); i++) {
            if (blockEntities.get(i) instanceof CompoundTag blockEntityTag
                    && NbtMerge.isBlockEntityAt(blockEntityTag, pos)) {
                if (!recordedTypeMatches(holder, blockEntityTag)) {
                    return false; // the block here changed identity since capture; drop the stale overlay (Gate 1)
                }
                blockEntities.set(i, merge.apply(blockEntityTag, holder));
                return true;
            }
        }
        return false; // no captured block entity at this pos
    }

    /**
     * Whether {@code holder}'s recorded block-entity type ({@code wdl_block_entity_id}, stamped at open) matches
     * {@code blockEntityTag}'s saved {@code "id"} (Gate 1). A holder carrying no {@code wdl_block_entity_id} makes no
     * type claim and always matches. The registry-key strings compare like for like, so a mismatch means the position
     * was replaced by a different block entity after capture and the stale items must not be written.
     */
    static boolean recordedTypeMatches(CompoundTag holder, CompoundTag blockEntityTag) {
        Tag recorded = holder.get("wdl_block_entity_id");
        return recorded == null || recorded.equals(blockEntityTag.get("id"));
    }
}
