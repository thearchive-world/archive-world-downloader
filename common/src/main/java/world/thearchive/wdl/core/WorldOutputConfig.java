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

/**
 * The world-output side of the config: the generator selection (world type, seed, structures), the two masters, the
 * world-open flags, the two capture knobs, and the sparse {@code gamerule.*} override map. A single nested value object
 * composed onto {@link WdlConfig} (which delegates its parse and defaults here), so {@link WdlConfig}'s constructor
 * stays sane. The scalar fields are constructed from a schema read via {@link #from(ConfigValues, Properties)}; only
 * the sparse gamerule harvest and the per-band merge stay here. MC-free and headless-testable: the per-band variance
 * (the curated safe set, the rule ids/types, the storage path) lives in the {@code LevelDataWriter} impls, reached here
 * only through {@link GameRuleSchema}.
 */
public final class WorldOutputConfig {
    /** The config-key prefix for the sparse override map: one {@code gamerule.<bandId>} key per changed rule. */
    static final String GAME_RULE_PREFIX = "gamerule.";

    /**
     * Both masters on, creative on, cheats on, both capture knobs off, no rule overrides, and the honest void generator
     * (no seed, no structures).
     */
    public static final WorldOutputConfig DEFAULTS = new WorldOutputConfig(true, Collections.emptyMap(), true,
            true, false, false, WorldType.VOID, 0L, false);

    private final boolean overrideGameRules;
    private final Map<String, String> gameRuleOverrides;
    private final boolean overrideWorldDefaults;
    private final boolean allowCommands;
    private final boolean skipVoidChunks;
    private final boolean autoDownload;
    private final WorldType worldType;
    private final long worldSeed;
    private final boolean generateFeatures;

    WorldOutputConfig(boolean overrideGameRules, Map<String, String> gameRuleOverrides,
            boolean overrideWorldDefaults,
            boolean allowCommands, boolean skipVoidChunks, boolean autoDownload,
            WorldType worldType, long worldSeed, boolean generateFeatures) {
        this.overrideGameRules = overrideGameRules;
        this.gameRuleOverrides = Collections.unmodifiableMap(new TreeMap<>(gameRuleOverrides));
        this.overrideWorldDefaults = overrideWorldDefaults;
        this.allowCommands = allowCommands;
        this.skipVoidChunks = skipVoidChunks;
        this.autoDownload = autoDownload;
        this.worldType = worldType;
        this.worldSeed = worldSeed;
        this.generateFeatures = generateFeatures;
    }

    /** Master: when off, no game rules are written and the world's vanilla defaults stand. */
    public boolean overrideGameRules() {
        return overrideGameRules;
    }

    /** The sparse overrides, by that band's rule id to raw string value; empty by default. Never null. */
    public Map<String, String> gameRuleOverrides() {
        return gameRuleOverrides;
    }

    /** Master: when off, creative game mode and cheats are not imposed; the world opens in your captured mode. */
    public boolean overrideWorldDefaults() {
        return overrideWorldDefaults;
    }

    /**
     * Whether the downloaded world has cheats enabled, so commands work in singleplayer (gated by
     * {@link #overrideWorldDefaults()}).
     */
    public boolean allowCommands() {
        return allowCommands;
    }

    /** Whether a genuinely-empty (void) captured chunk is omitted from the download. */
    public boolean skipVoidChunks() {
        return skipVoidChunks;
    }

    /** Whether joining a remote world auto-starts a download. */
    public boolean autoDownload() {
        return autoDownload;
    }

    /** The generator the downloaded world uses for its un-captured surroundings; {@link WorldType#VOID} by default. */
    public WorldType worldType() {
        return worldType;
    }

    /** The full-{@code long} seed for a {@link WorldType#DEFAULT}/{@link WorldType#FLAT} world; irrelevant to void. */
    public long worldSeed() {
        return worldSeed;
    }

    /** Whether a {@link WorldType#DEFAULT}/{@link WorldType#FLAT} world generates structures; off by default. */
    public boolean generateFeatures() {
        return generateFeatures;
    }

    /** Read every key, defaulting any that is missing; never fails. */
    public static WorldOutputConfig parse(Properties properties) {
        return parse(properties, new ArrayList<>());
    }

    /** As {@link #parse(Properties)}, but recording any unparseable scalar in {@code malformed} for the file heal. */
    static WorldOutputConfig parse(Properties properties, List<String> malformed) {
        return from(ConfigSchema.read(properties, malformed), properties);
    }

    /**
     * The world-output fields drawn from a completed schema read: the scalars come from {@code values}, while the
     * sparse {@code gamerule.*} override map is harvested straight from {@code properties} (it is dynamic, not a
     * descriptor row).
     */
    static WorldOutputConfig from(ConfigValues values, Properties properties) {
        return new WorldOutputConfig(
                values.booleanValue("overrideGamerules"),
                parseGameRuleOverrides(properties),
                values.booleanValue("overrideWorldDefaults"),
                values.booleanValue("allowCommands"),
                values.booleanValue("skipVoidChunks"),
                values.booleanValue("autoDownload"),
                values.enumValue("worldType", WorldType.class),
                values.longValue("worldSeed"),
                values.booleanValue("generateFeatures"));
    }

    /** Each {@code gamerule.<id>} key, stripped of its prefix, sorted by id for a stable diff. */
    private static Map<String, String> parseGameRuleOverrides(Properties properties) {
        Map<String, String> overrides = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(GAME_RULE_PREFIX) && key.length() > GAME_RULE_PREFIX.length()) {
                overrides.put(key.substring(GAME_RULE_PREFIX.length()), properties.getProperty(key));
            }
        }
        return overrides;
    }

    /**
     * Merge the band's {@code curated} safe set with the user's overrides, gated on the master. Master off: an empty
     * resolution (vanilla defaults stand). Master on: the curated set, then each override applied on top, with
     * id-existence and value-type validated against {@code schema}: an id absent at this band is surfaced as
     * {@link GameRuleResolution#unknownIds()} and an unparseable value as
     * {@link GameRuleResolution#droppedInvalidValues()}, neither written, so a typo can never yield an unopenable
     * world. The override map is an arbitrary valid-rule passthrough, not limited to the curated set.
     */
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
