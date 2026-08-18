// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;

/**
 * Shared block-entity NBT fixtures for the merge tests: the tag vanilla writes for a block entity, a chunk tag wrapping
 * a {@code Level.TileEntities} list, and by-position lookups into that list. Hoisted here so the
 * container/lectern/interaction merge tests share one copy rather than each carrying its own.
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
     * As {@link #blockEntity} for a block entity the player renamed, so a merge can be shown to leave a real sibling
     * field alone. Below 1.20.5 only a {@link net.minecraft.world.level.block.entity.BaseContainerBlockEntity} (a chest
     * and the like) carries an arbitrary name; the raw {@code "CustomName"} JSON key written here is exactly what its
     * {@code setCustomName} would write, so the fidelity round trip still passes for one.
     */
    public static CompoundTag namedBlockEntity(String id, int x, int y, int z, String customName) {
        CompoundTag tag = blockEntity(id, x, y, z);
        tag.putString("CustomName", Component.Serializer.toJson(new TextComponent(customName)));
        return tag;
    }

    /** The custom name a {@link #namedBlockEntity} tag carries, or {@code ""} when it carries none. */
    public static String customNameOf(CompoundTag blockEntityTag) {
        if (!blockEntityTag.contains("CustomName", Tag.TAG_STRING)) {
            return "";
        }
        Component name = Component.Serializer.fromJson(blockEntityTag.getString("CustomName"));
        return name == null ? "" : name.getString();
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

    /**
     * The open-time holder the band sink produces for a container of {@code containerSize} whose every slot is empty,
     * stamped with {@code blockEntityId} as the open-time write stamps it so a fold's type gate is exercised rather
     * than bypassed. Built through the sink rather than by hand because the emptiness is the subject wherever this is
     * used: an empty container serializes {@code "Items"} as a present empty list, which is what a re-captured
     * container that nobody opened also looks like.
     */
    public static CompoundTag emptyContainerHolder(int containerSize, String blockEntityId) {
        RegistryAccess registries = TestRegistries.frozen();
        CompoundTag holder = new ContainerSinkImpl()
                .captureItems(NonNullList.withSize(containerSize, ItemStack.EMPTY), registries);
        holder.putString("wdl_block_entity_id", blockEntityId);
        return holder;
    }

    /**
     * A {@code "Bees"}-shaped list of one occupant per named {@code ticksInHive}, the pre-component
     * {@code {EntityData, TicksInHive, MinOccupationTicks}} entry vanilla's {@code BeehiveBlockEntity.load} reads
     * (below 1.20.5 there is no {@code Occupant} record to encode through).
     */
    public static ListTag bees(int... ticksInHive) {
        ListTag list = new ListTag();
        for (int ticks : ticksInHive) {
            CompoundTag entityData = new CompoundTag();
            entityData.putString("id", "minecraft:bee");
            CompoundTag occupant = new CompoundTag();
            occupant.put("EntityData", entityData);
            occupant.putInt("TicksInHive", ticks);
            occupant.putInt("MinOccupationTicks", 600);
            list.add(occupant);
        }
        return list;
    }

    /**
     * A chunk tag whose {@code Level.TileEntities} list holds {@code blockEntities}, the pre-1.18 layout, each carrying
     * the {@code keepPacked} the chunk layer writes around a live block entity.
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
        CompoundTag level = new CompoundTag();
        level.put("TileEntities", list);
        chunkTag.put("Level", level);
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
        CompoundTag level = new CompoundTag();
        level.put("TileEntities", list);
        chunkTag.put("Level", level);
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
            CompoundTag tag = list.getCompound(i);
            if (tag.getInt("x") == x && tag.getInt("y") == y && tag.getInt("z") == z) {
                return tag;
            }
        }
        throw new AssertionError("no block entity at " + x + "," + y + "," + z);
    }

    /** The block-entity tag in {@code chunkTag} at {@code x/y/z}, or an {@link AssertionError} when none matches. */
    public static CompoundTag findByPos(CompoundTag chunkTag, int x, int y, int z) {
        return findByPos(chunkTag.getCompound("Level").getList("TileEntities", Tag.TAG_COMPOUND), x, y, z);
    }

    /** The block-entity tag in {@code chunkTag} at {@code x/y/z}, or {@code null} when none matches. */
    public static @Nullable CompoundTag findByPosOrNull(CompoundTag chunkTag, int x, int y, int z) {
        ListTag list = chunkTag.getCompound("Level").getList("TileEntities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            if (tag.getInt("x") == x && tag.getInt("y") == y && tag.getInt("z") == z) {
                return tag;
            }
        }
        return null;
    }
}
