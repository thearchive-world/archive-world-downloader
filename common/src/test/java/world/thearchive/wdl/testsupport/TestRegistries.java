// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.tags.TagManager;

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

        PackRepository packs = new PackRepository(new ServerPacksSource());
        packs.reload();
        packs.setSelected(List.of(VANILLA_PACK_ID));
        List<PackResources> openPacks = packs.openAllSelected();
        CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, openPacks);

        LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
        RegistryAccess.Frozen loadingBase = layered.getAccessForLoading(RegistryLayer.WORLDGEN);
        RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, loadingBase,
                RegistryDataLoader.WORLDGEN_REGISTRIES);
        RegistryAccess.Frozen composite = layered.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();

        composite.registries().forEach(entry -> bindRegistryTags(entry, resources));

        frozen = composite;
        return frozen;
    }

    private static <T> void bindRegistryTags(RegistryAccess.RegistryEntry<T> entry, ResourceManager resources) {
        ResourceKey<? extends Registry<T>> key = entry.key();
        Registry<T> registry = entry.value();
        TagLoader<Holder<T>> loader = new TagLoader<>(
                location -> registry.getHolder(ResourceKey.create(key, location)), TagManager.getTagDir(key));
        Map<TagKey<T>, List<Holder<T>>> bound = loader.loadAndBuild(resources).entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(tag -> TagKey.create(key, tag.getKey()),
                        tag -> List.copyOf(tag.getValue())));
        registry.bindTags(bound);
    }
}
