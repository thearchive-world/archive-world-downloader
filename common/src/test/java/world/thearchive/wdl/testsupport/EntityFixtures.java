// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;

import world.thearchive.wdl.adapter.impl.EntitySinkImpl;

public final class EntityFixtures {
    private EntityFixtures() {}

    /** An entity tag carrying only its {@code id}, the key a recursive load reads before anything else. */
    public static CompoundTag entityTag(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        return tag;
    }

    /** An entity tag carrying the {@code id} and UUID a merge matches on, and nothing else. */
    public static CompoundTag entity(String id, UUID uuid) {
        CompoundTag tag = entityTag(id);
        tag.putUUID("UUID", uuid);
        return tag;
    }

    /**
     * An entity carrying the {@code "Pos"} three-double list a chunk locator reads.
     */
    public static CompoundTag entityAt(String id, UUID uuid, double x, double y, double z) {
        CompoundTag tag = entity(id, uuid);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        tag.put("Pos", pos);
        return tag;
    }

    /** An entity whose {@code "Pos"} is a two-double list, one axis short of what a locator needs. */
    public static CompoundTag entityWithShortPos(String id, UUID uuid, double x, double y) {
        CompoundTag tag = entity(id, uuid);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        tag.put("Pos", pos);
        return tag;
    }

    /** A container vehicle at a position. */
    public static CompoundTag containerVehicleAt(String id, UUID uuid, double x, double y, double z,
            String... itemIds) {
        CompoundTag tag = entityAt(id, uuid, x, y, z);
        if (itemIds.length > 0) {
            tag.put("Items", ItemFixtures.items(itemIds));
        }
        return tag;
    }

    /**
     * {@code root} carrying {@code passenger} in the {@code "Passengers"} list vanilla's own save recurses into, so a
     * reader that only inspects the root entity misses the nested one.
     */
    public static CompoundTag entityCarrying(CompoundTag root, CompoundTag passenger) {
        ListTag passengers = new ListTag();
        passengers.add(passenger);
        root.put("Passengers", passengers);
        return root;
    }

    /** A container vehicle carrying {@code itemIds} at ascending slots, in the shape vanilla writes them. */
    public static CompoundTag containerVehicle(String id, UUID uuid, String... itemIds) {
        CompoundTag tag = entity(id, uuid);
        if (itemIds.length > 0) {
            tag.put("Items", ItemFixtures.items(itemIds));
        }
        return tag;
    }

    /**
     * An entity carrying no UUID at all.
     */
    public static CompoundTag entityWithoutUuid(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        return tag;
    }

    public static CompoundTag entityWithShortUuid(String id, int... uuid) {
        CompoundTag tag = entityWithoutUuid(id);
        tag.putIntArray("UUID", uuid);
        return tag;
    }

    public static CompoundTag entityWithWrongTypeUuid(String id, String uuid) {
        CompoundTag tag = entityWithoutUuid(id);
        tag.putString("UUID", uuid);
        return tag;
    }

    /**
     * An in-chunk entity carrier holding {@code entityTags} under {@code "Entities"}, each having its {@code "Items"}
     * checked against the shape its producer emits first.
     */
    public static CompoundTag entityChunkTagWith(CompoundTag... entityTags) {
        return entityChunkTagAt(new ChunkPos(0, 0), entityTags);
    }

    /** As {@link #entityChunkTagWith}, for a chunk that is not at the origin. */
    public static CompoundTag entityChunkTagAt(ChunkPos pos, CompoundTag... entityTags) {
        List<CompoundTag> tags = new ArrayList<>();
        for (CompoundTag entityTag : entityTags) {
            FixtureFidelity.assertItemsHolderShape(entityTag);
            tags.add(entityTag);
        }
        if (tags.isEmpty()) {
            CompoundTag empty = new CompoundTag();
            empty.put("Entities", new ListTag());
            return empty;
        }
        CompoundTag encoded = new EntitySinkImpl().encodeChunk(tags, pos);
        if (encoded == null) {
            throw new AssertionError("the entity sink refused a non-empty entity list");
        }
        return encoded;
    }
}
