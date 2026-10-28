// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
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
import net.minecraft.tags.TagLoader;

public final class TestRegistries {
    private static final String VANILLA_PACK_ID = "vanilla";

    private static RegistryAccess.Frozen frozen;

    private TestRegistries() {}

    /** The composite STATIC + WORLDGEN registry access, built once per JVM. */
    public static synchronized RegistryAccess.Frozen frozen() {
        if (frozen != null) {
            return frozen;
        }

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        PackRepository packs = ServerPacksSource.createVanillaTrustedRepository();
        packs.reload();
        packs.setSelected(List.of(VANILLA_PACK_ID));
        List<PackResources> openPacks = packs.openAllSelected();
        CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, openPacks);

        LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
        List<Registry.PendingTags<?>> pendingTags = TagLoader.loadTagsForExistingRegistries(resources,
                layered.getLayer(RegistryLayer.STATIC));
        RegistryAccess.Frozen loadingBase = layered.getAccessForLoading(RegistryLayer.WORLDGEN);
        List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(loadingBase, pendingTags);
        RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, lookups,
                RegistryDataLoader.WORLDGEN_REGISTRIES);

        pendingTags.forEach(Registry.PendingTags::apply);

        frozen = layered.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();
        return frozen;
    }
}
