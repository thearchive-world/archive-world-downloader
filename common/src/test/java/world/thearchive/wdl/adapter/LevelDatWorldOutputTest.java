// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.clock.ClockState;
import net.minecraft.world.clock.PackedClockStates;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The 26.1.2 world-output writes, verified through the production save to disk. The 26.x world metadata is split across
 * namespaced save-side files rather than one level.dat: worldgen is {@code data/minecraft/world_gen_settings.dat}, the
 * curated game rules are {@code data/minecraft/game_rules.dat}, and noon is a {@code data/minecraft/world_clocks.dat}
 * payload (setDayTime is gone). Weather is left unwritten so vanilla opens clear. The game-rule master gates the
 * curated set, and the override validation drops a bad value or surfaces an unknown id rather than writing it.
 */
class LevelDatWorldOutputTest {
    private final LevelDataWriter writer = new LevelDataWriterImpl();

    @TempDir
    private Path saves;

    private int worldCounter;

    /** A built and paired save root: everything worldgen, game rules and clocks land in is on disk under saveRoot. */
    private record Saved(LevelDataWriter.LevelData built, Path saveRoot) {}

    private LevelDataWriter.LevelData build(WorldOutputConfig worldOutput) {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        return writer.buildLevelData(registries, worldOutput, null);
    }

    /** Build the metadata and run the real production save to disk, returning it paired with its save root. */
    private Saved save(WorldOutputConfig worldOutput) throws IOException {
        LevelDataWriter.LevelData built = build(worldOutput);
        String name = "world" + worldCounter++;
        LevelStorageSource source = LevelStorageSource.createDefault(saves);
        try (LevelStorageSource.LevelStorageAccess access = source.createAccess(name)) {
            writer.save(access, built, null);
        }
        return new Saved(built, saves.resolve(name));
    }

    private CompoundTag dataTag(LevelDataWriter.LevelData built) {
        return built.worldData().createTag(null);
    }

    /** The inner {@code data} tag of a namespaced {@code data/minecraft/<name>.dat} SavedData envelope. */
    private static CompoundTag savedData(Path saveRoot, String name) throws IOException {
        Path file = saveRoot.resolve("data").resolve("minecraft").resolve(name + ".dat");
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data");
    }

    private WorldGenSettings worldGen(Saved saved) throws IOException {
        DynamicOps<Tag> ops = saved.built().registries().createSerializationContext(NbtOps.INSTANCE);
        return WorldGenSettings.CODEC.parse(ops, savedData(saved.saveRoot(), "world_gen_settings")).getOrThrow();
    }

    private static GameRules gameRules(Saved saved) throws IOException {
        return GameRules.codec(FeatureFlags.DEFAULT_FLAGS)
                .parse(NbtOps.INSTANCE, savedData(saved.saveRoot(), "game_rules")).getOrThrow();
    }

    private long dayTime(Saved saved) throws IOException {
        DynamicOps<Tag> ops = saved.built().registries().createSerializationContext(NbtOps.INSTANCE);
        PackedClockStates clocks = PackedClockStates.CODEC.parse(ops, savedData(saved.saveRoot(), "world_clocks"))
                .getOrThrow();
        Holder<WorldClock> overworld = saved.built().registries().lookupOrThrow(Registries.WORLD_CLOCK)
                .getOrThrow(WorldClocks.OVERWORLD);
        ClockState state = clocks.clocks().get(overworld);
        assertNotNull(state, "the overworld clock is present in world_clocks.dat");
        return state.totalTicks();
    }

    /** Whether a weather SavedData was written; the mod writes none so vanilla opens the world clear. */
    private static boolean weatherWritten(Path saveRoot) {
        return Files.exists(saveRoot.resolve("data").resolve("minecraft").resolve("weather.dat"));
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

    @Test
    void defaultGeneratorWritesTheFullLongSeed() throws IOException {
        Saved saved = save(with("worldType", "DEFAULT", "worldSeed", Long.toString(Long.MIN_VALUE)));

        assertEquals(Long.MIN_VALUE, worldGen(saved).options().seed(),
                "the full signed-long seed lands in world_gen_settings.dat, not an int-capped value");
    }

    @Test
    void defaultGeneratorWritesHashedStringSeed() throws IOException {
        Saved saved = save(with("worldType", "DEFAULT", "worldSeed", "hello"));

        assertEquals("hello".hashCode(), worldGen(saved).options().seed(),
                "a non-numeric seed lands as the same long vanilla would hash it to");
    }

    @Test
    void generateFeaturesTogglesStructureGeneration() throws IOException {
        assertTrue(worldGen(save(with("worldType", "DEFAULT", "generateFeatures", "true"))).options()
                .generateStructures());
        assertFalse(worldGen(save(with("worldType", "DEFAULT"))).options().generateStructures(),
                "structures default off");
    }

    @Test
    void voidGeneratorKeepsSeedZeroAndStaysStructureless() throws IOException {
        WorldGenSettings voidSettings = worldGen(save(WorldOutputConfig.DEFAULTS));

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
    void flatGeneratorBuildsAndRoundTripsItsSeed() throws IOException {
        WorldGenSettings flat = worldGen(save(with("worldType", "FLAT", "worldSeed", "777")));

        assertEquals(777L, flat.options().seed(), "the FLAT generator builds and its seed lands on disk");
    }

    private static Lifecycle lifecycle(LevelDataWriter.LevelData built) {
        return ((PrimaryLevelData) built.worldData()).worldGenSettingsLifecycle();
    }

    @Test
    void defaultsWriteTheCuratedSafeGameRules() throws IOException {
        GameRules rules = gameRules(save(WorldOutputConfig.DEFAULTS));

        assertFalse(rules.get(GameRules.SPAWN_MOBS), "no mob spawning");
        assertTrue(rules.get(GameRules.KEEP_INVENTORY), "keep inventory");
        assertEquals(0, rules.get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER), "no fire spread (int 0)");
        assertFalse(rules.get(GameRules.MOB_GRIEFING), "no mob griefing");
        assertFalse(rules.get(GameRules.ADVANCE_TIME), "day frozen");
        assertFalse(rules.get(GameRules.ADVANCE_WEATHER), "weather frozen");
    }

    @Test
    void gameRuleMasterOffLeavesVanillaDefaults() throws IOException {
        GameRules rules = gameRules(save(with("overrideGamerules", "false")));

        assertTrue(rules.get(GameRules.SPAWN_MOBS), "master off: the vanilla default (mobs spawn) stands");
        assertFalse(rules.get(GameRules.KEEP_INVENTORY), "master off: keep-inventory stays at its vanilla default");
    }

    @Test
    void defaultsOpenTheWorldAtNoon() throws IOException {
        assertEquals(6000L, dayTime(save(WorldOutputConfig.DEFAULTS)),
                "the overworld clock opens at noon through world_clocks.dat");
    }

    @Test
    void defaultsOpenTheWorldWithClearWeather() throws IOException {
        assertFalse(weatherWritten(save(WorldOutputConfig.DEFAULTS).saveRoot()),
                "no weather SavedData is written, so vanilla opens the world clear; advance_weather=false holds it");
    }

    @Test
    void worldDefaultsMasterOffStillOpensAtNoonAndClear() throws IOException {
        Saved saved = save(with("overrideWorldDefaults", "false"));

        assertEquals(6000L, dayTime(saved),
                "noon is a fixed invariant, applied even with the world-defaults master off");
        assertFalse(weatherWritten(saved.saveRoot()),
                "the fresh world opens clear regardless of the master; no weather SavedData is written");
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
    void anInvalidOverrideValueIsDroppedAndTheCuratedValueStands() throws IOException {
        Saved saved = save(with("gamerule.keep_inventory", "banana"));

        assertTrue(saved.built().gameRules().droppedInvalidValues().contains("keep_inventory"),
                "the typo is surfaced");
        assertTrue(gameRules(saved).get(GameRules.KEEP_INVENTORY),
                "the curated value stands; the typo is not written");
    }

    @Test
    void anUnknownOverrideIdIsSurfacedNotWritten() throws IOException {
        Saved saved = save(with("gamerule.doMobSpawning", "false")); // a 1.21.4 id

        assertTrue(saved.built().gameRules().unknownIds().contains("doMobSpawning"),
                "the cross-band loss is surfaced");
        assertFalse(gameRules(saved).get(GameRules.SPAWN_MOBS), "the curated safe set still applies");
    }

    @Test
    void aValidOverrideIsWritten() throws IOException {
        // immediate_respawn is not in the curated set; a valid override of it passes through.
        GameRules rules = gameRules(save(with("gamerule.immediate_respawn", "true")));

        assertTrue(rules.get(GameRules.IMMEDIATE_RESPAWN), "an arbitrary valid rule passes through");
    }
}
