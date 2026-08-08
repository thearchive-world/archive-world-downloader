// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * One curated game rule as the settings menu needs it: the stable WDL rule name the menu orders and labels by, the
 * running band's own id for that rule (the config override key and what the download writes), the curated (safe) value
 * baked into the download's default rule set, and the two values a toggle row writes for its enabled and disabled
 * positions. For a boolean rule the two positions are {@code "true"} and {@code "false"}; for the integer fire rule
 * they are the band's vanilla radius and {@code "0"}. The curated value equals one of them and is the row's default (a
 * menu row shows its enabled position when its effective value equals {@link #enabledValue}).
 *
 * <p>The per-band {@code LevelDataWriter} builds this from its live rule registry, so the menu binds the same curated
 * set the download writes without re-hardcoding a set that would drift across bands. The WDL name is band-stable, so
 * {@code core} and the lang catalogs stay identical across bands; only the band id varies. MC-free and Java-8-clean:
 * the strings are raw config values, not typed rule objects.
 */
public final class CuratedGameRule {
    private final String id;
    private final String bandId;
    private final String curatedValue;
    private final String enabledValue;
    private final String disabledValue;

    public CuratedGameRule(String id, String bandId, String curatedValue, String enabledValue, String disabledValue) {
        this.id = id;
        this.bandId = bandId;
        this.curatedValue = curatedValue;
        this.enabledValue = enabledValue;
        this.disabledValue = disabledValue;
    }

    /** The stable WDL rule name: the menu order key and the {@code wdl.settings.gamerule.<id>} label key. */
    public String id() {
        return id;
    }

    /**
     * The running band's rule id: the {@code gamerule.<bandId>} override key the row writes and the download applies.
     */
    public String bandId() {
        return bandId;
    }

    /** The value baked into the curated safe set: the row's default and its revert-to-default target. */
    public String curatedValue() {
        return curatedValue;
    }

    /** The value the enabled (on) position writes: {@code "true"} for a boolean, the vanilla default for fire. */
    public String enabledValue() {
        return enabledValue;
    }

    /** The value the disabled (off) toggle position writes: {@code "false"} for a boolean, {@code "0"} for fire. */
    public String disabledValue() {
        return disabledValue;
    }
}
