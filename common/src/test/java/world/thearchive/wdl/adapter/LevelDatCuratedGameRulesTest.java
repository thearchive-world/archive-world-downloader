// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.CuratedGameRule;
import world.thearchive.wdl.core.SettingsLayout;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The curated game-rule set surfaced across the SPI for the settings menu: the curated safe rules (seven at 1.18.2),
 * each with its curated (safe) value and the two toggle-position values, computed from the live {@code GameRules} so
 * the menu never re-hardcodes a band-specific set. The band-neutral order carries rows with no rule at 1.18.2
 * (fire-spread, vine-spread and warden-spawn), whose order slots the menu skips.
 */
class LevelDatCuratedGameRulesTest {
    private Map<String, CuratedGameRule> curated() {
        TestRegistries.frozen(); // bootstrap the vanilla registries so a default GameRules can be built
        Map<String, CuratedGameRule> byId = new LinkedHashMap<>();
        for (CuratedGameRule rule : new LevelDataWriterImpl().curatedGameRules()) {
            byId.put(rule.id(), rule);
        }
        return byId;
    }

    @Test
    void surfacesTheSevenCuratedRulesById() {
        // Two of the nine band-neutral curated specs have no rule at 1.18.2 (doVinesSpread and doWardenSpawning are
        // 1.19 additions), so the runtime filter drops them and seven surface.
        Map<String, CuratedGameRule> byId = curated();
        assertEquals(7, byId.size());
        assertTrue(byId.containsKey("keep_inventory"));
        assertTrue(byId.containsKey("spawn_mobs"));
    }

    @Test
    void booleanRuleCarriesTrueFalseTogglesAndItsCuratedValue() {
        Map<String, CuratedGameRule> byId = curated();

        CuratedGameRule keepInventory = byId.get("keep_inventory");
        assertEquals("true", keepInventory.curatedValue(), "keep_inventory is curated on");
        assertEquals("true", keepInventory.enabledValue());
        assertEquals("false", keepInventory.disabledValue());

        CuratedGameRule spawnMobs = byId.get("spawn_mobs");
        assertEquals("false", spawnMobs.curatedValue(), "mob spawning is curated off");
        assertEquals("true", spawnMobs.enabledValue());
        assertEquals("false", spawnMobs.disabledValue());
    }

    @Test
    void everyCuratedRuleIsLaidOutAndOnlyTheBandAbsentRuleIsSkipped() {
        Map<String, CuratedGameRule> byId = curated();
        for (String id : byId.keySet()) {
            assertTrue(SettingsLayout.GAME_RULE_ORDER.contains(id), id + " is curated but not laid out as a row");
        }
        // At 1.18.2 three laid-out rows have no live rule: the band-neutral fire-spread row (no rule at any current
        // band) plus vine-spread and warden-spawn, both 1.19 additions.
        Set<String> absentAtThisBand = Set.of("fire_spread_radius_around_player", "spread_vines", "spawn_wardens");
        for (String id : SettingsLayout.GAME_RULE_ORDER) {
            if (!byId.containsKey(id)) {
                assertTrue(absentAtThisBand.contains(id),
                        id + " is laid out but has no curated rule at this band");
            }
        }
    }
}
