// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;

public final class TestRegistries {
    private static RegistryAccess.Frozen frozen;

    private TestRegistries() {}

    /** The composite static + worldgen registry access, built once per JVM. */
    public static synchronized RegistryAccess.Frozen frozen() {
        if (frozen != null) {
            return frozen;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        frozen = RegistryAccess.builtinCopy().freeze();
        return frozen;
    }
}
