// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import org.jspecify.annotations.Nullable;

/**
 * The read-merge for the {@code entities/} target, UUID-keyed ({@code UUIDUtil.CODEC}, band-stable post-1.16). Two
 * carry-forwards, both so a re-write of an entity-chunk adds to, never overwrites, what is already on disk:
 *
 * <ul>
 * <li>Container contents: a container vehicle's or chested animal's on-disk {@code "Items"} is carried into the
 * matching fresh entity, at any depth of the {@code "Passengers"} tree ({@link EntityTreeWalk}), preferring the
 * non-empty side, so a parked storage minecart or a chested animal pushed into one, opened in a prior flush, is not
 * wiped by a re-capture that never re-opened it.</li>
 * <li>Absent entities (union): every on-disk entity the fresh capture does not contain is carried forward, so a partial
 * re-flush of a chunk (the entity packet path drains and flushes a chunk repeatedly as frames stream in over time or on
 * a revisit) does not drop the entities a prior flush already saved. UUID-deduped, so an entity present on both sides
 * stays single. This is accepted ghosting (over-capture &gt; under-capture); it also means a resume carries forward an
 * entity that has since despawned.</li>
 * </ul>
 *
 * <p>Pure {@code CompoundTag} in/out and band-agnostic over the post-1.17 {@code entities/} layout. The UUID-keyed
 * sibling of {@link ChunkMerge}. The 1.21.4-only saddle ({@code "SaddleItem"}, an inventory slot below the 1.21.5
 * equipment-slot cut) is an additional content carry-forward key the 1.21.4 port adds here; on this band the saddle is
 * a synced equipment slot and needs no guard, so only {@code "Items"} carries forward.
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
        if (!(fresh.get("Entities") instanceof ListTag freshEntities)
                || !(onDisk.get("Entities") instanceof ListTag diskEntities)) {
            return 0;
        }
        Map<UUID, CompoundTag> diskNodes = new LinkedHashMap<>();
        for (int i = 0; i < diskEntities.size(); i++) {
            if (diskEntities.get(i) instanceof CompoundTag diskEntity) {
                diskNodes.putAll(EntityTreeWalk.byUuid(diskEntity));
            }
        }
        Set<UUID> freshUuids = new HashSet<>();
        int merged = 0;
        for (int i = 0; i < freshEntities.size(); i++) {
            if (!(freshEntities.get(i) instanceof CompoundTag freshEntity)) {
                continue;
            }
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(freshEntity).entrySet()) {
                freshUuids.add(node.getKey());
                CompoundTag diskNode = diskNodes.get(node.getKey());
                if (diskNode != null && NbtMerge.carryList(diskNode, node.getValue(), "Items")) {
                    merged++;
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
            if (!(diskEntities.get(i) instanceof CompoundTag diskEntity)) {
                continue;
            }
            UUID uuid = readUuid(diskEntity);
            if (uuid == null || !freshUuids.contains(uuid)) {
                freshEntities.add(diskEntity.copy());
                merged++;
            }
        }
        return merged;
    }

    /**
     * Whether {@code entity}'s on-disk tag holds interaction-captured container content: a non-empty {@code "Items"}
     * list. The single definition of prior-captured entity content, shared with {@link RecoveredScan} so the outline's
     * recovered-entity set tracks exactly what {@link NbtMerge#carryList} preserves, the UUID-keyed sibling of
     * {@link ChunkMerge#hasCapturedContent}. A future band's added content key (the pre-1.21.5 saddle slot) extends
     * this and the carry-forward together.
     */
    static boolean hasCapturedContent(CompoundTag entity) {
        return NbtMerge.isNonEmptyList(entity, "Items");
    }

    /** Decode an entity tag's {@code "UUID"} (the {@code UUIDUtil.CODEC} 4-int array), or null if absent/malformed. */
    static @Nullable UUID readUuid(CompoundTag entityTag) {
        return UUIDUtil.CODEC.parse(NbtOps.INSTANCE, entityTag.get("UUID")).result().orElse(null);
    }
}
