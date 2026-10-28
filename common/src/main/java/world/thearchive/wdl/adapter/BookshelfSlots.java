// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

final class BookshelfSlots {
    private BookshelfSlots() {}

    /** 0 when {@code state} is not a bookshelf. */
    static int occupiedSlotMask(BlockState state) {
        if (!(state.getBlock() instanceof ChiseledBookShelfBlock)) {
            return 0;
        }
        int mask = 0;
        List<BooleanProperty> slots = ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES;
        for (int slot = 0; slot < slots.size(); slot++) {
            if (state.getValue(slots.get(slot))) {
                mask |= 1 << slot;
            }
        }
        return mask;
    }
}
