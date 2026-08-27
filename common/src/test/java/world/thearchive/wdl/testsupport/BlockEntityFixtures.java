// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.NonNullList;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
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
     * The tag vanilla writes for a freshly placed block entity of type {@code id} at {@code x/y/z}: its metadata and
     * every key its own save writes unconditionally.
     */
    public static NBTTagCompound blockEntity(String id, int x, int y, int z) {
        return FixtureFidelity.blockEntityShape(id, x, y, z);
    }

    /**
     * As {@link #blockEntity} for a block entity the player renamed, so a merge can be shown to leave a real sibling
     * field alone. At 1.12.2 only a {@link net.minecraft.tileentity.TileEntityLockableLoot} (a chest and the like)
     * carries an arbitrary name; the raw {@code "CustomName"} JSON key written here is exactly what its
     * {@code setCustomName} would write, so the fidelity round trip still passes for one.
     */
    public static NBTTagCompound namedBlockEntity(String id, int x, int y, int z, String customName) {
        NBTTagCompound tag = blockEntity(id, x, y, z);
        tag.setString("CustomName", ITextComponent.Serializer.componentToJson(new TextComponentString(customName)));
        return tag;
    }

    /** The custom name a {@link #namedBlockEntity} tag carries, or {@code ""} when it carries none. */
    public static String customNameOf(NBTTagCompound blockEntityTag) {
        if (!blockEntityTag.hasKey("CustomName", 8)) {
            return "";
        }
        ITextComponent name = ITextComponent.Serializer.jsonToComponent(blockEntityTag.getString("CustomName"));
        return name == null ? "" : name.getUnformattedText();
    }

    /**
     * A block entity of a type no block on this band hosts. {@link #blockEntity} cannot build one: it resolves the type
     * to a representative block state to take the producer's shape from, and a type this band does not register has
     * none. Only for such a type, and only where what is under test is band-agnostic tag code a save written on a band
     * that does have the block reaches; a registered type belongs on {@link #blockEntity}, where the shape is checked,
     * and a tag from here reaches a chunk through {@link #malformedChunkTagWith}.
     */
    public static NBTTagCompound unhostedBlockEntity(String id, int x, int y, int z) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", id);
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        return tag;
    }

    /**
     * A block entity carrying a key no vanilla writer emits, for the one property that needs exactly that: the chunk
     * codec must pass a block entity's NBT through opaquely, since the tags it re-encodes come from the client and may
     * have been written by a foreign or modded server. A probe vanilla itself round-trips cannot prove that, because a
     * codec that silently rebuilt every tag from vanilla's own load would still preserve it. Not for a fixture standing
     * in for producer output.
     */
    public static NBTTagCompound blockEntityWithForeignKey(String id, int x, int y, int z, String key,
            String value) {
        NBTTagCompound tag = blockEntity(id, x, y, z);
        tag.setString(key, value);
        return tag;
    }

    /**
     * The open-time holder the band sink produces for a container of {@code containerSize} whose every slot is empty,
     * stamped with {@code blockEntityId} as the open-time write stamps it so a fold's type gate is exercised rather
     * than bypassed. Built through the sink rather than by hand because the emptiness is the subject wherever this is
     * used: an empty container serializes {@code "Items"} as a present empty list, which is what a re-captured
     * container that nobody opened also looks like.
     */
    public static NBTTagCompound emptyContainerHolder(int containerSize, String blockEntityId) {
        TestRegistries.bootstrap();
        NBTTagCompound holder = new ContainerSinkImpl()
                .captureItems(NonNullList.withSize(containerSize, ItemStack.EMPTY));
        holder.setString("wdl_block_entity_id", blockEntityId);
        return holder;
    }

    /**
     * A {@code "Bees"}-shaped list of one occupant per named {@code ticksInHive}, the
     * {@code {EntityData, TicksInHive, MinOccupationTicks}} entry vanilla's {@code TileEntityBeehive.readFromNBT}
     * reads.
     */
    public static NBTTagList bees(int... ticksInHive) {
        NBTTagList list = new NBTTagList();
        for (int ticks : ticksInHive) {
            NBTTagCompound entityData = new NBTTagCompound();
            entityData.setString("id", "minecraft:bee");
            NBTTagCompound occupant = new NBTTagCompound();
            occupant.setTag("EntityData", entityData);
            occupant.setInteger("TicksInHive", ticks);
            occupant.setInteger("MinOccupationTicks", 600);
            list.appendTag(occupant);
        }
        return list;
    }

    /**
     * A chunk tag whose {@code Level.TileEntities} list holds {@code blockEntities}, each carrying the
     * {@code keepPacked} the chunk layer writes around a live block entity.
     *
     * <p>Every tag is checked against its producer's shape first. This is the choke point: a fixture assembled anywhere
     * and handed to a chunk merge passes through here, so an omitted key fails the build at the test that would
     * otherwise have passed over it.
     */
    public static NBTTagCompound chunkTagWith(NBTTagCompound... blockEntities) {
        NBTTagCompound chunkTag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (NBTTagCompound blockEntity : blockEntities) {
            list.appendTag(savedBlockEntity(blockEntity));
        }
        NBTTagCompound level = new NBTTagCompound();
        level.setTag("TileEntities", list);
        chunkTag.setTag("Level", level);
        return chunkTag;
    }

    /**
     * A chunk tag whose block entities are NOT checked against their producers, for the cases whose subject is a shape
     * no producer emits: an entry a corrupted or foreign save holds, or a field deliberately removed to prove the
     * reader drops it. Reach for {@link #chunkTagWith} everywhere else; a fixture that merely stands in for producer
     * output belongs there, and routing it here is how the check stops meaning anything.
     */
    public static NBTTagCompound malformedChunkTagWith(NBTTagCompound... blockEntities) {
        NBTTagCompound chunkTag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (NBTTagCompound blockEntity : blockEntities) {
            NBTTagCompound saved = blockEntity.copy();
            saved.setBoolean(FixtureFidelity.KEEP_PACKED, false);
            list.appendTag(saved);
        }
        NBTTagCompound level = new NBTTagCompound();
        level.setTag("TileEntities", list);
        chunkTag.setTag("Level", level);
        return chunkTag;
    }

    /**
     * One block entity as the chunk layer hands it to the region writer: checked against its producer's shape, then
     * carrying the {@code keepPacked} that layer stamps beside every live block entity it saves. Use this where a
     * fixture reaches a chunk without going through {@link #chunkTagWith}.
     */
    public static NBTTagCompound savedBlockEntity(NBTTagCompound blockEntityTag) {
        FixtureFidelity.assertBlockEntityShape(blockEntityTag);
        NBTTagCompound saved = blockEntityTag.copy();
        saved.setBoolean(FixtureFidelity.KEEP_PACKED, false);
        return saved;
    }

    /** The block-entity tag in {@code list} at {@code x/y/z}, or an {@link AssertionError} when none matches. */
    public static NBTTagCompound findByPos(NBTTagList list, int x, int y, int z) {
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            if (tag.getInteger("x") == x && tag.getInteger("y") == y && tag.getInteger("z") == z) {
                return tag;
            }
        }
        throw new AssertionError("no block entity at " + x + "," + y + "," + z);
    }

    /** The block-entity tag in {@code chunkTag} at {@code x/y/z}, or an {@link AssertionError} when none matches. */
    public static NBTTagCompound findByPos(NBTTagCompound chunkTag, int x, int y, int z) {
        return findByPos(chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10), x, y, z);
    }

    /** The block-entity tag in {@code chunkTag} at {@code x/y/z}, or {@code null} when none matches. */
    public static @Nullable NBTTagCompound findByPosOrNull(NBTTagCompound chunkTag, int x, int y, int z) {
        NBTTagList list = chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            if (tag.getInteger("x") == x && tag.getInteger("y") == y && tag.getInteger("z") == z) {
                return tag;
            }
        }
        return null;
    }
}
