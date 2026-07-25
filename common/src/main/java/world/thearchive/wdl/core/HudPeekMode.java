// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * How the HUD peek key reveals the detailed layout: {@link #HOLD} shows it only while the key is held, {@link #TOGGLE}
 * flips it on and off per press. TOGGLE is the accessibility path for input that cannot sustain a hold (switch,
 * eye-tracking, limited dexterity).
 */
public enum HudPeekMode {
    HOLD,
    TOGGLE
}
