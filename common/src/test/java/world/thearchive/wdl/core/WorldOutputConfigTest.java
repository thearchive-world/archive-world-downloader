// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The world-output model: the masters, the world-open flags, the two capture knobs, the sparse {@code gamerule.*}
 * override map, and the band-agnostic game-rule merge (master-gate, curated + override merge, id-existence AND
 * value-type validation, unknown-id surfacing). MC-free.
 */
class WorldOutputConfigTest {
    /** A fake band schema: the known rule ids mapped to a primitive type ("bool" or "int"). */
    private static GameRuleSchema schema(Map<String, String> idToType) {
        return new GameRuleSchema() {
            @Override
            public boolean hasRule(String id) {
                return idToType.containsKey(id);
            }

            @Override
            public boolean acceptsValue(String id, String rawValue) {
                String type = idToType.get(id);
                if ("bool".equals(type)) {
                    return "true".equals(rawValue) || "false".equals(rawValue);
                }
                if ("int".equals(type)) {
                    try {
                        Integer.parseInt(rawValue);
                        return true;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }
                return false;
            }
        };
    }

    private static Map<String, String> ruleTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("spawn_mobs", "bool");
        types.put("keep_inventory", "bool");
        types.put("fire_spread_radius_around_player", "int");
        types.put("command_block_output", "bool");
        return types;
    }

    private static Map<String, String> curated() {
        Map<String, String> curated = new LinkedHashMap<>();
        curated.put("spawn_mobs", "false");
        curated.put("keep_inventory", "true");
        return curated;
    }

    @Test
    void parseDefaultsEveryMissingKey() {
        WorldOutputConfig config = WorldOutputConfig.parse(new Properties());

        assertTrue(config.overrideGameRules(), "the game-rule master defaults on");
        assertTrue(config.overrideWorldDefaults(), "the world-defaults master defaults on");
        assertTrue(config.allowCommands(), "commands (cheats) default on");
        assertFalse(config.skipVoidChunks(), "skipping void chunks defaults off");
        assertFalse(config.autoDownload(), "auto-download on join defaults off");
        assertTrue(config.gameRuleOverrides().isEmpty(), "no overrides by default");
        assertEquals(WorldType.VOID, config.worldType(), "the honest void generator is the default");
        assertEquals(0L, config.worldSeed(), "the seed defaults to zero");
        assertFalse(config.generateFeatures(), "structure generation defaults off");
    }

    @Test
    void aBarePrefixGameRuleKeyIsNotAnOverride() {
        Properties properties = new Properties();
        properties.setProperty("gamerule.", "true");

        assertTrue(WorldOutputConfig.parse(properties).gameRuleOverrides().isEmpty(),
                "a key that is only the prefix carries no rule id and is dropped");
    }

    @Test
    void parseReadsTheGenerationKeys() {
        Properties properties = new Properties();
        properties.setProperty("worldType", "DEFAULT");
        properties.setProperty("worldSeed", "12345");
        properties.setProperty("generateFeatures", "true");

        WorldOutputConfig config = WorldOutputConfig.parse(properties);

        assertEquals(WorldType.DEFAULT, config.worldType());
        assertEquals(12345L, config.worldSeed());
        assertTrue(config.generateFeatures());
    }

    @Test
    void worldTypeParsesCaseInsensitively() {
        Properties properties = new Properties();
        properties.setProperty("worldType", "flat");

        assertEquals(WorldType.FLAT, WorldOutputConfig.parse(properties).worldType());
    }

    @Test
    void malformedWorldTypeSelfHealsToVoidAndIsSurfaced() {
        Properties properties = new Properties();
        properties.setProperty("worldType", "banana");
        List<String> malformed = new ArrayList<>();

        WorldOutputConfig config = WorldOutputConfig.parse(properties, malformed);

        assertEquals(WorldType.VOID, config.worldType(), "an unparseable world type reverts to the default");
        assertTrue(malformed.contains("worldType"), "the bad value is surfaced so the file self-heals");
    }

    @Test
    void worldSeedAcceptsTheFullSignedLongRange() {
        Properties properties = new Properties();
        properties.setProperty("worldSeed", Long.toString(Long.MIN_VALUE));

        assertEquals(Long.MIN_VALUE, WorldOutputConfig.parse(properties).worldSeed(),
                "the seed is a full long, not an int-capped value");
    }

    @Test
    void worldSeedHashesNonNumericStringLikeVanilla() {
        Properties properties = new Properties();
        properties.setProperty("worldSeed", "hello");

        assertEquals("hello".hashCode(), WorldOutputConfig.parse(properties).worldSeed(),
                "a non-numeric seed hashes to the same long vanilla would use");
    }

    @Test
    void worldSeedEmptyDefaultsToZero() {
        Properties properties = new Properties();
        properties.setProperty("worldSeed", "   ");

        assertEquals(0L, WorldOutputConfig.parse(properties).worldSeed(), "a blank seed is the deterministic default");
    }

    @Test
    void parseReadsEveryFlatKey() {
        Properties properties = new Properties();
        properties.setProperty("overrideGamerules", "false");
        properties.setProperty("overrideWorldDefaults", "false");
        properties.setProperty("allowCommands", "false");
        properties.setProperty("skipVoidChunks", "true");
        properties.setProperty("autoDownload", "true");

        WorldOutputConfig config = WorldOutputConfig.parse(properties);

        assertFalse(config.overrideGameRules());
        assertFalse(config.overrideWorldDefaults());
        assertFalse(config.allowCommands());
        assertTrue(config.skipVoidChunks());
        assertTrue(config.autoDownload());
    }

    @Test
    void parseReadsTheSparseGameRuleMapByItsBandId() {
        Properties properties = new Properties();
        properties.setProperty("gamerule.spawn_mobs", "true");
        properties.setProperty("gamerule.fire_spread_radius_around_player", "0");
        properties.setProperty("captureEntities", "false"); // an unrelated key is not a gamerule

        Map<String, String> overrides = WorldOutputConfig.parse(properties).gameRuleOverrides();

        assertEquals(2, overrides.size(), "only gamerule.* keys, stripped of the prefix");
        assertEquals("true", overrides.get("spawn_mobs"));
        assertEquals("0", overrides.get("fire_spread_radius_around_player"));
    }

    @Test
    void resolveMasterOffWritesNoRulesEvenWithOverrides() {
        Properties properties = new Properties();
        properties.setProperty("overrideGamerules", "false");
        properties.setProperty("gamerule.keep_inventory", "true");

        GameRuleResolution resolution = WorldOutputConfig.parse(properties).resolveGameRules(curated(),
                schema(ruleTypes()));

        assertTrue(resolution.effective().isEmpty(), "master off: vanilla defaults stand, write nothing");
        assertTrue(resolution.unknownIds().isEmpty());
        assertTrue(resolution.droppedInvalidValues().isEmpty());
    }

    @Test
    void resolveMasterOnAppliesTheCuratedSet() {
        GameRuleResolution resolution = WorldOutputConfig.DEFAULTS.resolveGameRules(curated(), schema(ruleTypes()));

        assertEquals(curated(), resolution.effective());
        assertTrue(resolution.unknownIds().isEmpty());
        assertTrue(resolution.droppedInvalidValues().isEmpty());
    }

    @Test
    void resolveOverrideReplacesCuratedValue() {
        Properties properties = new Properties();
        properties.setProperty("gamerule.spawn_mobs", "true"); // curated has it as false

        GameRuleResolution resolution = WorldOutputConfig.parse(properties).resolveGameRules(curated(),
                schema(ruleTypes()));

        assertEquals("true", resolution.effective().get("spawn_mobs"), "the user override wins over the curated value");
        assertEquals("true", resolution.effective().get("keep_inventory"), "the rest of the curated set survives");
    }

    @Test
    void resolveNonCuratedValidIdPassesThrough() {
        Properties properties = new Properties();
        properties.setProperty("gamerule.command_block_output", "false"); // valid id, not in the curated set

        GameRuleResolution resolution = WorldOutputConfig.parse(properties).resolveGameRules(curated(),
                schema(ruleTypes()));

        assertEquals("false", resolution.effective().get("command_block_output"), "any valid rule passes through");
        assertEquals("false", resolution.effective().get("spawn_mobs"), "the curated set still applies");
    }

    @Test
    void resolveInvalidBooleanValueIsDroppedNotWritten() {
        Properties properties = new Properties();
        properties.setProperty("gamerule.spawn_mobs", "banana"); // valid id, garbage value

        GameRuleResolution resolution = WorldOutputConfig.parse(properties).resolveGameRules(curated(),
                schema(ruleTypes()));

        assertEquals("false", resolution.effective().get("spawn_mobs"),
                "the curated value stands; the typo is not written");
        assertTrue(resolution.droppedInvalidValues().contains("spawn_mobs"),
                "an unparseable value is dropped and surfaced");
    }

    @Test
    void resolveInvalidIntegerValueIsDropped() {
        Properties properties = new Properties();
        properties.setProperty("gamerule.fire_spread_radius_around_player", "true"); // a bool for an int rule

        GameRuleResolution resolution = WorldOutputConfig.parse(properties).resolveGameRules(curated(),
                schema(ruleTypes()));

        assertFalse(resolution.effective().containsKey("fire_spread_radius_around_player"));
        assertTrue(resolution.droppedInvalidValues().contains("fire_spread_radius_around_player"));
    }

    @Test
    void resolveUnknownIdIsSurfacedWhileCuratedSetStillApplies() {
        Properties properties = new Properties();
        properties.setProperty("gamerule.doMobSpawning", "false"); // a 1.21.4 id, absent at this band

        GameRuleResolution resolution = WorldOutputConfig.parse(properties).resolveGameRules(curated(),
                schema(ruleTypes()));

        assertFalse(resolution.effective().containsKey("doMobSpawning"), "an id absent at this band is not written");
        assertTrue(resolution.unknownIds().contains("doMobSpawning"), "the cross-band loss is surfaced, not hidden");
        assertEquals("false", resolution.effective().get("spawn_mobs"),
                "the curated set still applies (masking the loss)");
    }
}
