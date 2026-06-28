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
 * out once rather than in five parallel places. The typed value object ({@link WdlConfig}) keeps its fields and
 * accessors; only the model's expression is centralized here.
 *
 * <p>Two orderings are preserved because they genuinely differ: {@link #OPTIONS} is in template (file) order, so
 * {@link #renderDefaultTemplate} is a straight in-order walk, while {@link #REPORT_ORDER} is the reportable
 * {@link WdlConfig} scalar keys in value-object field order, which the download report pins.
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

    private static final String ENCODE_BUDGET_MILLIS_PREAMBLE = ""
            + "# Max milliseconds per tick (1 to 10) spent encoding chunks/entities, so loading a fresh area\n"
            + "# or flying fast never stutters the frame; the rest spills to later ticks (the download lags\n"
            + "# exploration by a few ticks). Higher catches up faster but costs more per frame; lower is\n"
            + "# smoother but lags more behind you.\n";

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

    static final List<ConfigOption> OPTIONS = Collections.unmodifiableList(Arrays.asList(
            ConfigOption.rangedInteger("encodeBudgetMillis", "2", 1, 10, config -> config.encodeBudgetMillis(),
                    ENCODE_BUDGET_MILLIS_PREAMBLE),
            ConfigOption.booleanOption("appendDateSuffix", "true", config -> config.appendDateSuffix(),
                    APPEND_DATE_SUFFIX_PREAMBLE),
            ConfigOption.booleanOption("showChatMessages", "true", config -> config.showChatMessages(),
                    SHOW_CHAT_MESSAGES_PREAMBLE)));

    private static final Map<String, ConfigOption> BY_KEY = Collections.unmodifiableMap(indexByKey(OPTIONS));

    static final List<ConfigOption> REPORT_ORDER = report("encodeBudgetMillis", "appendDateSuffix", "showChatMessages");

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
     * default template, each option carrying its value. The settings-menu commit writes this so an in-mod Save keeps
     * the file's comments rather than degrading it to a bare {@code key=value} dump; a commit of an unchanged config
     * reproduces the default template byte for byte.
     */
    public static String renderConfigFile(WdlConfig config) {
        Map<String, String> values = project(config);
        StringBuilder file = new StringBuilder(HEADER).append(VERSION_BLOCK);
        for (ConfigOption option : OPTIONS) {
            file.append(option.preamble).append(option.key).append('=').append(values.get(option.key)).append('\n');
        }
        return file.toString();
    }

    /**
     * The complete key-to-string view of a live config: every option rendered by its type. Order is not
     * contract-pinned.
     */
    static Map<String, String> project(WdlConfig config) {
        Map<String, String> projection = new LinkedHashMap<>();
        for (ConfigOption option : OPTIONS) {
            projection.put(option.key, option.type.format(option.accessor.apply(config)));
        }
        return projection;
    }

    /** The reportable {@link WdlConfig} scalars that differ from {@code baseline}, each by its key, in field order. */
    static Map<String, String> reportDiff(WdlConfig config, WdlConfig baseline) {
        return diffByOrder(REPORT_ORDER, config, baseline);
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
