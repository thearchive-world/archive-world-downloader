// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.ItemFixtures;

/**
 * The pack/unpack round-trip guard for {@link StashHolder}: a compacted holder decodes back to a tag deep-equal to the
 * original (so the drain merges exactly what was stashed), a fresh holder hands back its live tree, and compacting
 * twice is a no-op.
 */
class StashHolderTest {
    private static NBTTagCompound nestedHolder() {
        NBTTagCompound holder = ItemFixtures
                .itemsHolder(ItemFixtures.namedStack("minecraft:diamond_pickaxe", "Efficiency Pick"));
        holder.setString("wdl_block_entity_id", "minecraft:chest");
        return holder;
    }

    @Test
    void freshHolderHandsBackItsLiveTree() throws IOException {
        NBTTagCompound tag = nestedHolder();
        StashHolder holder = StashHolder.of(tag);

        assertFalse(holder.isPacked());
        assertTrue(holder.shouldCompact(), "a fresh holder is the pump's candidate");
        assertSame(tag, holder.unpack(), "an uncompacted holder returns the live tree, not a copy");
    }

    @Test
    void compactedHolderRoundTripsDeepEqual() throws IOException {
        NBTTagCompound tag = nestedHolder();
        StashHolder holder = StashHolder.of(tag);

        holder.compact();

        assertTrue(holder.isPacked());
        assertFalse(holder.shouldCompact(), "a packed holder leaves the pump's candidate set");
        assertEquals(tag, holder.unpack(), "the packed bytes decode back to a deep-equal tag");
    }

    @Test
    void secondCompactChangesNothing() throws IOException {
        StashHolder holder = StashHolder.of(nestedHolder());
        holder.compact();

        holder.compact();

        assertTrue(holder.isPacked());
        assertEquals(nestedHolder(), holder.unpack());
    }
}
