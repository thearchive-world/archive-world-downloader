// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The off-thread folder-size walk feeding the download screen's on-disk size: delivery and close. */
class OnDiskSizeScannerTest {
    private static final long AWAIT_MILLIS = 2000;

    @Test
    void deliversTheOnDiskTotalBack(@TempDir Path folder) throws IOException, InterruptedException {
        Files.write(folder.resolve("level.dat"), new byte[100]);
        Path region = Files.createDirectories(folder.resolve("region"));
        Files.write(region.resolve("r.0.0.mca"), new byte[50]);

        OnDiskSizeScanner scanner = new OnDiskSizeScanner();
        try {
            scanner.submit(folder, false);
            OnDiskSizeScanner.Result result = awaitOne(scanner, folder);
            assertEquals(OptionalLong.of(150), result.size(), "the walk delivers the recursive on-disk total");
            assertNull(result.restoreSource(), "a size walk never carries a restore source");
        } finally {
            scanner.close();
        }
    }

    @Test
    void deliversEmptyWhenTheWalkFails(@TempDir Path parent) throws InterruptedException {
        Path missing = parent.resolve("does-not-exist");

        OnDiskSizeScanner scanner = new OnDiskSizeScanner();
        try {
            scanner.submit(missing, false);
            OnDiskSizeScanner.Result result = awaitOne(scanner, missing);
            assertTrue(result.size().isEmpty(), "a failed walk delivers no size, so the row shows none");
        } finally {
            scanner.close();
        }
    }

    @Test
    void availabilityProbeDeliversTheCleanSourceZip(@TempDir Path saves) throws IOException, InterruptedException {
        Path zip = cleanSourceZip(saves, "World");

        OnDiskSizeScanner scanner = new OnDiskSizeScanner();
        try {
            scanner.submit(saves.resolve("World"), true);
            OnDiskSizeScanner.Result result = awaitOne(scanner, saves.resolve("World"));
            assertEquals(zip, result.restoreSource(), "the probe delivers the qualifying source zip");
            assertTrue(result.size().isEmpty(), "an availability probe never carries a size");
        } finally {
            scanner.close();
        }
    }

    @Test
    void availabilityProbeDeliversNullWhenNoSourceQualifies(@TempDir Path saves) throws InterruptedException {
        OnDiskSizeScanner scanner = new OnDiskSizeScanner();
        try {
            scanner.submit(saves.resolve("World"), true);
            OnDiskSizeScanner.Result result = awaitOne(scanner, saves.resolve("World"));
            assertNull(result.restoreSource(), "no qualifying zip means no source, and the chip stays hidden");
            assertTrue(result.size().isEmpty(), "an availability probe never carries a size");
        } finally {
            scanner.close();
        }
    }

    /** A minimal qualifying source zip beside the folder name: level.dat plus a record with a finish time. */
    private static Path cleanSourceZip(Path saves, String folderName) throws IOException {
        Path zip = saves.resolve(folderName + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry(folderName + "/level.dat"));
            out.write(new byte[8]);
            out.closeEntry();
            out.putNextEntry(new ZipEntry(folderName + "/wdl/download.jsonl"));
            out.write("{\"id\":\"x\",\"finishedAt\":\"2026-01-01T00:00:00Z\"}\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    @Test
    void closeMarksTheScannerClosedAndSubmitStaysQuiet(@TempDir Path folder) {
        OnDiskSizeScanner scanner = new OnDiskSizeScanner();
        assertFalse(scanner.isClosed(), "a fresh scanner is open");
        scanner.close();
        assertTrue(scanner.isClosed(), "close() marks the scanner closed");
        // The screen replaces a closed scanner rather than reopening it, so a late submit must not throw at it.
        scanner.submit(folder, false);
    }

    @Test
    void drainDeliversEveryFolderSubmitted(@TempDir Path parent) throws IOException, InterruptedException {
        Path first = Files.createDirectories(parent.resolve("first"));
        Files.write(first.resolve("a.bin"), new byte[10]);
        Path second = Files.createDirectories(parent.resolve("second"));
        Files.write(second.resolve("b.bin"), new byte[20]);

        OnDiskSizeScanner scanner = new OnDiskSizeScanner();
        try {
            scanner.submit(first, false);
            scanner.submit(second, false);
            Map<Path, OptionalLong> delivered = awaitAll(scanner, first, second);
            assertEquals(OptionalLong.of(10), delivered.get(first), "the first folder's size lands");
            assertEquals(OptionalLong.of(20), delivered.get(second), "the second folder's size lands");
        } finally {
            scanner.close();
        }
    }

    /** Drain until the submitted folder's result lands, failing after a bounded wait rather than blocking forever. */
    private static OnDiskSizeScanner.Result awaitOne(OnDiskSizeScanner scanner, Path folder)
            throws InterruptedException {
        return awaitDrained(scanner, byFolder -> byFolder.containsKey(folder), byFolder -> byFolder.get(folder),
                "the walk result did not arrive within " + AWAIT_MILLIS + " ms");
    }

    /** Drain until every submitted folder's result has landed; order across folders is not guaranteed. */
    private static Map<Path, OptionalLong> awaitAll(OnDiskSizeScanner scanner, Path... folders)
            throws InterruptedException {
        return awaitDrained(scanner, byFolder -> {
            for (Path folder : folders) {
                if (!byFolder.containsKey(folder)) {
                    return false;
                }
            }
            return true;
        }, byFolder -> {
            Map<Path, OptionalLong> delivered = new HashMap<>();
            for (Map.Entry<Path, OnDiskSizeScanner.Result> entry : byFolder.entrySet()) {
                delivered.put(entry.getKey(), entry.getValue().size());
            }
            return delivered;
        }, "not all walk results arrived within " + AWAIT_MILLIS + " ms");
    }

    /**
     * Polls every 5 ms, draining completed walks into a folder-keyed map until {@code ready} accepts the accumulation,
     * then returns {@code extract} of it; on timeout fails with {@code timeoutMessage} rather than blocking forever.
     */
    private static <T> T awaitDrained(OnDiskSizeScanner scanner,
            Predicate<Map<Path, OnDiskSizeScanner.Result>> ready,
            Function<Map<Path, OnDiskSizeScanner.Result>, T> extract, String timeoutMessage)
            throws InterruptedException {
        Map<Path, OnDiskSizeScanner.Result> byFolder = new HashMap<>();
        long deadline = System.nanoTime() + AWAIT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            for (OnDiskSizeScanner.Result result : scanner.drainCompleted()) {
                byFolder.put(result.folder(), result);
            }
            if (ready.test(byFolder)) {
                return extract.apply(byFolder);
            }
            Thread.sleep(5);
        }
        throw new AssertionError(timeoutMessage);
    }
}
