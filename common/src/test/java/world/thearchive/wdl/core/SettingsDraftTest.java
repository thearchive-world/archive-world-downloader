// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The settings screen's headless edit model: a working copy seeded from the live config, a screen-level dirty flag,
 * per-key revert to default, the scalar-versus-gamerule revert split, and the parse-back-to-config path the commit
 * re-runs.
 */
class SettingsDraftTest {
    private static WdlConfig live(String... keyValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            properties.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return WdlConfig.parse(properties);
    }

    @Test
    void seedsFromTheLiveConfigAndStartsClean() {
        SettingsDraft draft = SettingsDraft.of(WdlConfig.DEFAULTS);

        assertEquals("true", draft.get("captureEntities"));
        assertTrue(draft.getBoolean("captureEntities"));
        assertEquals(15, draft.getInteger("recaptureSeconds"));
        assertEquals(MarkerHue.RED, draft.getEnum("unscannedColor", MarkerHue.class));
        assertFalse(draft.isDirty(), "an untouched working copy is not dirty");
    }

    @Test
    void seedsFromNonDefaultLiveConfig() {
        SettingsDraft draft = SettingsDraft.of(live("captureEntities", "false", "recaptureSeconds", "30"));

        assertEquals("false", draft.get("captureEntities"));
        assertEquals(30, draft.getInteger("recaptureSeconds"));
        assertFalse(draft.isDirty(), "seeding from the live values is not itself a pending edit");
    }

    @Test
    void aChangeIsDirtyAndSettingItBackClearsDirty() {
        SettingsDraft draft = SettingsDraft.of(WdlConfig.DEFAULTS);

        draft.set("captureEntities", "false");
        assertTrue(draft.isDirty());
        assertFalse(draft.getBoolean("captureEntities"));

        draft.set("captureEntities", "true");
        assertFalse(draft.isDirty(), "restoring the seeded value clears the pending state");
    }

    @Test
    void isModifiedFromDefaultTracksScalarAgainstItsDescriptorDefault() {
        SettingsDraft draft = SettingsDraft.of(WdlConfig.DEFAULTS);

        assertFalse(draft.isModifiedFromDefault("captureEntities"), "a default value is not modified");
        draft.set("captureEntities", "false");
        assertTrue(draft.isModifiedFromDefault("captureEntities"), "a non-default value shows the revert affordance");
    }

    @Test
    void aRowTheScreenDoesNotDrawCannotLightTheDefaultsButton() {
        SettingsDraft draft = SettingsDraft.of(live("outlineLineWidthScale", "2.5"));

        assertTrue(draft.isModifiedFromDefault("outlineLineWidthScale"), "the staged value is off default");
        assertFalse(draft.isAtDefaults(), "and the unfiltered read still sees it");
        assertTrue(draft.isAtDefaults(key -> !key.equals("outlineLineWidthScale")),
                "a band that hides the row offers no revert for it, so it must not light Defaults");
    }

    @Test
    void aRowTheScreenDrawsStillLightsTheDefaultsButton() {
        SettingsDraft draft = SettingsDraft.of(live("recaptureSeconds", "45"));

        assertFalse(draft.isAtDefaults(key -> !key.equals("outlineLineWidthScale")),
                "hiding one row must not blind the check to every other row, which a negated predicate would");
    }

    @Test
    void revertResetsScalarToItsDescriptorDefault() {
        SettingsDraft draft = SettingsDraft.of(live("recaptureSeconds", "45"));

        assertTrue(draft.isModifiedFromDefault("recaptureSeconds"));
        draft.revert("recaptureSeconds");
        assertEquals(15, draft.getInteger("recaptureSeconds"), "revert restores the descriptor default");
        assertFalse(draft.isModifiedFromDefault("recaptureSeconds"));
    }

    @Test
    void commitHealsAnEmptySeedToTheOpenTimeValue() {
        SettingsDraft draft = SettingsDraft.of(live("worldSeed", "42"));

        draft.set("worldSeed", "");

        assertEquals(42L, draft.toConfig().worldOutput().worldSeed(),
                "a blanked seed heals to the open-time value, not the descriptor default 0");
    }

    @Test
    void commitStillHashesNonEmptySeedGarbageRatherThanHealing() {
        SettingsDraft draft = SettingsDraft.of(live("worldSeed", "42"));

        draft.set("worldSeed", "hello"); // non-empty garbage is a deliberate text seed, hashed as vanilla does

        assertEquals((long) "hello".hashCode(), draft.toConfig().worldOutput().worldSeed(),
                "non-empty seed text is hashed, not healed to the open-time value");
    }

    @Test
    void crossTabEditsAllPersistIntoTheParsedConfig() {
        SettingsDraft draft = SettingsDraft.of(WdlConfig.DEFAULTS);

        draft.set("captureEntities", "false");     // Download tab
        draft.set("showHud", "false");            // Interface tab
        draft.set("worldType", "FLAT");           // World tab

        WdlConfig committed = draft.toConfig();
        assertFalse(committed.captureEntities());
        assertFalse(committed.hud().showHud());
        assertEquals(WorldType.FLAT, committed.worldOutput().worldType());
    }

    @Test
    void gameRuleOverrideIsWrittenOnlyWhenItDiffersFromTheCuratedValue() {
        SettingsDraft draft = SettingsDraft.of(WdlConfig.DEFAULTS);

        // Curated keep_inventory is "true"; setting it to "true" writes no override (keeps the map sparse)
        draft.setGameRule("keep_inventory", "true", "true");
        assertFalse(draft.hasGameRuleOverride("keep_inventory"));

        // Setting it away from curated writes the sparse override
        draft.setGameRule("keep_inventory", "false", "true");
        assertTrue(draft.hasGameRuleOverride("keep_inventory"));
        assertEquals("false", draft.toConfig().worldOutput().gameRuleOverrides().get("keep_inventory"));
    }

    @Test
    void revertingGameRuleDeletesItsOverrideInsteadOfSettingModelDefault() {
        SettingsDraft draft = SettingsDraft.of(live("gamerule.spawn_mobs", "true"));

        assertTrue(draft.hasGameRuleOverride("spawn_mobs"), "the seeded override is present");
        draft.revert("gamerule.spawn_mobs");
        assertFalse(draft.hasGameRuleOverride("spawn_mobs"), "revert deletes the sparse entry, so curated stands");
        assertTrue(draft.toConfig().worldOutput().gameRuleOverrides().isEmpty());
    }

    @Test
    void gameRuleValueReturnsTheOverrideOrElseTheCuratedValue() {
        SettingsDraft draft = SettingsDraft.of(live("gamerule.spawn_mobs", "true"));

        assertEquals("true", draft.gameRuleValue("spawn_mobs", "false"), "an override wins");
        assertEquals("false", draft.gameRuleValue("mob_griefing", "false"), "an untouched rule shows curated");
    }

    @Test
    void gameRuleIsModifiedKeysOffValueDifferenceNotOverridePresence() {
        // A hand-written override equal to the curated value is seeded verbatim (parse never drops it), so the
        // sparse entry is present even though the row sits at its default. Keying the revert affordance off
        // presence would wrongly show the glyph; value-difference is the correct test.
        SettingsDraft draft = SettingsDraft.of(live("gamerule.keep_inventory", "true"));

        assertTrue(draft.hasGameRuleOverride("keep_inventory"), "the equal-to-curated override is in the seed");
        assertFalse(draft.isGameRuleModified("keep_inventory", "true"),
                "an override equal to the curated value is not a modification");

        draft.setGameRule("keep_inventory", "false", "true");
        assertTrue(draft.isGameRuleModified("keep_inventory", "true"),
                "a value differing from curated is a modification");
    }

    @Test
    void revertAllToDefaultsClearsEveryScalarAndEveryOverride() {
        SettingsDraft draft = SettingsDraft.of(live("captureEntities", "false", "recaptureSeconds", "45",
                "gamerule.spawn_mobs", "true"));

        draft.revertAllToDefaults();

        assertEquals("true", draft.get("captureEntities"));
        assertEquals(15, draft.getInteger("recaptureSeconds"), "the reset covers a ranged scalar too");
        assertTrue(draft.toConfig().worldOutput().gameRuleOverrides().isEmpty());
        assertTrue(draft.toConfig().nonDefaultSettings().isEmpty(), "nothing reportable differs from the defaults");
    }

    @Test
    void isAtDefaultsHoldsForPristineDraftAndBreaksOnAnyScalarEdit() {
        SettingsDraft draft = SettingsDraft.of(WdlConfig.DEFAULTS);
        assertTrue(draft.isAtDefaults(), "a draft seeded from the defaults is at the defaults");

        draft.set("captureEntities", "false");
        assertFalse(draft.isAtDefaults(), "a non-default scalar leaves the defaults");

        draft.set("captureEntities", "true");
        assertTrue(draft.isAtDefaults(), "restoring the scalar returns to the defaults");
    }

    @Test
    void isAtDefaultsBreaksOnGameRuleOverrideAndRevertAllRestoresIt() {
        SettingsDraft draft = SettingsDraft.of(live("gamerule.spawn_mobs", "true"));
        assertFalse(draft.isAtDefaults(), "a staged gamerule override is not the defaults");

        draft.revertAllToDefaults();
        assertTrue(draft.isAtDefaults(), "clearing every override returns to the defaults");
    }

    @Test
    void isAtDefaultsTracksTheValueNotTheDirtyFlag() {
        SettingsDraft draft = SettingsDraft.of(live("captureEntities", "false"));
        assertFalse(draft.isAtDefaults(), "a non-default seeded scalar is not at the defaults");

        draft.set("captureEntities", "true");
        assertTrue(draft.isAtDefaults(), "an edit that lands on the default value is at the defaults");
        assertTrue(draft.isDirty(), "though it still differs from the seeded live value");
    }

    @Test
    void isAtDefaultsCountsPresentButEqualGameRuleOverrideAsNotDefault() {
        // The gamerule half keys off override presence, not value difference, so a redundant override equal to
        // the curated value still blocks at-defaults: revertAllToDefaults would drop that sparse line from the
        // rendered file, so the reset button is not a no-op and must stay shown.
        SettingsDraft draft = SettingsDraft.of(live("gamerule.keep_inventory", "true"));
        assertTrue(draft.hasGameRuleOverride("keep_inventory"), "the equal-to-curated override is in the seed");
        assertFalse(draft.isAtDefaults(), "a present override blocks at-defaults because reset would remove the key");

        draft.revertAllToDefaults();
        assertTrue(draft.isAtDefaults(), "dropping the redundant override returns to the defaults");
    }
}
