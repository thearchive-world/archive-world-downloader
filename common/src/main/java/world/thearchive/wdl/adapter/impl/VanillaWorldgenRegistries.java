// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import org.jspecify.annotations.Nullable;

/**
 * The full vanilla worldgen {@link RegistryAccess} (noise settings, biomes, and the rest of worldgen), which a
 * multiplayer client never receives: the server syncs only the gameplay registries, so a DEFAULT/FLAT download must
 * reconstruct worldgen itself to build a real generator.
 *
 * <p>Built once from the code-defined builtin registries ({@code RegistryAccess.builtin}) after bootstrap, then
 * memoized: the build is expensive and the built access is stable, so building it once per client session is correct
 * and cheap thereafter. Only the opt-in DEFAULT/FLAT path pays it; the default VOID download uses the synced client
 * registries and never touches this.
 */
final class VanillaWorldgenRegistries {
    private static final Supplier<RegistryAccess> defaultLoader = VanillaWorldgenRegistries::load;

    private static @Nullable RegistryAccess worldgen;

    // The reconstruction source, indirected only so a test can substitute a throwing or latching loader (the
    // real decode can be neither failed nor stalled mid-flight); production is always the real load.
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

    /** Test seam: drop the memo back to cold on the real loader. */
    static synchronized void resetForTesting() {
        resetForTesting(defaultLoader);
    }

    /** Test seam: drop the memo back to cold on a substitute loader (a throwing or latching reconstruction). */
    static synchronized void resetForTesting(Supplier<RegistryAccess> testLoader) {
        worldgen = null;
        loadCount = 0;
        loader = testLoader;
    }

    /** Test seam: how many reconstruction attempts the memo has made since the last reset. */
    static synchronized int loadCountForTesting() {
        return loadCount;
    }

    private static RegistryAccess load() {
        SharedConstants.getCurrentVersion();
        Bootstrap.bootStrap();
        // The STATIC-layer tags stay unbound, which is correct here: worldgen codecs store tag references as lazy
        // TagKeys, and this access only builds the presets and encodes WorldGenSettings by id, never generates
        // terrain, so binding block/item tags would be needless.
        return RegistryAccess.builtin();
    }
}
