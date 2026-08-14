// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
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

/**
 * The full vanilla worldgen {@link RegistryAccess} (noise settings, density functions, biomes, structure sets, placed
 * features, world presets), which a multiplayer client never receives: the server syncs only the gameplay registries,
 * so a DEFAULT/FLAT download must reconstruct worldgen itself to build a real generator.
 *
 * <p>Loaded once from the jar-embedded vanilla data pack ({@code ServerPacksSource} -&gt;
 * {@code RegistryDataLoader.load}), then memoized: the load is expensive and the frozen access is immutable, so
 * building it once per client session is correct and cheap thereafter. Only the opt-in DEFAULT/FLAT path pays it; the
 * default VOID download uses the synced client registries and never touches this.
 */
final class VanillaWorldgenRegistries {
    /** The base pack of every default world; selecting exactly it yields the standard vanilla registry set. */
    private static final String VANILLA_PACK_ID = "vanilla";

    private static final Supplier<RegistryAccess.Frozen> defaultLoader = VanillaWorldgenRegistries::load;

    private static RegistryAccess.@Nullable Frozen worldgen;

    // The reconstruction source, indirected only so a test can substitute a throwing or latching loader (the
    // real decode can be neither failed nor stalled mid-flight); production is always the real load.
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

    /** Test seam: drop the memo back to cold on the real loader. */
    static synchronized void resetForTesting() {
        resetForTesting(defaultLoader);
    }

    /** Test seam: drop the memo back to cold on a substitute loader (a throwing or latching reconstruction). */
    static synchronized void resetForTesting(Supplier<RegistryAccess.Frozen> testLoader) {
        worldgen = null;
        loadCount = 0;
        loader = testLoader;
    }

    /** Test seam: how many reconstruction attempts the memo has made since the last reset. */
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

        // The loaded registries are decoded eagerly into memory, so the resource manager is only needed during
        // the load and can be closed once the composite access is built.
        try (CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, openPacks)) {
            LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
            // Unlike WorldLoader, the STATIC-layer tags are deliberately left unbound: worldgen codecs store tag
            // references as lazy TagKeys, and this access only builds the presets and encodes WorldGenSettings by
            // id, never generates terrain, so binding block/item tags would be needless work.
            RegistryAccess.Frozen loadingBase = layered.getAccessForLoading(RegistryLayer.WORLDGEN);
            RegistryAccess.Frozen loaded = RegistryDataLoader.load(resources, loadingBase,
                    RegistryDataLoader.WORLDGEN_REGISTRIES);
            return layered.replaceFrom(RegistryLayer.WORLDGEN, loaded).compositeAccess();
        }
    }
}
