// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Merges captured open-time container-vehicle items into the matching captured entity tag, keyed by entity
 * {@link UUID}, with per-entry failure isolation that mirrors {@link ContainerMerge}: a merge that throws is logged and
 * skipped so one bad entity can never abort the whole save. It is the {@code entities/}-region sibling of
 * {@link ContainerMerge}: that class locates a block entity by its {@code x/y/z} in the {@code Level.TileEntities}
 * list, this one locates a container holder by its {@code "UUID"} across the {@code "Entities"} tree, each record and
 * every node beneath its {@code "Passengers"} ({@link EntityTreeWalk}). The {@code "Items"} write itself is reused
 * verbatim from {@link ContainerSink#merge} (a chest minecart / boat persists its contents under {@code "Items"} via
 * the exact call the sink makes), so the two classes share the serialize and differ only in the locator.
 *
 * <p>The same UUID locator also folds a villager's captured trades ({@link #mergeMerchantStash} and its refold): those
 * set {@code "Offers"} and {@code "Xp"} from a holder the tick already serialized, a plain tag copy with no sink and no
 * per-entry failure isolation, so unlike the item fold they cannot throw and always report zero failed.
 *
 * <p>Portable across the entities-region format: the {@code "Entities"} list, the recursive {@code "Passengers"} list
 * and a per-entity {@code "UUID"} 4-int array ({@code UUIDUtil.CODEC}) are vanilla-stable post-1.16, so only the
 * per-band {@code "Items"} serialization (behind {@link ContainerSink#merge}) differs. The matched node's
 * {@code "Items"} is set in place inside the captured chunk tag, so the merged contents are written by the regular
 * entity write that follows.
 */
final class EntityContainerMerge {
    private static final Logger LOGGER = LogManager.getLogger(EntityContainerMerge.class);

    private EntityContainerMerge() {}

    /**
     * Re-fold contents already folded into an earlier write, without draining: for each entity in this chunk whose UUID
     * is in {@code folded}, set {@code "Items"} from the holder that was folded before. A container vehicle's contents
     * reach the client only through its open menu, so every serialize after the first fold is empty; a vehicle that has
     * since moved to another entity-chunk, or crossed into another dimension, would otherwise be written a second time
     * holding nothing. Re-folding makes every copy carry the contents, so a reader that reaches any one of them finds
     * the loot.
     *
     * <p>Known residual, stated because it is easy to get wrong: this does not leave one copy. Vanilla's same-UUID
     * rejection is scoped to entities resident at the same moment, since the UUID leaves its known set again on chunk
     * unload, so two copies in chunks that are never loaded together both materialize and the archive holds the
     * vehicle, and its items, more than once. That is the over-capture direction this project takes over a silent loss,
     * but it is a residual rather than a clean answer, and the clean answer needs either a retraction of the earlier
     * copy or a per-vehicle pinned chunk key.
     *
     * <p>Deliberately not draining: the holder must stay available for any later chunk this vehicle reaches. A vehicle
     * whose menu was reopened is skipped entirely, since {@link #mergeEntityStash} is about to fold its fresher capture
     * and folding the stale one first would only double-count a failure. Failures are tallied, not thrown, on the same
     * per-entity isolation as the first fold.
     */
    static MergeTally refoldFlushedContainers(ContainerSink sink, CompoundTag entitiesChunkTag,
            Map<UUID, CompoundTag> folded, Map<UUID, CompoundTag> stash) {
        ListTag entities = entitiesChunkTag.get("Entities") instanceof ListTag
                ? (ListTag) entitiesChunkTag.get("Entities")
                : null;
        if (folded.isEmpty() || entities == null) {
            return new MergeTally(0, 0);
        }
        int merged = 0;
        int failed = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag)) {
                continue;
            }
            CompoundTag entityTag = (CompoundTag) entities.get(i);
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(entityTag).entrySet()) {
                UUID uuid = node.getKey();
                CompoundTag holder = stash.containsKey(uuid) ? null : folded.get(uuid);
                if (holder == null) {
                    continue; // no earlier fold to repeat, or a reopened menu whose own fold runs next and wins
                }
                try {
                    // A copy, because the sink aliases the list it is handed: the retained holder is folded into
                    // several envelopes over a session and one of them may already be across the thread boundary.
                    foldItems(sink, node.getValue(), holder.copy());
                    merged++;
                } catch (RuntimeException e) {
                    failed++; // this copy saves without its contents beside one that has them
                    LOGGER.warn("skipping entity {}: container re-fold failed", uuid, e);
                }
            }
        }
        return new MergeTally(merged, failed);
    }

    /**
     * Fold (and drain) the UUID-keyed {@code stash} into {@code entitiesChunkTag}: walk each top-level
     * {@code "Entities"} record and every node beneath its {@code "Passengers"}, and for each node whose {@code "UUID"}
     * is stashed, set its {@code "Items"} from {@code sink.merge(node, holder)} in place and remove the drained entry.
     * A throwing merge is logged and skipped (one bad entity never aborts the save). Unmatched stash entries are left
     * for another chunk; whatever remains after all chunks is the accepted revisit edge (an entity that unloaded,
     * despawned, or whose terrain was not captured). Returns how many nodes actually gained their captured items.
     */
    static MergeTally mergeEntityStash(ContainerSink sink, CompoundTag entitiesChunkTag,
            Map<UUID, CompoundTag> stash) {
        ListTag entities = entitiesChunkTag.get("Entities") instanceof ListTag
                ? (ListTag) entitiesChunkTag.get("Entities")
                : null;
        if (stash.isEmpty() || entities == null) {
            return new MergeTally(0, 0);
        }
        int merged = 0;
        int failed = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag)) {
                continue;
            }
            CompoundTag entityTag = (CompoundTag) entities.get(i);
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(entityTag).entrySet()) {
                CompoundTag holder = stash.remove(node.getKey());
                if (holder == null) {
                    continue;
                }
                try {
                    foldItems(sink, node.getValue(), holder);
                    merged++;
                } catch (RuntimeException e) {
                    failed++; // a thrown merge loses this vehicle's captured contents
                    LOGGER.warn("skipping entity {}: container merge failed", node.getKey(), e);
                }
            }
        }
        return new MergeTally(merged, failed);
    }

    private static void foldItems(ContainerSink sink, CompoundTag entityTag, CompoundTag holder) {
        entityTag.put("Items", sink.merge(entityTag, holder).getList("Items", 10));
    }

    /**
     * Fold (and drain) the UUID-keyed merchant {@code stash} into {@code entitiesChunkTag}: for each node whose
     * {@code "UUID"} is stashed, set its {@code "Offers"} and (if the holder carries one) {@code "Xp"} from the holder,
     * then remove the drained entry. The merchant sibling of {@link #mergeEntityStash}: it walks the same
     * {@code "Passengers"} tree by UUID, but sets the trade payload rather than {@code "Items"}. It carries no per-node
     * failure isolation and always reports zero failed, because the fold is a plain tag copy and not a codec run: the
     * offers were serialized when the tick stashed them and scrubbed once at flush, so by here nothing can throw and a
     * malformed holder merely skips a key. Returns how many nodes gained their trades.
     */
    static MergeTally mergeMerchantStash(CompoundTag entitiesChunkTag, Map<UUID, CompoundTag> stash) {
        ListTag entities = entitiesChunkTag.get("Entities") instanceof ListTag
                ? (ListTag) entitiesChunkTag.get("Entities")
                : null;
        if (stash.isEmpty() || entities == null) {
            return new MergeTally(0, 0);
        }
        int merged = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag)) {
                continue;
            }
            CompoundTag entityTag = (CompoundTag) entities.get(i);
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(entityTag).entrySet()) {
                CompoundTag holder = stash.remove(node.getKey());
                if (holder == null) {
                    continue;
                }
                foldOffers(node.getValue(), holder);
                merged++;
            }
        }
        return new MergeTally(merged, 0);
    }

    /**
     * Re-fold trades already folded into an earlier write, without draining: for each node in this chunk whose UUID is
     * in {@code folded} and not awaiting a fresher capture in {@code stash}, re-apply the folded holder. The merchant
     * sibling of {@link #refoldFlushedContainers}, so a wandering trader that crossed entity-chunks carries its trades
     * to each copy. A node whose menu was reopened is skipped, since {@link #mergeMerchantStash} is about to fold its
     * fresher capture. Like the merge above it cannot fail and reports zero failed.
     */
    static MergeTally refoldFlushedMerchants(CompoundTag entitiesChunkTag, Map<UUID, CompoundTag> folded,
            Map<UUID, CompoundTag> stash) {
        ListTag entities = entitiesChunkTag.get("Entities") instanceof ListTag
                ? (ListTag) entitiesChunkTag.get("Entities")
                : null;
        if (folded.isEmpty() || entities == null) {
            return new MergeTally(0, 0);
        }
        int merged = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag)) {
                continue;
            }
            CompoundTag entityTag = (CompoundTag) entities.get(i);
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(entityTag).entrySet()) {
                UUID uuid = node.getKey();
                CompoundTag holder = stash.containsKey(uuid) ? null : folded.get(uuid);
                if (holder == null) {
                    continue; // no earlier fold to repeat, or a reopened menu whose own fold runs next and wins
                }
                foldOffers(node.getValue(), holder);
                merged++;
            }
        }
        return new MergeTally(merged, 0);
    }

    /**
     * Copy the holder's {@code "Offers"} onto the node, and its {@code "Xp"} when present (a wandering trader has
     * none).
     */
    private static void foldOffers(CompoundTag entityTag, CompoundTag holder) {
        if (holder.get("Offers") instanceof CompoundTag) {
            CompoundTag offers = (CompoundTag) holder.get("Offers");
            entityTag.put("Offers", offers.copy());
        }
        Tag experience = holder.get("Xp");
        if (experience != null) {
            entityTag.put("Xp", experience.copy());
        }
    }
}
