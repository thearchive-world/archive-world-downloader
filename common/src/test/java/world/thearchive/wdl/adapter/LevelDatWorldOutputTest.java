// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
import java.util.Properties;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The 1.21.11 world-output writes: the curated safe game rules and the world-open state land in the level.dat Data tag.
 * Noon and clear weather are fixed invariants applied regardless of the world-defaults master, the game-rule master
 * gates the curated set, and the override validation drops a bad value or surfaces an unknown id rather than writing it
 * against the real {@code GameRules}.
 */
class LevelDatWorldOutputTest {
    private final LevelDataWriter writer = new LevelDataWriterImpl();

    private LevelDataWriter.LevelData build(WorldOutputConfig worldOutput) {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        return writer.buildLevelData(registries, worldOutput, null);
    }

    private CompoundTag dataTag(LevelDataWriter.LevelData built) {
        return built.worldData().createTag(built.registries(), null);
    }

    private GameRules gameRules(LevelDataWriter.LevelData built) {
        DynamicOps<Tag> ops = built.registries().createSerializationContext(NbtOps.INSTANCE);
        return GameRules.codec(FeatureFlags.DEFAULT_FLAGS)
                .parse(ops, dataTag(built).getCompoundOrEmpty("game_rules"))
                .getOrThrow();
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

    private WorldGenSettings worldGen(LevelDataWriter.LevelData built) {
        DynamicOps<Tag> ops = built.registries().createSerializationContext(NbtOps.INSTANCE);
        return WorldGenSettings.CODEC.parse(ops, dataTag(built).getCompoundOrEmpty("WorldGenSettings")).getOrThrow();
    }

    @Test
    void defaultGeneratorWritesTheFullLongSeed() {
        LevelDataWriter.LevelData built = build(
                with("worldType", "DEFAULT", "worldSeed", Long.toString(Long.MIN_VALUE)));

        assertEquals(Long.MIN_VALUE, worldGen(built).options().seed(),
                "the full signed-long seed lands in level.dat, not an int-capped value");
    }

    @Test
    void defaultGeneratorWritesHashedStringSeed() {
        LevelDataWriter.LevelData built = build(with("worldType", "DEFAULT", "worldSeed", "hello"));

        assertEquals("hello".hashCode(), worldGen(built).options().seed(),
                "a non-numeric seed lands as the same long vanilla would hash it to");
    }

    @Test
    void generateFeaturesTogglesStructureGeneration() {
        assertTrue(worldGen(build(with("worldType", "DEFAULT", "generateFeatures", "true"))).options()
                .generateStructures());
        assertFalse(worldGen(build(with("worldType", "DEFAULT"))).options().generateStructures(),
                "structures default off");
    }

    @Test
    void voidGeneratorKeepsSeedZeroAndStaysStructureless() {
        WorldGenSettings voidSettings = worldGen(build(WorldOutputConfig.DEFAULTS));

        assertEquals(0L, voidSettings.options().seed(), "the default void world keeps seed 0 (byte-unchanged)");
        assertFalse(voidSettings.options().generateStructures(), "the void world generates no structures");
    }

    @Test
    void aDefaultWorldIsStableWhileVoidStaysExperimental() {
        // A DEFAULT world is the three vanilla generators, so its re-derived lifecycle is stable and it opens
        // without the experimental-world warning; the void world's flat nether/end are inherently experimental.
        // The lifecycle is re-derived from the baked generators at load, never read from level.dat, so no
        // level.dat write can suppress the void world's warning.
        assertEquals(Lifecycle.stable(), lifecycle(build(with("worldType", "DEFAULT"))));
        assertEquals(Lifecycle.experimental(), lifecycle(build(WorldOutputConfig.DEFAULTS)));
    }

    @Test
    void flatGeneratorBuildsAndRoundTripsItsSeed() {
        WorldGenSettings flat = worldGen(build(with("worldType", "FLAT", "worldSeed", "777")));

        assertEquals(777L, flat.options().seed(), "the FLAT generator builds and its seed lands in level.dat");
    }

    private static Lifecycle lifecycle(LevelDataWriter.LevelData built) {
        return ((PrimaryLevelData) built.worldData()).worldGenSettingsLifecycle();
    }

    @Test
    void defaultsWriteTheCuratedSafeGameRules() {
        GameRules rules = gameRules(build(WorldOutputConfig.DEFAULTS));

        assertFalse(rules.get(GameRules.SPAWN_MOBS), "no mob spawning");
        assertTrue(rules.get(GameRules.KEEP_INVENTORY), "keep inventory");
        assertEquals(0, rules.get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER), "no fire spread (int 0)");
        assertFalse(rules.get(GameRules.MOB_GRIEFING), "no mob griefing");
        assertFalse(rules.get(GameRules.ADVANCE_TIME), "day frozen");
        assertFalse(rules.get(GameRules.ADVANCE_WEATHER), "weather frozen");
    }

    @Test
    void gameRuleMasterOffLeavesVanillaDefaults() {
        GameRules rules = gameRules(build(with("overrideGamerules", "false")));

        assertTrue(rules.get(GameRules.SPAWN_MOBS), "master off: the vanilla default (mobs spawn) stands");
        assertFalse(rules.get(GameRules.KEEP_INVENTORY), "master off: keep-inventory stays at its vanilla default");
    }

    @Test
    void defaultsOpenTheWorldAtNoon() {
        assertEquals(6000L, dataTag(build(WorldOutputConfig.DEFAULTS)).getLongOr("DayTime", -1L));
    }

    @Test
    void defaultsOpenTheWorldWithClearWeather() {
        CompoundTag data = dataTag(build(WorldOutputConfig.DEFAULTS));

        assertFalse(data.getBooleanOr("raining", true), "not raining");
        assertFalse(data.getBooleanOr("thundering", true), "not thundering");
        assertEquals(0, data.getIntOr("clearWeatherTime", -1),
                "weather opens clear, not force-held; the curated advance_weather=false is what holds it");
    }

    @Test
    void worldDefaultsMasterOffStillOpensAtNoonAndClear() {
        CompoundTag data = dataTag(build(with("overrideWorldDefaults", "false")));

        assertEquals(6000L, data.getLongOr("DayTime", -1L),
                "noon is a fixed invariant, applied even with the world-defaults master off");
        assertFalse(data.getBooleanOr("raining", true), "the fresh world opens clear regardless of the master");
        assertEquals(0, data.getIntOr("clearWeatherTime", -1),
                "weather opens clear, not force-held, regardless of the master");
    }

    @Test
    void defaultsAllowCommands() {
        assertTrue(dataTag(build(WorldOutputConfig.DEFAULTS)).getBooleanOr("allowCommands", false),
                "cheats default on so /gamemode and /tp work in the saved world");
    }

    @Test
    void allowCommandsOffWritesCheatsOff() {
        assertFalse(dataTag(build(with("allowCommands", "false"))).getBooleanOr("allowCommands", true),
                "with the knob off the saved world has cheats disabled");
    }

    @Test
    void worldDefaultsMasterOffDisablesCheats() {
        assertFalse(dataTag(build(with("overrideWorldDefaults", "false"))).getBooleanOr("allowCommands", true),
                "master off leaves the world vanilla: cheats are not forced on even with the cheats knob on");
    }

    @Test
    void anInvalidOverrideValueIsDroppedAndTheCuratedValueStands() {
        LevelDataWriter.LevelData built = build(with("gamerule.keep_inventory", "banana"));

        assertTrue(built.gameRules().droppedInvalidValues().contains("keep_inventory"), "the typo is surfaced");
        assertTrue(gameRules(built).get(GameRules.KEEP_INVENTORY), "the curated value stands; the typo is not written");
    }

    @Test
    void anUnknownOverrideIdIsSurfacedNotWritten() {
        LevelDataWriter.LevelData built = build(with("gamerule.doMobSpawning", "false")); // a 1.21.4 id

        assertTrue(built.gameRules().unknownIds().contains("doMobSpawning"), "the cross-band loss is surfaced");
        assertFalse(gameRules(built).get(GameRules.SPAWN_MOBS), "the curated safe set still applies");
    }

    @Test
    void aValidOverrideIsWritten() {
        // immediate_respawn is not in the curated set; a valid override of it passes through.
        GameRules rules = gameRules(build(with("gamerule.immediate_respawn", "true")));

        assertTrue(rules.get(GameRules.IMMEDIATE_RESPAWN), "an arbitrary valid rule passes through");
    }
}
