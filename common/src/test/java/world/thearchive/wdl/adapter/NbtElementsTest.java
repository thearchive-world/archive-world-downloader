// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import org.junit.jupiter.api.Test;

/**
 * Pins the iterable view every adapter tag-list walk goes through, including the two guarantees a walk site relies on
 * without saying so: the view is repeatable, and the cursor throws past the end instead of handing on the fresh
 * NBTTagEnd that {@code NBTTagList.get} answers an out of range index with.
 */
class NbtElementsTest {
    private static NBTTagList listOf(String... values) {
        NBTTagList list = new NBTTagList();
        for (String value : values) {
            list.appendTag(new NBTTagString(value));
        }
        return list;
    }

    private static List<NBTBase> walk(Iterable<NBTBase> view) {
        List<NBTBase> seen = new ArrayList<>();
        for (NBTBase element : view) {
            seen.add(element);
        }
        return seen;
    }

    @Test
    void theViewYieldsTheListsOwnElementsInOrder() {
        NBTTagList list = listOf("a", "b", "c");
        List<NBTBase> seen = walk(NbtElements.of(list));
        assertEquals(3, seen.size(), "the walk yields exactly as many elements as the list holds");
        for (int i = 0; i < seen.size(); i++) {
            assertSame(list.get(i), seen.get(i), "element " + i + " is the list's own element, in list order");
        }
    }

    @Test
    void anEmptyListYieldsNothing() {
        assertEquals(0, walk(NbtElements.of(listOf())).size(), "an empty list has nothing to walk");
    }

    @Test
    void theViewIsRepeatable() {
        NBTTagList list = listOf("a", "b");
        Iterable<NBTBase> view = NbtElements.of(list);
        assertEquals(walk(view), walk(view), "a second walk over one view starts again from the front");
    }

    @Test
    void theCursorRefusesToRunPastTheEnd() {
        Iterator<NBTBase> cursor = NbtElements.of(listOf("only")).iterator();
        cursor.next();
        assertFalse(cursor.hasNext(), "a one-element list is exhausted after one element");
        assertThrows(NoSuchElementException.class, cursor::next,
                "next past the end throws instead of answering NBTTagList.get's out of range NBTTagEnd");
    }
}
