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
    void changedFromDefaultsIsEmptyWhenNothingChanged() {
        assertTrue(WdlConfig.DEFAULTS.changedFrom(WdlConfig.DEFAULTS).isEmpty(),
                "an unchanged config diffs to nothing, so the report's settings section is empty");
    }

    private record DefaultOnBoolean(String key, Predicate<WdlConfig> getter) {}

    // The default-on scalar booleans that share one parse, heal, report, and materialize shape below.
    private static final List<DefaultOnBoolean> DEFAULT_ON_SCALAR_BOOLEANS = List.of(
            new DefaultOnBoolean("showChatMessages", WdlConfig::showChatMessages));

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
    void parseClampsEncodeBudgetMillisToItsOneToTenBound() {
        Properties tooHigh = new Properties();
        tooHigh.setProperty("encodeBudgetMillis", "100");
        assertEquals(10, WdlConfig.parse(tooHigh).encodeBudgetMillis(), "encodeBudgetMillis clamps to its 10ms cap");

        Properties tooLow = new Properties();
        tooLow.setProperty("encodeBudgetMillis", "0");
        assertEquals(1, WdlConfig.parse(tooLow).encodeBudgetMillis(), "encodeBudgetMillis clamps to its 1ms floor");
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
    void loadRoundTripsTheWorldOutputDefaults(@TempDir Path directory) {
        Path file = directory.resolve("wdl.properties");

        WdlConfig first = WdlConfig.load(file);
        WdlConfig reloaded = WdlConfig.load(file);

        assertEquals(first.worldOutput().overrideGameRules(), reloaded.worldOutput().overrideGameRules());
        assertEquals(first.worldOutput().overrideWorldDefaults(), reloaded.worldOutput().overrideWorldDefaults());
        assertEquals(first.worldOutput().allowCommands(), reloaded.worldOutput().allowCommands());
        assertEquals(first.worldOutput().skipVoidChunks(), reloaded.worldOutput().skipVoidChunks());
        assertEquals(first.worldOutput().autoDownload(), reloaded.worldOutput().autoDownload());
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
        Files.writeString(file, "overrideGamerules=ture\nskipVoidChunks=true\ngamerule.spawn_mobs=false\n");

        WdlConfig config = WdlConfig.load(file);

        assertTrue(config.worldOutput().overrideGameRules(), "the malformed default-on flag heals to its default");
        assertTrue(config.worldOutput().skipVoidChunks(),
                "a valid edit alongside the malformed one is preserved, not reset");
        assertEquals("false", config.worldOutput().gameRuleOverrides().get("spawn_mobs"),
                "a valid gamerule override survives the per-key heal");
        String rewritten = Files.readString(file);
        assertTrue(rewritten.contains("overrideGamerules=true"), "the healed key is rewritten valid");
        assertTrue(rewritten.contains("skipVoidChunks=true"), "the preserved edit is written back");
        assertTrue(rewritten.contains("gamerule.spawn_mobs=false"), "the preserved override is written back");
        assertTrue(rewritten.contains("configVersion="), "the rewrite is the full documented file");
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
}
