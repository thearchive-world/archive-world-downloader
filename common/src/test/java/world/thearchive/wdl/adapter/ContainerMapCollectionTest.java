// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.core.MapManifest;

/**
 * The container-map loss gate: a filled map nested in a chest and in a shulker box is collected and remapped through
 * the item-level {@code Damage} path, while a non-map item carrying its own {@code Damage} is left untouched. At this
 * band a filled map's id is the item-level {@code Damage} short behind the {@code id == "minecraft:filled_map"}
 * identity gate, not the inner {@code tag."map"} of the higher bands. Without the item-compound walk the maps are never
 * collected; without the identity gate the damaged pickaxe is rewritten as a map, corrupting its durability and
 * aliasing it to a captured map image. Hand-built classic-MCP item NBT drives it, no live ItemStack.
 */
class ContainerMapCollectionTest {
    private static final String FILLED_MAP = "minecraft:filled_map";
    private static final int PICKAXE_DAMAGE = 800;
    private static final int CHEST_MAP_SESSION_ID = 40;
    private static final int SHULKER_MAP_SESSION_ID = 41;

    private static NBTTagCompound item(String id, int damage) {
        NBTTagCompound item = new NBTTagCompound();
        item.setString("id", id);
        item.setByte("Count", (byte) 1);
        item.setShort("Damage", (short) damage);
        return item;
    }

    private static NBTTagCompound shulkerHolding(NBTTagCompound nested) {
        NBTTagList nestedItems = new NBTTagList();
        nestedItems.appendTag(nested);
        NBTTagCompound blockEntityTag = new NBTTagCompound();
        blockEntityTag.setTag("Items", nestedItems);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("BlockEntityTag", blockEntityTag);
        NBTTagCompound shulker = item("minecraft:shulker_box", 0);
        shulker.setTag("tag", tag);
        return shulker;
    }

    /** A chest holder whose {@code Items} carry the chest map, the damaged pickaxe, and a shulker nesting a map. */
    private static NBTTagCompound holder(NBTTagCompound chestMap, NBTTagCompound pickaxe, NBTTagCompound shulker) {
        NBTTagList items = new NBTTagList();
        items.appendTag(chestMap);
        items.appendTag(pickaxe);
        items.appendTag(shulker);
        NBTTagCompound holder = new NBTTagCompound();
        holder.setTag("Items", items);
        return holder;
    }

    /** A synthetic serialized inner map data tag with the three hashed fields, distinct per {@code colorFill}. */
    private static NBTTagCompound picture(int colorFill) {
        byte[] colors = new byte[16384];
        Arrays.fill(colors, (byte) colorFill);
        NBTTagCompound data = new NBTTagCompound();
        data.setTag("colors", new NBTTagByteArray(colors));
        data.setByte("scale", (byte) 0);
        data.setInteger("dimension", 0);
        return data;
    }

    @Test
    void collectsFilledMapIdsFromChestAndShulkerNotTheDamagedPickaxe() {
        NBTTagCompound holder = holder(item(FILLED_MAP, CHEST_MAP_SESSION_ID), item("minecraft:diamond_pickaxe",
                PICKAXE_DAMAGE), shulkerHolding(item(FILLED_MAP, SHULKER_MAP_SESSION_ID)));

        Set<Integer> ids = new LinkedHashSet<>();
        MapIdCollector.collectFromItemList(holder, "Items", ids);

        assertTrue(ids.contains(CHEST_MAP_SESSION_ID), "the chest filled map's item-level Damage id is collected");
        assertTrue(ids.contains(SHULKER_MAP_SESSION_ID), "the shulker-nested filled map's id is collected");
        assertFalse(ids.contains(PICKAXE_DAMAGE),
                "the damaged pickaxe's universal Damage field is not read as a map id");
    }

    @Test
    void remapStreamsTheFilledMapsAndLeavesTheNonMapDamageUntouched(@TempDir Path directory) throws IOException {
        NBTTagCompound chestMap = item(FILLED_MAP, CHEST_MAP_SESSION_ID);
        NBTTagCompound shulkerMap = item(FILLED_MAP, SHULKER_MAP_SESSION_ID);
        NBTTagCompound pickaxe = item("minecraft:diamond_pickaxe", PICKAXE_DAMAGE);
        NBTTagCompound holder = holder(chestMap, pickaxe, shulkerHolding(shulkerMap));

        Path dataDirectory = directory.resolve("data");
        Map<Integer, NBTBase> streamed = new LinkedHashMap<>();
        MapArchive archive = new MapArchive(MapManifest.empty(),
                sessionId -> sessionId == CHEST_MAP_SESSION_ID ? picture(1)
                        : sessionId == SHULKER_MAP_SESSION_ID ? picture(2) : null,
                (archiveId, dataTag) -> {
                    streamed.put(archiveId, dataTag);
                    try {
                        MapDataWriter.write(dataDirectory, "map_" + archiveId, dataTag);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        archive.remap(holder, "Items");

        int chestArchiveId = chestMap.getShort("Damage");
        int shulkerArchiveId = shulkerMap.getShort("Damage");
        assertNotEquals(CHEST_MAP_SESSION_ID, chestArchiveId,
                "the chest map's in-container reference is remapped off its session id to its archive id");
        assertNotEquals(SHULKER_MAP_SESSION_ID, shulkerArchiveId,
                "the shulker-nested map's reference is remapped to its archive id");
        assertTrue(streamed.containsKey(chestArchiveId), "the chest map is streamed to the sink under its archive id");
        assertTrue(Files.exists(dataDirectory.resolve("map_" + chestArchiveId + ".dat")),
                "the chest map is streamed to data/map_<id>.dat");
        assertTrue(Files.exists(dataDirectory.resolve("map_" + shulkerArchiveId + ".dat")),
                "the shulker-nested map is streamed to data/map_<id>.dat");
        assertEquals(PICKAXE_DAMAGE, pickaxe.getShort("Damage"),
                "the damaged pickaxe's Damage is left untouched by the map-id remap");
    }
}
