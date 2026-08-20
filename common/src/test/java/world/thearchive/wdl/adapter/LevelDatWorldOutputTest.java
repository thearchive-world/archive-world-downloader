// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameRules;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The 1.15.2 world-output writes: the curated safe game rules and the world-open state land in the level.dat Data tag.
 * Noon and clear weather are fixed invariants applied regardless of the world-defaults master, the game-rule master
 * gates the curated set, and the override validation drops a bad value or surfaces an unknown id rather than writing it
 * against the real {@code GameRules}.
 */
class LevelDatWorldOutputTest {
    private final LevelDataWriter writer = new LevelDataWriterImpl();

    private LevelDataWriter.LevelData build(WorldOutputConfig worldOutput) {
        TestRegistries.bootstrap();
        return writer.buildLevelData(worldOutput, null);
    }

    private CompoundTag dataTag(LevelDataWriter.LevelData built) {
        return built.worldData().createTag(null);
    }

    private GameRules gameRules(LevelDataWriter.LevelData built) {
        GameRules rules = new GameRules();
        rules.loadFromTag(dataTag(built).getCompound("GameRules"));
        return rules;
    }

    private static WorldOutputConfig with(String key, String value) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return WorldOutputConfig.parse(properties);
    }

    private static WorldOutputConfig with(String k1, String v1, String k2, String v2) {
        Properties properties = new Properties();
        properties.setProperty(k1, v1);
        properties.setProperty(k2, v2);
        return WorldOutputConfig.parse(properties);
    }

    // At 1.15.2 the generator seed and structure toggle live directly in the Data tag (RandomSeed, MapFeatures),
    // not in a WorldGenSettings compound (that is 1.16 and later).
    private CompoundTag worldGenTag(LevelDataWriter.LevelData built) {
        return dataTag(built);
    }

    @Test
    void defaultGeneratorWritesTheFullLongSeed() {
        LevelDataWriter.LevelData built = build(
                with("worldType", "DEFAULT", "worldSeed", Long.toString(Long.MIN_VALUE)));

        assertEquals(Long.MIN_VALUE, worldGenTag(built).getLong("RandomSeed"),
                "the full signed-long seed lands in level.dat, not an int-capped value");
    }

    @Test
    void defaultGeneratorWritesHashedStringSeed() {
        LevelDataWriter.LevelData built = build(with("worldType", "DEFAULT", "worldSeed", "hello"));

        assertEquals("hello".hashCode(), worldGenTag(built).getLong("RandomSeed"),
                "a non-numeric seed lands as the same long vanilla would hash it to");
    }

    @Test
    void generateFeaturesTogglesStructureGeneration() {
        assertTrue(worldGenTag(build(with("worldType", "DEFAULT", "generateFeatures", "true")))
                .getBoolean("MapFeatures"));
        assertFalse(worldGenTag(build(with("worldType", "DEFAULT"))).getBoolean("MapFeatures"),
                "structures default off");
    }

    @Test
    void voidGeneratorKeepsSeedZeroAndStaysStructureless() {
        CompoundTag voidSettings = worldGenTag(build(WorldOutputConfig.DEFAULTS));

        assertEquals(0L, voidSettings.getLong("RandomSeed"), "the default void world keeps seed 0 (byte-unchanged)");
        assertFalse(voidSettings.getBoolean("MapFeatures"), "the void world generates no structures");
    }

    @Test
    void flatGeneratorBuildsAndRoundTripsItsSeed() {
        CompoundTag flat = worldGenTag(build(with("worldType", "FLAT", "worldSeed", "777")));

        assertEquals(777L, flat.getLong("RandomSeed"), "the FLAT generator builds and its seed lands in level.dat");
    }

    @Test
    void defaultsWriteTheCuratedSafeGameRules() {
        GameRules rules = gameRules(build(WorldOutputConfig.DEFAULTS));

        assertFalse(rules.method_4671("doMobSpawning"), "no mob spawning");
        assertTrue(rules.method_4671("keepInventory"), "keep inventory");
        assertFalse(rules.method_4671("mobGriefing"), "no mob griefing");
        assertFalse(rules.method_4671("doDaylightCycle"), "day frozen");
        assertFalse(rules.method_4671("doWeatherCycle"), "weather frozen");
    }

    @Test
    void gameRuleMasterOffLeavesVanillaDefaults() {
        GameRules rules = gameRules(build(with("overrideGamerules", "false")));

        assertTrue(rules.method_4671("doMobSpawning"),
                "master off: the vanilla default (mobs spawn) stands");
        assertFalse(rules.method_4671("keepInventory"),
                "master off: keep-inventory stays at its vanilla default");
    }

    @Test
    void defaultsOpenTheWorldAtNoon() {
        CompoundTag data = dataTag(build(WorldOutputConfig.DEFAULTS));
        assertEquals(6000L, data.contains("DayTime") ? data.getLong("DayTime") : -1L);
    }

    @Test
    void defaultsOpenTheWorldWithClearWeather() {
        CompoundTag data = dataTag(build(WorldOutputConfig.DEFAULTS));

        assertFalse((data.contains("raining") ? data.getBoolean("raining") : true), "not raining");
        assertFalse((data.contains("thundering") ? data.getBoolean("thundering") : true), "not thundering");
        assertEquals(0, (data.contains("clearWeatherTime") ? data.getInt("clearWeatherTime") : -1),
                "weather opens clear, not force-held; the curated advance_weather=false is what holds it");
    }

    @Test
    void worldDefaultsMasterOffStillOpensAtNoonAndClear() {
        CompoundTag data = dataTag(build(with("overrideWorldDefaults", "false")));

        assertEquals(6000L, (data.contains("DayTime") ? data.getLong("DayTime") : -1L),
                "noon is a fixed invariant, applied even with the world-defaults master off");
        assertFalse((data.contains("raining") ? data.getBoolean("raining") : true),
                "the fresh world opens clear regardless of the master");
        assertEquals(0, (data.contains("clearWeatherTime") ? data.getInt("clearWeatherTime") : -1),
                "weather opens clear, not force-held, regardless of the master");
    }

    @Test
    void defaultsAllowCommands() {
        assertTrue(dataTag(build(WorldOutputConfig.DEFAULTS)).getBoolean("allowCommands"),
                "cheats default on so /gamemode and /tp work in the saved world");
    }

    @Test
    void allowCommandsOffWritesCheatsOff() {
        CompoundTag data = dataTag(build(with("allowCommands", "false")));
        assertFalse(data.contains("allowCommands") ? data.getBoolean("allowCommands") : true,
                "with the knob off the saved world has cheats disabled");
    }

    @Test
    void worldDefaultsMasterOffDisablesCheats() {
        CompoundTag data = dataTag(build(with("overrideWorldDefaults", "false")));
        assertFalse(data.contains("allowCommands") ? data.getBoolean("allowCommands") : true,
                "master off leaves the world vanilla: cheats are not forced on even with the cheats knob on");
    }

    @Test
    void anInvalidOverrideValueIsDroppedAndTheCuratedValueStands() {
        LevelDataWriter.LevelData built = build(with("gamerule.keepInventory", "banana"));

        assertTrue(built.gameRules().droppedInvalidValues().contains("keepInventory"), "the typo is surfaced");
        assertTrue(gameRules(built).method_4671("keepInventory"),
                "the curated value stands; the typo is not written");
    }

    @Test
    void anUnknownOverrideIdIsSurfacedNotWritten() {
        // spawn_mobs is the 1.21.5+ snake_case id; the 1.21.4 rule is doMobSpawning.
        LevelDataWriter.LevelData built = build(with("gamerule.spawn_mobs", "true"));

        assertTrue(built.gameRules().unknownIds().contains("spawn_mobs"), "the cross-band loss is surfaced");
        assertFalse(gameRules(built).method_4671("doMobSpawning"), "the curated safe set still applies");
    }

    @Test
    void aValidOverrideIsWritten() {
        // doLimitedCrafting is not in the curated set; a valid override of it passes through.
        GameRules rules = gameRules(build(with("gamerule.doLimitedCrafting", "true")));

        assertTrue(rules.method_4671("doLimitedCrafting"), "an arbitrary valid rule passes through");
    }
}
