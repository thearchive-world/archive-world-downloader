// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * One curated game rule as the settings menu needs it.
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

    /** The value baked into the curated safe set: the row's default. */
    public String curatedValue() {
        return curatedValue;
    }

    /** The value the enabled position writes: {@code "true"} for a boolean, the band's default for an integer rule. */
    public String enabledValue() {
        return enabledValue;
    }

    /** The value the disabled (off) position writes: {@code "false"} for a boolean, {@code "0"} for an integer rule. */
    public String disabledValue() {
        return disabledValue;
    }
}
