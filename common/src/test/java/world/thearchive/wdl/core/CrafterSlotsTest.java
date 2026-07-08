// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class CrafterSlotsTest {
    @Test
    void noneDisabledIsEmptyArray() {
        assertArrayEquals(new int[0], CrafterSlots.disabledSlotIndices(new boolean[9]));
    }

    @Test
    void sparseAscendingIndices() {
        boolean[] flags = new boolean[9];
        flags[0] = true;
        flags[4] = true;
        flags[8] = true;
        assertArrayEquals(new int[] { 0, 4, 8 }, CrafterSlots.disabledSlotIndices(flags));
    }

    @Test
    void allDisabledListsEveryIndex() {
        boolean[] flags = new boolean[9];
        java.util.Arrays.fill(flags, true);
        assertArrayEquals(new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8 }, CrafterSlots.disabledSlotIndices(flags));
    }
}
