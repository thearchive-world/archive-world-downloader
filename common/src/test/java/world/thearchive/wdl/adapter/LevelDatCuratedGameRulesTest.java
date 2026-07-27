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
 * The curated game-rule set surfaced across the SPI for the settings menu: the ten reference-band rules, each with its
 * curated (safe) value and the two toggle-position values, computed from the live {@code GameRules} so the menu never
 * re-hardcodes a band-specific set. The lone integer rule (fire spread) carries the band's real default as its enabled
 * value rather than a boolean.
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
    void surfacesTheTenCuratedRulesById() {
        Map<String, CuratedGameRule> byId = curated();
        assertEquals(10, byId.size());
        assertTrue(byId.containsKey("keep_inventory"));
        assertTrue(byId.containsKey("spawn_mobs"));
        assertTrue(byId.containsKey("fire_spread_radius_around_player"));
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
    void everyLaidOutGameRuleRowMapsToCuratedRule() {
        Map<String, CuratedGameRule> byId = curated();
        assertEquals(byId.size(), SettingsLayout.GAME_RULE_ORDER.size(), "the row order covers the whole curated set");
        for (String id : SettingsLayout.GAME_RULE_ORDER) {
            assertTrue(byId.containsKey(id), id + " is laid out as a row but is not in the curated set");
        }
    }

    @Test
    void integerFireRuleDisablesToZeroAndEnablesToTheBandDefault() {
        CuratedGameRule fire = curated().get("fire_spread_radius_around_player");
        assertEquals("0", fire.curatedValue(), "fire spread is curated off (radius 0)");
        assertEquals("0", fire.disabledValue(), "the off position writes radius 0");
        assertEquals("128", fire.enabledValue(),
                "the on position writes the band's vanilla fire-spread radius (128 at 1.21.11)");
    }
}
