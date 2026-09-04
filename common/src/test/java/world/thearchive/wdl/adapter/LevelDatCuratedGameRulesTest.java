// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.GameRules;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.CuratedGameRule;
import world.thearchive.wdl.core.SettingsLayout;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The curated game-rule set surfaced across the SPI for the settings menu: each curated safe rule with its curated
 * (safe) value and the two toggle-position values, computed from the live {@code GameRules} so the menu never
 * re-hardcodes a band-specific set. The band-neutral order carries rows whose rule a band may not have, and the menu
 * skips those order slots.
 */
class LevelDatCuratedGameRulesTest {
    /**
     * The band ids of the curated superset, restated rather than read back from the plug so the expected count stays
     * independent of the value under test.
     */
    private static final Set<String> CURATED_BAND_RULE_IDS = ImmutableSet.of("doMobSpawning", "doVinesSpread",
            "doDaylightCycle", "doWeatherCycle", "keepInventory", "mobGriefing", "doWardenSpawning",
            "doTraderSpawning", "doPatrolSpawning");

    private Map<String, CuratedGameRule> curated() {
        TestRegistries.bootstrap(); // bootstrap the vanilla registries so a default GameRules can be built
        Map<String, CuratedGameRule> byId = new LinkedHashMap<>();
        for (CuratedGameRule rule : new LevelDataWriterImpl().curatedGameRules()) {
            byId.put(rule.id(), rule);
        }
        return byId;
    }

    private static int curatedRulesLiveAtThisBand() {
        GameRules gameRules = new GameRules();
        int live = 0;
        for (String bandId : CURATED_BAND_RULE_IDS) {
            if (gameRules.hasRule(bandId)) {
                live++;
            }
        }
        return live;
    }

    @Test
    void surfacesEveryCuratedRuleThisBandHasById() {
        Map<String, CuratedGameRule> byId = curated();
        assertEquals(curatedRulesLiveAtThisBand(), byId.size());
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
        // fire_spread_radius_around_player is laid out but curated on no band, not merely absent at this one.
        Set<String> absentAtThisBand = ImmutableSet.of("fire_spread_radius_around_player", "spread_vines",
                "advance_weather", "spawn_wardens", "spawn_wandering_traders", "spawn_patrols");
        for (String id : SettingsLayout.GAME_RULE_ORDER) {
            if (!byId.containsKey(id)) {
                assertTrue(absentAtThisBand.contains(id),
                        id + " is laid out but has no curated rule at this band");
            }
        }
    }
}
