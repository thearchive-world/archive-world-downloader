// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import org.jspecify.annotations.Nullable;

/**
 * Turns a save-failure {@link Throwable} into an intelligible reason phrase that always names the failure category,
 * never a bare path. MC-free so the decision is headless-testable. A {@link FileSystemException} (access denied,
 * missing path) reports its {@link Throwable#getMessage() getMessage} as the offending path with no cause when its
 * {@link FileSystemException#getReason() reason} is null, which reads to a player as though the path itself were the
 * error; those are named by category instead, while a reason-bearing exception uses its reason. Any other throwable
 * keeps its own message, falling back to the exception's simple name when it has none. The offending path is
 * deliberately left out of this player-facing phrase: the failure's throwable is logged whole alongside it, so the path
 * stays available for diagnosis without crowding the surfaced line. Fail-soft: a null or odd throwable yields a
 * sensible fallback rather than throwing, since this runs on the failure path.
 */
public final class SaveFailureComposer {
    private SaveFailureComposer() {}

    /** The reason phrase for {@code error}, always naming the failure category; unknown-error key when null. */
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
