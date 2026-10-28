// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Iterator;
import java.util.NoSuchElementException;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagList;

final class NbtElements implements Iterator<NBTBase> {
    private final NBTTagList list;
    private int index;

    private NbtElements(NBTTagList list) {
        this.list = list;
    }

    static Iterable<NBTBase> of(NBTTagList list) {
        return () -> new NbtElements(list);
    }

    @Override
    public boolean hasNext() {
        return index < list.tagCount();
    }

    // Keep the bounds check: NBTTagList.get answers an out of range index with a fresh NBTTagEnd instead of
    // throwing, so without it an off-by-one in hasNext walks one element past the end in silence.
    @Override
    public NBTBase next() {
        if (index >= list.tagCount()) {
            throw new NoSuchElementException();
        }
        return list.get(index++);
    }
}
