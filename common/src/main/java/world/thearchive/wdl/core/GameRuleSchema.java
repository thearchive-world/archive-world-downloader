// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The band's view of its own game rules, so {@code core} can validate a user's {@code gamerule.*} overrides without
 * naming a single MC type. The per-band {@code LevelDataWriter} implements it over that band's rule registry;
 * {@link WorldOutputConfig#resolveGameRules} consults it to drop a typo'd value and surface an id that does not exist
 * at the running band.
 */
public interface GameRuleSchema {
    /** Whether a rule with this id exists (and is enabled) at the running band. */
    boolean hasRule(String id);

    /** Whether {@code rawValue} parses to the type the rule {@code id} expects at this band. */
    boolean acceptsValue(String id, String rawValue);
}
