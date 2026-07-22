// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import org.jspecify.annotations.Nullable;

/**
 * The reason phrase of a save failure, either a wdl translation key naming a failure category (rendered at the platform
 * seam) or a verbatim literal for a throwable's own message, which has no fixed key. MC-free so the decision stays
 * headless-testable; the seam turns a keyed reason into a translatable and a literal reason into plain text.
 */
public final class SaveFailureReason {
    private final @Nullable String translationKey;
    private final String text;

    private SaveFailureReason(@Nullable String translationKey, String text) {
        this.translationKey = translationKey;
        this.text = text;
    }

    /** A category reason named by a wdl translation key (access denied, path not found, unknown error). */
    static SaveFailureReason keyed(String translationKey) {
        return new SaveFailureReason(translationKey, "");
    }

    /** A verbatim reason carrying a throwable's own message, which has no fixed key to resolve. */
    public static SaveFailureReason literal(String text) {
        return new SaveFailureReason(null, text);
    }

    /** The wdl translation key naming the category, or null when the reason is a verbatim literal. */
    public @Nullable String translationKey() {
        return translationKey;
    }

    /** The verbatim reason text when {@link #translationKey()} is null, else empty. */
    public String text() {
        return text;
    }
}
