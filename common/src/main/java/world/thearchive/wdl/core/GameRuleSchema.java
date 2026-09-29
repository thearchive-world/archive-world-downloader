// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The band's view of its own game rules, so {@code core} can validate a user's {@code gamerule.*} overrides.
 */
public interface GameRuleSchema {
    /** Whether a rule with this id exists (and is enabled) at the running band. */
    boolean hasRule(String id);

    /** Whether {@code rawValue} parses to the type the rule {@code id} expects at this band. */
    boolean acceptsValue(String id, String rawValue);
}
