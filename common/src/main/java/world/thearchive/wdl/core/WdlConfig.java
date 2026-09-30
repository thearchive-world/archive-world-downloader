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

public final class WdlConfig {
    /** The config schema version; bump it and add a migrator when a key's name or value shape changes. */
    static final int CONFIG_VERSION = 1;

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

    public boolean captureAdvancements() {
        return captureAdvancements;
    }

    public boolean captureStatistics() {
        return captureStatistics;
    }

    public RecaptureMode recaptureChunks() {
        return recaptureChunks;
    }

    public int recaptureSeconds() {
        return recaptureSeconds;
    }

    public boolean savePlayerInventory() {
        return savePlayerInventory;
    }

    public boolean savePlayerEnderChest() {
        return savePlayerEnderChest;
    }

    public boolean saveItemCoordinates() {
        return saveItemCoordinates;
    }

    public boolean lockDownloadedMaps() {
        return lockDownloadedMaps;
    }

    public boolean remapMapIds() {
        return remapMapIds;
    }

    public int encodeBudgetMillis() {
        return encodeBudgetMillis;
    }

    public boolean forceMobPersistence() {
        return forceMobPersistence;
    }

    public boolean dumpReceivedFrames() {
        return dumpReceivedFrames;
    }

    public boolean zipOnFinish() {
        return zipOnFinish;
    }

    public boolean zipOnResume() {
        return zipOnResume;
    }

    public boolean appendDateSuffix() {
        return appendDateSuffix;
    }

    public boolean confirmResume() {
        return confirmResume;
    }

    public boolean blockTaintedResume() {
        return blockTaintedResume;
    }

    public boolean showToasts() {
        return showToasts;
    }

    public boolean checkForUpdates() {
        return checkForUpdates;
    }

    public boolean showChatMessages() {
        return showChatMessages;
    }

    public boolean renderCoverageOverlay() {
        return renderCoverageOverlay;
    }

    public MarkerHue overlayCoveredColor() {
        return overlayCoveredColor;
    }

    public MarkerHue overlaySuspectColor() {
        return overlaySuspectColor;
    }

    public WorldOutputConfig worldOutput() {
        return worldOutput;
    }

    public HudConfig hud() {
        return hud;
    }

    public OutlineConfig outline() {
        return outline;
    }

    Map<String, String> changedFrom(WdlConfig baseline) {
        Map<String, String> changed = ConfigSchema.reportDiff(this, baseline);
        changed.putAll(ConfigSchema.worldOutputDiff(this, baseline));
        return changed;
    }

    public Map<String, String> nonDefaultSettings() {
        Map<String, String> settings = changedFrom(DEFAULTS);
        for (Map.Entry<String, String> override : worldOutput.gameRuleOverrides().entrySet()) {
            settings.put(WorldOutputConfig.GAME_RULE_PREFIX + override.getKey(), override.getValue());
        }
        return settings;
    }

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
     * is absent. Falls back to {@link #DEFAULTS} if the file cannot be read or written.
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
