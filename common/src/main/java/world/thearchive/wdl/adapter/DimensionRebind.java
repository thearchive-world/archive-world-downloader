// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The one owner of what a dimension change does to capture state: every store whose contents belong to a single
 * dimension registers here under the behavior a portal gives it, and {@link #rebind} is the whole of the change.
 *
 * <p>Three behaviors, because the stores genuinely want different ones and a wrapper that gave them all the same one
 * would be wrong. A {@linkplain #registerDrain drain} empties a store into the dimension being left, which only works
 * while that dimension is still bound. A {@linkplain #registerSwap swap} exchanges a per-dimension store for the
 * entered dimension's own instance. A {@linkplain #registerClear clear} empties state that cannot cross, so the entered
 * dimension starts from its own. A store keyed by something globally unique (an entity UUID) is dimension-agnostic and
 * registers nothing.
 *
 * <p>Ordering is the property this exists to hold, not a convenience: every drain runs before every swap, so a store
 * drained after the swap, which writes one dimension's contents into another's folder, is not something a registration
 * can express. The clears run last, after the entered dimension is bound.
 *
 * <p>Generic over the dimension key {@code D} so the ordering is MC-free and headless-testable.
 */
final class DimensionRebind<D> {
    private final List<Runnable> drains = new ArrayList<>();
    private final List<Consumer<D>> swaps = new ArrayList<>();
    private final List<Runnable> clears = new ArrayList<>();

    /** A store drained into the dimension being left, before anything retargets to the one being entered. */
    void registerDrain(Runnable drain) {
        drains.add(drain);
    }

    /** A store held per dimension, exchanged for the entered dimension's own instance. */
    void registerSwap(Consumer<D> swap) {
        swaps.add(swap);
    }

    /** State that cannot cross a dimension change, emptied so the entered dimension starts from its own. */
    void registerClear(Runnable clear) {
        clears.add(clear);
    }

    /** Bind the first dimension: the swaps alone, since there is nothing yet to drain into or clear for. */
    void bind(D dimension) {
        for (Consumer<D> swap : swaps) {
            swap.accept(dimension);
        }
    }

    /** Follow the player into {@code dimension}: drain what is being left, swap, then clear. */
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
