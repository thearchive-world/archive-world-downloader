// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import java.util.regex.Pattern;

/**
 * The trailing {@code -YYYY-MM-DD} download-name suffix, in one place so the strip-for-display and the
 * append-idempotence guard read the same definition and cannot drift.
 */
final class DatedSuffix {
    private static final Pattern pattern = Pattern.compile("-\\d{4}-\\d{2}-\\d{2}$");

    private DatedSuffix() {}

    /** Whether {@code name} already ends in a dated suffix. */
    static boolean isPresent(String name) {
        return pattern.matcher(name).find();
    }

    /** {@code name} with any trailing dated suffix removed. */
    static String strip(String name) {
        return pattern.matcher(name).replaceFirst("");
    }
}
