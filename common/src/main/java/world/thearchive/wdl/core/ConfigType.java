// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The closed classification of a config value's type. Each constant owns both directions: {@link #parse} turns a raw
 * string into a typed value (recording a present-but-unparseable value in {@code malformed} and returning {@code null}
 * so the caller falls back to the descriptor default, clamping a ranged number in range), and {@link #format} renders a
 * typed value back to its canonical string. A settings menu maps each constant to an editing widget. Package-private.
 *
 * <p>Failure contract: a present value that does not parse records {@link ConfigOption#key} and returns {@code null};
 * an out-of-range number clamps and is not malformed; a {@link #LONG} accepts any text and a blank one returns
 * {@code null} to take the default.
 */
public enum ConfigType {
    BOOLEAN {
        @Override
        @Nullable
        Object parse(ConfigOption option, String raw, List<String> malformed) {
            String value = raw.trim();
            if (value.equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            }
            if (value.equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
            malformed.add(option.key);
            return null;
        }

        @Override
        String format(Object value) {
            return value.toString();
        }
    },
    INTEGER {
        @Override
        @Nullable
        Object parse(ConfigOption option, String raw, List<String> malformed) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                return (int) Math.max(option.min, Math.min(option.max, parsed));
            } catch (NumberFormatException e) {
                malformed.add(option.key);
                return null;
            }
        }

        @Override
        String format(Object value) {
            return value.toString();
        }
    },
    LONG {
        @Override
        @Nullable
        Object parse(ConfigOption option, String raw, List<String> malformed) {
            String value = raw.trim();
            if (value.isEmpty()) {
                return null;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return (long) value.hashCode();
            }
        }

        @Override
        String format(Object value) {
            return value.toString();
        }
    },
    FLOAT {
        @Override
        @Nullable
        Object parse(ConfigOption option, String raw, List<String> malformed) {
            try {
                float parsed = Float.parseFloat(raw.trim());
                // Float.parseFloat accepts NaN and Infinity, which the clamp cannot tame (NaN poisons both
                // Math.min and Math.max, Infinity pins silently to a bound), so a non-finite value is malformed.
                if (Float.isFinite(parsed)) {
                    return Math.max((float) option.min, Math.min((float) option.max, parsed));
                }
            } catch (NumberFormatException e) {
                // An unparseable value falls through to the same malformed flag as a non-finite one
            }
            malformed.add(option.key);
            return null;
        }

        @Override
        String format(Object value) {
            return value.toString();
        }
    },
    ENUM {
        @Override
        @Nullable
        Object parse(ConfigOption option, String raw, List<String> malformed) {
            try {
                return Objects.requireNonNull(option.enumParser).apply(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                malformed.add(option.key);
                return null;
            }
        }

        @Override
        String format(Object value) {
            return ((Enum<?>) value).name();
        }
    };

    @Nullable
    abstract Object parse(ConfigOption option, String raw, List<String> malformed);

    abstract String format(Object value);
}
