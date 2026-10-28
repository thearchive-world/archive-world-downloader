// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;

public final class BlockEntityFixtures {
    private BlockEntityFixtures() {}

    /**
     * The tag vanilla writes for a freshly placed block entity of type {@code id} at {@code x/y/z}: its metadata and
     * every key its own save writes unconditionally.
     */
    public static CompoundTag blockEntity(String id, int x, int y, int z) {
        return FixtureFidelity.blockEntityShape(id, x, y, z);
    }

    /**
     * As {@link #blockEntity}, carrying a custom name.
     */
    public static CompoundTag namedBlockEntity(String id, int x, int y, int z, String customName) {
        CompoundTag tag = blockEntity(id, x, y, z);
        tag.putString("CustomName", Component.Serializer.toJson(Component.literal(customName)));
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
     * A block entity carrying a key no vanilla writer emits.
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
     * than bypassed.
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
     * {@code {EntityData, TicksInHive, MinOccupationTicks}} entry vanilla's {@code BeehiveBlockEntity.load} reads.
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
     * A chunk tag whose {@code "block_entities"} list holds {@code blockEntities}, the post-1.18 flat layout, each
     * carrying the {@code keepPacked} the chunk layer writes around a live block entity.
     *
     * <p>Every tag is checked against its producer's shape first, so an omitted key fails the build at the test that
     * would otherwise have passed over it.
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
     * A chunk tag whose block entities are NOT checked against their producers.
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
     * carrying the {@code keepPacked} that layer stamps beside every live block entity it saves.
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
        return findByPos(chunkTag.getList("block_entities", Tag.TAG_COMPOUND), x, y, z);
    }

    /** The block-entity tag in {@code chunkTag} at {@code x/y/z}, or {@code null} when none matches. */
    public static @Nullable CompoundTag findByPosOrNull(CompoundTag chunkTag, int x, int y, int z) {
        ListTag list = chunkTag.getList("block_entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            if (tag.getInt("x") == x && tag.getInt("y") == y && tag.getInt("z") == z) {
                return tag;
            }
        }
        return null;
    }
}
