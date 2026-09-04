// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.init.Bootstrap;

/**
 * Headless vanilla registry bootstrap for plain JUnit tests.
 *
 * <p>There is no game running here. At this band the registries the chunk codec and level.dat writer read are the
 * static built-in {@code net.minecraft.util.registry.RegistryNamespaced} tables (blocks, items, biomes), each populated
 * by its own class's static initializer; there is no composite registry-access object at this band. So this only runs
 * the vanilla bootstrap, and a test that needs a block or item reads the static {@code Block.REGISTRY}/{@code
 * Item.REGISTRY} tables directly.
 *
 * <p>{@link Bootstrap#register()} is idempotent (guarded by its own {@code alreadyRegistered} flag) but expensive, so
 * it is run once per JVM.
 */
public final class TestRegistries {
    private static boolean bootstrapped;

    private TestRegistries() {}

    /** Run the vanilla bootstrap once, populating the static built-in registries the tests read. */
    public static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        Bootstrap.register();
        bootstrapped = true;
    }
}
