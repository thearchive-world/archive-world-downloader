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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/**
 * 1.15.2 {@code level.dat} writer for the selected generator: the default superflat VOID (a single air layer over the
 * void biome), or the vanilla DEFAULT/FLAT presets. Pre-1.16 {@code level.dat} records only the generator name and its
 * options string, so no worldgen registries are reconstructed here. The captured chunks always supply the real terrain;
 * the generator only fills the un-captured gaps, which for DEFAULT/FLAT are freshly generated and not the server's
 * actual land (the server's seed is not recoverable from a client).
 */
public final class LevelDataWriterImpl implements LevelDataWriter {
    private static final Logger LOGGER = LogManager.getLogger(LevelDataWriterImpl.class);

    private static final String LEVEL_NAME = "Archive World Downloader";

    /** 24000-tick day; 6000 is noon. */
    private static final long NOON = 6000L;

    /**
     * The curated safe set: each rule's stable WDL name, the running band's own id for it, and the curated raw value.
     * The WDL name is what the menu and the lang catalogs bind (band-stable); the band id is what the download writes.
     * Every curated rule is a boolean here; a curated rule with no rule at this band is dropped at runtime by
     * {@link #curatedGameRules} and skipped by {@link #applyGameRules}. The user's gamerule.* overrides, keyed by band
     * id, are validated and applied on top of this (see {@link WorldOutputConfig}).
     */
    private static final List<CuratedSpec> CURATED_GAME_RULES = buildCuratedGameRules();

    /** One curated rule: its stable WDL name, the running band's id for it, and the curated raw value. */
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
        // Cheats and game mode are the world-defaults master's to impose; with it off the world opens vanilla,
        // so cheats fall back to off here. Noon is a fixed invariant, applied unconditionally below.
        if (worldOutput.overrideWorldDefaults() && worldOutput.allowCommands()) {
            settings.enableSinglePlayerCommands();
        }

        String levelName = worldName == null || worldName.isEmpty() ? LEVEL_NAME : worldName;
        net.minecraft.world.level.storage.LevelData levelData = new net.minecraft.world.level.storage.LevelData(
                settings, levelName);
        GameRuleResolution gameRuleResolution = applyGameRules(levelData.getGameRules(), worldOutput);
        // Downloaded worlds always open at noon, a fixed world-open invariant. A fresh LevelData already opens clear
        // (raining, thundering, and their timers default off), so weather needs no write here.
        levelData.setDayTime(NOON);
        return new LevelDataWriter.LevelData(levelData, gameRuleResolution);
    }

    /**
     * The superflat options for a VOID world: a single air layer over the void biome. Empty layers cannot express it
     * because {@code FlatLevelGeneratorSettings.fromObject} falls back to the default flat preset on an empty layer
     * list, so the void is a one-block air layer, which leaves the whole column air.
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

    /**
     * Resolve the curated set with the user's overrides against this band's live rules, apply the effective rules to
     * {@code gameRules} through the offline {@code loadFromTag} write, and log the dropped/unknown diagnostics. Returns
     * the resolution.
     */
    private static GameRuleResolution applyGameRules(GameRules gameRules, WorldOutputConfig worldOutput) {
        Map<String, GameRules.Value<?>> available = availableRulesById(gameRules);
        GameRuleSchema schema = new GameRuleSchema() {
            @Override
            public boolean hasRule(String id) {
                return available.containsKey(id);
            }

            @Override
            public boolean acceptsValue(String id, String rawValue) {
                GameRules.Value<?> rule = available.get(id);
                return rule != null && valueParses(rule, rawValue);
            }
        };
        GameRuleResolution resolution = worldOutput.resolveGameRules(curatedByBandId(), schema);
        // There is no public per-value string setter (IntegerValue.deserialize is protected), so effective rules go
        // through the offline loadFromTag. At this band that resets every rule from the tag: an absent id reads back
        // from the empty string to false or zero, so the tag is seeded from the level's current defaults before the
        // effective values are overlaid, leaving non-curated rules at their vanilla defaults.
        CompoundTag ruleTag = gameRules.createTag();
        for (Map.Entry<String, String> rule : resolution.effective().entrySet()) {
            if (available.containsKey(rule.getKey())) {
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

    /** The curated set keyed by the band's own rule id, the shape {@link WorldOutputConfig#resolveGameRules} merges. */
    private static Map<String, String> curatedByBandId() {
        Map<String, String> byBandId = new LinkedHashMap<>();
        for (CuratedSpec spec : CURATED_GAME_RULES) {
            byBandId.put(spec.bandId(), spec.curatedValue());
        }
        return byBandId;
    }

    /** Whether the raw string is a valid value for this rule: true or false for a boolean, an int for an integer. */
    private static boolean valueParses(GameRules.Value<?> rule, String rawValue) {
        if (rule instanceof GameRules.BooleanValue) {
            return "true".equals(rawValue) || "false".equals(rawValue);
        }
        if (rule instanceof GameRules.IntegerValue) {
            try {
                Integer.parseInt(rawValue);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /** Index the rules available at this band by their short id, for lookup and validation. */
    private static Map<String, GameRules.Value<?>> availableRulesById(GameRules gameRules) {
        Map<String, GameRules.Value<?>> byId = new HashMap<>();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                byId.put(key.getId(), gameRules.getRule(key));
            }
        });
        return byId;
    }

    @Override
    public List<CuratedGameRule> curatedGameRules() {
        Map<String, GameRules.Value<?>> byId = availableRulesById(new GameRules());
        List<CuratedGameRule> rules = new ArrayList<>();
        for (CuratedSpec spec : CURATED_GAME_RULES) {
            GameRules.Value<?> rule = byId.get(spec.bandId());
            if (rule == null) {
                continue; // a curated rule with no rule at this band is omitted, and the menu skips its order slot
            }
            if (rule instanceof GameRules.BooleanValue) {
                rules.add(new CuratedGameRule(spec.wdlId(), spec.bandId(), spec.curatedValue(), "true", "false"));
            } else {
                // An integer rule's enabled position is its band default, and disabled is 0.
                String enabled = String.valueOf(((GameRules.IntegerValue) rule).get());
                rules.add(new CuratedGameRule(spec.wdlId(), spec.bandId(), spec.curatedValue(), enabled, "0"));
            }
        }
        return Collections.unmodifiableList(rules);
    }

    // The WDL names are the band-neutral curated keys; each maps to its 1.15.2 vanilla rule id here. A curated name
    // with no rule at this band (the newer bands' fire and warden rules) is dropped at runtime, so this list is the
    // superset.
    private static List<CuratedSpec> buildCuratedGameRules() {
        List<CuratedSpec> curated = new ArrayList<>();
        curated.add(new CuratedSpec("spawn_mobs", "doMobSpawning", "false"));
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
            storage.saveLevelData(data.worldData(), null);
            return;
        }
        net.minecraft.world.level.storage.LevelData levelData = data.worldData();
        levelData.setGameType(player.gameType());
        // 1.15.2 setSpawn takes only a position; the spawn yaw has no level.dat field at this band.
        levelData.setSpawn(player.spawnPos());
        levelData.setDifficulty(player.difficulty());
        storage.saveLevelData(levelData, player.playerTag());
    }

    @Override
    public @Nullable CompoundTag readPriorPlayer(Path levelDatFile) {
        if (!Files.exists(levelDatFile)) {
            return null;
        }
        // 1.15.2 NbtIo.readCompressed takes an InputStream, not a File.
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
