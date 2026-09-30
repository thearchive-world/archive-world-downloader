// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ConfigTextBandNeutralityTest {
    @Test
    void noConfigCommentNamesAnyMinecraftVersion() {
        Pattern version = Pattern.compile("\\b(1\\.(?:[7-9]|1\\d|2\\d)(?:\\.\\d+)?|2[5-9]\\.\\d+(?:\\.\\d+)?)\\b");
        assertTrue(version.matcher("map locking arrived in 1.14").find(),
                "the pattern must catch a bare version, or this test passes vacuously");
        assertFalse(version.matcher("0.5 to 4.0; 1.0 matches the outline; 5 to 60 s; 1 to 256").find(),
                "the pattern must admit the ranges and defaults a preamble legitimately carries");
        for (String line : ConfigSchema.renderConfigFile(WdlConfig.DEFAULTS).split("\n")) {
            if (line.startsWith("#")) {
                assertFalse(version.matcher(line).find(),
                        "this line ships to every band, so it cannot name one Minecraft version: " + line);
            }
        }
    }
}
