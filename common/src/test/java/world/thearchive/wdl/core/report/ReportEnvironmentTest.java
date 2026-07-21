// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReportEnvironmentTest {
    @Test
    void carriesItsFieldsVerbatim() {
        ReportEnvironment environment = new ReportEnvironment("Paper", 8, "overworld", "1.21.11", "1.0.0");
        assertEquals("Paper", environment.serverBrand());
        assertEquals(8, environment.simulationDistance());
        assertEquals("overworld", environment.dimensionName());
        assertEquals("1.21.11", environment.minecraftVersion());
        assertEquals("1.0.0", environment.modVersion());
    }
}
