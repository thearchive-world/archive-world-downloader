// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import org.jspecify.annotations.Nullable;

/**
 * The read-merge for a chunk's captured entities, UUID-keyed (the pre-1.16 {@code UUIDMost}/{@code UUIDLeast} long
 * pair). At 1.15.2 the entities live inside the {@code region/} chunk under {@code Level.Entities} (there is no
 * separate {@code entities/} region until 1.17), and the writer folds the fresh capture in through this merge. Two
 * carry-forwards, both so a re-write of an entity-chunk adds to, never overwrites, what is already on disk:
 *
 * <ul>
 * <li>Container contents: a container vehicle's or chested animal's on-disk {@code "Items"}, and a villager's trade
 * {@code "Offers"} and experience {@code "Xp"}, are carried into the matching fresh entity, at any depth of the
 * {@code "Passengers"} tree ({@link EntityTreeWalk}), preferring the non-empty side, so a parked storage minecart, a
 * chested animal pushed into one, or a villager captured through its trade menu, opened in a prior flush, is not wiped
 * by a re-capture that never re-opened it.</li>
 * <li>Absent entities (union): every on-disk entity the fresh capture does not contain is carried forward, so a partial
 * re-flush of a chunk (the entity packet path drains and flushes a chunk repeatedly as frames stream in over time or on
 * a revisit) does not drop the entities a prior flush already saved. UUID-deduped, so an entity present on both sides
 * stays single. This is accepted ghosting (over-capture &gt; under-capture); it also means a resume carries forward an
 * entity that has since despawned.</li>
 * </ul>
 *
 * <p>Pure {@code CompoundTag} in/out, operating on a {@code {Entities:[...]}} envelope regardless of where the chunk
 * stores it (the {@code region/} {@code Level.Entities} at this band, a separate {@code entities/} region above it).
 * The UUID-keyed sibling of {@link ChunkMerge}. The saddle needs no carry-forward key on either side of the 1.21.5
 * equipment-slot cut: above it the saddle rides in the synced equipment every capture re-reads, and below it capture
 * re-derives {@code "SaddleItem"} from the synced saddled flag on every pass, so only {@code "Items"} carries forward.
 */
final class EntityMerge {
    private EntityMerge() {}

    /**
     * Merge the on-disk entity-chunk into {@code fresh}, in place: carry forward each matching entity's container
     * {@code "Items"}, then union in every on-disk entity the fresh capture lacks. Returns how many entities received a
     * carry-forward (a content carry or a union add). A chunk with no {@code "Entities"} list on either side is a
     * no-op.
     */
    static int merge(CompoundTag onDisk, CompoundTag fresh) {
        if (!(fresh.get("Entities") instanceof ListTag) || !(onDisk.get("Entities") instanceof ListTag)) {
            return 0;
        }
        ListTag freshEntities = (ListTag) fresh.get("Entities");
        ListTag diskEntities = (ListTag) onDisk.get("Entities");
        Map<UUID, CompoundTag> diskNodes = new LinkedHashMap<>();
        for (int i = 0; i < diskEntities.size(); i++) {
            if (diskEntities.get(i) instanceof CompoundTag) {
                CompoundTag diskEntity = (CompoundTag) diskEntities.get(i);
                diskNodes.putAll(EntityTreeWalk.byUuid(diskEntity));
            }
        }
        Set<UUID> freshUuids = new HashSet<>();
        int merged = 0;
        for (int i = 0; i < freshEntities.size(); i++) {
            if (!(freshEntities.get(i) instanceof CompoundTag)) {
                continue;
            }
            CompoundTag freshEntity = (CompoundTag) freshEntities.get(i);
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(freshEntity).entrySet()) {
                freshUuids.add(node.getKey());
                CompoundTag diskNode = diskNodes.get(node.getKey());
                if (diskNode != null) {
                    // The Offers and Xp carries run for every node but are inert for non-villagers under client
                    // capture: only AbstractVillager writes Offers, and only server-side, so a client-reconstructed
                    // disk node never holds it; and villagerXp is never synced, so a client entity always serializes
                    // Xp at the client zero. The carry therefore fires only for the villager the merchant fold
                    // injected, matched by UUID. A band that synced villagerXp, or a modded entity carrying these
                    // keys, would change that.
                    boolean carried = NbtMerge.carryList(diskNode, node.getValue(), "Items");
                    carried |= NbtMerge.carryCompound(diskNode, node.getValue(), "Offers", null);
                    carried |= NbtMerge.carryValue(diskNode, node.getValue(), "Xp", new IntTag(0));
                    if (carried) {
                        merged++;
                    }
                }
            }
        }
        // Union: an on-disk entity the fresh capture does not have is carried forward, so a partial re-flush
        // adds to rather than overwrites the prior on-disk set (the entity-loss fix). A null-UUID on-disk entity
        // (corrupt or foreign-written; we always write a valid one) cannot be deduped, so it is carried forward
        // unconditionally rather than dropped: the contract is over-capture over under-capture, and dropping it
        // would silently lose existing world data. A keyed one is carried only when the fresh set lacks it, matched
        // against the whole fresh tree so an entity that moved into or out of a vehicle is deduped, not duplicated.
        for (int i = 0; i < diskEntities.size(); i++) {
            if (!(diskEntities.get(i) instanceof CompoundTag)) {
                continue;
            }
            CompoundTag diskEntity = (CompoundTag) diskEntities.get(i);
            UUID uuid = readUuid(diskEntity);
            if (uuid == null || !freshUuids.contains(uuid)) {
                freshEntities.add(diskEntity.copy());
                merged++;
            }
        }
        return merged;
    }

    /**
     * Whether {@code entity}'s on-disk tag holds interaction-captured content: a non-empty {@code "Items"} list, or a
     * villager's non-empty trade {@code "Offers"}. The single definition of prior-captured entity content, shared with
     * {@link RecoveredScan} so the outline's recovered-entity set tracks exactly what the carry-forward preserves, the
     * UUID-keyed sibling of {@link ChunkMerge#hasCapturedContent}. The pre-1.21.5 saddle is deliberately not one of
     * them: capture re-derives it from the synced saddled flag on every pass, so it needs neither this nor the
     * carry-forward.
     */
    static boolean hasCapturedContent(CompoundTag entity) {
        return NbtMerge.isNonEmptyList(entity, "Items") || hasCapturedOffers(entity);
    }

    private static boolean hasCapturedOffers(CompoundTag entity) {
        return entity.get("Offers") instanceof CompoundTag
                && NbtMerge.isNonEmptyList((CompoundTag) entity.get("Offers"), "Recipes");
    }

    /**
     * Decode an entity tag's {@code "UUID"} (the pre-1.16 {@code UUIDMost}/{@code UUIDLeast} long pair), or null when
     * absent.
     */
    static @Nullable UUID readUuid(CompoundTag entityTag) {
        return entityTag.hasUUID("UUID") ? entityTag.getUUID("UUID") : null;
    }
}
