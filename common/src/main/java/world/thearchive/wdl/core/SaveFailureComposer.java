// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import org.jspecify.annotations.Nullable;

/**
 * A {@link FileSystemException} (access denied, missing path) reports its {@link Throwable#getMessage() getMessage} as
 * the offending path with no cause when its {@link FileSystemException#getReason() reason} is null, which reads to a
 * player as though the path itself were the error; those are named by category instead, while a reason-bearing
 * exception uses its reason.
 */
public final class SaveFailureComposer {
    private SaveFailureComposer() {}

    /** The reason phrase for {@code error}; unknown-error key when null. */
    public static SaveFailureReason describe(@Nullable Throwable error) {
        if (error == null) {
            return SaveFailureReason.keyed("wdl.reason.unknown");
        }
        if (error instanceof FileSystemException) {
            return fileSystemReason((FileSystemException) error);
        }
        String message = error.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return SaveFailureReason.literal(message);
        }
        return SaveFailureReason.literal(error.getClass().getSimpleName());
    }

    private static SaveFailureReason fileSystemReason(FileSystemException error) {
        String reason = error.getReason();
        if (reason != null && !reason.trim().isEmpty()) {
            return SaveFailureReason.literal(reason);
        }
        if (error instanceof AccessDeniedException) {
            return SaveFailureReason.keyed("wdl.reason.access_denied");
        }
        if (error instanceof NoSuchFileException) {
            return SaveFailureReason.keyed("wdl.reason.path_not_found");
        }
        return SaveFailureReason.literal(error.getClass().getSimpleName());
    }
}
