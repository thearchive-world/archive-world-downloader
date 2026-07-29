// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The band-agnostic config: pure java.util/java.nio parsing, default-on-missing, materialize-on-absent. */
class WdlConfigTest {
    @Test
    void parseDefaultsEveryMissingKey() {
        WdlConfig config = WdlConfig.parse(new Properties());

        assertTrue(config.captureEntities());
        assertTrue(config.captureContainers());
        assertTrue(config.worldOutput().openInCreative());
        assertEquals(RecaptureMode.EVERYWHERE, config.recaptureChunks(), "recapture defaults to EVERYWHERE");
        assertEquals(15, config.recaptureSeconds());
        assertTrue(config.savePlayerInventory());
        assertTrue(config.savePlayerEnderChest());
        assertFalse(config.saveItemCoordinates(), "item coordinates are blanked by default (scrub on)");
        assertTrue(config.lockDownloadedMaps(), "downloaded maps are auto-locked by default");
        assertFalse(config.forceMobPersistence(), "all-mob persistence is off by default (named mobs persist anyway)");
        assertFalse(config.dumpReceivedFrames(), "the per-frame received dump diagnostic is off by default");
        assertTrue(config.showToasts(), "the job-done toasts are on by default");
    }

    @Test
    void parseReadsEveryKey() {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        properties.setProperty("openInCreative", "false");
        properties.setProperty("recaptureChunks", "OFF");
        properties.setProperty("recaptureSeconds", "30");
        properties.setProperty("savePlayerInventory", "false");
        properties.setProperty("savePlayerEnderChest", "false");
        properties.setProperty("saveItemCoordinates", "false");
        properties.setProperty("lockDownloadedMaps", "false");
        properties.setProperty("forceMobPersistence", "true");
        properties.setProperty("dumpReceivedFrames", "true");
        properties.setProperty("showToasts", "false");

        WdlConfig config = WdlConfig.parse(properties);

        assertFalse(config.captureEntities());
        assertFalse(config.captureContainers());
        assertFalse(config.worldOutput().openInCreative());
        assertEquals(RecaptureMode.OFF, config.recaptureChunks());
        assertEquals(30, config.recaptureSeconds());
        assertFalse(config.savePlayerInventory());
        assertFalse(config.savePlayerEnderChest());
        assertFalse(config.saveItemCoordinates());
        assertFalse(config.lockDownloadedMaps());
        assertTrue(config.forceMobPersistence());
        assertTrue(config.dumpReceivedFrames());
    }

    @Test
    void parseReadsTheNearbyRecaptureMode() {
        Properties properties = new Properties();
        properties.setProperty("recaptureChunks", "NEARBY");

        assertEquals(RecaptureMode.NEARBY, WdlConfig.parse(properties).recaptureChunks());
    }

    @Test
    void aStaleRecaptureBooleanSelfHealsToEverywhere() {
        Properties properties = new Properties();
        properties.setProperty("recaptureChunks", "true"); // a leftover from the retired boolean, not a valid mode

        assertEquals(RecaptureMode.EVERYWHERE, WdlConfig.parse(properties).recaptureChunks(),
                "a stale boolean value fails the enum parse and heals to the default, not to a silent OFF");
    }

    @Test
    void parseFallsBackToDefaultOnMalformedInt() {
        Properties properties = new Properties();
        properties.setProperty("recaptureSeconds", "not-a-number");

        WdlConfig config = WdlConfig.parse(properties);

        assertEquals(15, config.recaptureSeconds(), "a malformed int keeps the default");
    }

    @Test
    void parseClampsRecaptureSecondsToItsFiveToSixtyBound() {
        Properties tooHigh = new Properties();
        tooHigh.setProperty("recaptureSeconds", "120");
        assertEquals(60, WdlConfig.parse(tooHigh).recaptureSeconds(), "recaptureSeconds clamps to its 60s ceiling");

        Properties tooLow = new Properties();
        tooLow.setProperty("recaptureSeconds", "1");
        assertEquals(5, WdlConfig.parse(tooLow).recaptureSeconds(), "recaptureSeconds clamps to its 5s floor");
    }

    @Test
    void parseClampsEncodeBudgetMillisToItsOneToTenBound() {
        Properties tooHigh = new Properties();
        tooHigh.setProperty("encodeBudgetMillis", "100");
        assertEquals(10, WdlConfig.parse(tooHigh).encodeBudgetMillis(), "encodeBudgetMillis clamps to its 10ms cap");

        Properties tooLow = new Properties();
        tooLow.setProperty("encodeBudgetMillis", "0");
        assertEquals(1, WdlConfig.parse(tooLow).encodeBudgetMillis(), "encodeBudgetMillis clamps to its 1ms floor");
    }

    @Test
    void loadOnAbsentPathWritesDefaultsAndRoundTrips(@TempDir Path directory) {
        Path file = directory.resolve("wdl.properties");

        WdlConfig first = WdlConfig.load(file);

        assertTrue(Files.exists(file), "load() must materialize the default config file");
        assertTrue(first.captureEntities());
        assertTrue(first.worldOutput().openInCreative());
        assertEquals(RecaptureMode.EVERYWHERE, first.recaptureChunks());
        assertEquals(15, first.recaptureSeconds());

        // The written template must parse back to the same values.
        WdlConfig reloaded = WdlConfig.load(file);
        assertEquals(first.captureEntities(), reloaded.captureEntities());
        assertEquals(first.captureContainers(), reloaded.captureContainers());
        assertEquals(first.worldOutput().openInCreative(), reloaded.worldOutput().openInCreative());
        assertEquals(first.recaptureChunks(), reloaded.recaptureChunks());
        assertEquals(first.recaptureSeconds(), reloaded.recaptureSeconds());
    }

    @Test
    void changedFromDefaultsIsEmptyWhenNothingChanged() {
        assertTrue(WdlConfig.DEFAULTS.changedFrom(WdlConfig.DEFAULTS).isEmpty(),
                "an unchanged config diffs to nothing, so the report's settings section is empty");
    }

    @Test
    void changedFromReportsOnlyTheChangedLeafByItsKey() {
        Properties properties = new Properties();
        properties.setProperty("saveItemCoordinates", "true"); // the default is false

        Map<String, String> changed = WdlConfig.parse(properties).changedFrom(WdlConfig.DEFAULTS);

        assertEquals(1, changed.size());
        assertEquals("true", changed.get("saveItemCoordinates"));
    }

    @Test
    void changedFromReportsForceMobPersistenceWhenEnabled() {
        Properties properties = new Properties();
        properties.setProperty("forceMobPersistence", "true"); // the default is false

        Map<String, String> changed = WdlConfig.parse(properties).changedFrom(WdlConfig.DEFAULTS);

        assertEquals(1, changed.size());
        assertEquals("true", changed.get("forceMobPersistence"));
    }

    @Test
    void changedFromReportsShowToastsWhenDisabled() {
        Properties properties = new Properties();
        properties.setProperty("showToasts", "false"); // the default is true

        Map<String, String> changed = WdlConfig.parse(properties).changedFrom(WdlConfig.DEFAULTS);

        assertEquals(1, changed.size());
        assertEquals("false", changed.get("showToasts"));
    }

    @Test
    void changedFromReportsChangedWorldOutputGenerationFields() {
        Properties properties = new Properties();
        properties.setProperty("worldType", "DEFAULT"); // default VOID
        properties.setProperty("worldSeed", "42"); // default 0
        properties.setProperty("generateFeatures", "true"); // default false

        Map<String, String> changed = WdlConfig.parse(properties).changedFrom(WdlConfig.DEFAULTS);

        assertEquals("DEFAULT", changed.get("worldType"));
        assertEquals("42", changed.get("worldSeed"));
        assertEquals("true", changed.get("generateFeatures"));
    }

    @Test
    void changedFromReportsChangedWorldOutputFlag() {
        Properties properties = new Properties();
        properties.setProperty("skipVoidChunks", "true"); // the default is false

        Map<String, String> changed = WdlConfig.parse(properties).changedFrom(WdlConfig.DEFAULTS);

        assertEquals(1, changed.size());
        assertEquals("true", changed.get("skipVoidChunks"));
    }

    @Test
    void changedFromReportsWorldOutputAllowCommandsOff() {
        Properties properties = new Properties();
        properties.setProperty("allowCommands", "false"); // the default is true

        Map<String, String> changed = WdlConfig.parse(properties).changedFrom(WdlConfig.DEFAULTS);

        assertEquals(1, changed.size());
        assertEquals("false", changed.get("allowCommands"));
    }

    @Test
    void changedFromExcludesTheGameRuleOverrideList() {
        Properties properties = new Properties();
        properties.setProperty("skipVoidChunks", "true"); // a scalar flag, default false
        properties.setProperty("gamerule.spawn_mobs", "true"); // an override: a change by nature, not a diffable scalar

        Map<String, String> changed = WdlConfig.parse(properties).changedFrom(WdlConfig.DEFAULTS);

        assertEquals("true", changed.get("skipVoidChunks"), "the scalar flag is diffed");
        assertFalse(changed.containsKey("gamerule.spawn_mobs"),
                "the sparse override list is not diffed here; it is reported via gameRuleOverrides()");
    }

    @Test
    void loadWritesTheShowToastsDefault(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl.properties");

        WdlConfig.load(file);

        assertTrue(Files.readString(file).contains("showToasts=true"),
                "the materialized default file documents the toast toggle, default on");
    }

    private record DefaultOnBoolean(String key, Predicate<WdlConfig> getter) {}

    // The default-on scalar booleans that share one parse, heal, report, and materialize shape below.
    private static final List<DefaultOnBoolean> DEFAULT_ON_SCALAR_BOOLEANS = List.of(
            new DefaultOnBoolean("checkForUpdates", WdlConfig::checkForUpdates),
            new DefaultOnBoolean("showChatMessages", WdlConfig::showChatMessages),
            new DefaultOnBoolean("zipOnFinish", WdlConfig::zipOnFinish),
            new DefaultOnBoolean("zipOnResume", WdlConfig::zipOnResume),
            new DefaultOnBoolean("appendDateSuffix", WdlConfig::appendDateSuffix),
            new DefaultOnBoolean("confirmResume", WdlConfig::confirmResume));

    @Test
    void parseDefaultsEachScalarBooleanOn() {
        WdlConfig config = WdlConfig.parse(new Properties());

        for (DefaultOnBoolean toggle : DEFAULT_ON_SCALAR_BOOLEANS) {
            assertTrue(toggle.getter().test(config), toggle.key() + " defaults on");
        }
    }

    @Test
    void parseReadsEachScalarBooleanWhenDisabled() {
        for (DefaultOnBoolean toggle : DEFAULT_ON_SCALAR_BOOLEANS) {
            Properties properties = new Properties();
            properties.setProperty(toggle.key(), "false");

            assertFalse(toggle.getter().test(WdlConfig.parse(properties)), toggle.key());
        }
    }

    @Test
    void parseHealsEachMalformedScalarBooleanToItsDefault() {
        for (DefaultOnBoolean toggle : DEFAULT_ON_SCALAR_BOOLEANS) {
            Properties properties = new Properties();
            properties.setProperty(toggle.key(), "ture"); // a typo silently disabling a default-on flag

            assertTrue(toggle.getter().test(WdlConfig.parse(properties)),
                    toggle.key() + " heals to its default, not a silent false");
        }
    }

    @Test
    void changedFromReportsEachScalarBooleanWhenDisabled() {
        for (DefaultOnBoolean toggle : DEFAULT_ON_SCALAR_BOOLEANS) {
            Properties properties = new Properties();
            properties.setProperty(toggle.key(), "false");

            Map<String, String> changed = WdlConfig.parse(properties).changedFrom(WdlConfig.DEFAULTS);

            assertEquals(1, changed.size(), toggle.key());
            assertEquals("false", changed.get(toggle.key()), toggle.key());
        }
    }

    @Test
    void loadWritesEachScalarBooleanDefault(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl.properties");

        WdlConfig.load(file);

        String written = Files.readString(file);
        for (DefaultOnBoolean toggle : DEFAULT_ON_SCALAR_BOOLEANS) {
            assertTrue(written.contains(toggle.key() + "=true"),
                    "the materialized default file documents " + toggle.key() + ", default on");
        }
    }

    @Test
    void changedFromListsEachDifferingLeafByKeyInDeclarationOrder() {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false"); // default true
        properties.setProperty("captureContainers", "false"); // default true
        properties.setProperty("recaptureSeconds", "30"); // default 15

        Map<String, String> changed = WdlConfig.parse(properties).changedFrom(WdlConfig.DEFAULTS);

        assertEquals(Arrays.asList("captureEntities", "captureContainers", "recaptureSeconds"),
                new ArrayList<>(changed.keySet()));
        assertEquals("false", changed.get("captureEntities"));
        assertEquals("false", changed.get("captureContainers"));
        assertEquals("30", changed.get("recaptureSeconds"));
    }

    @Test
    void configVersionIsStampedInTheDefaultFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl.properties");

        WdlConfig.load(file);

        assertTrue(Files.readString(file).contains("configVersion=1"),
                "the materialized default file stamps the schema version");
    }

    @Test
    void loadWritesTheAllowCommandsDefault(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl.properties");

        WdlConfig.load(file);

        assertTrue(Files.readString(file).contains("allowCommands=true"),
                "the materialized default file documents the cheats knob, default on");
    }

    @Test
    void configVersionReadsTheStampedValueOrDefaultsToCurrent() {
        Properties stamped = new Properties();
        stamped.setProperty("configVersion", "1");
        assertEquals(1, WdlConfig.configVersion(stamped));

        assertEquals(WdlConfig.CONFIG_VERSION, WdlConfig.configVersion(new Properties()),
                "a config with no version is read as the current version (no migrator yet)");
    }

    @Test
    void changedFromIgnoresTheConfigVersionMetadata() {
        Properties properties = new Properties();
        properties.setProperty("configVersion", "99"); // metadata, not a user setting

        assertTrue(WdlConfig.parse(properties).changedFrom(WdlConfig.DEFAULTS).isEmpty(),
                "the schema version is metadata and never shows up in the settings diff");
    }

    @Test
    void worldOutputDefaultsToBothMastersOnAndBothKnobsOff() {
        WorldOutputConfig worldOutput = WdlConfig.parse(new Properties()).worldOutput();

        assertTrue(worldOutput.overrideGameRules());
        assertTrue(worldOutput.overrideWorldDefaults());
        assertFalse(worldOutput.skipVoidChunks());
        assertFalse(worldOutput.autoDownload());
        assertTrue(worldOutput.gameRuleOverrides().isEmpty());
    }

    @Test
    void parseDelegatesTheWorldOutputKeys() {
        Properties properties = new Properties();
        properties.setProperty("skipVoidChunks", "true");
        properties.setProperty("autoDownload", "true");
        properties.setProperty("gamerule.spawn_mobs", "false");

        WorldOutputConfig worldOutput = WdlConfig.parse(properties).worldOutput();

        assertTrue(worldOutput.skipVoidChunks());
        assertTrue(worldOutput.autoDownload());
        assertEquals("false", worldOutput.gameRuleOverrides().get("spawn_mobs"));
    }

    @Test
    void changedFromIncludesWorldOutputChange() {
        Properties properties = new Properties();
        properties.setProperty("autoDownload", "true"); // default false

        Map<String, String> changed = WdlConfig.parse(properties).changedFrom(WdlConfig.DEFAULTS);

        assertEquals("true", changed.get("autoDownload"));
    }

    @Test
    void changedFromIsScalarOnlyAndExcludesGameRuleOverrides() {
        Properties properties = new Properties();
        properties.setProperty("gamerule.spawn_mobs", "true");

        assertFalse(WdlConfig.parse(properties).changedFrom(WdlConfig.DEFAULTS).containsKey("gamerule.spawn_mobs"),
                "changedFrom is a complete scalar diff; the override list is not part of it");
    }

    @Test
    void nonDefaultSettingsListsScalarChangesAndEveryConfiguredGameRuleOverride() {
        Properties properties = new Properties();
        properties.setProperty("autoDownload", "true"); // a scalar change
        properties.setProperty("gamerule.spawn_mobs", "true"); // a configured override, always a non-default setting

        Map<String, String> settings = WdlConfig.parse(properties).nonDefaultSettings();

        assertEquals("true", settings.get("autoDownload"), "scalar changes are included");
        assertEquals("true", settings.get("gamerule.spawn_mobs"), "each configured override is included");
    }

    @Test
    void loadRoundTripsTheWorldOutputDefaults(@TempDir Path directory) {
        Path file = directory.resolve("wdl.properties");

        WdlConfig first = WdlConfig.load(file);
        WdlConfig reloaded = WdlConfig.load(file);

        assertEquals(first.worldOutput().overrideGameRules(), reloaded.worldOutput().overrideGameRules());
        assertEquals(first.worldOutput().overrideWorldDefaults(), reloaded.worldOutput().overrideWorldDefaults());
        assertEquals(first.worldOutput().openInCreative(), reloaded.worldOutput().openInCreative());
        assertEquals(first.worldOutput().allowCommands(), reloaded.worldOutput().allowCommands());
        assertEquals(first.worldOutput().skipVoidChunks(), reloaded.worldOutput().skipVoidChunks());
        assertEquals(first.worldOutput().autoDownload(), reloaded.worldOutput().autoDownload());
    }

    @Test
    void loadParsesAnExistingHandEditedFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl.properties");
        Files.writeString(file, "captureEntities=false\nrecaptureSeconds=30\n");

        WdlConfig config = WdlConfig.load(file);

        assertTrue(config.captureContainers(), "a missing key keeps its default");
        assertFalse(config.captureEntities());
        assertEquals(30, config.recaptureSeconds());
        assertTrue(config.worldOutput().openInCreative());
    }

    @Test
    void parseHealsMalformedTopLevelBooleanToItsDefault() {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "ture"); // a typo; the default is true

        assertTrue(WdlConfig.parse(properties).captureEntities(),
                "a malformed default-on flag heals to its default, not a silent false");
    }

    @Test
    void parseHealsMalformedWorldOutputBooleanToItsDefault() {
        Properties properties = new Properties();
        properties.setProperty("overrideGamerules", "ture"); // the sharp edge: a typo disabling a default-on flag

        assertTrue(WdlConfig.parse(properties).worldOutput().overrideGameRules(),
                "a malformed default-on flag heals to its default, not a silent false");
    }

    @Test
    void loadHealsOnlyTheMalformedKeysAndKeepsValidEdits(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl.properties");
        Files.writeString(file, "overrideGamerules=ture\ncaptureEntities=false\ngamerule.spawn_mobs=false\n");

        WdlConfig config = WdlConfig.load(file);

        assertTrue(config.worldOutput().overrideGameRules(), "the malformed default-on flag heals to its default");
        assertFalse(config.captureEntities(), "a valid edit alongside the malformed one is preserved, not reset");
        assertEquals("false", config.worldOutput().gameRuleOverrides().get("spawn_mobs"),
                "a valid gamerule override survives the per-key heal");
        String rewritten = Files.readString(file);
        assertTrue(rewritten.contains("overrideGamerules=true"), "the healed key is rewritten valid");
        assertTrue(rewritten.contains("captureEntities=false"), "the preserved edit is written back");
        assertTrue(rewritten.contains("gamerule.spawn_mobs=false"), "the preserved override is written back");
        assertTrue(rewritten.contains("configVersion="), "the rewrite is the full documented file");
    }

    @Test
    void loadRewritesTheFileWhenAnIntegerIsMalformed(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl.properties");
        Files.writeString(file, "recaptureSeconds=not-a-number\n");

        WdlConfig config = WdlConfig.load(file);

        assertEquals(15, config.recaptureSeconds(), "a malformed int heals to its default");
        assertTrue(Files.readString(file).contains("recaptureSeconds=15"),
                "the file on disk is rewritten to a valid default");
    }

    @Test
    void loadLeavesNoTempSiblingAfterRewriting(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl.properties");
        Files.writeString(file, "recaptureSeconds=not-a-number\n");

        WdlConfig.load(file); // the heal rewrite goes through the staging-then-atomic-move writer

        assertFalse(Files.exists(directory.resolve("wdl.properties.tmp")),
                "the atomic-move writer moves its staging sibling away, never leaving a half-written sibling");
    }

    @Test
    void loadLogsAndFallsBackWhenTheFileCannotBeRead(@TempDir Path directory) throws IOException {
        // A directory where the config file is expected: an open-for-read of it throws IOException, standing in
        // for the transient read lock (antivirus, backup, cloud sync) the fail-soft path silently swallowed.
        Path unreadable = directory.resolve("wdl.properties");
        Files.createDirectory(unreadable);

        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(WdlConfig.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            assertSame(WdlConfig.DEFAULTS, WdlConfig.load(unreadable),
                    "an unreadable config falls back to the defaults for this session");
        } finally {
            logger.removeHandler(handler);
        }

        assertTrue(records.stream().anyMatch(record -> record.getLevel() == Level.WARNING),
                "the read failure is now logged, not swallowed silently");
    }

    @Test
    void loadLeavesValidHandEditedFileUntouched(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl.properties");
        String original = "captureEntities=false\nrecaptureSeconds=30\n";
        Files.writeString(file, original);

        WdlConfig config = WdlConfig.load(file);

        assertFalse(config.captureEntities(), "a valid edit is honored");
        assertEquals(30, config.recaptureSeconds());
        assertEquals(original, Files.readString(file), "a valid file is never rewritten (no spurious heal)");
    }

    @Test
    void loadHealsAnEmptyBooleanValue(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl.properties");
        Files.writeString(file, "captureEntities=\n"); // present but empty: not a boolean literal

        WdlConfig config = WdlConfig.load(file);

        assertTrue(config.captureEntities(),
                "an empty value is malformed and heals to its default, not a silent false");
        assertTrue(Files.readString(file).contains("captureEntities=true"), "the file on disk is rewritten valid");
    }

    @Test
    void loadDoesNotHealGarbageConfigVersion(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl.properties");
        String original = "configVersion=banana\n";
        Files.writeString(file, original);

        WdlConfig.load(file);

        assertEquals(original, Files.readString(file),
                "configVersion is metadata read outside parse(); a bad value keeps its silent fallback, no reset");
    }

    @Test
    void loadDoesNotHealGarbageGameRuleOverride(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("wdl.properties");
        String original = "gamerule.spawn_mobs=banana\n";
        Files.writeString(file, original);

        WdlConfig.load(file);

        assertEquals(original, Files.readString(file),
                "gamerule.* values are raw strings validated per band at write time, never healed at parse time");
    }

    @Test
    void loadIgnoresRetiredKeysWithoutRewriting(@TempDir Path directory) throws IOException {
        // A config written by an older mod version still carries the retired captureChunks and saveNamePrefix
        // keys. Both are unknown now, so they are ignored rather than treated as malformed, and the file that
        // every real user upgrades with is left byte-for-byte untouched (no spurious heal-rewrite).
        Path file = directory.resolve("wdl.properties");
        String original = "captureChunks=false\nsaveNamePrefix=museum\ncaptureEntities=false\n";
        Files.writeString(file, original);

        WdlConfig config = WdlConfig.load(file);

        assertFalse(config.captureEntities(), "a still-recognized edit alongside the retired keys is honored");
        assertTrue(config.captureContainers(), "a retired key does not disturb the other defaults");
        assertEquals(original, Files.readString(file),
                "a retired key is ignored, not malformed, so the file is never rewritten");
    }

    @Test
    void loadRoundTripsTheZipKnobDefaults(@TempDir Path directory) {
        Path file = directory.resolve("wdl.properties");

        WdlConfig first = WdlConfig.load(file);
        WdlConfig reloaded = WdlConfig.load(file);

        assertTrue(first.zipOnFinish());
        assertTrue(first.zipOnResume());
        assertEquals(first.zipOnFinish(), reloaded.zipOnFinish(), "the materialized template round-trips zipOnFinish");
        assertEquals(first.zipOnResume(), reloaded.zipOnResume(), "the materialized template round-trips zipOnResume");
    }

    @Test
    void advancementsAndStatisticsDefaultOn() {
        assertTrue(WdlConfig.DEFAULTS.captureAdvancements(), "captureAdvancements defaults on");
        assertTrue(WdlConfig.DEFAULTS.captureStatistics(), "captureStatistics defaults on");
    }

    @Test
    void parsesTheTwoNewToggles() {
        Properties properties = new Properties();
        properties.setProperty("captureAdvancements", "false");
        properties.setProperty("captureStatistics", "false");
        WdlConfig config = WdlConfig.parse(properties);
        assertEquals(false, config.captureAdvancements());
        assertEquals(false, config.captureStatistics());
    }

    @Test
    void reportsTheTogglesAsNonDefaultWhenOff() {
        Properties properties = new Properties();
        properties.setProperty("captureAdvancements", "false");
        assertEquals("false", WdlConfig.parse(properties).nonDefaultSettings().get("captureAdvancements"));
    }

    @Test
    void overlayToneColorsDefaultToTealAndAmber() {
        assertEquals(MarkerHue.TEAL, WdlConfig.DEFAULTS.overlayCoveredColor());
        assertEquals(MarkerHue.AMBER, WdlConfig.DEFAULTS.overlaySuspectColor());
    }

    @Test
    void parsesCycledOverlayToneColors() {
        Properties properties = new Properties();
        properties.setProperty("overlayCoveredColor", "BLUE");
        properties.setProperty("overlaySuspectColor", "REDDISH_PURPLE");
        WdlConfig config = WdlConfig.parse(properties);
        assertEquals(MarkerHue.BLUE, config.overlayCoveredColor());
        assertEquals(MarkerHue.REDDISH_PURPLE, config.overlaySuspectColor());
    }

    @Test
    void malformedOverlayToneColorsSelfHealToTheirOwnDefaults() {
        Properties properties = new Properties();
        properties.setProperty("overlayCoveredColor", "octarine");
        properties.setProperty("overlaySuspectColor", "octarine");
        WdlConfig config = WdlConfig.parse(properties);
        assertEquals(MarkerHue.TEAL, config.overlayCoveredColor());
        assertEquals(MarkerHue.AMBER, config.overlaySuspectColor());
    }

    @Test
    void renderCoverageOverlayDefaultsToOn() {
        assertTrue(WdlConfig.DEFAULTS.renderCoverageOverlay());
    }

    @Test
    void parsesRenderCoverageOverlayOff() {
        Properties properties = new Properties();
        properties.setProperty("renderCoverageOverlay", "false");
        assertFalse(WdlConfig.parse(properties).renderCoverageOverlay());
    }
}
