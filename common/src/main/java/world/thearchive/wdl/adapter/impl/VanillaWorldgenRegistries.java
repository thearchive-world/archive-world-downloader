// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import org.jspecify.annotations.Nullable;

final class VanillaWorldgenRegistries {
    private static final Supplier<RegistryAccess.Frozen> defaultLoader = VanillaWorldgenRegistries::load;

    private static RegistryAccess.@Nullable Frozen worldgen;

    private static Supplier<RegistryAccess.Frozen> loader = defaultLoader;

    private static int loadCount;

    private VanillaWorldgenRegistries() {}

    static synchronized RegistryAccess.Frozen get() {
        RegistryAccess.Frozen cached = worldgen;
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

    static synchronized void resetForTesting(Supplier<RegistryAccess.Frozen> testLoader) {
        worldgen = null;
        loadCount = 0;
        loader = testLoader;
    }

    static synchronized int loadCountForTesting() {
        return loadCount;
    }

    private static RegistryAccess.Frozen load() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        return RegistryAccess.builtinCopy().freeze();
    }
}
