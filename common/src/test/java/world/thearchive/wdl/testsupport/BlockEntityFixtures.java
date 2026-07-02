// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * Shared block-entity NBT fixtures for the merge tests: the tag vanilla writes for a block entity, a chunk tag wrapping
 * a {@code "block_entities"} list, and by-position lookups into that list. Hoisted here so the merge tests share one
 * copy rather than each carrying its own.
 *
 * <p>{@link #blockEntity} is the producer's own output rather than a hand-listed set of keys, and {@link #chunkTagWith}
 * re-checks every tag handed to it against {@link FixtureFidelity}, so a fixture that overlays keys onto the base is
 * checked after the overlay, wherever it was assembled.
 */
public final class BlockEntityFixtures {
    private BlockEntityFixtures() {}

    /**
     * The tag vanilla writes for a freshly placed block entity of type {@code id} at {@code x/y/z}: its metadata, its
     * {@code components}, and every key its own save writes unconditionally.
     */
    public static CompoundTag blockEntity(String id, int x, int y, int z) {
        return FixtureFidelity.blockEntityShape(id, x, y, z);
    }

    /**
     * A block entity carrying a key no vanilla writer emits, for the one property that needs exactly that: the chunk
     * codec must pass a block entity's NBT through opaquely, since the tags it re-encodes come from the client and may
     * have been written by a foreign or modded server. A probe vanilla itself round-trips cannot prove that, because a
     * codec that silently rebuilt every tag from vanilla's own load would still preserve it. Not for a fixture standing
     * in for producer output.
     */
    public static CompoundTag blockEntityWithForeignKey(String id, int x, int y, int z, String key,
            String value) {
        CompoundTag tag = blockEntity(id, x, y, z);
        tag.putString(key, value);
        return tag;
    }

    /** The {@code "bees"} list vanilla writes for one occupant per named {@code ticksInHive}. */
    public static ListTag bees(int... ticksInHive) {
        List<BeehiveBlockEntity.Occupant> occupants = new ArrayList<>();
        for (int ticks : ticksInHive) {
            occupants.add(BeehiveBlockEntity.Occupant.create(ticks));
        }
        RegistryAccess registries = TestRegistries.frozen();
        return (ListTag) BeehiveBlockEntity.Occupant.LIST_CODEC
                .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), occupants)
                .getOrThrow();
    }

    /**
     * A chunk tag whose {@code "block_entities"} list holds {@code blockEntities}, the post-1.18 flat layout, each
     * carrying the {@code keepPacked} the chunk layer writes around a live block entity.
     *
     * <p>Every tag is checked against its producer's shape first. This is the choke point: a fixture assembled anywhere
     * and handed to a chunk merge passes through here, so an omitted key fails the build at the test that would
     * otherwise have passed over it.
     */
    public static CompoundTag chunkTagWith(CompoundTag... blockEntities) {
        CompoundTag chunkTag = new CompoundTag();
        ListTag list = new ListTag();
        for (CompoundTag blockEntity : blockEntities) {
            list.add(savedBlockEntity(blockEntity));
        }
        chunkTag.put("block_entities", list);
        return chunkTag;
    }

    /**
     * A chunk tag whose block entities are NOT checked against their producers, for the cases whose subject is a shape
     * no producer emits: an entry a corrupted or foreign save holds, or a field deliberately removed to prove the
     * reader drops it. Reach for {@link #chunkTagWith} everywhere else; a fixture that merely stands in for producer
     * output belongs there, and routing it here is how the check stops meaning anything.
     */
    public static CompoundTag malformedChunkTagWith(CompoundTag... blockEntities) {
        CompoundTag chunkTag = new CompoundTag();
        ListTag list = new ListTag();
        for (CompoundTag blockEntity : blockEntities) {
            CompoundTag saved = blockEntity.copy();
            saved.putBoolean(FixtureFidelity.KEEP_PACKED, false);
            list.add(saved);
        }
        chunkTag.put("block_entities", list);
        return chunkTag;
    }

    /**
     * One block entity as the chunk layer hands it to the region writer: checked against its producer's shape, then
     * carrying the {@code keepPacked} that layer stamps beside every live block entity it saves. Use this where a
     * fixture reaches a chunk without going through {@link #chunkTagWith}.
     */
    public static CompoundTag savedBlockEntity(CompoundTag blockEntityTag) {
        FixtureFidelity.assertBlockEntityShape(blockEntityTag);
        CompoundTag saved = blockEntityTag.copy();
        saved.putBoolean(FixtureFidelity.KEEP_PACKED, false);
        return saved;
    }

    /** The block-entity tag in {@code list} at {@code x/y/z}, or an {@link AssertionError} when none matches. */
    public static CompoundTag findByPos(ListTag list, int x, int y, int z) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompoundOrEmpty(i);
            if (tag.getIntOr("x", 0) == x && tag.getIntOr("y", 0) == y && tag.getIntOr("z", 0) == z) {
                return tag;
            }
        }
        throw new AssertionError("no block entity at " + x + "," + y + "," + z);
    }

    /** The block-entity tag in {@code chunkTag} at {@code x/y/z}, or an {@link AssertionError} when none matches. */
    public static CompoundTag findByPos(CompoundTag chunkTag, int x, int y, int z) {
        return findByPos(chunkTag.getListOrEmpty("block_entities"), x, y, z);
    }

    /** The block-entity tag in {@code chunkTag} at {@code x/y/z}, or {@code null} when none matches. */
    public static @Nullable CompoundTag findByPosOrNull(CompoundTag chunkTag, int x, int y, int z) {
        ListTag list = chunkTag.getListOrEmpty("block_entities");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompoundOrEmpty(i);
            if (tag.getIntOr("x", 0) == x && tag.getIntOr("y", 0) == y && tag.getIntOr("z", 0) == z) {
                return tag;
            }
        }
        return null;
    }
}
