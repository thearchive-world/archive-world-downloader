// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The immutable captured-set view the outline classifier reads: block keys, entity ids, and the ender flag. */
class CapturedContainersTest {
    private static final UUID PRESENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ABSENT = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void emptyContainsNothing() {
        assertFalse(CapturedContainers.EMPTY.containsBlock(42L));
        assertFalse(CapturedContainers.EMPTY.containsEntity(PRESENT));
        assertFalse(CapturedContainers.EMPTY.enderCaptured());
    }

    @Test
    void reportsBlockMembership() {
        CapturedContainers captured = new CapturedContainers(new LongOpenHashSet(new long[] { 7L, 9L }), Set.of(),
                false);
        assertTrue(captured.containsBlock(7L));
        assertTrue(captured.containsBlock(9L));
        assertFalse(captured.containsBlock(8L));
    }

    @Test
    void reportsEntityMembership() {
        CapturedContainers captured = new CapturedContainers(LongSets.EMPTY_SET, Set.of(PRESENT), false);
        assertTrue(captured.containsEntity(PRESENT));
        assertFalse(captured.containsEntity(ABSENT));
    }

    @Test
    void reportsEnderFlag() {
        assertTrue(new CapturedContainers(LongSets.EMPTY_SET, Set.of(), true).enderCaptured());
        assertFalse(new CapturedContainers(LongSets.EMPTY_SET, Set.of(), false).enderCaptured());
    }

    @Test
    void reportsBookshelfCapturedSlots() {
        Long2IntMap masks = new Long2IntOpenHashMap();
        masks.put(7L, 0b101);
        CapturedContainers captured = new CapturedContainers(LongSets.EMPTY_SET, Set.of(), false, masks,
                new Long2ObjectOpenHashMap<>());
        assertEquals(0b101, captured.bookshelfCapturedSlots(7L));
        assertEquals(0, captured.bookshelfCapturedSlots(8L));
    }

    @Test
    void emptyHasNoBookshelfCapturedSlots() {
        assertEquals(0, CapturedContainers.EMPTY.bookshelfCapturedSlots(42L));
    }

    @Test
    void reportsCapturedBlockType() {
        Long2ObjectMap<String> types = new Long2ObjectOpenHashMap<>();
        types.put(7L, "minecraft:barrel");
        CapturedContainers captured = new CapturedContainers(new LongOpenHashSet(new long[] { 7L }), Set.of(), false,
                new Long2IntOpenHashMap(), types);
        assertEquals("minecraft:barrel", captured.capturedBlockType(7L));
        assertNull(captured.capturedBlockType(8L));
    }

    @Test
    void emptyHasNoCapturedBlockType() {
        assertNull(CapturedContainers.EMPTY.capturedBlockType(42L));
    }
}
