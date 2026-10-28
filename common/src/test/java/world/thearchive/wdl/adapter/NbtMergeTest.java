// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

class NbtMergeTest {
    private static final int ALL_SLOTS = ~0;

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static NBTTagCompound withBooks(int... slots) {
        String[] books = new String[slots.length];
        Arrays.fill(books, "minecraft:written_book");
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Items", ItemFixtures.itemsAtSlots(slots, books));
        return tag;
    }

    @Test
    void aCarryThatCarriesNothingWritesNothing() {
        // A fresh side that already holds a list under the key cannot see the difference, which is why this drives one
        // that does not.
        NBTTagCompound disk = withBooks(0);
        NBTTagCompound fresh = new NBTTagCompound();

        assertFalse(NbtMerge.carryListBySlot(disk, fresh, "Items", 0),
                "no slot the disk names is still occupied, so nothing carries");
        assertFalse(fresh.hasKey("Items"),
                "and the fresh tag gains no key: a carry-forward that carried nothing writes nothing");
    }

    @Test
    void aCarryThatCarriesSomethingWritesTheUnion() {
        NBTTagCompound disk = withBooks(0, 2);
        NBTTagCompound fresh = withBooks(1);

        assertTrue(NbtMerge.carryListBySlot(disk, fresh, "Items", ALL_SLOTS));
        assertEquals(3, fresh.getTagList("Items", 10).tagCount(), "both disk slots joined the fresh one");
    }

    @Test
    void aCarryWritesTheKeyWhenTheFreshTagHasNoList() {
        // The other half of the write side: the fresh tag has no list to add into, so the union builds one and the
        // write back is the only thing that puts it on the tag. Without it the carried books are assembled and
        // dropped, and the carry still reports true.
        NBTTagCompound disk = withBooks(0, 2);
        NBTTagCompound fresh = new NBTTagCompound();

        assertTrue(NbtMerge.carryListBySlot(disk, fresh, "Items", ALL_SLOTS));
        assertEquals(2, fresh.getTagList("Items", 10).tagCount(), "both disk slots reach the fresh tag");
    }

    @Test
    void aFreshValueOfTheWrongTypeIsLeftAloneWhenNothingCarries() {
        NBTTagCompound disk = withBooks(0);
        NBTTagCompound fresh = new NBTTagCompound();
        fresh.setTag("Items", new NBTTagString("not a list"));

        assertFalse(NbtMerge.carryListBySlot(disk, fresh, "Items", 0));
        assertEquals(new NBTTagString("not a list"), fresh.getTag("Items"),
                "a malformed fresh value is not replaced by an empty list when nothing was carried");
    }
}
