// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;

/**
 * Resolves a typed or selected name into a {@link DownloadTarget}, MC-free and headless. Owns the screen's name rules:
 * sanitize and contain to the saves directory, judge whether a typed name is usable at all, disambiguate a new folder
 * with an idempotent date suffix that also names the world in {@code level.dat} (the screen strips the date for the row
 * label), target an existing folder on a resume normalized to its filesystem-reported spelling, and recognize the
 * currently-loaded world by filesystem identity so it is refused as a target.
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
     * {@code level.dat} (the screen strips any date for the row label), so with the suffix off both the folder and the
     * world name are the bare sanitized name. Callers gate on {@link #hasUsableName}, so {@code name} is already usable
     * here.
     */
    public static DownloadTarget resolveNew(String name, LocalDate date, boolean appendDateSuffix) {
        String base = sanitize(name);
        String folderName = appendDateSuffix ? appendDate(base, date) : base;
        return new DownloadTarget(folderName, folderName, DownloadMode.NEW);
    }

    /**
     * A resume: the existing folder under {@code savesDirectory}, normalized to the filesystem-reported spelling (no
     * new date suffix). The worldName is the folder name only for the report's recorded name; the resumed world's
     * actual level.dat name is read from disk by the session (it is not renamed), so this never overwrites the existing
     * name.
     */
    public static DownloadTarget resolveResume(String folderName, Path savesDirectory) {
        String onDiskName = readBackOnDiskSpelling(folderName, savesDirectory);
        return new DownloadTarget(onDiskName, onDiskName, DownloadMode.RESUME);
    }

    /**
     * The sole case-normalization mechanism for a RESUME target: resolve the candidate and read back its
     * filesystem-reported spelling, never a directory listing search. A name that does not resolve to an existing
     * directory (an exact miss on a case-sensitive filesystem, since {@code toRealPath} throws) keeps its typed
     * spelling; the caller already classified the folder as existing before minting this target.
     */
    private static String readBackOnDiskSpelling(String folderName, Path savesDirectory) {
        try {
            Path fileName = savesDirectory.resolve(folderName).toRealPath(LinkOption.NOFOLLOW_LINKS).getFileName();
            return fileName != null ? fileName.toString() : folderName;
        } catch (IOException e) {
            return folderName;
        }
    }

    /** Whether {@code candidate} is the currently-loaded world, by filesystem identity rather than path string. */
    static boolean isSameWorld(Path candidate, @Nullable Path loadedWorld) {
        if (loadedWorld == null) {
            return false;
        }
        try {
            return Files.isSameFile(candidate, loadedWorld);
        } catch (IOException e) {
            return candidate.toAbsolutePath().normalize().equals(loadedWorld.toAbsolutePath().normalize());
        }
    }

    /**
     * Classify {@code folderName} under {@code savesDirectory} against what is on disk: the currently-loaded world is
     * {@link TargetClassification#REFUSE_LOADED} (checked first, so a resume can never target it), an existing folder
     * is {@link TargetClassification#RESUME_EXISTING}, and anything else is {@link TargetClassification#NEW}. The
     * screen's primary action and {@code /wdl start <name>} both branch on this.
     */
    public static TargetClassification classifyTarget(String folderName, Path savesDirectory,
            @Nullable Path loadedWorld) {
        Path candidate = savesDirectory.resolve(folderName);
        if (isSameWorld(candidate, loadedWorld)) {
            return TargetClassification.REFUSE_LOADED;
        }
        if (Files.exists(candidate)) {
            return TargetClassification.RESUME_EXISTING;
        }
        return TargetClassification.NEW;
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
