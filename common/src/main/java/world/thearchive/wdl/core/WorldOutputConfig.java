// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

public final class WorldOutputConfig {
    static final String GAME_RULE_PREFIX = "gamerule.";

    public static final WorldOutputConfig DEFAULTS = new WorldOutputConfig(true, Collections.emptyMap(), true, true,
            true, false, false, WorldType.VOID, 0L, false);

    private final boolean overrideGameRules;
    private final Map<String, String> gameRuleOverrides;
    private final boolean overrideWorldDefaults;
    private final boolean openInCreative;
    private final boolean allowCommands;
    private final boolean skipVoidChunks;
    private final boolean autoDownload;
    private final WorldType worldType;
    private final long worldSeed;
    private final boolean generateFeatures;

    WorldOutputConfig(boolean overrideGameRules, Map<String, String> gameRuleOverrides,
            boolean overrideWorldDefaults, boolean openInCreative,
            boolean allowCommands, boolean skipVoidChunks, boolean autoDownload,
            WorldType worldType, long worldSeed, boolean generateFeatures) {
        this.overrideGameRules = overrideGameRules;
        this.gameRuleOverrides = Collections.unmodifiableMap(new TreeMap<>(gameRuleOverrides));
        this.overrideWorldDefaults = overrideWorldDefaults;
        this.openInCreative = openInCreative;
        this.allowCommands = allowCommands;
        this.skipVoidChunks = skipVoidChunks;
        this.autoDownload = autoDownload;
        this.worldType = worldType;
        this.worldSeed = worldSeed;
        this.generateFeatures = generateFeatures;
    }

    public boolean overrideGameRules() {
        return overrideGameRules;
    }

    public Map<String, String> gameRuleOverrides() {
        return gameRuleOverrides;
    }

    public boolean overrideWorldDefaults() {
        return overrideWorldDefaults;
    }

    public boolean openInCreative() {
        return openInCreative;
    }

    public boolean allowCommands() {
        return allowCommands;
    }

    public boolean skipVoidChunks() {
        return skipVoidChunks;
    }

    public boolean autoDownload() {
        return autoDownload;
    }

    public WorldType worldType() {
        return worldType;
    }

    public long worldSeed() {
        return worldSeed;
    }

    public boolean generateFeatures() {
        return generateFeatures;
    }

    public static WorldOutputConfig parse(Properties properties) {
        return parse(properties, new ArrayList<>());
    }

    static WorldOutputConfig parse(Properties properties, List<String> malformed) {
        return from(ConfigSchema.read(properties, malformed), properties);
    }

    static WorldOutputConfig from(ConfigValues values, Properties properties) {
        return new WorldOutputConfig(
                values.booleanValue("overrideGamerules"),
                parseGameRuleOverrides(properties),
                values.booleanValue("overrideWorldDefaults"),
                values.booleanValue("openInCreative"),
                values.booleanValue("allowCommands"),
                values.booleanValue("skipVoidChunks"),
                values.booleanValue("autoDownload"),
                values.enumValue("worldType", WorldType.class),
                values.longValue("worldSeed"),
                values.booleanValue("generateFeatures"));
    }

    private static Map<String, String> parseGameRuleOverrides(Properties properties) {
        Map<String, String> overrides = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(GAME_RULE_PREFIX) && key.length() > GAME_RULE_PREFIX.length()) {
                overrides.put(key.substring(GAME_RULE_PREFIX.length()), properties.getProperty(key));
            }
        }
        return overrides;
    }

    public GameRuleResolution resolveGameRules(Map<String, String> curated, GameRuleSchema schema) {
        Map<String, String> effective = new LinkedHashMap<>();
        List<String> droppedInvalidValues = new ArrayList<>();
        List<String> unknownIds = new ArrayList<>();
        if (overrideGameRules) {
            effective.putAll(curated);
            for (Map.Entry<String, String> override : gameRuleOverrides.entrySet()) {
                String id = override.getKey();
                String value = override.getValue();
                if (!schema.hasRule(id)) {
                    unknownIds.add(id);
                } else if (!schema.acceptsValue(id, value)) {
                    droppedInvalidValues.add(id);
                } else {
                    effective.put(id, value);
                }
            }
        }
        return new GameRuleResolution(effective, droppedInvalidValues, unknownIds);
    }
}
