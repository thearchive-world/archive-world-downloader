// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import org.jspecify.annotations.Nullable;

public final class SaveFailureReason {
    private final @Nullable String translationKey;
    private final String text;

    private SaveFailureReason(@Nullable String translationKey, String text) {
        this.translationKey = translationKey;
        this.text = text;
    }

    static SaveFailureReason keyed(String translationKey) {
        return new SaveFailureReason(translationKey, "");
    }

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
