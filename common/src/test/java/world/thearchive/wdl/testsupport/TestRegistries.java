// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.server.Bootstrap;

/**
 * Headless vanilla registry bootstrap for plain JUnit tests.
 *
 * <p>There is no game running here. At 1.13.2 the registries the chunk codec and level.dat writer read are the static
 * built-in {@code net.minecraft.core.Registry} tables (blocks, items, biomes), populated by the idempotent vanilla
 * bootstrap; there is no composite {@code RegistryAccess} at this band (that is the 1.16 rework). So this only runs the
 * bootstrap, and a test that needs a block or biome reads the static {@code net.minecraft.core.Registry} tables
 * directly.
 *
 * <p>At 1.13.2 {@link Bootstrap#bootStrap()} is self-standing: {@code SharedConstants} carries no version accessor to
 * initialize first (the detected-version dependency the bootstrap gained is a later band). The bootstrap is idempotent
 * but expensive, so it is run once per JVM.
 */
public final class TestRegistries {
    private static boolean bootstrapped;

    private TestRegistries() {}

    /** Run the vanilla bootstrap once, populating the static built-in registries the tests read. */
    public static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        Bootstrap.bootStrap();
        bootstrapped = true;
    }
}
