// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * The settings screen's headless working copy: a staged {@link Properties} seeded by projecting the live config through
 * the descriptor, edited by the screen's widget callbacks, and parsed back to a {@link WdlConfig} on commit. Keeping
 * the model here (MC-free) lets the whole edit lifecycle, the dirty flag, the diff against the defaults, and the
 * scalar-versus-gamerule revert split, be tested without a client; the screen holds one of these on its instance so a
 * resize rebuild reads the same staged values.
 *
 * <p>A scalar option is keyed by its config key and reverts to the descriptor default; a game rule is keyed by
 * {@code gamerule.<id>} and reverts by deleting its sparse entry so the band's curated value re-applies. An override is
 * written only when it differs from the curated value, so the map stays sparse.
 */
public final class SettingsDraft {
    private final Properties baseline;
    private final Properties working;
    private @Nullable WdlConfig parsed;

    private SettingsDraft(Properties baseline, Properties working) {
        this.baseline = baseline;
        this.working = working;
    }

    /** Seed a fresh working copy from the live config's full projection (every option, plus its overrides). */
    public static SettingsDraft of(WdlConfig live) {
        Properties seed = new Properties();
        for (Map.Entry<String, String> entry : ConfigSchema.project(live).entrySet()) {
            seed.setProperty(entry.getKey(), entry.getValue());
        }
        return new SettingsDraft((Properties) seed.clone(), seed);
    }

    /** The staged raw string for {@code key}, or null when no such key is staged (an untouched game rule). */
    public @Nullable String get(String key) {
        return working.getProperty(key);
    }

    /** Stage a raw string for {@code key} (a scalar option key); use {@link #setGameRule} for a game rule. */
    public void set(String key, String value) {
        working.setProperty(key, value);
        parsed = null;
    }

    /** Whether any staged value differs from the seeded live values, the screen-level dirty state. */
    public boolean isDirty() {
        return !working.equals(baseline);
    }

    /** Whether a scalar option's staged value differs from its descriptor default, gating its revert row. */
    public boolean isModifiedFromDefault(String key) {
        ConfigOption option = ConfigSchema.option(key);
        return !option.accessor.apply(toConfig()).equals(option.accessor.apply(WdlConfig.DEFAULTS));
    }

    /** Whether the staged state already equals defaults, so {@link #revertAllToDefaults} would be a no-op. */
    public boolean isAtDefaults() {
        for (ConfigOption option : ConfigSchema.OPTIONS) {
            if (isModifiedFromDefault(option.key())) {
                return false;
            }
        }
        return gameRuleKeys().isEmpty();
    }

    /** Revert one row: a scalar to its descriptor default, a {@code gamerule.<id>} by deleting its sparse entry. */
    public void revert(String key) {
        if (key.startsWith(WorldOutputConfig.GAME_RULE_PREFIX)) {
            working.remove(key);
        } else {
            working.setProperty(key, ConfigSchema.option(key).defaultValue());
        }
        parsed = null;
    }

    /** Stage every scalar at its descriptor default and drop every game-rule override (the Defaults button). */
    public void revertAllToDefaults() {
        for (ConfigOption option : ConfigSchema.OPTIONS) {
            working.setProperty(option.key(), option.defaultValue());
        }
        for (String name : gameRuleKeys()) {
            working.remove(name);
        }
        parsed = null;
    }

    /** The staged values parsed back into a config: the value a commit persists and the source of typed reads. */
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
     * custom value with stock. Non-empty seed text parses (it hashes as vanilla does), so it is never bad here, and a
     * {@code gamerule.*} override is not a scalar option and is left untouched.
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

    /** The staged value of game rule {@code id}: its override if one is staged, otherwise {@code curatedValue}. */
    public String gameRuleValue(String id, String curatedValue) {
        return working.getProperty(WorldOutputConfig.GAME_RULE_PREFIX + id, curatedValue);
    }

    /**
     * Stage game rule {@code id} at {@code value}: written as a sparse override only when it differs from
     * {@code curatedValue}, and otherwise deleted, so an override is present exactly when the row differs from the
     * curated default.
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

    /** Whether game rule {@code id} has a staged sparse override entry, apart from whether it differs from curated. */
    boolean hasGameRuleOverride(String id) {
        return working.containsKey(WorldOutputConfig.GAME_RULE_PREFIX + id);
    }

    /**
     * Whether game rule {@code id}'s staged value differs from {@code curatedValue}, gating its revert row. Keyed off
     * the value, not off override presence: a hand-written override harvested equal to the curated value is present but
     * not a modification, so it must not show the revert affordance.
     */
    public boolean isGameRuleModified(String id, String curatedValue) {
        return !gameRuleValue(id, curatedValue).equals(curatedValue);
    }

    /** Revert game rule {@code id} by deleting its sparse override so the band's curated value re-applies. */
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
