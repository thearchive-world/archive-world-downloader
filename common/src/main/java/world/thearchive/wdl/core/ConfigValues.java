// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.Map;
import java.util.Objects;

/**
 * The typed result of one {@link ConfigSchema#read} pass: every option's parsed value keyed by its config key. The
 * typed value objects ({@link WdlConfig}, {@link HudConfig}, {@link OutlineConfig}, {@link WorldOutputConfig})
 * construct their fields from it. Package-private. A key absent from a read is a programming error, so a getter for a
 * missing key fails fast rather than returning a wrong-typed default.
 */
final class ConfigValues {
    private final Map<String, Object> values;

    ConfigValues(Map<String, Object> values) {
        this.values = values;
    }

    boolean booleanValue(String key) {
        return (Boolean) get(key);
    }

    int integer(String key) {
        return (Integer) get(key);
    }

    long longValue(String key) {
        return (Long) get(key);
    }

    float floatValue(String key) {
        return (Float) get(key);
    }

    <E extends Enum<E>> E enumValue(String key, Class<E> type) {
        return type.cast(get(key));
    }

    private Object get(String key) {
        return Objects.requireNonNull(values.get(key), key);
    }
}
