// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public final class TestRegistries {
    private static boolean bootstrapped;

    private TestRegistries() {}

    /** Run the vanilla bootstrap once, populating the static built-in registries the tests read. */
    public static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        SharedConstants.getCurrentVersion();
        Bootstrap.bootStrap();
        bootstrapped = true;
    }
}
