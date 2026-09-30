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

public final class SettingsLayout {
    public static final List<Tab> TABS = Collections.unmodifiableList(Arrays.asList(
            tab("interface",
                    section("wdl.settings.section.hud", "showHud", "hudDetailed", "hudPeekMode",
                            "hudAnchor", "hudOffsetX", "hudOffsetY", "hudBackground", "hudPanelOpacity",
                            "hudDoneLingerSeconds"),
                    section("wdl.settings.section.container_outline", "renderUnsavedOutline",
                            "outlineDistance", "unscannedColor", "recoveredColor", "outlineLineWidthScale"),
                    section("wdl.settings.section.chunk_overlay", "renderCoverageOverlay",
                            "overlayCoveredColor", "overlaySuspectColor"),
                    section("wdl.settings.section.notifications", "showToasts", "showChatMessages",
                            "checkForUpdates")),
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
                            "dumpReceivedFrames", "outlineDebugTiming"))));

    public static final List<String> GAME_RULE_ORDER = Collections.unmodifiableList(Arrays.asList(
            "keep_inventory", "spawn_mobs", "advance_time", "mob_griefing", "advance_weather",
            "fire_spread_radius_around_player", "spread_vines", "spawn_wandering_traders", "spawn_patrols",
            "spawn_wardens"));

    static final Map<String, String> ROW_MASTER = buildRowMaster();

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

    public static String optionTooltipKey(String key) {
        return optionLabelKey(key) + ".tooltip";
    }

    /**
     * The on-widget value key for a numeric slider ({@code wdl.settings.option.<snake_case_key>.value}).
     */
    public static String optionValueKey(String key) {
        return optionLabelKey(key) + ".value";
    }

    /**
     * The step caption key for one constant of an enum-valued row ({@code wdl.settings.value.<lower_case_name>}).
     */
    public static String valueLabelKey(Enum<?> value) {
        return "wdl.settings.value." + value.name().toLowerCase(Locale.ROOT);
    }

    /** The per-toggle confirm-body key ({@code wdl.settings.confirm.<snake_case_key>.message}). */
    public static String confirmMessageKey(String key) {
        return "wdl.settings.confirm." + toSnake(key) + ".message";
    }

    /** The row caption key for a curated game rule ({@code wdl.settings.gamerule.<id>}). */
    public static String gameRuleLabelKey(String ruleId) {
        return "wdl.settings.gamerule." + ruleId;
    }

    public static @Nullable String masterKey(String optionKey) {
        return ROW_MASTER.get(optionKey);
    }

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
        for (String key : new String[] { "outlineDistance", "unscannedColor", "recoveredColor",
                "outlineLineWidthScale" }) {
            master.put(key, "renderUnsavedOutline");
        }
        for (String key : new String[] { "openInCreative", "allowCommands" }) {
            master.put(key, "overrideWorldDefaults");
        }
        for (String key : new String[] { "overlayCoveredColor", "overlaySuspectColor" }) {
            master.put(key, "renderCoverageOverlay");
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

    public static final class Section {
        private final @Nullable String labelKey;
        private final List<String> optionKeys;
        private final boolean gameRuleGroup;

        Section(@Nullable String labelKey, List<String> optionKeys, boolean gameRuleGroup) {
            this.labelKey = labelKey;
            this.optionKeys = Collections.unmodifiableList(optionKeys);
            this.gameRuleGroup = gameRuleGroup;
        }

        public @Nullable String labelKey() {
            return labelKey;
        }

        public List<String> optionKeys() {
            return optionKeys;
        }

        public boolean isGameRuleGroup() {
            return gameRuleGroup;
        }
    }
}
