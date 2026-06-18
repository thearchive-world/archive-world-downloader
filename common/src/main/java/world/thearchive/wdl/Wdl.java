// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import com.mojang.logging.LogUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import world.thearchive.wdl.platform.PlatformBridge;

/**
 * The mod's loader-agnostic entry point. Each loader's own entrypoint constructs its {@link PlatformBridge} and hands
 * it here, so everything above this line is written once and knows nothing about which loader is running.
 */
public final class Wdl {
    public static final String MOD_ID = "wdl";

    private static final Logger LOGGER = LogUtils.getLogger();

    private static @Nullable PlatformBridge bridge;

    private Wdl() {}

    /** Called once by the running loader's client entrypoint, with that loader's bridge. */
    public static void initialize(PlatformBridge platformBridge) {
        bridge = platformBridge;
        LOGGER.info("Archive World Downloader {} on {} {}", platformBridge.modVersion(),
                platformBridge.loaderName(), platformBridge.loaderVersion());
    }

    /** The running loader's bridge. Throws if read before {@link #initialize}, which is a wiring bug. */
    public static PlatformBridge platform() {
        PlatformBridge current = bridge;
        if (current == null) {
            throw new IllegalStateException("Wdl.initialize has not run");
        }
        return current;
    }
}
