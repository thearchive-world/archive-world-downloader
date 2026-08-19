// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;

/**
 * Headless vanilla {@link RegistryAccess} for plain JUnit tests.
 *
 * <p>There is no game running here. After the idempotent vanilla bootstrap populates the static built-in registries
 * (blocks, items, ...), {@code RegistryAccess.builtin} assembles the code-defined dynamic registries the chunk codec
 * and level.dat writer read: the dimension types (via {@code DimensionType.registerBuiltin}) and the biomes and the
 * rest of worldgen. It deliberately carries no LEVEL_STEM, mirroring a real multiplayer client, which is derived from a
 * world preset where it is needed.
 *
 * <p>The result is memoized: the vanilla bootstrap is idempotent but expensive, and the frozen access is immutable, so
 * it is built once per JVM.
 */
public final class TestRegistries {
    private static RegistryAccess frozen;

    private TestRegistries() {}

    /** The composite static + worldgen registry access, built once per JVM. */
    public static synchronized RegistryAccess frozen() {
        if (frozen != null) {
            return frozen;
        }
        SharedConstants.getCurrentVersion();
        Bootstrap.bootStrap();
        frozen = RegistryAccess.builtin();
        return frozen;
    }
}
