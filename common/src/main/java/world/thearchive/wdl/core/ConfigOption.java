// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One config option's descriptor: its key, value {@link ConfigType}, canonical default string, optional numeric range,
 * the enum parser for an {@link ConfigType#ENUM} option, the read accessor off a live {@link WdlConfig}, and the exact
 * template bytes that precede its {@code key=value} line. The single source the template, parse, defaults, projection,
 * and report all derive from. The settings menu reads the value shape through the public accessors below to map each
 * option to an editing widget.
 *
 * <p>The range is stored as a {@code double} pair holding either integer or float bounds (both are exactly
 * representable), so one range representation serves both numeric types. An unbounded integer carries the full
 * {@code int} range, which makes its clamp an identity.
 */
public final class ConfigOption {
    final String key;
    final ConfigType type;
    final String defaultValue;
    final double min;
    final double max;
    final @Nullable Function<String, Object> enumParser;
    final Function<WdlConfig, Object> accessor;
    final String preamble;
    final boolean losesDataOnDisable;

    private ConfigOption(String key, ConfigType type, String defaultValue, double min, double max,
            @Nullable Function<String, Object> enumParser, Function<WdlConfig, Object> accessor, String preamble,
            boolean losesDataOnDisable) {
        this.key = key;
        this.type = type;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.enumParser = enumParser;
        this.accessor = accessor;
        this.preamble = preamble;
        this.losesDataOnDisable = losesDataOnDisable;
    }

    /** The {@code wdl.properties} key, the settings row's binding and its {@code gamerule.*}-free identity. */
    public String key() {
        return key;
    }

    /** The value shape, which the settings menu maps to an editing widget. */
    public ConfigType type() {
        return type;
    }

    /** The canonical default string, the row's revert-to-default target. */
    public String defaultValue() {
        return defaultValue;
    }

    /** The inclusive lower bound of a ranged numeric option (the full type range when unbounded). */
    public double min() {
        return min;
    }

    /** The inclusive upper bound of a ranged numeric option (the full type range when unbounded). */
    public double max() {
        return max;
    }

    /** Whether disabling this toggle destroys already-captured data, so the settings menu confirms the change. */
    boolean losesDataOnDisable() {
        return losesDataOnDisable;
    }

    static ConfigOption booleanOption(String key, String defaultValue, Function<WdlConfig, Object> accessor,
            String preamble) {
        return new ConfigOption(key, ConfigType.BOOLEAN, defaultValue, 0, 0, null, accessor, preamble, false);
    }

    /** A boolean option whose disabling destroys already-captured data, so the menu confirms the change. */
    static ConfigOption dataLossBoolean(String key, String defaultValue, Function<WdlConfig, Object> accessor,
            String preamble) {
        return new ConfigOption(key, ConfigType.BOOLEAN, defaultValue, 0, 0, null, accessor, preamble, true);
    }

    static ConfigOption rangedInteger(String key, String defaultValue, int min, int max,
            Function<WdlConfig, Object> accessor, String preamble) {
        return new ConfigOption(key, ConfigType.INTEGER, defaultValue, min, max, null, accessor, preamble, false);
    }

    static ConfigOption longSeed(String key, String defaultValue, Function<WdlConfig, Object> accessor,
            String preamble) {
        return new ConfigOption(key, ConfigType.LONG, defaultValue, 0, 0, null, accessor, preamble, false);
    }

    static ConfigOption rangedFloat(String key, String defaultValue, float min, float max,
            Function<WdlConfig, Object> accessor, String preamble) {
        return new ConfigOption(key, ConfigType.FLOAT, defaultValue, min, max, null, accessor, preamble, false);
    }

    static ConfigOption enumOption(String key, String defaultValue, Function<String, Object> enumParser,
            Function<WdlConfig, Object> accessor, String preamble) {
        return new ConfigOption(key, ConfigType.ENUM, defaultValue, 0, 0, enumParser, accessor, preamble, false);
    }
}
