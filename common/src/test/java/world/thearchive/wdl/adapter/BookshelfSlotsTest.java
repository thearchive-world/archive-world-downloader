// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

/** The shared occupied-slot bitmask over real bookshelf block states. */
class BookshelfSlotsTest {
    @BeforeAll
    static void bootstrap() {
        TestRegistries.frozen();
    }

    @Test
    void masksEachOccupiedSlotByItsBitPosition() {
        BlockState state = Blocks.CHISELED_BOOKSHELF.defaultBlockState()
                .setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(1), true)
                .setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(5), true);

        assertEquals(0b100010, BookshelfSlots.occupiedSlotMask(state),
                "bit n is slot n, not a collapsed count or a shifted-down mask");
    }

    @Test
    void emptyShelfAndOtherBlocksBothMaskZero() {
        assertEquals(0, BookshelfSlots.occupiedSlotMask(Blocks.CHISELED_BOOKSHELF.defaultBlockState()),
                "no occupied slot sets no bit");
        assertEquals(0, BookshelfSlots.occupiedSlotMask(Blocks.STONE.defaultBlockState()),
                "a non-bookshelf state has no slots to mask");
    }
}
