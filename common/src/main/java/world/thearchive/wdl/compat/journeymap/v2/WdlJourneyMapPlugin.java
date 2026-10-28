// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.journeymap.v2;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;

import world.thearchive.wdl.Wdl;

@JourneyMapPlugin(apiVersion = "2.0.0")
public final class WdlJourneyMapPlugin implements IClientPlugin {
    private final JourneyMapOverlayDriver driver = new JourneyMapOverlayDriver();

    public WdlJourneyMapPlugin() {
        // A public no-arg constructor is a JourneyMap discovery requirement.
    }

    @Override
    public void initialize(IClientAPI jmClientApi) {
        driver.setApi(jmClientApi);
        driver.subscribeMappingEvents();
        Wdl.runWhenReady(driver::wireOnce);
    }

    @Override
    public String getModId() {
        return "wdl";
    }
}
