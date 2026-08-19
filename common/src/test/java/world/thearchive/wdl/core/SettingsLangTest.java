// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * A completeness guard over the derived {@code wdl.settings.*} keys the settings screen resolves. Every tab and section
 * caption, every option label and its help tooltip, every curated game-rule label, and every guarded toggle's confirm
 * body is looked up by a key derived from the layout, the descriptor, and the toggle guard, so a missing or renamed key
 * is a silent failure in game (a tooltip renders only under I18n.exists, so a typo shows nothing at all). This pins the
 * whole derived-key surface against the shipped en_us so the build fails instead. MC-free: the keys derive from core,
 * resolved against the lang file on the test classpath (the ChatCopyTest pattern).
 *
 * <p>Both directions are asserted, and the second one is an obligation to LangKeyCoverageTest rather than a
 * convenience: that guard skips these prefixes because this test owns them, so without the converse a key added or
 * outliving its producer under one of them would be checked by nothing at all.
 */
class SettingsLangTest {
    private static final Map<String, String> LANG = Collections.unmodifiableMap(loadLang());

    private static final List<String> DERIVED_PREFIXES = ImmutableList.of(
            "wdl.settings.confirm.",
            "wdl.settings.gamerule.",
            "wdl.settings.option.",
            "wdl.settings.tab.",
            "wdl.settings.value.");

    @Test
    void everyDerivedSettingsKeyResolvesInEnUs() {
        for (String key : derivedKeys()) {
            assertNotNull(LANG.get(key), "en_us.json is missing " + key);
        }
    }

    @Test
    void everyDelegatedPrefixKeyIsDerived() {
        Set<String> derived = new TreeSet<>(derivedKeys());
        Set<String> undeclared = new TreeSet<>();
        for (String key : LANG.keySet()) {
            if (DERIVED_PREFIXES.stream().anyMatch(key::startsWith) && !derived.contains(key)) {
                undeclared.add(key);
            }
        }
        assertEquals(ImmutableSet.of(), undeclared,
                "en_us carries a key under a prefix this test claims to derive, and does not derive it.\n"
                        + "LangKeyCoverageTest skips these prefixes on the strength of that claim, so the key\n"
                        + "is checked by nothing. Either its producer is gone and the key is dead across every\n"
                        + "locale, or it is hand-written and belongs outside the delegated prefixes.");
    }

    private static List<String> derivedKeys() {
        List<String> keys = new ArrayList<>();
        for (SettingsLayout.Tab tab : SettingsLayout.TABS) {
            keys.add(SettingsLayout.tabLabelKey(tab.id()));
            for (SettingsLayout.Section section : tab.sections()) {
                if (section.labelKey() != null) {
                    keys.add(section.labelKey());
                }
            }
        }
        for (ConfigOption option : ConfigSchema.OPTIONS) {
            keys.add(SettingsLayout.optionLabelKey(option.key()));
            keys.add(SettingsLayout.optionTooltipKey(option.key()));
            if (option.type() == ConfigType.INTEGER || option.type() == ConfigType.FLOAT) {
                keys.add(SettingsLayout.optionValueKey(option.key()));
            }
            if (option.type() == ConfigType.ENUM) {
                // A cycle row renders every constant through wdl.settings.value.<name>, so a constant added
                // without its label key draws the raw key in game. Locale parity cannot see an en_us-wide gap.
                Enum<?> current = (Enum<?>) option.accessor.apply(WdlConfig.DEFAULTS);
                for (Object constant : current.getDeclaringClass().getEnumConstants()) {
                    keys.add(SettingsLayout.valueLabelKey((Enum<?>) constant));
                }
            }
            if (CaptureToggleGuard.whenDisabling(option.key()) != CaptureToggleGuard.NONE) {
                keys.add(SettingsLayout.confirmMessageKey(option.key()));
            }
        }
        // Confirm-dialog frame keys are shared, not derived per option, and the recapture reduction confirm
        // carries per-target bodies (an enum-transition confirm, not a boolean-disable one driven above).
        for (String frame : new String[] { "wdl.settings.confirm.capture", "wdl.settings.confirm.recapture" }) {
            keys.add(frame + ".title");
            keys.add(frame + ".confirm");
        }
        keys.add("wdl.settings.confirm.recapture.to_nearby.message");
        keys.add("wdl.settings.confirm.recapture.to_off.message");
        for (String ruleId : SettingsLayout.GAME_RULE_ORDER) {
            keys.add(SettingsLayout.gameRuleLabelKey(ruleId));
        }
        return keys;
    }

    private static Map<String, String> loadLang() {
        InputStream stream = SettingsLangTest.class.getResourceAsStream("/assets/wdl/lang/en_us.json");
        if (stream == null) {
            throw new IllegalStateException("en_us.json not on the test classpath");
        }
        return new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8),
                new TypeToken<Map<String, String>>() {}.getType());
    }
}
