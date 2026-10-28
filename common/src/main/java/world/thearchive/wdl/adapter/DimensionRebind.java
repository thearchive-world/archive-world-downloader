// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A {@linkplain #registerDrain drain} empties a store into the dimension being left, which only works while that
 * dimension is still bound.
 *
 * <p>Ordering is the property this exists to hold, not a convenience: every drain runs before every swap, so a store
 * drained after the swap is not something a registration can express.
 */
final class DimensionRebind<D> {
    private final List<Runnable> drains = new ArrayList<>();
    private final List<Consumer<D>> swaps = new ArrayList<>();
    private final List<Runnable> clears = new ArrayList<>();

    void registerDrain(Runnable drain) {
        drains.add(drain);
    }

    void registerSwap(Consumer<D> swap) {
        swaps.add(swap);
    }

    void registerClear(Runnable clear) {
        clears.add(clear);
    }

    void bind(D dimension) {
        for (Consumer<D> swap : swaps) {
            swap.accept(dimension);
        }
    }

    void rebind(D dimension) {
        for (Runnable drain : drains) {
            drain.run();
        }
        bind(dimension);
        for (Runnable clear : clears) {
            clear.run();
        }
    }
}
