// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The settings screen's layout, the single source the render walks: the three tabs, their sections, and each section's
 * ordered rows. Verified against the descriptor so the two never drift, and pinned on the two orderings the render
 * cannot re-derive from the descriptor (masters lead their gated sections) and the lone gamerule group.
 */
class SettingsLayoutTest {
    private static List<String> allRowKeys() {
        List<String> keys = new ArrayList<>();
        for (SettingsLayout.Tab tab : SettingsLayout.TABS) {
            for (SettingsLayout.Section section : tab.sections()) {
                keys.addAll(section.optionKeys());
            }
        }
        return keys;
    }

    @Test
    void theThreeTabsAreInterfaceWorldDownloadInOrder() {
        List<String> ids = new ArrayList<>();
        for (SettingsLayout.Tab tab : SettingsLayout.TABS) {
            ids.add(tab.id());
        }
        assertEquals(List.of("interface", "world", "download"), ids);
    }

    @Test
    void everyRowBindsToRealDescriptorOption() {
        for (String key : allRowKeys()) {
            ConfigSchema.option(key); // fails fast if the row names a key the descriptor does not carry
        }
    }

    @Test
    void everyDescriptorOptionHasExactlyOneRow() {
        List<String> rows = allRowKeys();
        Set<String> unique = new LinkedHashSet<>(rows);
        assertEquals(rows.size(), unique.size(), "no option is laid out twice");

        for (ConfigOption option : ConfigSchema.OPTIONS) {
            assertTrue(unique.contains(option.key()), option.key() + " must have a settings row");
        }
        assertEquals(ConfigSchema.OPTIONS.size(), unique.size(), "every option is a row");
    }

    @Test
    void exactlyOneSectionIsTheGameRuleGroupAndItLeadsWithTheMaster() {
        SettingsLayout.Section gamerules = null;
        int count = 0;
        for (SettingsLayout.Tab tab : SettingsLayout.TABS) {
            for (SettingsLayout.Section section : tab.sections()) {
                if (section.isGameRuleGroup()) {
                    gamerules = section;
                    count++;
                }
            }
        }
        assertEquals(1, count, "the dynamic curated game-rule rows attach to exactly one section");
        assertEquals("overrideGamerules", gamerules.optionKeys().get(0), "the master leads the gamerule section");
    }

    @Test
    void gatedSectionsLeadWithTheirMaster() {
        // The world-defaults master follows openInCreative in the descriptor's template order, yet its section
        // must render it first; the same holds for the outline master over the container outline rows.
        assertEquals("overrideWorldDefaults", sectionContaining("openInCreative").get(0),
                "the world-defaults master leads its section");
        assertEquals("renderUnsavedOutline", sectionContaining("outlineDistance").get(0),
                "the outline master leads the container outline section");
    }

    private static List<String> sectionContaining(String key) {
        for (SettingsLayout.Tab tab : SettingsLayout.TABS) {
            for (SettingsLayout.Section section : tab.sections()) {
                if (section.optionKeys().contains(key)) {
                    return section.optionKeys();
                }
            }
        }
        throw new AssertionError("no section contains " + key);
    }

    @Test
    void everyRowMasterBindsRealRowsToBooleanMasters() {
        for (Map.Entry<String, String> entry : SettingsLayout.ROW_MASTER.entrySet()) {
            ConfigSchema.option(entry.getKey()); // the gated row is a real option (of any value type)
            assertEquals(ConfigType.BOOLEAN, ConfigSchema.option(entry.getValue()).type(),
                    entry.getValue() + " gates rows, so it must be a boolean toggle");
        }
    }

    @Test
    void everyRowMasterPrecedesTheRowsItGates() {
        // A master need not lead a section, but it must render above every row it grays so the dependency
        // reads top down: the outline and HUD masters lead their sections, while the coverage master leads
        // its own Chunk Overlay section above the two hue rows it grays.
        List<String> order = allRowKeys();
        for (Map.Entry<String, String> entry : SettingsLayout.ROW_MASTER.entrySet()) {
            String gated = entry.getKey();
            String master = entry.getValue();
            int masterAt = order.indexOf(master);
            int gatedAt = order.indexOf(gated);
            assertTrue(masterAt >= 0 && masterAt < gatedAt,
                    master + " must be laid out above the row it grays (" + gated + ")");
        }
    }

    @Test
    void theCoverageHuesGrayWithTheirOwnMasterNotTheOutlineMaster() {
        // The map coverage overlay is independent of the in-world container outline: both tone hues sit in the
        // chunk overlay section but gray with the coverage master, while the outline rows gray with the outline master.
        assertEquals("renderCoverageOverlay", SettingsLayout.masterKey("overlayCoveredColor"),
                "the covered hue grays with the coverage overlay master");
        assertEquals("renderCoverageOverlay", SettingsLayout.masterKey("overlaySuspectColor"),
                "the suspect hue grays with the coverage overlay master");
        assertEquals("renderUnsavedOutline", SettingsLayout.masterKey("outlineDistance"),
                "the outline rows gray with the outline master");
    }

    @Test
    void chunkOverlayRowsRequireEitherMapMod() {
        Set<String> mods = SettingsLayout.requiredMods("renderCoverageOverlay");
        assertTrue(mods.contains("xaeroplus"));
        assertTrue(mods.contains("journeymap"));
        assertEquals(mods, SettingsLayout.requiredMods("overlayCoveredColor"));
        assertEquals(mods, SettingsLayout.requiredMods("overlaySuspectColor"));
    }
}
