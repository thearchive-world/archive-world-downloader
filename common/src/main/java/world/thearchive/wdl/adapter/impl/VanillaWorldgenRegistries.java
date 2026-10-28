// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import org.jspecify.annotations.Nullable;

final class VanillaWorldgenRegistries {
    private static final Supplier<RegistryAccess> defaultLoader = VanillaWorldgenRegistries::load;

    private static @Nullable RegistryAccess worldgen;

    private static Supplier<RegistryAccess> loader = defaultLoader;

    private static int loadCount;

    private VanillaWorldgenRegistries() {}

    static synchronized RegistryAccess get() {
        RegistryAccess cached = worldgen;
        if (cached == null) {
            loadCount++;
            cached = loader.get();
            worldgen = cached;
        }
        return cached;
    }

    static synchronized void resetForTesting() {
        resetForTesting(defaultLoader);
    }

    static synchronized void resetForTesting(Supplier<RegistryAccess> testLoader) {
        worldgen = null;
        loadCount = 0;
        loader = testLoader;
    }

    static synchronized int loadCountForTesting() {
        return loadCount;
    }

    private static RegistryAccess load() {
        SharedConstants.getCurrentVersion();
        Bootstrap.bootStrap();
        return RegistryAccess.builtin();
    }
}
