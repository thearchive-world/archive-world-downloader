// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.journeymap;

import journeymap.client.api.ClientPlugin;
import journeymap.client.api.IClientAPI;
import journeymap.client.api.IClientPlugin;
import journeymap.client.api.event.ClientEvent;

import world.thearchive.wdl.Wdl;

@SuppressWarnings("deprecation")
@ClientPlugin
public final class WdlJourneyMapPlugin implements IClientPlugin {
    private final JourneyMapOverlayDriver driver = new JourneyMapOverlayDriver();

    public WdlJourneyMapPlugin() {
        // Public no-arg constructor is a JourneyMap discovery requirement.
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
