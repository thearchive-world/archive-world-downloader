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

    private final boolean captureEntities;
    private final boolean captureContainers;
    private final boolean captureAdvancements;
    private final boolean captureStatistics;
    private final RecaptureMode recaptureChunks;
    private final int recaptureSeconds;
    private final boolean savePlayerInventory;
    private final boolean savePlayerEnderChest;
    private final boolean saveItemCoordinates;
    private final boolean lockDownloadedMaps;
    private final boolean remapMapIds;
    private final int encodeBudgetMillis;
    private final boolean forceMobPersistence;
    private final boolean dumpReceivedFrames;
    private final boolean zipOnFinish;
    private final boolean zipOnResume;
    private final boolean appendDateSuffix;
    private final boolean confirmResume;
    private final boolean blockTaintedResume;
    private final boolean showToasts;
    private final boolean checkForUpdates;
    private final boolean showChatMessages;
    private final boolean renderCoverageOverlay;
    private final MarkerHue overlayCoveredColor;
    private final MarkerHue overlaySuspectColor;
    private final WorldOutputConfig worldOutput;
    private final HudConfig hud;
    private final OutlineConfig outline;

    private WdlConfig(boolean captureEntities, boolean captureContainers,
            boolean captureAdvancements, boolean captureStatistics,
            RecaptureMode recaptureChunks, int recaptureSeconds,
            boolean savePlayerInventory, boolean savePlayerEnderChest, boolean saveItemCoordinates,
            boolean lockDownloadedMaps, boolean remapMapIds, int encodeBudgetMillis, boolean forceMobPersistence,
            boolean dumpReceivedFrames, boolean zipOnFinish, boolean zipOnResume, boolean appendDateSuffix,
            boolean confirmResume, boolean blockTaintedResume, boolean showToasts, boolean checkForUpdates,
            boolean showChatMessages,
            boolean renderCoverageOverlay, MarkerHue overlayCoveredColor, MarkerHue overlaySuspectColor,
            WorldOutputConfig worldOutput, HudConfig hud, OutlineConfig outline) {
        this.captureEntities = captureEntities;
        this.captureContainers = captureContainers;
        this.captureAdvancements = captureAdvancements;
        this.captureStatistics = captureStatistics;
        this.recaptureChunks = recaptureChunks;
        this.recaptureSeconds = recaptureSeconds;
        this.savePlayerInventory = savePlayerInventory;
        this.savePlayerEnderChest = savePlayerEnderChest;
        this.saveItemCoordinates = saveItemCoordinates;
        this.lockDownloadedMaps = lockDownloadedMaps;
        this.remapMapIds = remapMapIds;
        this.encodeBudgetMillis = encodeBudgetMillis;
        this.forceMobPersistence = forceMobPersistence;
        this.dumpReceivedFrames = dumpReceivedFrames;
        this.zipOnFinish = zipOnFinish;
        this.zipOnResume = zipOnResume;
        this.appendDateSuffix = appendDateSuffix;
        this.confirmResume = confirmResume;
        this.blockTaintedResume = blockTaintedResume;
        this.showToasts = showToasts;
        this.checkForUpdates = checkForUpdates;
        this.showChatMessages = showChatMessages;
        this.renderCoverageOverlay = renderCoverageOverlay;
        this.overlayCoveredColor = overlayCoveredColor;
        this.overlaySuspectColor = overlaySuspectColor;
        this.worldOutput = worldOutput;
        this.hud = hud;
        this.outline = outline;
    }

    public boolean captureEntities() {
        return captureEntities;
    }

    public boolean captureContainers() {
        return captureContainers;
    }

    /** Whether to capture the player's advancement progress into {@code advancements/<uuid>.json}. */
    public boolean captureAdvancements() {
        return captureAdvancements;
    }

    /** Whether to capture the player's statistics into {@code stats/<uuid>.json}. */
    public boolean captureStatistics() {
        return captureStatistics;
    }

    /** How current the download keeps the world as the player records (snapshot-once, nearby-only, or on revisit). */
    public RecaptureMode recaptureChunks() {
        return recaptureChunks;
    }

    /** How often (seconds) the whole set of nearby buffered chunks is refreshed while recording. */
    public int recaptureSeconds() {
        return recaptureSeconds;
    }

    /** Whether to write the captured inventory (and selected slot) into the download. */
    public boolean savePlayerInventory() {
        return savePlayerInventory;
    }

    /** Whether to write the captured ender-chest contents (captured open-time) into the download. */
    public boolean savePlayerEnderChest() {
        return savePlayerEnderChest;
    }

    /** Whether to keep item-borne coordinates ({@code true}) or blank them for privacy ({@code false}). */
    public boolean saveItemCoordinates() {
        return saveItemCoordinates;
    }

    /** Whether captured filled maps are auto-locked ({@code true}, frozen image) or left live ({@code false}). */
    public boolean lockDownloadedMaps() {
        return lockDownloadedMaps;
    }

    /**
     * Whether captured filled maps are re-keyed to stable archive ids ({@code true}) or keep the original server ids
     * ({@code false}).
     */
    public boolean remapMapIds() {
        return remapMapIds;
    }

    /** Max milliseconds per tick spent encoding chunks/entities; the rest spills to later ticks (smoothness knob). */
    public int encodeBudgetMillis() {
        return encodeBudgetMillis;
    }

    /** Whether to stamp {@code PersistenceRequired} on every captured mob ({@code true}) or only named ones. */
    public boolean forceMobPersistence() {
        return forceMobPersistence;
    }

    /** Diagnostic (default off): dump every received item frame's position to {@code wdl/received-item-frames.txt}. */
    public boolean dumpReceivedFrames() {
        return dumpReceivedFrames;
    }

    /** Whether a finished download is also zipped to {@code <folder>.zip} beside the folder (default on). */
    public boolean zipOnFinish() {
        return zipOnFinish;
    }

    /**
     * Whether a resume first zips the existing folder to {@code <folder>-pre-resume.zip} before merging (default on).
     */
    public boolean zipOnResume() {
        return zipOnResume;
    }

    /** Whether a new download's resolved name is decorated with a {@code -YYYY-MM-DD} suffix (default on). */
    public boolean appendDateSuffix() {
        return appendDateSuffix;
    }

    /** Whether resuming into an existing folder asks for confirmation first, or continues silently (default on). */
    public boolean confirmResume() {
        return confirmResume;
    }

    /**
     * Whether resuming into a folder opened in singleplayer is blocked; off downgrades to a per-attempt confirm
     * (default on).
     */
    public boolean blockTaintedResume() {
        return blockTaintedResume;
    }

    /** Whether the job-done toasts (download complete, download error) are shown (default on). */
    public boolean showToasts() {
        return showToasts;
    }

    /** Whether the once-per-launch newer-release check runs at all; off means zero requests (default on). */
    public boolean checkForUpdates() {
        return checkForUpdates;
    }

    /** Whether the mod's chat notices (the update-available line) are shown; in-screen notices ignore it. */
    public boolean showChatMessages() {
        return showChatMessages;
    }

    /** Whether the saved-chunk coverage overlay draws while recording; the master over the two tone hues. */
    public boolean renderCoverageOverlay() {
        return renderCoverageOverlay;
    }

    /** The covered-chunk overlay hue; the overlay packs a fixed alpha onto its {@code rgb()}. */
    public MarkerHue overlayCoveredColor() {
        return overlayCoveredColor;
    }

    /** The suspect-chunk overlay hue, for terrain saved without its decorations in send range. */
    public MarkerHue overlaySuspectColor() {
        return overlaySuspectColor;
    }

    /** The world-output options (game-rule overrides, world-open defaults, and the two capture knobs). */
    public WorldOutputConfig worldOutput() {
        return worldOutput;
    }

    /** The HUD-overlay options (anchor, offset, layout, peek mode, panel, linger, tint). */
    public HudConfig hud() {
        return hud;
    }

    /** The unsaved-container outline options (master toggle, render distance, the two state hues). */
    public OutlineConfig outline() {
        return outline;
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
     * The settings to show in the download report: every scalar field that differs from {@link #DEFAULTS}, plus each
     * configured game-rule override by its {@code gamerule.<id>} key. An override is inherently a non-default setting
     * (the model has no per-rule default), so it is listed here rather than diffed by {@link #changedFrom(WdlConfig)}.
     * With a pristine config the map is empty.
     */
    public Map<String, String> nonDefaultSettings() {
        Map<String, String> settings = changedFrom(DEFAULTS);
        for (Map.Entry<String, String> override : worldOutput.gameRuleOverrides().entrySet()) {
            settings.put(WorldOutputConfig.GAME_RULE_PREFIX + override.getKey(), override.getValue());
        }
        return settings;
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
     * can self-heal the file. A missing key keeps its default and is not malformed, and the sparse {@code gamerule.*}
     * overrides are raw strings validated per band at write time, not here.
     */
    static WdlConfig parse(Properties properties, List<String> malformed) {
        ConfigValues values = ConfigSchema.read(properties, malformed);
        return new WdlConfig(
                values.booleanValue("captureEntities"),
                values.booleanValue("captureContainers"),
                values.booleanValue("captureAdvancements"),
                values.booleanValue("captureStatistics"),
                values.enumValue("recaptureChunks", RecaptureMode.class),
                values.integer("recaptureSeconds"),
                values.booleanValue("savePlayerInventory"),
                values.booleanValue("savePlayerEnderChest"),
                values.booleanValue("saveItemCoordinates"),
                values.booleanValue("lockDownloadedMaps"),
                values.booleanValue("remapMapIds"),
                values.integer("encodeBudgetMillis"),
                values.booleanValue("forceMobPersistence"),
                values.booleanValue("dumpReceivedFrames"),
                values.booleanValue("zipOnFinish"),
                values.booleanValue("zipOnResume"),
                values.booleanValue("appendDateSuffix"),
                values.booleanValue("confirmResume"),
                values.booleanValue("blockTaintedResume"),
                values.booleanValue("showToasts"),
                values.booleanValue("checkForUpdates"),
                values.booleanValue("showChatMessages"),
                values.booleanValue("renderCoverageOverlay"),
                values.enumValue("overlayCoveredColor", MarkerHue.class),
                values.enumValue("overlaySuspectColor", MarkerHue.class),
                WorldOutputConfig.from(values, properties),
                HudConfig.from(values),
                OutlineConfig.from(values));
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
