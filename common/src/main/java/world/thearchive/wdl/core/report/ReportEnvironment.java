// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

/**
 * The server- and software-context facts known when a download begins: the server's brand (its in-session software
 * identity), simulation distance, the captured dimension's short path, and the MC and mod versions. The advertised
 * server version is deliberately not carried: it is populated only by the server-list ping and falls back to the
 * client's own version on a direct connect, so it is not a reliable server fact. MC-free and Java-8-clean; the few
 * client facts arrive as plain strings through the adapter. A null server brand is mapped to the empty string at the
 * adapter boundary, so every field here is non-null.
 */
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
