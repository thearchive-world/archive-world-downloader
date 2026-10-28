// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.init.Biomes;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.GameRules;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.CapturedPlayer;
import world.thearchive.wdl.adapter.LevelDataWriter;
import world.thearchive.wdl.core.CuratedGameRule;
import world.thearchive.wdl.core.GameRuleResolution;
import world.thearchive.wdl.core.GameRuleSchema;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.core.WorldType;

public final class LevelDataWriterImpl implements LevelDataWriter {
    private static final Logger LOGGER = LogManager.getLogger(LevelDataWriterImpl.class);

    private static final String LEVEL_NAME = "Archive World Downloader";

    private static final long NOON = 6000L;

    private static final List<CuratedSpec> CURATED_GAME_RULES = buildCuratedGameRules();

    private static final class CuratedSpec {
        private final String wdlId;
        private final String bandId;
        private final String curatedValue;

        CuratedSpec(String wdlId, String bandId, String curatedValue) {
            this.wdlId = wdlId;
            this.bandId = bandId;
            this.curatedValue = curatedValue;
        }

        String wdlId() {
            return wdlId;
        }

        String bandId() {
            return bandId;
        }

        String curatedValue() {
            return curatedValue;
        }
    }

    @Override
    public LevelDataWriter.LevelData buildLevelData(WorldOutputConfig worldOutput, @Nullable String worldName) {
        WorldType worldType = worldOutput.worldType();
        net.minecraft.world.WorldType levelType = worldType == WorldType.DEFAULT ? net.minecraft.world.WorldType.DEFAULT
                : net.minecraft.world.WorldType.FLAT;
        long seed = worldType == WorldType.VOID ? 0L : worldOutput.worldSeed();
        boolean generateMapFeatures = worldType != WorldType.VOID && worldOutput.generateFeatures();

        WorldSettings settings = new WorldSettings(seed, GameType.SURVIVAL, generateMapFeatures, false, levelType);
        if (worldType == WorldType.VOID) {
            settings.setGeneratorOptions(voidGeneratorOptions());
        }
        // Cheats are the world-defaults master's to impose; with it off cheats fall back to off here.
        if (worldOutput.overrideWorldDefaults() && worldOutput.allowCommands()) {
            settings.enableCommands();
        }

        String levelName = worldName == null || worldName.isEmpty() ? LEVEL_NAME : worldName;
        WorldInfo levelData = new WorldInfo(settings, levelName);
        // Vanilla treats a level.dat whose initialized flag is false as new and replaces the spawn on first open.
        levelData.setServerInitialized(true);
        GameRuleResolution gameRuleResolution = applyGameRules(levelData.getGameRulesInstance(), worldOutput);
        levelData.setWorldTime(NOON);
        return new LevelDataWriter.LevelData(levelData, gameRuleResolution);
    }

    /**
     * The grammar is {@code FlatGeneratorInfo}'s classic form, {@code "<version>;<layers>;<biome>;<features>"}: version
     * 3 selects namespaced block names for the layers.
     */
    private static String voidGeneratorOptions() {
        return "3;minecraft:air;" + Biome.getIdForBiome(Biomes.VOID) + ";";
    }

    @Override
    public void warmWorldgen() {}

    private static GameRuleResolution applyGameRules(GameRules gameRules, WorldOutputConfig worldOutput) {
        GameRuleSchema schema = new GameRuleSchema() {
            @Override
            public boolean hasRule(String id) {
                return gameRules.hasRule(id);
            }

            @Override
            public boolean acceptsValue(String id, String rawValue) {
                return gameRules.hasRule(id) && valueParses(gameRules, id, rawValue);
            }
        };
        GameRuleResolution resolution = worldOutput.resolveGameRules(curatedByBandId(), schema);
        // The curated set is merged in by resolveGameRules, so a curated id absent from this band's own rule set (a
        // curated rule only a higher band has) is still guarded here before the write.
        for (Map.Entry<String, String> rule : resolution.effective().entrySet()) {
            if (gameRules.hasRule(rule.getKey())) {
                gameRules.setOrCreateGameRule(rule.getKey(), rule.getValue());
            }
        }
        for (String id : resolution.droppedInvalidValues()) {
            LOGGER.warn("ignoring game-rule override gamerule.{}: its value does not parse for this rule", id);
        }
        for (String id : resolution.unknownIds()) {
            LOGGER.warn("ignoring game-rule override gamerule.{}: no such game rule on this Minecraft version", id);
        }
        return resolution;
    }

    private static Map<String, String> curatedByBandId() {
        Map<String, String> byBandId = new LinkedHashMap<>();
        for (CuratedSpec spec : CURATED_GAME_RULES) {
            byBandId.put(spec.bandId(), spec.curatedValue());
        }
        return byBandId;
    }

    private static boolean valueParses(GameRules gameRules, String id, String rawValue) {
        if (gameRules.areSameType(id, GameRules.ValueType.BOOLEAN_VALUE)) {
            return "true".equals(rawValue) || "false".equals(rawValue);
        }
        if (gameRules.areSameType(id, GameRules.ValueType.NUMERICAL_VALUE)) {
            try {
                Integer.parseInt(rawValue);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    @Override
    public List<CuratedGameRule> curatedGameRules() {
        GameRules gameRules = new GameRules();
        List<CuratedGameRule> rules = new ArrayList<>();
        for (CuratedSpec spec : CURATED_GAME_RULES) {
            if (!gameRules.hasRule(spec.bandId())) {
                continue; // a curated rule with no rule at this band is omitted
            }
            if (gameRules.areSameType(spec.bandId(), GameRules.ValueType.BOOLEAN_VALUE)) {
                rules.add(new CuratedGameRule(spec.wdlId(), spec.bandId(), spec.curatedValue(), "true", "false"));
            } else {
                String enabled = String.valueOf(gameRules.getInt(spec.bandId()));
                rules.add(new CuratedGameRule(spec.wdlId(), spec.bandId(), spec.curatedValue(), enabled, "0"));
            }
        }
        return Collections.unmodifiableList(rules);
    }

    // The fire-spread row is named for the newer integer radius rule; the boolean doFireTick is this band's equivalent.
    private static List<CuratedSpec> buildCuratedGameRules() {
        List<CuratedSpec> curated = new ArrayList<>();
        curated.add(new CuratedSpec("spawn_mobs", "doMobSpawning", "false"));
        curated.add(new CuratedSpec("fire_spread_radius_around_player", "doFireTick", "false"));
        curated.add(new CuratedSpec("spread_vines", "doVinesSpread", "false"));
        curated.add(new CuratedSpec("advance_time", "doDaylightCycle", "false"));
        curated.add(new CuratedSpec("advance_weather", "doWeatherCycle", "false"));
        curated.add(new CuratedSpec("keep_inventory", "keepInventory", "true"));
        curated.add(new CuratedSpec("mob_griefing", "mobGriefing", "false"));
        curated.add(new CuratedSpec("spawn_wardens", "doWardenSpawning", "false"));
        curated.add(new CuratedSpec("spawn_wandering_traders", "doTraderSpawning", "false"));
        curated.add(new CuratedSpec("spawn_patrols", "doPatrolSpawning", "false"));
        return Collections.unmodifiableList(curated);
    }

    @Override
    public void save(ISaveHandler storage, LevelDataWriter.LevelData data, @Nullable CapturedPlayer player) {
        if (player == null) {
            // level.dat's "Player" is the only copy of the player record at this band and this write carries
            // none, so writing unconditionally destroys a prior download's record, silently.
            if (!Files.exists(storage.getWorldDirectory().toPath().resolve("level.dat"))) {
                storage.saveWorldInfo(data.worldData());
            }
            return;
        }
        WorldInfo worldInfo = data.worldData();
        worldInfo.setGameType(player.gameType());
        worldInfo.setSpawn(player.spawnPos());
        worldInfo.setDifficulty(player.difficulty());
        storage.saveWorldInfoWithPlayer(worldInfo, player.playerTag());
    }

    @Override
    public @Nullable NBTTagCompound readPriorPlayer(Path levelDatFile) {
        if (!Files.exists(levelDatFile)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(levelDatFile)) {
            NBTTagCompound root = CompressedStreamTools.readCompressed(input);
            if (!root.hasKey("Data", 10)) {
                return null;
            }
            NBTTagCompound data = root.getCompoundTag("Data");
            return data.hasKey("Player", 10) ? data.getCompoundTag("Player") : null;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read the prior player data " + levelDatFile, e);
        }
    }
}
