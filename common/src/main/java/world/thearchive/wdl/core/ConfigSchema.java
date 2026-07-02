// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * The config model: one ordered descriptor list from which the documented template, the parse and validation, the
 * defaults, the download-report diff, and a complete key-to-string projection are all derived, so an option is spelled
 * out once rather than in five parallel places. The typed value objects ({@link WdlConfig} and its nested holders) keep
 * their fields and accessors; only the model's expression is centralized here.
 *
 * <p>Two orderings are preserved because they genuinely differ: {@link #OPTIONS} is in template (file) order, so
 * {@link #renderDefaultTemplate} is a straight in-order walk, while {@link #REPORT_ORDER} is the reportable
 * {@link WdlConfig} scalar keys in value-object field order, which the download report pins. The sparse
 * {@code gamerule.*} overrides are not a descriptor row: they are a dynamic map handled as literal preamble bytes, a
 * harvested map, and a projection append.
 */
public final class ConfigSchema {
    private ConfigSchema() {}

    private static final String HEADER = ""
            + "# Archive World Downloader configuration.\n"
            + "# Edit and save; changes apply on the next download (no client restart needed).\n"
            + "\n";

    private static final String VERSION_BLOCK = ""
            + "# Config schema version, set by the mod (do not edit); lets a future version migrate an old config.\n"
            + "configVersion=" + WdlConfig.CONFIG_VERSION + "\n";

    private static final String CAPTURE_TOGGLES_PREAMBLE = ""
            + "\n"
            + "# Content toggles: which kinds of data to include in the download. The world's terrain is always\n"
            + "# downloaded; these choose the extra data kinds, so set any to false to skip it.\n";

    private static final String RECAPTURE_CHUNKS_PREAMBLE = ""
            + "\n"
            + "# How current the download keeps the world as you explore (a lower mode costs less per tick).\n"
            + "# OFF: Download each area once and keep it as you first saw it.\n"
            + "# NEARBY: Keep the area around you up to date, but leave already-downloaded areas as you first\n"
            + "# saved them.\n"
            + "# EVERYWHERE (default): Keep areas you revisit up to date too, re-saving terrain that changed\n"
            + "# while you were away. Contents are the exception: a Chest, Barrel, or Lectern can only be\n"
            + "# saved by opening it, so a revisit refreshes the blocks but keeps the items from the last\n"
            + "# time you opened it. Reopen a container to bring its contents up to date.\n";

    private static final String RECAPTURE_SECONDS_PREAMBLE = ""
            + "# How often (seconds, 5 to 60) the full set of nearby chunks is refreshed. Higher is cheaper\n"
            + "# but lets a far change wait longer before it is refreshed; the area right around you stays\n"
            + "# current regardless.\n";

    private static final String ENCODE_BUDGET_MILLIS_PREAMBLE = ""
            + "# Max milliseconds per tick (1 to 10) spent encoding chunks/entities, so loading a fresh area\n"
            + "# or flying fast never stutters the frame; the rest spills to later ticks (the download lags\n"
            + "# exploration by a few ticks). Higher catches up faster but costs more per frame; lower is\n"
            + "# smoother but lags more behind you.\n";

    private static final String FORCE_MOB_PERSISTENCE_PREAMBLE = ""
            + "\n"
            + "# Keep every mob you download from despawning when the world is opened, not just name-tagged ones.\n"
            + "# Name-tagged mobs (pets, named Villagers) always survive the open regardless of this setting.\n"
            + "# Off by default: leaving it off matches vanilla, where an ordinary un-named mob may wander off or\n"
            + "# despawn. Set true to pin every downloaded mob in place so your scene comes out exactly as "
            + "downloaded.\n";

    private static final String WORLD_TYPE_PREAMBLE = ""
            + "\n"
            + "# What fills the space between the chunks you downloaded. Your downloaded area is always the real "
            + "thing;\n"
            + "# this only governs the surroundings.\n"
            + "# VOID (default): superflat empty air, the honest archive view of just your build against nothing.\n"
            + "# DEFAULT: normal terrain generation. FLAT: superflat layers. DEFAULT and FLAT fill the gaps with\n"
            + "# freshly generated terrain that is plausible but NOT the server's real land (a client cannot recover\n"
            + "# the server's seed), so they are off by default; the downloaded chunks still blend into it without a\n"
            + "# vertical boundary wall.\n";

    private static final String WORLD_SEED_PREAMBLE = ""
            + "# Seed for DEFAULT/FLAT terrain: the full 64-bit range, and any non-numeric text is hashed exactly as\n"
            + "# the vanilla seed field does. Ignored by VOID. 0 by default.\n";

    private static final String GENERATE_FEATURES_PREAMBLE = ""
            + "# Generate structures (villages, strongholds, and the rest) in DEFAULT/FLAT terrain. Off by default.\n";

    private static final String OVERRIDE_GAME_RULES_PREAMBLE = ""
            + "\n"
            + "# Game rules written into the download so it opens calm and safe. On by default: the download\n"
            + "# writes a curated safe set (no mob spawning or griefing, no fire or vine spread, day and weather\n"
            + "# frozen, inventory kept). Set false to write no game rules at all (the world's vanilla defaults\n"
            + "# stand).\n";

    private static final String OVERRIDE_WORLD_DEFAULTS_PREAMBLE = ""
            + "# Override or add an individual rule with gamerule.<id>=<value>, using this Minecraft version's\n"
            + "# rule id (the ids differ across versions). An id that does not exist here, or a value that does\n"
            + "# not fit the rule, is skipped and never written, so a typo can never make the world fail to open.\n"
            + "# Examples (commented out):\n"
            + "#gamerule.keep_inventory=false\n"
            + "#gamerule.random_tick_speed=0\n"
            + "\n"
            + "# The world-open state the download imposes: cheats.\n"
            + "# On by default. Set false to open with cheats off.\n";

    private static final String ALLOW_COMMANDS_PREAMBLE = ""
            + "\n"
            + "# Enable cheats in the downloaded world, so commands like /gamemode and /tp work when you open it\n"
            + "# in singleplayer. On by default. Set false to open it as a normal world with cheats off.\n"
            + "# Applied only when overrideWorldDefaults is on.\n";

    private static final String SKIP_VOID_CHUNKS_PREAMBLE = ""
            + "\n"
            + "# Skip a completely empty downloaded chunk (all air: no blocks, block entities, entities, or\n"
            + "# containers) to save space. Off by default. A chunk holding anything at all, even a single Boat\n"
            + "# or a pet over a void gap, is always saved. A skipped chunk is regenerated by the world's generator\n"
            + "# when the download is opened: under VOID it returns as empty air (no change), but under DEFAULT or\n"
            + "# FLAT it fills with terrain, so a genuinely-empty area you downloaded comes back as generated "
            + "ground.\n";

    private static final String AUTO_DOWNLOAD_PREAMBLE = ""
            + "\n"
            + "# Automatically start a download when you join a multiplayer server. Off by default.\n";

    private static final String APPEND_DATE_SUFFIX_PREAMBLE = ""
            + "\n"
            + "# Add a -YYYY-MM-DD date to a new download's save folder (and to the world's name inside it), so\n"
            + "# downloads of the same place made on different days sit side by side instead of clashing. On by\n"
            + "# default. Set false to use the name exactly as typed; a same-name download then lands on the\n"
            + "# existing folder and merges into it rather than overwriting.\n";

    private static final String SHOW_CHAT_MESSAGES_PREAMBLE = ""
            + "\n"
            + "# Chat notices from the mod, like the update-available line. Set false to silence them; notices\n"
            + "# inside the mod's own screens are unaffected.\n";

    private static final String DUMP_RECEIVED_FRAMES_PREAMBLE = ""
            + "\n"
            + "# Diagnostics (advanced); leave off for normal use.\n"
            + "# Dumps the position of every item frame the client received to wdl/received-item-frames.txt,\n"
            + "# so a missing frame can be checked against what was actually received (a download-loss audit).\n"
            + "# Off by default; it collects every received item frame in memory and writes a file at save.\n";

    static final List<ConfigOption> OPTIONS = Collections.unmodifiableList(Arrays.asList(
            ConfigOption.dataLossBoolean("captureEntities", "true", config -> config.captureEntities(),
                    CAPTURE_TOGGLES_PREAMBLE),
            ConfigOption.enumOption("recaptureChunks", "EVERYWHERE", name -> RecaptureMode.valueOf(name),
                    config -> config.recaptureChunks(), RECAPTURE_CHUNKS_PREAMBLE),
            ConfigOption.rangedInteger("recaptureSeconds", "15", 5, 60, config -> config.recaptureSeconds(),
                    RECAPTURE_SECONDS_PREAMBLE),
            ConfigOption.rangedInteger("encodeBudgetMillis", "2", 1, 10, config -> config.encodeBudgetMillis(),
                    ENCODE_BUDGET_MILLIS_PREAMBLE),
            ConfigOption.booleanOption("forceMobPersistence", "false", config -> config.forceMobPersistence(),
                    FORCE_MOB_PERSISTENCE_PREAMBLE),
            ConfigOption.enumOption("worldType", "VOID", name -> WorldType.valueOf(name),
                    config -> config.worldOutput().worldType(), WORLD_TYPE_PREAMBLE),
            ConfigOption.longSeed("worldSeed", "0", config -> config.worldOutput().worldSeed(), WORLD_SEED_PREAMBLE),
            ConfigOption.booleanOption("generateFeatures", "false", config -> config.worldOutput().generateFeatures(),
                    GENERATE_FEATURES_PREAMBLE),
            ConfigOption.booleanOption("overrideGamerules", "true", config -> config.worldOutput().overrideGameRules(),
                    OVERRIDE_GAME_RULES_PREAMBLE),
            ConfigOption.booleanOption("overrideWorldDefaults", "true",
                    config -> config.worldOutput().overrideWorldDefaults(),
                    OVERRIDE_WORLD_DEFAULTS_PREAMBLE),
            ConfigOption.booleanOption("allowCommands", "true", config -> config.worldOutput().allowCommands(),
                    ALLOW_COMMANDS_PREAMBLE),
            ConfigOption.booleanOption("skipVoidChunks", "false", config -> config.worldOutput().skipVoidChunks(),
                    SKIP_VOID_CHUNKS_PREAMBLE),
            ConfigOption.booleanOption("autoDownload", "false", config -> config.worldOutput().autoDownload(),
                    AUTO_DOWNLOAD_PREAMBLE),
            ConfigOption.booleanOption("appendDateSuffix", "true", config -> config.appendDateSuffix(),
                    APPEND_DATE_SUFFIX_PREAMBLE),
            ConfigOption.booleanOption("showChatMessages", "true", config -> config.showChatMessages(),
                    SHOW_CHAT_MESSAGES_PREAMBLE),
            ConfigOption.booleanOption("dumpReceivedFrames", "false", config -> config.dumpReceivedFrames(),
                    DUMP_RECEIVED_FRAMES_PREAMBLE)));

    private static final Map<String, ConfigOption> BY_KEY = Collections.unmodifiableMap(indexByKey(OPTIONS));

    static final List<ConfigOption> REPORT_ORDER = report(
            "captureEntities", "recaptureChunks", "recaptureSeconds", "encodeBudgetMillis",
            "forceMobPersistence", "dumpReceivedFrames", "appendDateSuffix", "showChatMessages");

    static final List<ConfigOption> WORLD_OUTPUT_REPORT_ORDER = report(
            "overrideGamerules", "overrideWorldDefaults",
            "allowCommands", "skipVoidChunks", "autoDownload", "worldType", "worldSeed", "generateFeatures");

    /**
     * Read every option, defaulting any key that is missing or present-but-unparseable to its descriptor default
     * (recording the latter in {@code malformed} so the file self-heals). Never fails.
     */
    static ConfigValues read(Properties properties, List<String> malformed) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (ConfigOption option : OPTIONS) {
            String raw = properties.getProperty(option.key);
            Object value = raw == null ? null : option.type.parse(option, raw, malformed);
            if (value == null) {
                value = defaultValueOf(option);
            }
            values.put(option.key, value);
        }
        return new ConfigValues(values);
    }

    private static Object defaultValueOf(ConfigOption option) {
        return Objects.requireNonNull(option.type.parse(option, option.defaultValue, new ArrayList<>()), option.key);
    }

    /** The documented default file, byte-identical to what the mod writes on first run. */
    static String renderDefaultTemplate() {
        StringBuilder template = new StringBuilder(HEADER).append(VERSION_BLOCK);
        for (ConfigOption option : OPTIONS) {
            template.append(option.preamble).append(option.key).append('=').append(option.defaultValue).append('\n');
        }
        return template.toString();
    }

    /**
     * The documented config file rendered with {@code config}'s live values: the same preambles and key order as the
     * default template, each option carrying its value, then any {@code gamerule.<id>} override appended. The
     * settings-menu commit writes this so an in-mod Save keeps the file's comments rather than degrading it to a bare
     * {@code key=value} dump; a commit of an unchanged config reproduces the default template byte for byte.
     */
    public static String renderConfigFile(WdlConfig config) {
        Map<String, String> values = project(config);
        StringBuilder file = new StringBuilder(HEADER).append(VERSION_BLOCK);
        for (ConfigOption option : OPTIONS) {
            file.append(option.preamble).append(option.key).append('=').append(values.get(option.key)).append('\n');
        }
        for (Map.Entry<String, String> override : values.entrySet()) {
            if (override.getKey().startsWith(WorldOutputConfig.GAME_RULE_PREFIX)) {
                file.append(override.getKey()).append('=').append(override.getValue()).append('\n');
            }
        }
        return file.toString();
    }

    /**
     * The complete key-to-string view of a live config: every option rendered by its type, plus each configured
     * {@code gamerule.<id>} override appended. Order is not contract-pinned.
     */
    static Map<String, String> project(WdlConfig config) {
        Map<String, String> projection = new LinkedHashMap<>();
        for (ConfigOption option : OPTIONS) {
            projection.put(option.key, option.type.format(option.accessor.apply(config)));
        }
        for (Map.Entry<String, String> override : config.worldOutput().gameRuleOverrides().entrySet()) {
            projection.put(WorldOutputConfig.GAME_RULE_PREFIX + override.getKey(), override.getValue());
        }
        return projection;
    }

    /**
     * The reportable {@link WdlConfig} scalars that differ from {@code baseline}, each by its key, in field order.
     * Excludes the nested world-output options; the caller appends the world-output diff.
     */
    static Map<String, String> reportDiff(WdlConfig config, WdlConfig baseline) {
        return diffByOrder(REPORT_ORDER, config, baseline);
    }

    /**
     * The world-output scalars that differ from {@code baseline}, each by its key, in field order. The sparse
     * {@code gamerule.*} overrides are not diffable scalars (an override is inherently a change, with no per-rule
     * default to diff against), so they are excluded here and reported through the override list instead.
     */
    static Map<String, String> worldOutputDiff(WdlConfig config, WdlConfig baseline) {
        return diffByOrder(WORLD_OUTPUT_REPORT_ORDER, config, baseline);
    }

    private static Map<String, String> diffByOrder(List<ConfigOption> order, WdlConfig config, WdlConfig baseline) {
        Map<String, String> changed = new LinkedHashMap<>();
        for (ConfigOption option : order) {
            String mine = option.type.format(option.accessor.apply(config));
            String theirs = option.type.format(option.accessor.apply(baseline));
            if (!mine.equals(theirs)) {
                changed.put(option.key, mine);
            }
        }
        return changed;
    }

    private static Map<String, ConfigOption> indexByKey(List<ConfigOption> options) {
        Map<String, ConfigOption> byKey = new LinkedHashMap<>();
        for (ConfigOption option : options) {
            byKey.put(option.key, option);
        }
        return byKey;
    }

    private static List<ConfigOption> report(String... keys) {
        List<ConfigOption> ordered = new ArrayList<>();
        for (String key : keys) {
            ordered.add(Objects.requireNonNull(BY_KEY.get(key), key));
        }
        return Collections.unmodifiableList(ordered);
    }
}
