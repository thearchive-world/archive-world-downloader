// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public final class SettingsDraft {
    private final Properties baseline;
    private final Properties working;
    private @Nullable WdlConfig parsed;

    private SettingsDraft(Properties baseline, Properties working) {
        this.baseline = baseline;
        this.working = working;
    }

    public static SettingsDraft of(WdlConfig live) {
        Properties seed = new Properties();
        for (Map.Entry<String, String> entry : ConfigSchema.project(live).entrySet()) {
            seed.setProperty(entry.getKey(), entry.getValue());
        }
        return new SettingsDraft((Properties) seed.clone(), seed);
    }

    /** The staged raw string for {@code key}, or null when no such key is staged. */
    public @Nullable String get(String key) {
        return working.getProperty(key);
    }

    /** Stage a raw string for {@code key} (a scalar option key); use {@link #setGameRule} for a game rule. */
    public void set(String key, String value) {
        working.setProperty(key, value);
        parsed = null;
    }

    /** Whether any staged value differs from the seeded live values. */
    public boolean isDirty() {
        return !working.equals(baseline);
    }

    public boolean isModifiedFromDefault(String key) {
        ConfigOption option = ConfigSchema.option(key);
        return !option.accessor.apply(toConfig()).equals(option.accessor.apply(WdlConfig.DEFAULTS));
    }

    public boolean isAtDefaults() {
        return isAtDefaults(key -> true);
    }

    public boolean isAtDefaults(Predicate<String> drawn) {
        for (ConfigOption option : ConfigSchema.OPTIONS) {
            if (drawn.test(option.key()) && isModifiedFromDefault(option.key())) {
                return false;
            }
        }
        return gameRuleKeys().isEmpty();
    }

    public void revert(String key) {
        if (key.startsWith(WorldOutputConfig.GAME_RULE_PREFIX)) {
            working.remove(key);
        } else {
            working.setProperty(key, ConfigSchema.option(key).defaultValue());
        }
        parsed = null;
    }

    public void revertAllToDefaults() {
        for (ConfigOption option : ConfigSchema.OPTIONS) {
            working.setProperty(option.key(), option.defaultValue());
        }
        for (String name : gameRuleKeys()) {
            working.remove(name);
        }
        parsed = null;
    }

    public WdlConfig toConfig() {
        WdlConfig config = parsed;
        if (config == null) {
            config = WdlConfig.parse(healBadValuesToBaseline());
            parsed = config;
        }
        return config;
    }

    /**
     * A staged scalar whose value no longer parses (an unparseable color, a blanked seed) heals to the value the field
     * held when the screen opened, not the descriptor default, so an interrupted mid-edit does not silently replace a
     * custom value with stock.
     */
    private Properties healBadValuesToBaseline() {
        Properties healed = null;
        List<String> throwaway = new ArrayList<>();
        for (ConfigOption option : ConfigSchema.OPTIONS) {
            String staged = working.getProperty(option.key());
            String openTime = baseline.getProperty(option.key());
            if (staged == null || openTime == null || option.type.parse(option, staged, throwaway) != null) {
                continue;
            }
            if (healed == null) {
                healed = (Properties) working.clone();
            }
            healed.setProperty(option.key(), openTime);
        }
        return healed != null ? healed : working;
    }

    public boolean getBoolean(String key) {
        return (Boolean) typed(key);
    }

    public int getInteger(String key) {
        return (Integer) typed(key);
    }

    public float getFloat(String key) {
        return (Float) typed(key);
    }

    public <E extends Enum<E>> E getEnum(String key, Class<E> type) {
        return type.cast(typed(key));
    }

    public String gameRuleValue(String id, String curatedValue) {
        return working.getProperty(WorldOutputConfig.GAME_RULE_PREFIX + id, curatedValue);
    }

    /**
     * Stage game rule {@code id} at {@code value}: written as a sparse override only when it differs from
     * {@code curatedValue}, and otherwise deleted.
     */
    public void setGameRule(String id, String value, String curatedValue) {
        String key = WorldOutputConfig.GAME_RULE_PREFIX + id;
        if (value.equals(curatedValue)) {
            working.remove(key);
        } else {
            working.setProperty(key, value);
        }
        parsed = null;
    }

    boolean hasGameRuleOverride(String id) {
        return working.containsKey(WorldOutputConfig.GAME_RULE_PREFIX + id);
    }

    /**
     * Whether game rule {@code id}'s staged value differs from {@code curatedValue}. Keyed off the value, not off
     * override presence: an override equal to the curated value is present but not a modification.
     */
    public boolean isGameRuleModified(String id, String curatedValue) {
        return !gameRuleValue(id, curatedValue).equals(curatedValue);
    }

    public void revertGameRule(String id) {
        revert(WorldOutputConfig.GAME_RULE_PREFIX + id);
    }

    private Object typed(String key) {
        return ConfigSchema.option(key).accessor.apply(toConfig());
    }

    private List<String> gameRuleKeys() {
        List<String> keys = new ArrayList<>();
        for (String name : working.stringPropertyNames()) {
            if (name.startsWith(WorldOutputConfig.GAME_RULE_PREFIX)) {
                keys.add(name);
            }
        }
        return keys;
    }
}
