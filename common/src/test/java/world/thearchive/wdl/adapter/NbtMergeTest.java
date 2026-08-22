// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The direct guard for {@link NbtMerge#carryListBySlot}'s write side. Every other case reaches it through a bookshelf
 * carry-forward, where the fresh block entity always carries an {@code "Items"} list because vanilla writes one
 * unconditionally, so nothing there can tell a carry that writes back from one that does not.
 */
class NbtMergeTest {
    private static final int ALL_SLOTS = ~0;

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static CompoundTag withBooks(int... slots) {
        String[] books = new String[slots.length];
        Arrays.fill(books, "minecraft:written_book");
        CompoundTag tag = new CompoundTag();
        tag.put("Items", ItemFixtures.itemsAtSlots(slots, books));
        return tag;
    }

    @Test
    void aCarryThatCarriesNothingWritesNothing() {
        // The occupancy says slot 0 now reads empty, so nothing is carried; the write must then not happen at
        // all. A fresh side that already carries the key cannot see the difference, which is why this drives
        // one that does not.
        CompoundTag disk = withBooks(0);
        CompoundTag fresh = new CompoundTag();

        assertFalse(NbtMerge.carryListBySlot(disk, fresh, "Items", 0),
                "no slot the disk names is still occupied, so nothing carries");
        assertFalse(fresh.contains("Items"),
                "and the fresh tag gains no key: a carry-forward that carried nothing writes nothing");
    }

    @Test
    void aCarryThatCarriesSomethingWritesTheUnion() {
        CompoundTag disk = withBooks(0, 2);
        CompoundTag fresh = withBooks(1);

        assertTrue(NbtMerge.carryListBySlot(disk, fresh, "Items", ALL_SLOTS));
        assertEquals(3, fresh.getList("Items", 10).size(), "both disk slots joined the fresh one");
    }

    @Test
    void aCarryWritesTheKeyWhenTheFreshTagHasNoList() {
        // The other half of the write side: the fresh tag has no list to add into, so the union builds one and the
        // write back is the only thing that puts it on the tag. Without it the carried books are assembled and
        // dropped, and the carry still reports true.
        CompoundTag disk = withBooks(0, 2);
        CompoundTag fresh = new CompoundTag();

        assertTrue(NbtMerge.carryListBySlot(disk, fresh, "Items", ALL_SLOTS));
        assertEquals(2, fresh.getList("Items", 10).size(), "both disk slots reach the fresh tag");
    }

    @Test
    void aFreshValueOfTheWrongTypeIsLeftAloneWhenNothingCarries() {
        // The fresh side is read through an instanceof guard, so a value that is not a list falls back to an
        // empty one. Nothing carried means that empty list must not be written over what was there.
        CompoundTag disk = withBooks(0);
        CompoundTag fresh = new CompoundTag();
        fresh.put("Items", new StringTag("not a list"));

        assertFalse(NbtMerge.carryListBySlot(disk, fresh, "Items", 0));
        assertEquals(new StringTag("not a list"), fresh.get("Items"),
                "a malformed fresh value is not replaced by an empty list when nothing was carried");
    }
}
