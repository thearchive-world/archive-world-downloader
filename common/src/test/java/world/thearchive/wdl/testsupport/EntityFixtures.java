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

/**
 * Entity-chunk fixtures for the entity-side merges: a container vehicle and the {@code "Entities"} envelope around it.
 *
 * <p>An entity tag has no producer callable here, because capture derives it from a server packet rather than from a
 * serializable live entity, so the {@code id} and {@code "UUID"} metadata a merge matches on is built directly. Its
 * {@code "Items"} holder does have one ({@code ItemStackHelper.saveAllItems}, the same writer
 * {@code ContainerSink.captureItems} calls), so {@link #entityChunkTagWith} checks that part of every tag handed to it.
 */
public final class EntityFixtures {
    private EntityFixtures() {}

    /** An entity tag carrying only its {@code id}, the key a recursive load reads before anything else. */
    public static NBTTagCompound entityTag(String id) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", id);
        return tag;
    }

    /** An entity tag carrying the {@code id} and {@code "UUID"} a merge matches on, and nothing else. */
    public static NBTTagCompound entity(String id, UUID uuid) {
        NBTTagCompound tag = entityTag(id);
        tag.setUniqueId("UUID", uuid);
        return tag;
    }

    /**
     * An entity carrying the {@code "Pos"} three-double list a chunk locator reads. Built through the codec rather than
     * as a hand-assembled list so a locator that reads the axes in the wrong order still misses rather than landing on
     * the fixture.
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

    /** A container vehicle at a position, the chest-boat shape whose loss takes a chest of loot with it. */
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
     * An entity carrying no {@code "UUID"} at all, one of the three unreadable-identity shapes a chunk read back from
     * disk can hold. The merge has to skip it rather than throw, because it runs between the chunk flush and the
     * writer's finish.
     */
    public static NBTTagCompound entityWithoutUuid(String id) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", id);
        return tag;
    }

    /** An entity whose {@code "UUID"} is an int array of the wrong length, so its decode is an error result. */
    public static NBTTagCompound entityWithShortUuid(String id, int... uuid) {
        NBTTagCompound tag = entityWithoutUuid(id);
        tag.setIntArray("UUID", uuid);
        return tag;
    }

    /** An entity whose {@code "UUID"} is a string where a four-int array belongs. */
    public static NBTTagCompound entityWithWrongTypeUuid(String id, String uuid) {
        NBTTagCompound tag = entityWithoutUuid(id);
        tag.setString("UUID", uuid);
        return tag;
    }

    /**
     * An in-chunk entity carrier holding {@code entityTags} under {@code "Entities"}, each having its {@code "Items"}
     * checked against the shape its producer emits first.
     *
     * <p>The envelope comes from the real sink, which at this band writes only the {@code "Entities"} list (the writer
     * folds it into the region chunk's {@code Level.Entities}). The one carve-out is the empty case: the producer
     * returns null there rather than an empty envelope, and a chunk whose fresh capture holds no entity is one of the
     * cases under test.
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
