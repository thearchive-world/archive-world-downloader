// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.entity.Entity;
import net.minecraft.inventory.Container;
import org.jspecify.annotations.Nullable;

public abstract class MountMenuReader {
    private static volatile @Nullable MountMenuReader installed;

    /** Publish this loader's reader. */
    public static void install(MountMenuReader reader) {
        installed = reader;
    }

    /**
     * The mount {@code menu} names, or {@code null} when it is not a mount-inventory menu.
     *
     * <p>Null is also what an uninstalled reader returns, and that case does not drop the open: it falls through to the
     * vehicle or block bind, where the slot-count match is the only thing left between it and a bind, and a
     * strength-one chested llama's menu is five non-player slots, exactly a hopper's and a brewing stand's. Treat the
     * uninstalled state as a real hazard rather than a safe no-op.
     */
    static @Nullable Entity mountOf(Container menu) {
        MountMenuReader reader = installed;
        return reader != null ? reader.mount(menu) : null;
    }

    /** The menu's mount. */
    protected abstract @Nullable Entity mount(Container menu);
}
