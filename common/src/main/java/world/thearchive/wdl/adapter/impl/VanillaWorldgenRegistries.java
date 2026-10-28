// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.jspecify.annotations.Nullable;

final class VanillaWorldgenRegistries {
    private static final String VANILLA_PACK_ID = "vanilla";

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

        PackRepository packs = ServerPacksSource.createVanillaTrustedRepository();
        packs.reload();
        packs.setSelected(List.of(VANILLA_PACK_ID));
        List<PackResources> openPacks = packs.openAllSelected();

        try (CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, openPacks)) {
            LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
            // Unlike WorldLoader, this loads no STATIC-layer tags: that layer is the client's own built-in registries,
            // whose tags are bound by the time a world is joined, and the reconstruction reads them.
            List<HolderLookup.RegistryLookup<?>> loadingBase = layered.getAccessForLoading(RegistryLayer.WORLDGEN)
                    .listRegistries().toList();
            RegistryAccess.Frozen loaded = RegistryDataLoader.load(resources, loadingBase,
                    RegistryDataLoader.WORLDGEN_REGISTRIES, Runnable::run).join();
            return layered.replaceFrom(RegistryLayer.WORLDGEN, loaded).compositeAccess();
        }
    }
}
