// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.LevelType;
import net.minecraft.world.level.storage.LevelStorage;
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
        LevelType levelType = worldType == WorldType.DEFAULT ? LevelType.NORMAL : LevelType.FLAT;
        long seed = worldType == WorldType.VOID ? 0L : worldOutput.worldSeed();
        boolean generateMapFeatures = worldType != WorldType.VOID && worldOutput.generateFeatures();

        LevelSettings settings = new LevelSettings(seed, GameType.SURVIVAL, generateMapFeatures, false, levelType);
        if (worldType == WorldType.VOID) {
            settings.setLevelTypeOptions(voidGeneratorOptions());
        }
        // Cheats are the world-defaults master's to impose; with it off cheats fall back to off here.
        if (worldOutput.overrideWorldDefaults() && worldOutput.allowCommands()) {
            settings.enableSinglePlayerCommands();
        }

        String levelName = worldName == null || worldName.isEmpty() ? LEVEL_NAME : worldName;
        net.minecraft.world.level.storage.LevelData levelData = new net.minecraft.world.level.storage.LevelData(
                settings, levelName);
        // Vanilla treats a level.dat whose initialized flag is false as new and replaces the spawn on first open.
        levelData.setInitialized(true);
        GameRuleResolution gameRuleResolution = applyGameRules(levelData.getGameRules(), worldOutput);
        levelData.setDayTime(NOON);
        return new LevelDataWriter.LevelData(levelData, gameRuleResolution);
    }

    /**
     * Empty layers cannot express a void world because {@code FlatLevelGeneratorSettings.fromObject} falls back to the
     * default flat preset on an empty layer list, so the void is a one-block air layer, which leaves the whole column
     * air.
     */
    private static JsonElement voidGeneratorOptions() {
        JsonObject layer = new JsonObject();
        layer.addProperty("height", 1);
        layer.addProperty("block", "minecraft:air");
        JsonArray layers = new JsonArray();
        layers.add(layer);
        JsonObject options = new JsonObject();
        options.add("layers", layers);
        options.addProperty("biome", "minecraft:the_void");
        options.add("structures", new JsonObject());
        return options;
    }

    @Override
    public void warmWorldgen() {}

    private static GameRuleResolution applyGameRules(GameRules gameRules, WorldOutputConfig worldOutput) {
        Set<String> availableIds = gameRules.createTag().getAllKeys();
        GameRuleSchema schema = new GameRuleSchema() {
            @Override
            public boolean hasRule(String id) {
                return availableIds.contains(id);
            }

            @Override
            public boolean acceptsValue(String id, String rawValue) {
                GameRules.class_1440 rule = gameRules.method_16301(id);
                return rule != null && valueParses(rule, rawValue);
            }
        };
        GameRuleResolution resolution = worldOutput.resolveGameRules(curatedByBandId(), schema);
        CompoundTag ruleTag = gameRules.createTag();
        for (Map.Entry<String, String> rule : resolution.effective().entrySet()) {
            if (availableIds.contains(rule.getKey())) {
                ruleTag.putString(rule.getKey(), rule.getValue());
            }
        }
        gameRules.loadFromTag(ruleTag);
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

    private static boolean valueParses(GameRules.class_1440 rule, String rawValue) {
        GameRules.class_2166 type = rule.method_8477();
        if (type == GameRules.class_2166.field_17497) {
            return "true".equals(rawValue) || "false".equals(rawValue);
        }
        if (type == GameRules.class_2166.field_17498) {
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
            GameRules.class_1440 rule = gameRules.method_16301(spec.bandId());
            if (rule == null) {
                continue; // a curated rule with no rule at this band is omitted
            }
            if (rule.method_8477() == GameRules.class_2166.field_17497) {
                rules.add(new CuratedGameRule(spec.wdlId(), spec.bandId(), spec.curatedValue(), "true", "false"));
            } else {
                String enabled = String.valueOf(rule.method_8476());
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
    public void save(LevelStorage storage, LevelDataWriter.LevelData data, @Nullable CapturedPlayer player) {
        if (player == null) {
            // level.dat's "Player" is the only copy of the player record at this band and this write carries
            // none, so writing unconditionally destroys a prior download's record, silently.
            if (!Files.exists(storage.getFolder().toPath().resolve("level.dat"))) {
                storage.saveLevelData(data.worldData(), null);
            }
            return;
        }
        net.minecraft.world.level.storage.LevelData levelData = data.worldData();
        levelData.setGameType(player.gameType());
        levelData.setSpawn(player.spawnPos());
        levelData.setDifficulty(player.difficulty());
        storage.saveLevelData(levelData, player.playerTag());
    }

    @Override
    public @Nullable CompoundTag readPriorPlayer(Path levelDatFile) {
        if (!Files.exists(levelDatFile)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(levelDatFile)) {
            CompoundTag root = NbtIo.readCompressed(input);
            return root.get("Data") instanceof CompoundTag
                    && ((CompoundTag) root.get("Data")).get("Player") instanceof CompoundTag
                            ? (CompoundTag) ((CompoundTag) root.get("Data")).get("Player")
                            : null;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read the prior player data " + levelDatFile, e);
        }
    }
}
