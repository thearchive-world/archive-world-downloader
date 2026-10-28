// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.xaeroplus;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import world.thearchive.wdl.platform.PlatformBridge;

/**
 * Holds no {@code xaeroplus.*} reference, so a JVM without XaeroPlus never resolves one: the first (and only) touch of
 * an {@code xaeroplus.*} type is inside {@link XaeroPlusBinding}, reached only after the
 * {@link PlatformBridge#isModLoaded} gate passes.
 */
public final class XaeroPlusIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();

    private XaeroPlusIntegration() {}

    /** A silent no-op when XaeroPlus is absent. */
    public static void initialize(PlatformBridge platform) {
        if (!platform.isModLoaded("xaeroplus")) {
            return;
        }
        // The isModLoaded gate is version-blind, and the draw-feature API XaeroPlusBinding names
        // (DrawFeatureFactory.multiColorAsyncChunkHighlights, Globals.drawManager) is specific to the XaeroPlus era
        // this was built against. A user on an older or divergent XaeroPlus links this call to a missing class, method,
        // or field: catch that here so the overlay disables for the session rather than the LinkageError escaping to
        // the loader entrypoint and crashing the client at startup. RuntimeException covers a present-but-uncooperative
        // registry.
        try {
            XaeroPlusBinding.register();
        } catch (LinkageError | RuntimeException e) {
            LOGGER.warn("XaeroPlus is present but its draw-feature API did not resolve; "
                    + "the wdl coverage overlay is disabled for this session", e);
        }
    }
}
