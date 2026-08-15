// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.journeymap;

import journeymap.client.api.ClientPlugin;
import journeymap.client.api.IClientAPI;
import journeymap.client.api.IClientPlugin;
import journeymap.client.api.event.ClientEvent;

import world.thearchive.wdl.Wdl;

/**
 * JourneyMap discovers this through the {@code journeymap} entrypoint (Fabric) or the {@code ClientPlugin}-annotated
 * classpath scan (NeoForge) only when it is installed, so it never loads without JourneyMap present; the JourneyMap API
 * is compile-only and never ships.
 */
// ClientPlugin is deprecated by JourneyMap in favor of the Fabric entrypoint, but it is the NeoForge discovery scan.
@SuppressWarnings("deprecation")
@ClientPlugin
public final class WdlJourneyMapPlugin implements IClientPlugin {
    private final JourneyMapOverlayDriver driver = new JourneyMapOverlayDriver();

    public WdlJourneyMapPlugin() {
        // Public no-arg constructor is a JourneyMap discovery requirement, and JourneyMap runs it twice: the
        // entrypoint scan builds one instance only to read its class and drops it, then the plugin registry
        // builds the instance it keeps. Only the second is ever initialized, so construction must stay free of
        // side effects; anything eager here leaks on the copy that is thrown away. Once-only work goes below.
    }

    @Override
    public void initialize(IClientAPI jmClientApi) {
        driver.initialize(jmClientApi);
        Wdl.runWhenReady(driver::wireOnce);
    }

    @Override
    public String getModId() {
        return "wdl";
    }

    @Override
    public void onEvent(ClientEvent event) {
        driver.onClientEvent(event);
    }
}
