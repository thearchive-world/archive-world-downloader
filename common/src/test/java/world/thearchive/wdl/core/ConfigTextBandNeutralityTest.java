// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the one mechanically checkable half of the rule that text shipping byte-identical to every band names only
 * what every supported version has. The config file's comments reach a player as the explanation of what a setting
 * does, and the same bytes reach every band, so a Minecraft version named in one of them is false wherever it does not
 * apply. That class of defect has been introduced and re-introduced by hand while wording these very lines, and a
 * reviewer catching it months later is a worse outcome than a red build catching it on the commit that writes it.
 *
 * <p>Only the version half is checkable here. A sentence can still name a block some version lacks without naming a
 * number, which stays a reading job.
 */
class ConfigTextBandNeutralityTest {
    /**
     * The pattern matches only versions this project ships and deliberately admits the numbers a preamble legitimately
     * carries, a slider range or a default among them. The two control assertions run first because a check nobody has
     * watched fail proves nothing: they pin both directions of the pattern before it is trusted.
     */
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
