// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.ChunkPos;

import world.thearchive.wdl.adapter.impl.EntitySinkImpl;

public final class EntityFixtures {
    private EntityFixtures() {}

    /** An entity tag carrying only its {@code id}, the key a recursive load reads before anything else. */
    public static NBTTagCompound entityTag(String id) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", id);
        return tag;
    }

    /** An entity tag carrying the {@code id} and UUID a merge matches on, and nothing else. */
    public static NBTTagCompound entity(String id, UUID uuid) {
        NBTTagCompound tag = entityTag(id);
        tag.setUniqueId("UUID", uuid);
        return tag;
    }

    /**
     * An entity carrying the {@code "Pos"} three-double list a chunk locator reads.
     */
    public static NBTTagCompound entityAt(String id, UUID uuid, double x, double y, double z) {
        NBTTagCompound tag = entity(id, uuid);
        NBTTagList pos = new NBTTagList();
        pos.appendTag(new NBTTagDouble(x));
        pos.appendTag(new NBTTagDouble(y));
        pos.appendTag(new NBTTagDouble(z));
        tag.setTag("Pos", pos);
        return tag;
    }

    /** An entity whose {@code "Pos"} is a two-double list, one axis short of what a locator needs. */
    public static NBTTagCompound entityWithShortPos(String id, UUID uuid, double x, double y) {
        NBTTagCompound tag = entity(id, uuid);
        NBTTagList pos = new NBTTagList();
        pos.appendTag(new NBTTagDouble(x));
        pos.appendTag(new NBTTagDouble(y));
        tag.setTag("Pos", pos);
        return tag;
    }

    /** A container vehicle at a position. */
    public static NBTTagCompound containerVehicleAt(String id, UUID uuid, double x, double y, double z,
            String... itemIds) {
        NBTTagCompound tag = entityAt(id, uuid, x, y, z);
        if (itemIds.length > 0) {
            tag.setTag("Items", ItemFixtures.items(itemIds));
        }
        return tag;
    }

    /**
     * {@code root} carrying {@code passenger} in the {@code "Passengers"} list vanilla's own save recurses into, so a
     * reader that only inspects the root entity misses the nested one.
     */
    public static NBTTagCompound entityCarrying(NBTTagCompound root, NBTTagCompound passenger) {
        NBTTagList passengers = new NBTTagList();
        passengers.appendTag(passenger);
        root.setTag("Passengers", passengers);
        return root;
    }

    /** A container vehicle carrying {@code itemIds} at ascending slots, in the shape vanilla writes them. */
    public static NBTTagCompound containerVehicle(String id, UUID uuid, String... itemIds) {
        NBTTagCompound tag = entity(id, uuid);
        if (itemIds.length > 0) {
            tag.setTag("Items", ItemFixtures.items(itemIds));
        }
        return tag;
    }

    /**
     * An entity carrying no UUID at all.
     */
    public static NBTTagCompound entityWithoutUuid(String id) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", id);
        return tag;
    }

    public static NBTTagCompound entityWithShortUuid(String id, int... uuid) {
        NBTTagCompound tag = entityWithoutUuid(id);
        tag.setIntArray("UUID", uuid);
        return tag;
    }

    public static NBTTagCompound entityWithWrongTypeUuid(String id, String uuid) {
        NBTTagCompound tag = entityWithoutUuid(id);
        tag.setString("UUID", uuid);
        return tag;
    }

    /**
     * An in-chunk entity carrier holding {@code entityTags} under {@code "Entities"}, each having its {@code "Items"}
     * checked against the shape its producer emits first.
     */
    public static NBTTagCompound entityChunkTagWith(NBTTagCompound... entityTags) {
        return entityChunkTagAt(new ChunkPos(0, 0), entityTags);
    }

    /** As {@link #entityChunkTagWith}, for a chunk that is not at the origin. */
    public static NBTTagCompound entityChunkTagAt(ChunkPos pos, NBTTagCompound... entityTags) {
        List<NBTTagCompound> tags = new ArrayList<>();
        for (NBTTagCompound entityTag : entityTags) {
            FixtureFidelity.assertItemsHolderShape(entityTag);
            tags.add(entityTag);
        }
        if (tags.isEmpty()) {
            NBTTagCompound empty = new NBTTagCompound();
            empty.setTag("Entities", new NBTTagList());
            return empty;
        }
        NBTTagCompound encoded = new EntitySinkImpl().encodeChunk(tags, pos);
        if (encoded == null) {
            throw new AssertionError("the entity sink refused a non-empty entity list");
        }
        return encoded;
    }
}
