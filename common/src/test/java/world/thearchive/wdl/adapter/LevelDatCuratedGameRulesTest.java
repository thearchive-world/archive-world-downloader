// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.CuratedGameRule;
import world.thearchive.wdl.core.SettingsLayout;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The curated game-rule set surfaced across the SPI for the settings menu: the curated safe rules (nine at 1.21.4),
 * each with its curated (safe) value and the two toggle-position values, computed from the live {@code GameRules} so
 * the menu never re-hardcodes a band-specific set. The band-neutral fire-spread row has no 1.21.4 rule, so the menu
 * skips its order slot.
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
    void surfacesTheNineCuratedRulesById() {
        Map<String, CuratedGameRule> byId = curated();
        assertEquals(9, byId.size());
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
        for (String id : SettingsLayout.GAME_RULE_ORDER) {
            if (!byId.containsKey(id)) {
                assertEquals("fire_spread_radius_around_player", id,
                        id + " is laid out but has no curated rule at this band");
            }
        }
    }
}
