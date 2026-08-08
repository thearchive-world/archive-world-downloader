// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The single-source config descriptor: the byte-identical golden template, the schema-derived defaults, the complete
 * projection, and the report ordering. The golden literal is the permanent guard that the descriptor renders the
 * documented file exactly; a deliberate template change updates this literal on purpose.
 */
class ConfigSchemaTest {
    /**
     * The exact bytes {@link WdlConfig#load} materializes on first run, with the version line derived from
     * {@link WdlConfig#CONFIG_VERSION} so a schema-version bump cannot silently desync the golden.
     */
    static final String GOLDEN_TEMPLATE = ""
            + "# Archive World Downloader configuration.\n"
            + "# Edit and save; changes apply on the next download (no client restart needed).\n"
            + "\n"
            + "# Config schema version, set by the mod (do not edit); lets a future version migrate an old config.\n"
            + "configVersion=" + WdlConfig.CONFIG_VERSION + "\n"
            + "\n"
            + "# Content toggles: which kinds of data to include in the download. The world's terrain is always\n"
            + "# downloaded; these choose the extra data kinds, so set any to false to skip it.\n"
            + "captureEntities=true\n"
            + "captureContainers=true\n"
            + "# Player advancements and statistics, written as advancements/<uuid>.json and stats/<uuid>.json.\n"
            + "# Tied to your account's UUID, so opening on a different account does not auto-inherit them.\n"
            + "captureAdvancements=true\n"
            + "captureStatistics=true\n"
            + "\n"
            + "# How current the download keeps the world as you explore (a lower mode costs less per tick).\n"
            + "# OFF: Download each area once and keep it as you first saw it.\n"
            + "# NEARBY: Keep the area around you up to date, but leave already-downloaded areas as you first\n"
            + "# saved them.\n"
            + "# EVERYWHERE (default): Keep areas you revisit up to date too, re-saving terrain that changed\n"
            + "# while you were away. Contents are the exception: a Chest, Barrel, or Lectern can only be\n"
            + "# saved by opening it, so a revisit refreshes the blocks but keeps the items from the last\n"
            + "# time you opened it. Reopen a container to bring its contents up to date.\n"
            + "recaptureChunks=EVERYWHERE\n"
            + "# How often (seconds, 5 to 60) the full set of nearby chunks is refreshed. Higher is cheaper\n"
            + "# but lets a far change wait longer before it is refreshed; the area right around you stays\n"
            + "# current regardless.\n"
            + "recaptureSeconds=15\n"
            + "# Max milliseconds per tick (1 to 10) spent encoding chunks/entities, so loading a fresh area\n"
            + "# or flying fast never stutters the frame; the rest spills to later ticks (the download lags\n"
            + "# exploration by a few ticks). Higher catches up faster but costs more per frame; lower is\n"
            + "# smoother but lags more behind you.\n"
            + "encodeBudgetMillis=2\n"
            + "\n"
            + "# Open the downloaded world in Creative mode for whoever opens it.\n"
            + "# Set false to open in the gamemode you were actually in at save time.\n"
            + "# Applied only when overrideWorldDefaults is on.\n"
            + "openInCreative=true\n"
            + "\n"
            + "# Player data written into the download so whoever opens it inherits your stuff and lands at\n"
            + "# your base. Set any to false to leave that part out.\n"
            + "savePlayerInventory=true\n"
            + "# Your Ender Chest is saved only while you have it open during the download (open it once).\n"
            + "savePlayerEnderChest=true\n"
            + "# Item coordinates are blanked by default for privacy: some items store a location that can point\n"
            + "# to a base the download does not otherwise reveal. Covers Lodestone-compass targets and the flower\n"
            + "# positions of Bees inside a silk-touched Beehive, on items in your inventory, your Ender Chest, any\n"
            + "# container you open, ones stored in a display block such as a decorated pot, and ones held by\n"
            + "# entities such as item frames, item displays, and mob equipment, including ones nested inside\n"
            + "# Shulker Boxes and Bundles. Set true to keep them pointing at their locations.\n"
            + "saveItemCoordinates=false\n"
            + "\n"
            + "# Filled maps you carried or saw framed during the download are written so they render their\n"
            + "# picture instead of blank. They are locked by default, which freezes that picture: an unlocked\n"
            + "# map repaints from live terrain when held, and the download's world is empty void, so an unlocked\n"
            + "# map would repaint to blank. Set false to keep maps live and extendable (and accept that risk).\n"
            + "lockDownloadedMaps=true\n"
            + "# Filled maps you downloaded are re-keyed to stable IDs so a server that renumbers map IDs each\n"
            + "# session can't mislink the wrong picture when you resume a download. On by default. Set false to\n"
            + "# keep the ORIGINAL server map IDs in the download (a map the server called map_1234 stays map_1234).\n"
            + "# Faithful to a vanilla server; on a server that renumbers IDs per session, resuming such a download\n"
            + "# can overwrite one map with another, so leave this on if you resume downloads on that kind of "
            + "server.\n"
            + "remapMapIds=true\n"
            + "\n"
            + "# Keep every mob you download from despawning when the world is opened, not just name-tagged ones.\n"
            + "# Name-tagged mobs (pets, named Villagers) always survive the open regardless of this setting.\n"
            + "# Off by default: leaving it off matches vanilla, where an ordinary un-named mob may wander off or\n"
            + "# despawn. Set true to pin every downloaded mob in place so your scene comes out exactly as "
            + "downloaded.\n"
            + "forceMobPersistence=false\n"
            + "\n"
            + "# What fills the space between the chunks you downloaded. Your downloaded area is always the real "
            + "thing;\n"
            + "# this only governs the surroundings.\n"
            + "# VOID (default): superflat empty air, the honest archive view of just your build against nothing.\n"
            + "# DEFAULT: normal terrain generation. FLAT: superflat layers. DEFAULT and FLAT fill the gaps with\n"
            + "# freshly generated terrain that is plausible but NOT the server's real land (a client cannot recover\n"
            + "# the server's seed), so they are off by default; the downloaded chunks still blend into it without a\n"
            + "# vertical boundary wall.\n"
            + "worldType=VOID\n"
            + "# Seed for DEFAULT/FLAT terrain: the full 64-bit range, and any non-numeric text is hashed exactly as\n"
            + "# the vanilla seed field does. Ignored by VOID. 0 by default.\n"
            + "worldSeed=0\n"
            + "# Generate structures (villages, strongholds, and the rest) in DEFAULT/FLAT terrain. Off by default.\n"
            + "generateFeatures=false\n"
            + "\n"
            + "# Game rules written into the download so it opens calm and safe. On by default: the download\n"
            + "# writes a curated safe set (no mob spawning or griefing, no fire or vine spread, day and weather\n"
            + "# frozen, inventory kept). Set false to write no game rules at all (the world's vanilla defaults\n"
            + "# stand).\n"
            + "overrideGamerules=true\n"
            + "# Override or add an individual rule with gamerule.<id>=<value>, using this Minecraft version's\n"
            + "# rule id (the ids differ across versions). An id that does not exist here, or a value that does\n"
            + "# not fit the rule, is skipped and never written, so a typo can never make the world fail to open.\n"
            + "# Examples (commented out):\n"
            + "#gamerule.<boolean_rule_id>=false\n"
            + "#gamerule.<integer_rule_id>=0\n"
            + "# Toggling a rule in the settings menu writes only its on or off value, so it replaces a custom\n"
            + "# value you hand-set here.\n"
            + "\n"
            + "# The world-open state the download imposes: creative game mode and cheats.\n"
            + "# On by default. Set false to open in your real game mode, with cheats off.\n"
            + "overrideWorldDefaults=true\n"
            + "\n"
            + "# Enable cheats in the downloaded world, so commands like /gamemode and /tp work when you open it\n"
            + "# in singleplayer. On by default. Set false to open it as a normal world with cheats off.\n"
            + "# Applied only when overrideWorldDefaults is on.\n"
            + "allowCommands=true\n"
            + "\n"
            + "# Skip a completely empty downloaded chunk (all air: no blocks, block entities, entities, or\n"
            + "# containers) to save space. Off by default. A chunk holding anything at all, even a single Boat\n"
            + "# or a pet over a void gap, is always saved. A skipped chunk is regenerated by the world's generator\n"
            + "# when the download is opened: under VOID it returns as empty air (no change), but under DEFAULT or\n"
            + "# FLAT it fills with terrain, so a genuinely-empty area you downloaded comes back as generated ground.\n"
            + "skipVoidChunks=false\n"
            + "\n"
            + "# Automatically start a download when you join a multiplayer server. Off by default.\n"
            + "autoDownload=false\n"
            + "\n"
            + "# When a download finishes, also write a .zip of the finished world beside the folder, the\n"
            + "# shareable copy to send to a friend. The openable folder is kept; the zip is extra. That export\n"
            + "# is also the clean backup Restore recovers from when a download was opened in singleplayer.\n"
            + "# On by default. A finish never overwrites an older zip, so each finish adds another (multi-GB\n"
            + "# worlds add up).\n"
            + "zipOnFinish=true\n"
            + "# Before a resume continues into an existing download folder (changing it in place), first zip that\n"
            + "# folder as a safety copy (<folder>-pre-resume.zip), so a bad resume is recoverable. The same guard\n"
            + "# covers a restore: before Restore replaces a folder, the folder is zipped as\n"
            + "# <folder>-singleplayer.zip. On by default.\n"
            + "zipOnResume=true\n"
            + "\n"
            + "# Add a -YYYY-MM-DD date to a new download's save folder (and to the world's name inside it), so\n"
            + "# downloads of the same place made on different days sit side by side instead of clashing. On by\n"
            + "# default. Set false to use the name exactly as typed; a same-name download then lands on the\n"
            + "# existing folder and merges into it rather than overwriting (asking first unless confirmResume is "
            + "off).\n"
            + "appendDateSuffix=true\n"
            + "\n"
            + "# Ask for confirmation before a download continues into an existing folder (a resume, which merges\n"
            + "# into it rather than overwriting). On by default. Set false to continue silently with no prompt,\n"
            + "# for a deliberate multi-day download you keep adding to; the backup zip is separate (zipOnResume).\n"
            + "confirmResume=true\n"
            + "\n"
            + "# Block resuming into a download that was opened in singleplayer. Opening a downloaded world in\n"
            + "# singleplayer makes Minecraft generate the missing chunks (void or fabricated land, not the\n"
            + "# server's real terrain) and change the save; resuming would merge new downloads on top of that\n"
            + "# and bake it in. On by default. Set false to warn and ask instead of blocking.\n"
            + "blockTaintedResume=true\n"
            + "\n"
            + "# Show a toast (the pop-up in the top-right corner) when a download finishes or fails, so you\n"
            + "# learn it even with chat hidden. On by default. Set false to turn the toasts off; chat and HUD\n"
            + "# messages are unaffected.\n"
            + "showToasts=true\n"
            + "\n"
            + "# Once per game launch, ask the mod's release list whether a newer release exists for this\n"
            + "# Minecraft version and loader, and if one does, say so in chat and on the downloads screen.\n"
            + "# A single small anonymous request at startup; nothing else is sent, and nothing installs\n"
            + "# itself. Set false to never make the request.\n"
            + "checkForUpdates=true\n"
            + "\n"
            + "# Chat notices from the mod, like the update-available line. Set false to silence them; notices\n"
            + "# inside the mod's own screens are unaffected.\n"
            + "showChatMessages=true\n"
            + "\n"
            + "# Diagnostics (advanced); leave off for normal use.\n"
            + "# Dumps the position of every item frame the client received to wdl/received-item-frames.txt,\n"
            + "# so a missing frame can be checked against what was actually received (a download-loss audit).\n"
            + "# Off by default; it collects every received item frame in memory and writes a file at save.\n"
            + "dumpReceivedFrames=false\n"
            + "\n"
            + "# Rendered HUD overlay: the live download status drawn on screen while a download runs and saves.\n"
            + "# Master: draw it at all. On by default. Set false to rely on the chat status line.\n"
            + "showHud=true\n"
            + "# Where it anchors. One of TOP_LEFT, TOP_CENTER, TOP_RIGHT, MIDDLE_LEFT, MIDDLE_CENTER,\n"
            + "# MIDDLE_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT (the bottom center is left for the hotbar).\n"
            + "hudAnchor=TOP_CENTER\n"
            + "# Pixel nudge from the anchor (-200 to 200), clamped to the screen.\n"
            + "hudOffsetX=0\n"
            + "hudOffsetY=0\n"
            + "# Show the detailed multi-line layout by default. When off, the peek key reveals it live.\n"
            + "hudDetailed=false\n"
            + "# How the peek key reveals the detailed layout: HOLD shows it while held, TOGGLE flips it per press.\n"
            + "hudPeekMode=HOLD\n"
            + "# Draw a panel behind the overlay. Off by default, so the text is drawn with a drop shadow instead.\n"
            + "hudBackground=false\n"
            + "# Panel opacity as a percent (0 to 100) when the panel is shown.\n"
            + "hudPanelOpacity=56\n"
            + "# How long (seconds, 0 to 30) the done frame holds after a save before it fades out.\n"
            + "hudDoneLingerSeconds=8\n"
            + "\n"
            + "# In-world outline around containers you have not opened yet this download (the download aid).\n"
            + "# A rim is drawn on an exposed face of every loaded container whose contents are still missing\n"
            + "# from the save, so you can see at a glance which Chests still need opening; it clears as you open\n"
            + "# them. Set false to turn the outline off.\n"
            + "renderUnsavedOutline=true\n"
            + "# How far (blocks, 1 to 256) from you the outline reaches; beyond this a rim is too small to see.\n"
            + "outlineDistance=96\n"
            + "# Rim hue for a still-unsaved container, and for one recovered from a prior download. One of RED,\n"
            + "# VIOLET, TEAL, AMBER, YELLOW, BLUE, REDDISH_PURPLE, WHITE (a colorblind-checked set).\n"
            + "unscannedColor=RED\n"
            + "recoveredColor=VIOLET\n"
            + "# Rim line thickness as a multiple of the game's default outline width (0.5 to 4.0); 1.0 is the\n"
            + "# same weight as the block-selection outline, higher is bolder, lower is finer.\n"
            + "outlineLineWidthScale=1.0\n"
            + "# Diagnostic (advanced, default off): log the outline's per-frame render-thread cost and per-tick\n"
            + "# enumeration cost, for tuning the render distance on a dense base. Leave off for normal use.\n"
            + "outlineDebugTiming=false\n"
            + "\n"
            + "# Coverage overlay: while a download records, saved chunks show as a translucent highlight on\n"
            + "# your minimap and world map (needs a supported map mod installed).\n"
            + "# Master: draw it at all. On by default. Set false to hide the saved-chunk highlight.\n"
            + "renderCoverageOverlay=true\n"
            + "# Highlight hue for a chunk whose entities were saved along with it. One of RED, VIOLET, TEAL,\n"
            + "# AMBER, YELLOW, BLUE, REDDISH_PURPLE, WHITE (a colorblind-checked set).\n"
            + "overlayCoveredColor=TEAL\n"
            + "# Highlight hue for a chunk whose blocks are saved but whose entities were never confirmed in\n"
            + "# send range, so its item frames and armor stands may still be missing. Same hue set as above.\n"
            + "overlaySuspectColor=AMBER\n";

    @Test
    void loadMaterializesTheByteIdenticalDefaultTemplate(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl.properties");

        WdlConfig.load(file);

        assertEquals(GOLDEN_TEMPLATE, new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                "the materialized default file must be byte-identical to the golden template");
    }

    @Test
    void renderDefaultTemplateEqualsTheGolden() {
        assertEquals(GOLDEN_TEMPLATE, ConfigSchema.renderDefaultTemplate(),
                "the schema renders the same bytes the golden pins");
    }

    @Test
    void renderConfigFileForTheDefaultsEqualsTheGolden() {
        assertEquals(GOLDEN_TEMPLATE, ConfigSchema.renderConfigFile(WdlConfig.DEFAULTS),
                "a commit of an unchanged config rewrites the byte-identical documented template");
    }

    @Test
    void renderConfigFileCarriesLiveValuesAndAppendsGameRuleOverrides() {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("gamerule.spawn_mobs", "true");

        String file = ConfigSchema.renderConfigFile(WdlConfig.parse(properties));

        assertTrue(file.contains("captureEntities=false"), "the edited scalar is written in place");
        assertTrue(file.contains("gamerule.spawn_mobs=true"), "the live override is appended");
        assertTrue(file.contains("# Content toggles:"), "the documented comments survive a commit");
    }

    @Test
    void projectRendersEveryOptionWithEnumCanonical() {
        Map<String, String> projected = ConfigSchema.project(WdlConfig.DEFAULTS);

        assertEquals("TOP_CENTER", projected.get("hudAnchor"), "ENUM renders as the constant name");
        assertEquals("HOLD", projected.get("hudPeekMode"));
        assertEquals("VOID", projected.get("worldType"));
        assertEquals("RED", projected.get("unscannedColor"));
        assertEquals("VIOLET", projected.get("recoveredColor"));
        assertEquals("TEAL", projected.get("overlayCoveredColor"));
        assertEquals("AMBER", projected.get("overlaySuspectColor"));
        assertEquals("0", projected.get("worldSeed"));
        assertEquals("1.0", projected.get("outlineLineWidthScale"));
        assertEquals(ConfigSchema.OPTIONS.size(), projected.size(), "the projection covers every option");
    }

    @Test
    void projectIncludesHudAndAppendsGameRuleOverrides() {
        Properties properties = new Properties();
        properties.setProperty("hudDetailed", "true");
        properties.setProperty("gamerule.keep_inventory", "false");

        Map<String, String> projected = ConfigSchema.project(WdlConfig.parse(properties));

        assertEquals("true", projected.get("hudDetailed"), "HUD options are in the projection (unlike the report)");
        assertEquals("false", projected.get("gamerule.keep_inventory"), "each live override is appended");
        assertEquals(ConfigSchema.OPTIONS.size() + 1, projected.size());
    }

    @Test
    void everyOptionRoundTripsFormatToParse() {
        for (ConfigOption option : ConfigSchema.OPTIONS) {
            Object parsedDefault = option.type.parse(option, option.defaultValue, new ArrayList<>());
            assertNotNull(parsedDefault, option.key);
            String formatted = option.type.format(parsedDefault);
            Object reparsed = option.type.parse(option, formatted, new ArrayList<>());
            assertEquals(parsedDefault, reparsed, option.key + " must round-trip format then parse");
        }
    }

    @Test
    void literalNestedDefaultsMatchTheSchemaDerivedOnes() {
        WdlConfig derived = WdlConfig.parse(new Properties());

        WorldOutputConfig world = derived.worldOutput();
        assertEquals(WorldOutputConfig.DEFAULTS.overrideGameRules(), world.overrideGameRules());
        assertEquals(WorldOutputConfig.DEFAULTS.overrideWorldDefaults(), world.overrideWorldDefaults());
        assertEquals(WorldOutputConfig.DEFAULTS.openInCreative(), world.openInCreative());
        assertEquals(WorldOutputConfig.DEFAULTS.allowCommands(), world.allowCommands());
        assertEquals(WorldOutputConfig.DEFAULTS.skipVoidChunks(), world.skipVoidChunks());
        assertEquals(WorldOutputConfig.DEFAULTS.autoDownload(), world.autoDownload());
        assertEquals(WorldOutputConfig.DEFAULTS.worldType(), world.worldType());
        assertEquals(WorldOutputConfig.DEFAULTS.worldSeed(), world.worldSeed());
        assertEquals(WorldOutputConfig.DEFAULTS.generateFeatures(), world.generateFeatures());
        assertTrue(world.gameRuleOverrides().isEmpty());
    }

    @Test
    void reportOrderIsScalarNonNestedSubsetOfOptions() {
        Set<String> optionKeys = new LinkedHashSet<>();
        for (ConfigOption option : ConfigSchema.OPTIONS) {
            optionKeys.add(option.key);
        }
        for (ConfigOption option : ConfigSchema.REPORT_ORDER) {
            assertTrue(optionKeys.contains(option.key), option.key + " must be an option");
            assertFalse(option.key.startsWith("hud"), option.key + " is a HUD option and must not be reported");
            assertFalse(option.key.startsWith("outline"), option.key + " is an outline option");
        }
        Set<String> reportKeys = new LinkedHashSet<>();
        for (ConfigOption option : ConfigSchema.REPORT_ORDER) {
            reportKeys.add(option.key);
        }
        assertFalse(reportKeys.contains("overlayCoveredColor"), "the covered marker hue is not reported");
        assertFalse(reportKeys.contains("overlaySuspectColor"), "the suspect marker hue is not reported");
        assertFalse(reportKeys.contains("renderCoverageOverlay"), "the overlay toggle is not reported");
        assertEquals(22, ConfigSchema.REPORT_ORDER.size());
    }

    @Test
    void changedFromPinsTheFullReportOrderAcrossBothHolders() {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        properties.setProperty("captureAdvancements", "false");
        properties.setProperty("captureStatistics", "false");
        properties.setProperty("openInCreative", "false");
        properties.setProperty("recaptureChunks", "OFF");
        properties.setProperty("recaptureSeconds", "30");
        properties.setProperty("savePlayerInventory", "false");
        properties.setProperty("savePlayerEnderChest", "false");
        properties.setProperty("saveItemCoordinates", "true");
        properties.setProperty("lockDownloadedMaps", "false");
        properties.setProperty("remapMapIds", "false");
        properties.setProperty("encodeBudgetMillis", "5");
        properties.setProperty("forceMobPersistence", "true");
        properties.setProperty("dumpReceivedFrames", "true");
        properties.setProperty("zipOnFinish", "false");
        properties.setProperty("zipOnResume", "false");
        properties.setProperty("appendDateSuffix", "false");
        properties.setProperty("confirmResume", "false");
        properties.setProperty("blockTaintedResume", "false");
        properties.setProperty("showToasts", "false");
        properties.setProperty("checkForUpdates", "false");
        properties.setProperty("showChatMessages", "false");
        properties.setProperty("overrideGamerules", "false");
        properties.setProperty("overrideWorldDefaults", "false");
        properties.setProperty("allowCommands", "false");
        properties.setProperty("skipVoidChunks", "true");
        properties.setProperty("autoDownload", "true");
        properties.setProperty("worldType", "DEFAULT");
        properties.setProperty("worldSeed", "42");
        properties.setProperty("generateFeatures", "true");
        properties.setProperty("overlayCoveredColor", "BLUE"); // a WdlConfig scalar that is not reported
        properties.setProperty("hudDetailed", "true"); // a HUD option that is not reported

        Map<String, String> changed = WdlConfig.parse(properties).changedFrom(WdlConfig.DEFAULTS);

        assertEquals(Arrays.asList(
                "captureEntities", "captureContainers", "captureAdvancements", "captureStatistics",
                "recaptureChunks", "recaptureSeconds", "savePlayerInventory",
                "savePlayerEnderChest", "saveItemCoordinates", "lockDownloadedMaps", "remapMapIds",
                "encodeBudgetMillis",
                "forceMobPersistence", "dumpReceivedFrames", "zipOnFinish", "zipOnResume", "appendDateSuffix",
                "confirmResume", "blockTaintedResume", "showToasts", "checkForUpdates", "showChatMessages",
                "overrideGamerules", "overrideWorldDefaults", "openInCreative",
                "allowCommands", "skipVoidChunks", "autoDownload", "worldType", "worldSeed", "generateFeatures"),
                new ArrayList<>(changed.keySet()));
        assertFalse(changed.containsKey("overlayCoveredColor"), "the marker hue is never in the report");
        assertFalse(changed.containsKey("hudDetailed"), "HUD options are never in the report");
    }

    /**
     * Per descriptor key, one valid value that differs from that option's default. Ranged numbers are picked in range
     * so the clamp keeps them off the default rather than snapping back onto it, and enums pick a real other constant.
     * {@link #nonDefaultValuesCoverEveryDescriptorKey} pins this map complete, so a newly added option cannot silently
     * skip the flip-one-key backstop below.
     */
    private static final Map<String, String> NON_DEFAULT_VALUES = Collections.unmodifiableMap(nonDefaultValues());

    private static Map<String, String> nonDefaultValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("captureEntities", "false");
        values.put("captureContainers", "false");
        values.put("captureAdvancements", "false");
        values.put("captureStatistics", "false");
        values.put("recaptureChunks", "OFF");
        values.put("recaptureSeconds", "30");
        values.put("encodeBudgetMillis", "5");
        values.put("openInCreative", "false");
        values.put("savePlayerInventory", "false");
        values.put("savePlayerEnderChest", "false");
        values.put("saveItemCoordinates", "true");
        values.put("lockDownloadedMaps", "false");
        values.put("remapMapIds", "false");
        values.put("forceMobPersistence", "true");
        values.put("worldType", "DEFAULT");
        values.put("worldSeed", "42");
        values.put("generateFeatures", "true");
        values.put("overrideGamerules", "false");
        values.put("overrideWorldDefaults", "false");
        values.put("allowCommands", "false");
        values.put("skipVoidChunks", "true");
        values.put("autoDownload", "true");
        values.put("zipOnFinish", "false");
        values.put("zipOnResume", "false");
        values.put("appendDateSuffix", "false");
        values.put("confirmResume", "false");
        values.put("blockTaintedResume", "false");
        values.put("showToasts", "false");
        values.put("checkForUpdates", "false");
        values.put("showChatMessages", "false");
        values.put("dumpReceivedFrames", "true");
        values.put("showHud", "false");
        values.put("hudAnchor", "TOP_LEFT");
        values.put("hudOffsetX", "50");
        values.put("hudOffsetY", "50");
        values.put("hudDetailed", "true");
        values.put("hudPeekMode", "TOGGLE");
        values.put("hudBackground", "true");
        values.put("hudPanelOpacity", "80");
        values.put("hudDoneLingerSeconds", "20");
        values.put("renderUnsavedOutline", "false");
        values.put("outlineDistance", "50");
        values.put("unscannedColor", "BLUE");
        values.put("recoveredColor", "BLUE");
        values.put("outlineLineWidthScale", "2.0");
        values.put("outlineDebugTiming", "true");
        values.put("renderCoverageOverlay", "false");
        values.put("overlayCoveredColor", "BLUE");
        values.put("overlaySuspectColor", "BLUE");
        return values;
    }

    @Test
    void nonDefaultValuesCoverEveryDescriptorKey() {
        Set<String> descriptorKeys = new LinkedHashSet<>();
        for (ConfigOption option : ConfigSchema.OPTIONS) {
            descriptorKeys.add(option.key);
        }

        assertEquals(descriptorKeys, NON_DEFAULT_VALUES.keySet(),
                "every descriptor key needs a non-default flip value, so a new option cannot skip the backstop");
    }

    @Test
    void flippingOneKeyMovesExactlyThatKeyInTheProjection() {
        Map<String, String> defaults = ConfigSchema.project(WdlConfig.DEFAULTS);

        for (ConfigOption option : ConfigSchema.OPTIONS) {
            Properties properties = new Properties();
            properties.setProperty(option.key, NON_DEFAULT_VALUES.get(option.key));

            Map<String, String> flipped = ConfigSchema.project(WdlConfig.parse(properties));

            Set<String> moved = new LinkedHashSet<>();
            for (String key : defaults.keySet()) {
                if (!defaults.get(key).equals(flipped.get(key))) {
                    moved.add(key);
                }
            }
            assertEquals(Set.of(option.key), moved,
                    option.key + " must move exactly its own projection entry; a swap of two same-typed reads in "
                            + "parse() lands the change on the wrong key and trips here");
        }
    }
}
