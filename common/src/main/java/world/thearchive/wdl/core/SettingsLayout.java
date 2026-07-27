// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The settings screen's structure: the three tabs, their sections, and each section's ordered option rows, the single
 * source the per-loader render walks. Kept beside the descriptor (and MC-free) rather than as fields on
 * {@link ConfigOption} because the menu's within-section order diverges from the descriptor's template order (a gated
 * section leads with its master, which is not template-first), so the layout carries the ordering the descriptor cannot
 * express; a test pins that every row binds to a real option and that every option has exactly one row.
 *
 * <p>Row, tab, and section captions are {@code wdl.settings.*} translation keys derived by the helpers below, so the
 * label text lives in the language file, not here. The one section flagged {@link Section#isGameRuleGroup} carries the
 * master row; the render appends the dynamic curated game-rule rows after it.
 */
public final class SettingsLayout {
    public static final List<Tab> TABS = Collections.unmodifiableList(Arrays.asList(
            tab("interface",
                    section("wdl.settings.section.hud", "showHud", "hudDetailed", "hudPeekMode",
                            "hudAnchor", "hudOffsetX", "hudOffsetY", "hudBackground", "hudPanelOpacity",
                            "hudDoneLingerSeconds"),
                    section("wdl.settings.section.notifications", "showToasts", "showChatMessages")),
            tab("world",
                    section("wdl.settings.section.generation", "worldType", "worldSeed", "generateFeatures"),
                    gameRuleGroup("wdl.settings.section.game_rules", "overrideGamerules"),
                    section("wdl.settings.section.world_defaults", "overrideWorldDefaults", "openInCreative",
                            "allowCommands")),
            tab("download",
                    section("wdl.settings.section.contents", "captureEntities", "captureContainers",
                            "recaptureChunks", "recaptureSeconds", "lockDownloadedMaps", "remapMapIds",
                            "skipVoidChunks", "forceMobPersistence"),
                    section("wdl.settings.section.player_data", "savePlayerInventory", "savePlayerEnderChest",
                            "saveItemCoordinates", "captureAdvancements", "captureStatistics"),
                    section("wdl.settings.section.output", "zipOnResume", "confirmResume", "blockTaintedResume",
                            "zipOnFinish", "appendDateSuffix"),
                    section("wdl.settings.section.advanced", "autoDownload", "encodeBudgetMillis",
                            "dumpReceivedFrames"))));

    /**
     * The curated game-rule rows in their locked option-set order (the render pairs each with the band's
     * {@link CuratedGameRule} and skips any absent at the running band). Distinct from the descriptor's option rows,
     * which is why it lives beside {@link #TABS} rather than as a section's {@code optionKeys}.
     */
    public static final List<String> GAME_RULE_ORDER = Collections.unmodifiableList(Arrays.asList(
            "keep_inventory", "spawn_mobs", "advance_time", "mob_griefing", "advance_weather",
            "fire_spread_radius_around_player", "spread_vines", "spawn_wandering_traders", "spawn_patrols",
            "spawn_wardens"));

    /**
     * Config option key to the master toggle key whose off-state grays its row live. A key absent here is never gated.
     * This lives beside {@link #TABS} rather than being read off it because a section's master is not always its
     * template-first option and not every section has one, so the mapping is carried explicitly. The HUD master grays
     * the rest of the HUD section.
     */
    static final Map<String, String> ROW_MASTER = buildRowMaster();

    /**
     * Config option key to the mod ids any one of which its row requires present. A key absent here needs no mod. This
     * is data because {@link SettingsLayout} is MC-free and cannot itself check what is loaded; the client render skips
     * a row whose required mods are all absent, and drops a section header left with no rows. Both coverage-overlay
     * rows require XaeroPlus or JourneyMap.
     */
    static final Map<String, Set<String>> ROW_REQUIRED_MOD = buildRowRequiredMod();

    private SettingsLayout() {}

    /** The tab caption key for a tab id ({@code wdl.settings.tab.<id>}). */
    public static String tabLabelKey(String tabId) {
        return "wdl.settings.tab." + tabId;
    }

    /** The row caption key for a config option ({@code wdl.settings.option.<snake_case_key>}). */
    public static String optionLabelKey(String key) {
        return "wdl.settings.option." + toSnake(key);
    }

    /** The optional tooltip key for a config option; the render shows it only when the language file defines it. */
    public static String optionTooltipKey(String key) {
        return optionLabelKey(key) + ".tooltip";
    }

    /**
     * The on-widget value key for a numeric slider ({@code wdl.settings.option.<snake_case_key>.value}); the language
     * file's format string carries the unit, so the localized message reads "56 %", not a bare "56".
     */
    public static String optionValueKey(String key) {
        return optionLabelKey(key) + ".value";
    }

    /**
     * The step caption key for one constant of an enum-valued row ({@code wdl.settings.value.<lower_case_name>}). Every
     * constant of every enum option needs one, so a row never draws a raw key for a step the user can reach.
     */
    public static String valueLabelKey(Enum<?> value) {
        return "wdl.settings.value." + value.name().toLowerCase(Locale.ROOT);
    }

    /** The per-toggle confirm-body key ({@code wdl.settings.confirm.<snake_case_key>.message}). */
    public static String confirmMessageKey(String key) {
        return "wdl.settings.confirm." + toSnake(key) + ".message";
    }

    /** The row caption key for a curated game rule ({@code wdl.settings.gamerule.<id>}, the id already snake_case). */
    public static String gameRuleLabelKey(String ruleId) {
        return "wdl.settings.gamerule." + ruleId;
    }

    /** The master toggle key that grays {@code optionKey}'s row live when off, or null when the row is never gated. */
    public static @Nullable String masterKey(String optionKey) {
        return ROW_MASTER.get(optionKey);
    }

    /**
     * The mods, any one of which makes {@code optionKey}'s row visible, or an empty set when the row needs no mod to
     * appear.
     */
    public static Set<String> requiredMods(String optionKey) {
        Set<String> mods = ROW_REQUIRED_MOD.get(optionKey);
        return mods == null ? Collections.emptySet() : mods;
    }

    private static Tab tab(String id, Section... sections) {
        return new Tab(id, Arrays.asList(sections));
    }

    private static Section section(String labelKey, String... optionKeys) {
        return new Section(labelKey, Arrays.asList(optionKeys), false);
    }

    private static Section gameRuleGroup(String labelKey, String... optionKeys) {
        return new Section(labelKey, Arrays.asList(optionKeys), true);
    }

    private static String toSnake(String camel) {
        StringBuilder out = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char character = camel.charAt(i);
            if (Character.isUpperCase(character)) {
                out.append('_').append(Character.toLowerCase(character));
            } else {
                out.append(character);
            }
        }
        return out.toString();
    }

    private static Map<String, String> buildRowMaster() {
        Map<String, String> master = new LinkedHashMap<>();
        for (String key : new String[] { "hudAnchor", "hudOffsetX", "hudOffsetY", "hudDetailed", "hudPeekMode",
                "hudBackground", "hudPanelOpacity", "hudDoneLingerSeconds" }) {
            master.put(key, "showHud");
        }
        for (String key : new String[] { "openInCreative", "allowCommands" }) {
            master.put(key, "overrideWorldDefaults");
        }
        return Collections.unmodifiableMap(master);
    }

    private static Map<String, Set<String>> buildRowRequiredMod() {
        Set<String> mapMods = Collections.unmodifiableSet(
                new LinkedHashSet<>(Arrays.asList("xaeroplus", "journeymap")));
        Map<String, Set<String>> required = new LinkedHashMap<>();
        required.put("renderCoverageOverlay", mapMods);
        required.put("overlayCoveredColor", mapMods);
        required.put("overlaySuspectColor", mapMods);
        return Collections.unmodifiableMap(required);
    }

    /** One tab: an id (its caption key and tab-navigation identity) and its ordered sections. */
    public static final class Tab {
        private final String id;
        private final List<Section> sections;

        Tab(String id, List<Section> sections) {
            this.id = id;
            this.sections = Collections.unmodifiableList(sections);
        }

        public String id() {
            return id;
        }

        public List<Section> sections() {
            return sections;
        }
    }

    /** One section: an optional caption key, its ordered option rows, and whether the game-rule rows attach here. */
    public static final class Section {
        private final @Nullable String labelKey;
        private final List<String> optionKeys;
        private final boolean gameRuleGroup;

        Section(@Nullable String labelKey, List<String> optionKeys, boolean gameRuleGroup) {
            this.labelKey = labelKey;
            this.optionKeys = Collections.unmodifiableList(optionKeys);
            this.gameRuleGroup = gameRuleGroup;
        }

        /** The section caption key, or null for a section that renders without a header. */
        public @Nullable String labelKey() {
            return labelKey;
        }

        /** The section's option rows, in render order (a gated section leads with its master). */
        public List<String> optionKeys() {
            return optionKeys;
        }

        /** Whether the render appends the dynamic curated game-rule rows after this section's rows. */
        public boolean isGameRuleGroup() {
            return gameRuleGroup;
        }
    }
}
