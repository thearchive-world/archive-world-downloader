// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The headless guard for the one ordering a dimension change rests on: everything drained lands in the dimension being
 * left, which is only true while that dimension is still bound, so every drain must run before any swap. A store
 * drained after the swap writes one world's contents into another world's folder, and that is the failure the
 * registration order cannot express because this owns the order rather than each caller.
 *
 * <p>The clears run last, after the entered dimension is bound, and the first bind runs the swaps alone, since a
 * session that has captured nothing has nothing to drain into or clear for.
 */
class DimensionRebindTest {
    private static final String OVERWORLD = "overworld";
    private static final String NETHER = "the_nether";

    @Test
    void everyDrainRunsBeforeEverySwap() {
        List<String> order = new ArrayList<>();
        DimensionRebind<String> rebind = new DimensionRebind<>();
        rebind.registerSwap(dimension -> order.add("swap-registered-first"));
        rebind.registerClear(() -> order.add("clear"));
        rebind.registerDrain(() -> order.add("drain-registered-last"));

        rebind.rebind(NETHER);

        assertEquals(ImmutableList.of("drain-registered-last", "swap-registered-first", "clear"), order,
                "the behavior a store registers under decides when it runs, not the line it registered on");
    }

    @Test
    void noSwapRunsBetweenTwoDrains() {
        // One drain against one swap cannot tell every drain before every swap from a zip of the two lists, and
        // the session registers two drains against five swaps.
        List<String> order = new ArrayList<>();
        DimensionRebind<String> rebind = new DimensionRebind<>();
        rebind.registerDrain(() -> order.add("drain-a"));
        rebind.registerSwap(dimension -> order.add("swap"));
        rebind.registerDrain(() -> order.add("drain-b"));

        rebind.rebind(NETHER);

        assertEquals(ImmutableList.of("drain-a", "drain-b", "swap"), order,
                "the second drain writes into the dimension being left, which the swap has not yet retargeted");
    }

    @Test
    void drainsRunInRegistrationOrder() {
        // The drains feed each other: entities promote into the buffer that the flush beside them then writes,
        // so their order among themselves is the caller's to state and this must preserve it.
        List<String> order = new ArrayList<>();
        DimensionRebind<String> rebind = new DimensionRebind<>();
        rebind.registerDrain(() -> order.add("promote"));
        rebind.registerDrain(() -> order.add("flush"));

        rebind.rebind(NETHER);

        assertEquals(ImmutableList.of("promote", "flush"), order);
    }

    @Test
    void everySwapReceivesTheDimensionBeingEntered() {
        List<String> bound = new ArrayList<>();
        DimensionRebind<String> rebind = new DimensionRebind<>();
        rebind.registerSwap(bound::add);
        rebind.registerSwap(dimension -> bound.add(dimension + "-again"));

        rebind.rebind(NETHER);

        assertEquals(ImmutableList.of(NETHER, NETHER + "-again"), bound,
                "the entered dimension arrives as the argument, so no swap reads a field mid-rebind");
    }

    @Test
    void theFirstBindSwapsWithoutDrainingOrClearing() {
        // A session binds its starting dimension through the same list, which is what keeps the enrollment from
        // drifting from the constructor. Draining there would flush a capture that has not begun.
        List<String> order = new ArrayList<>();
        DimensionRebind<String> rebind = new DimensionRebind<>();
        rebind.registerDrain(() -> order.add("drain"));
        rebind.registerSwap(order::add);
        rebind.registerClear(() -> order.add("clear"));

        rebind.bind(OVERWORLD);

        assertEquals(ImmutableList.of(OVERWORLD), order, "the swaps alone");
    }
}
