// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.MarkerHue;

class OutlineDrawSetTest {
    private static OutlineRim rim() {
        return new OutlineRim(MarkerHue.RED, new AxisAlignedBB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), new long[] { 0L });
    }

    private static int totalRims(OutlineDrawSet drawSet) {
        int total = 0;
        for (List<OutlineRim> rims : drawSet.sections().values()) {
            total += rims.size();
        }
        return total;
    }

    @Test
    void bucketsRimsBySection() {
        OutlineDrawSet drawSet = new OutlineDrawSet();
        drawSet.add(1L, rim());
        drawSet.add(1L, rim());
        drawSet.add(2L, rim());
        assertEquals(2, drawSet.sections().get(1L).size());
        assertEquals(1, drawSet.sections().get(2L).size());
    }

    @Test
    void rebuildDropsPriorTickRims() {
        OutlineDrawSet drawSet = new OutlineDrawSet();
        drawSet.add(1L, rim());
        drawSet.add(2L, rim());
        drawSet.clear();
        drawSet.add(1L, rim());
        assertEquals(1, totalRims(drawSet));
    }

    @Test
    void unpopulatedSectionIsEvicted() {
        OutlineDrawSet drawSet = new OutlineDrawSet();
        drawSet.add(1L, rim());
        drawSet.clear();
        drawSet.clear();
        assertTrue(drawSet.sections().isEmpty());
    }
}
