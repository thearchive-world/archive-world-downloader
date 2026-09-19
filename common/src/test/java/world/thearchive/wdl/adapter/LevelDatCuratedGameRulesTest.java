// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.CuratedGameRule;
import world.thearchive.wdl.core.SettingsLayout;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The curated game-rule set surfaced across the SPI for the settings menu: the curated safe rules (six on this band),
 * each with its curated (safe) value and the two toggle-position values, computed from the live {@code GameRules} so
 * the menu never re-hardcodes a band-specific set. The band-neutral order carries rows with no curated rule on this
 * band (vine-spread, warden-spawn, wandering-trader-spawn and patrol-spawn), whose order slots the menu skips.
 */
class LevelDatCuratedGameRulesTest {
    private Map<String, CuratedGameRule> curated() {
        TestRegistries.bootstrap(); // bootstrap the vanilla registries so a default GameRules can be built
        Map<String, CuratedGameRule> byId = new LinkedHashMap<>();
        for (CuratedGameRule rule : new LevelDataWriterImpl().curatedGameRules()) {
            byId.put(rule.id(), rule);
        }
        return byId;
    }

    @Test
    void surfacesTheSixCuratedRulesById() {
        // Four of the ten band-neutral curated specs have no rule on this band (doVinesSpread, doWardenSpawning,
        // doTraderSpawning and doPatrolSpawning are all later additions), so the runtime filter drops them and six
        // surface.
        Map<String, CuratedGameRule> byId = curated();
        assertEquals(6, byId.size());
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
    void everyCuratedRuleIsLaidOutAndOnlyTheBandAbsentRuleIsSkipped() {
        Map<String, CuratedGameRule> byId = curated();
        for (String id : byId.keySet()) {
            assertTrue(SettingsLayout.GAME_RULE_ORDER.contains(id), id + " is curated but not laid out as a row");
        }
        // On this band four laid-out rows have no curated rule: vine-spread, warden-spawn, wandering-trader-spawn and
        // patrol-spawn, all later additions.
        Set<String> absentAtThisBand = ImmutableSet.of("spread_vines", "spawn_wardens", "spawn_wandering_traders",
                "spawn_patrols");
        for (String id : SettingsLayout.GAME_RULE_ORDER) {
            if (!byId.containsKey(id)) {
                assertTrue(absentAtThisBand.contains(id),
                        id + " is laid out but has no curated rule at this band");
            }
        }
    }

    @Test
    void booleanFireRuleBindsDoFireTickAndTogglesTrueFalse() {
        CuratedGameRule fire = curated().get("fire_spread_radius_around_player");
        assertEquals("doFireTick", fire.bandId(), "the band-neutral fire-spread row binds this band's boolean rule");
        assertEquals("false", fire.curatedValue(), "fire spread is curated off");
        assertEquals("true", fire.enabledValue());
        assertEquals("false", fire.disabledValue());
    }
}
