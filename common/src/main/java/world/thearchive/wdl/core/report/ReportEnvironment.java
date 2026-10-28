// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

public final class ReportEnvironment {
    private final String serverBrand;
    private final int simulationDistance;
    private final String dimensionName;
    private final String minecraftVersion;
    private final String modVersion;

    public ReportEnvironment(String serverBrand, int simulationDistance, String dimensionName,
            String minecraftVersion, String modVersion) {
        this.serverBrand = serverBrand;
        this.simulationDistance = simulationDistance;
        this.dimensionName = dimensionName;
        this.minecraftVersion = minecraftVersion;
        this.modVersion = modVersion;
    }

    public String serverBrand() {
        return serverBrand;
    }

    public int simulationDistance() {
        return simulationDistance;
    }

    public String dimensionName() {
        return dimensionName;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public String modVersion() {
        return modVersion;
    }
}
