// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The band-agnostic, hand-editable mod config ({@code wdl.properties}). MC-free (java.util/java.nio only) so the core
 * owns it on every band with no per-loader config API. {@link #load(Path)} materializes a documented default file on
 * first run; the per-key comments are written from a template because {@code Properties.store} cannot emit them. The
 * template, parse, defaults, and report diff are all derived from one descriptor list ({@link ConfigSchema}); the typed
 * fields and accessors below stay hand-written.
 */
public final class WdlConfig {
    /** The config schema version; bump it and add a migrator when a key's name or value shape changes. */
    static final int CONFIG_VERSION = 1;

    /** The schema-derived defaults: what an empty {@code Properties} parses to, so every default has one source. */
    public static final WdlConfig DEFAULTS = parse(new Properties());

    private static final Logger LOGGER = Logger.getLogger(WdlConfig.class.getName());

    private final int encodeBudgetMillis;
    private final boolean appendDateSuffix;
    private final boolean showChatMessages;
    private final WorldOutputConfig worldOutput;

    private WdlConfig(int encodeBudgetMillis, boolean appendDateSuffix, boolean showChatMessages,
            WorldOutputConfig worldOutput) {
        this.encodeBudgetMillis = encodeBudgetMillis;
        this.appendDateSuffix = appendDateSuffix;
        this.showChatMessages = showChatMessages;
        this.worldOutput = worldOutput;
    }

    /** Max milliseconds per tick spent encoding chunks/entities; the rest spills to later ticks (smoothness knob). */
    public int encodeBudgetMillis() {
        return encodeBudgetMillis;
    }

    /** Whether a new download's resolved name is decorated with a {@code -YYYY-MM-DD} suffix (default on). */
    public boolean appendDateSuffix() {
        return appendDateSuffix;
    }

    /** Whether the mod's chat notices (the update-available line) are shown; in-screen notices ignore it. */
    public boolean showChatMessages() {
        return showChatMessages;
    }

    /** The world-output options (game-rule overrides, world-open defaults, and the two capture knobs). */
    public WorldOutputConfig worldOutput() {
        return worldOutput;
    }

    /**
     * The capture-time settings diffed against {@code baseline}: only the leaves that differ, keyed by their config
     * key, in declaration order. Values render via the locale-independent {@code toString} of the JDK types, so the
     * diff is stable on any machine. With no change the map is empty.
     */
    Map<String, String> changedFrom(WdlConfig baseline) {
        Map<String, String> changed = ConfigSchema.reportDiff(this, baseline);
        changed.putAll(ConfigSchema.worldOutputDiff(this, baseline));
        return changed;
    }

    /**
     * The config schema version stamped in {@code properties}, or the current {@link #CONFIG_VERSION} when the key is
     * absent (a hand-deleted version line is treated as current, since no migrator exists yet). This is file-format
     * metadata read outside the schema, not a user setting, so it never appears in {@link #changedFrom(WdlConfig)} and
     * a bad value keeps its silent fallback rather than self-healing.
     */
    static int configVersion(Properties properties) {
        String raw = properties.getProperty("configVersion");
        if (raw != null) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                // Metadata: a garbage version keeps the silent fallback, no file reset
            }
        }
        return CONFIG_VERSION;
    }

    /** Read every key, defaulting any that is missing; never fails. */
    public static WdlConfig parse(Properties properties) {
        return parse(properties, new ArrayList<>());
    }

    /**
     * As {@link #parse(Properties)}, but recording into {@code malformed} the key of any typed scalar whose present
     * value does not parse to its type (a non-boolean for a flag, a non-integer for a count), so {@link #load(Path)}
     * can self-heal the file. A missing key keeps its default and is not malformed.
     */
    static WdlConfig parse(Properties properties, List<String> malformed) {
        ConfigValues values = ConfigSchema.read(properties, malformed);
        return new WdlConfig(values.integer("encodeBudgetMillis"), values.booleanValue("appendDateSuffix"),
                values.booleanValue("showChatMessages"), WorldOutputConfig.from(values, properties));
    }

    /**
     * Load the config at {@code file}, or materialize the documented default file (and return {@link #DEFAULTS}) if it
     * is absent. A file holding a malformed typed value (a non-boolean for a flag, a non-integer for a count) has only
     * that key healed to its default, every other valid setting kept (including any {@code gamerule.*} override), and
     * the healed result is written back, rather than silently parsed as the wrong thing. Falls back to
     * {@link #DEFAULTS} if the file cannot be read or written, so a broken or unwritable config never stops the mod.
     */
    public static WdlConfig load(Path file) {
        try {
            if (Files.exists(file)) {
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                List<String> malformed = new ArrayList<>();
                WdlConfig config = parse(properties, malformed);
                if (!malformed.isEmpty()) {
                    LOGGER.warning("wdl.properties has an invalid value for " + malformed
                            + "; healing those keys to their defaults and keeping every valid setting");
                    write(file, ConfigSchema.renderConfigFile(config));
                }
                return config;
            }
            write(file, ConfigSchema.renderDefaultTemplate());
            return DEFAULTS;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "wdl.properties could not be read or written; using the defaults "
                    + "for this session", e);
            return DEFAULTS;
        }
    }

    private static void write(Path file, String content) throws IOException {
        AtomicFileWrite.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
