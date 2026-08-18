// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The immutable prior-session coverage the outline classifier reads: block keys, per-bookshelf saved slots, and
 * recovered container-entity UUIDs.
 */
class RecoveredCoverageTest {
    @Test
    void emptyContainsNothing() {
        assertFalse(RecoveredCoverage.EMPTY.contains(42L));
        assertEquals(0, RecoveredCoverage.EMPTY.bookshelfSavedSlots(42L));
    }

    @Test
    void reportsBlockMembership() {
        RecoveredCoverage recovered = new RecoveredCoverage(new LongOpenHashSet(new long[] { 7L }));
        assertTrue(recovered.contains(7L));
        assertFalse(recovered.contains(8L));
    }

    @Test
    void reportsBookshelfSavedSlots() {
        Long2IntMap masks = new Long2IntOpenHashMap();
        masks.put(7L, 0b101);
        RecoveredCoverage recovered = new RecoveredCoverage(LongSets.EMPTY_SET, masks, Set.of(), false);
        assertEquals(0b101, recovered.bookshelfSavedSlots(7L));
        assertEquals(0, recovered.bookshelfSavedSlots(8L));
    }

    @Test
    void emptyContainsNoEntity() {
        assertFalse(RecoveredCoverage.EMPTY.containsEntity(new UUID(0x1L, 0x2L)));
    }

    @Test
    void reportsEnderRecovered() {
        assertFalse(RecoveredCoverage.EMPTY.enderRecovered());
        assertTrue(RecoveredCoverage.ENDER_ONLY.enderRecovered());
        assertFalse(RecoveredCoverage.ENDER_ONLY.contains(7L), "the ender fact carries no per-position coverage");
        assertTrue(
                new RecoveredCoverage(LongSets.EMPTY_SET, new Long2IntOpenHashMap(), Set.of(), true).enderRecovered());
    }

    @Test
    void reportsEntityMembership() {
        UUID cart = new UUID(0xAAAAL, 0xBBBBL);
        RecoveredCoverage recovered = new RecoveredCoverage(LongSets.EMPTY_SET, new Long2IntOpenHashMap(), Set.of(cart),
                false);
        assertTrue(recovered.containsEntity(cart));
        assertFalse(recovered.containsEntity(new UUID(0xCCCCL, 0xDDDDL)));
    }
}
