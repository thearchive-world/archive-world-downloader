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

/**
 * Headless vanilla {@link RegistryAccess} for plain JUnit tests.
 *
 * <p>There is no game running here. Every call below is a static method on a vanilla class that MDG puts on the
 * {@code common} test classpath (along with the bundled vanilla data pack). The sequence mirrors
 * {@code net.minecraft.server.WorldLoader#load} up to the WORLDGEN layer, which is all the chunk codec needs: the
 * STATIC layer (built-in registries: blocks, etc., from {@link Bootstrap#bootStrap()}) composed with the dynamic
 * WORLDGEN registries (biomes, ...) loaded from the vanilla JSONs. We deliberately stop before
 * {@code DIMENSION_REGISTRIES}; {@code LEVEL_STEM} is derived from a world preset where it is needed.
 *
 * <p>The result is memoized: the vanilla bootstrap is idempotent but expensive, and the frozen access is immutable, so
 * it is built once per JVM.
 */
public final class TestRegistries {
    /**
     * The stable id of the vanilla data pack ({@code PackLocationInfo("vanilla", ...)} in {@code ServerPacksSource}).
     * It is the base pack of every default world, selecting exactly it gives the standard vanilla registry set without
     * the opt-in experimental datapacks
     * ({@code minecart_improvements}/{@code redstone_experiments}/{@code trade_rebalance}).
     */
    private static final String VANILLA_PACK_ID = "vanilla";

    private static RegistryAccess.Frozen frozen;

    private TestRegistries() {}

    /** The composite STATIC + WORLDGEN registry access, built once per JVM. */
    public static synchronized RegistryAccess.Frozen frozen() {
        if (frozen != null) {
            return frozen;
        }

        // Detect the bundled MC version and populate the static BuiltInRegistries (idempotent).
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // Build the vanilla SERVER_DATA resource manager from the trusted (jar-embedded) data pack.
        // reload() discovers the available packs but only auto-selects packs whose isRequired() is
        // true. The vanilla pack reports false in this headless context (in-game,
        // MinecraftServer.configurePackRepository selects it), so without an explicit selection
        // openAllSelected() is empty and every data-driven registry loads empty. Select the vanilla
        // base pack so its PackResources (carrying the biome/cat_variant/... JSONs) are opened.
        PackRepository packs = ServerPacksSource.createVanillaTrustedRepository();
        packs.reload();
        packs.setSelected(List.of(VANILLA_PACK_ID));
        List<PackResources> openPacks = packs.openAllSelected();
        CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, openPacks);

        // Mirror WorldLoader: load tags against the STATIC layer, then load the WORLDGEN registries
        // (the dynamic ones, biomes are not in BuiltInRegistries) and compose them over STATIC.
        LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
        List<Registry.PendingTags<?>> pendingTags = TagLoader.loadTagsForExistingRegistries(resources,
                layered.getLayer(RegistryLayer.STATIC));
        RegistryAccess.Frozen loadingBase = layered.getAccessForLoading(RegistryLayer.WORLDGEN);
        List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(loadingBase, pendingTags);
        // 26.x RegistryDataLoader.load is asynchronous: it takes an Executor and returns a future. Run it on the
        // calling thread and join, keeping this bootstrap synchronous.
        RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, lookups,
                RegistryDataLoader.WORLDGEN_REGISTRIES, Runnable::run).join();

        // Bind the loaded tags onto the STATIC registries (the updateStaticRegistryTags step WorldLoader runs
        // last via ReloadableServerResources), so ItemStack.is(tag) resolves against BuiltInRegistries headless.
        pendingTags.forEach(Registry.PendingTags::apply);

        // Keep resources open (as WorldLoader does): the composite is the long-lived result.
        frozen = layered.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();
        return frozen;
    }
}
