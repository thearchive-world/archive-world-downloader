// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;

/**
 * Resolves a typed name into a {@link DownloadTarget}, MC-free and headless. Owns the download's name rules: sanitize
 * and contain to the saves directory, judge whether a typed name is usable at all, and disambiguate a new folder with
 * an idempotent date suffix that also names the world in {@code level.dat}.
 *
 * <p>The name model contains a name to a single path component (no separator, no parent element); the world-open
 * boundary asserts the resolved path stays under the saves base as defense in depth.
 */
public final class TargetResolver {
    private static final Pattern pathSeparators = Pattern.compile("[/\\\\]");
    private static final Pattern illegalChars = Pattern.compile("[\\x00-\\x1f<>:\"|?*]");
    private static final Pattern leadingDotsOrSpaces = Pattern.compile("^[.\\s]+");
    private static final Pattern trailingDotsOrSpaces = Pattern.compile("[.\\s]+$");
    // Windows refuses these device names as a basename, case-insensitively, judged by the stem before the
    // first dot; without the defusing underscore the name passes hasUsableName and then dies at createAccess.
    // The superscript digits one, two, and three parse as their COM/LPT device too, per the Win32 naming rules.
    private static final Pattern reservedDeviceStem = Pattern
            .compile("(?i)^(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?=$|\\.)");

    private TargetResolver() {}

    /**
     * A fresh download: a contained folder whose name is the sanitized {@code name}, decorated with a
     * {@code -YYYY-MM-DD} suffix when {@code appendDateSuffix} is set. The same resolved name is written to
     * {@code level.dat}, so with the suffix off both the folder and the world name are the bare sanitized name. Callers
     * gate on {@link #hasUsableName}, so {@code name} is already usable here.
     */
    public static DownloadTarget resolveNew(String name, LocalDate date, boolean appendDateSuffix) {
        String base = sanitize(name);
        String folderName = appendDateSuffix ? appendDate(base, date) : base;
        return new DownloadTarget(folderName, folderName, DownloadMode.NEW);
    }

    /** Whether a typed name yields a usable folder name after sanitizing; blank or illegal-only input does not. */
    public static boolean hasUsableName(String typedName) {
        return !sanitize(typedName).isEmpty();
    }

    /** Strip a name to a single safe path component; the result may be empty when nothing usable remains. */
    static String sanitize(String typedName) {
        String name = typedName.trim();
        name = pathSeparators.matcher(name).replaceAll("_");
        name = illegalChars.matcher(name).replaceAll("");
        name = name.replace("..", "");
        name = leadingDotsOrSpaces.matcher(name).replaceAll("");
        name = trailingDotsOrSpaces.matcher(name).replaceAll("");
        name = reservedDeviceStem.matcher(name).replaceFirst("$1_");
        return name;
    }

    /** Append {@code -YYYY-MM-DD}, idempotently: a name already ending in a date is left verbatim. */
    static String appendDate(String base, LocalDate date) {
        if (DatedSuffix.isPresent(base)) {
            return base;
        }
        return base + "-" + date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
