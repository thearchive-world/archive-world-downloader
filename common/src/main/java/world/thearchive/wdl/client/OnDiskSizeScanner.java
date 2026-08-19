// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.export.FolderSize;
import world.thearchive.wdl.core.export.RestoreSource;

/**
 * Walks download folders off the render thread so the download screen can fill in each row's on-disk size: the screen
 * submits a folder here the first time the row is drawn and drains the true on-disk total when it lands; a row shows no
 * size until then. The walk reuses {@link FolderSize#onDiskSize} (the one size algorithm) on a single daemon thread, so
 * folders are walked sequentially and cannot saturate disk I/O on weak hardware. Each completed walk crosses back as a
 * {@link Result} on a queue the screen drains on its tick, never a callback, so nothing runs on the walker thread.
 *
 * <p>The same worker also answers the tainted rows' restore-source availability probe (the request kind is the submit
 * flag): one queue, disjoint row domains, since a tainted row never shows a size and a size row never shows the restore
 * chip. An availability result carries the newest clean source zip via {@link RestoreSource#find} and no size; a size
 * result carries no source.
 *
 * <p>{@link #close()} stops the scanner when its screen closes: queued walks are dropped, and because the underlying
 * {@link FolderSize} walk is uninterruptible, a walk already running finishes on the daemon thread and its
 * {@link Result} is discarded rather than written into the replaced screen. Daemon threads also mean a survivor walk
 * can never block JVM exit.
 */
final class OnDiskSizeScanner implements AutoCloseable {
    /**
     * One finished request for {@code folder}: a size walk's on-disk total ({@linkplain OptionalLong#empty() empty} if
     * it failed) with no source, or an availability probe's clean source zip (null when none qualifies) with no size.
     */
    static final class Result {
        private final Path folder;
        private final OptionalLong size;
        private final @Nullable Path restoreSource;

        Result(Path folder, OptionalLong size, @Nullable Path restoreSource) {
            this.folder = folder;
            this.size = size;
            this.restoreSource = restoreSource;
        }

        Path folder() {
            return folder;
        }

        OptionalLong size() {
            return size;
        }

        @Nullable
        Path restoreSource() {
            return restoreSource;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "wdl-size-scanner");
        thread.setDaemon(true);
        return thread;
    });
    private final Queue<Result> completed = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;

    /**
     * Schedule an off-thread request for {@code folder}, delivered through {@link #drainCompleted()}:
     * {@code availability} picks the restore-source probe over the size walk.
     */
    void submit(Path folder, boolean availability) {
        if (closed) {
            return;
        }
        try {
            executor.execute(() -> {
                Result result = availability
                        ? new Result(folder, OptionalLong.empty(), availabilitySource(folder))
                        : new Result(folder, FolderSize.onDiskSize(folder), null);
                if (!closed) {
                    completed.add(result);
                }
            });
        } catch (RejectedExecutionException e) {
            // Closed concurrently with this submit; the screen the result would feed is already gone.
        }
    }

    /** The newest clean restore source for the download folder, or null when no candidate qualifies. */
    private static @Nullable Path availabilitySource(Path folder) {
        Path savesDirectory = folder.getParent();
        Path name = folder.getFileName();
        if (savesDirectory == null || name == null) {
            return null;
        }
        return RestoreSource.find(savesDirectory, name.toString()).map(RestoreSource::zip).orElse(null);
    }

    /** Whether the scanner has been closed; a closed scanner accepts no walks and must be replaced to scan again. */
    boolean isClosed() {
        return closed;
    }

    /** The requests finished since the last drain, oldest first; called on the render thread (the screen tick). */
    List<Result> drainCompleted() {
        List<Result> drained = new ArrayList<>();
        Result result;
        while ((result = completed.poll()) != null) {
            drained.add(result);
        }
        return drained;
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
    }
}
