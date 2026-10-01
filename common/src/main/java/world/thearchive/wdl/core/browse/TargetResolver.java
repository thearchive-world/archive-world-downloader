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

    public static DownloadTarget resolveNew(String name, LocalDate date, boolean appendDateSuffix) {
        String base = sanitize(name);
        String folderName = appendDateSuffix ? appendDate(base, date) : base;
        return new DownloadTarget(folderName, folderName, DownloadMode.NEW);
    }

    public static DownloadTarget resolveResume(String folderName, Path savesDirectory) {
        String onDiskName = readBackOnDiskSpelling(folderName, savesDirectory);
        return new DownloadTarget(onDiskName, onDiskName, DownloadMode.RESUME);
    }

    private static String readBackOnDiskSpelling(String folderName, Path savesDirectory) {
        try {
            Path fileName = savesDirectory.resolve(folderName).toRealPath(LinkOption.NOFOLLOW_LINKS).getFileName();
            return fileName != null ? fileName.toString() : folderName;
        } catch (IOException e) {
            return folderName;
        }
    }

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
     * {@link TargetClassification#REFUSE_LOADED} (checked first, so a resume can never target it).
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

    public static boolean hasUsableName(String typedName) {
        return !sanitize(typedName).isEmpty();
    }

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

    static String appendDate(String base, LocalDate date) {
        if (DatedSuffix.isPresent(base)) {
            return base;
        }
        return base + "-" + date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
