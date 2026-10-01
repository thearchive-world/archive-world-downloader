// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import java.util.regex.Pattern;

final class DatedSuffix {
    private static final Pattern pattern = Pattern.compile("-\\d{4}-\\d{2}-\\d{2}$");

    private DatedSuffix() {}

    static boolean isPresent(String name) {
        return pattern.matcher(name).find();
    }

    static String strip(String name) {
        return pattern.matcher(name).replaceFirst("");
    }
}
