// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.core.export.RestoreOperation.RestoreSweep;
import world.thearchive.wdl.core.export.RestoreOperation.RestoreSweep.SweepResult;
import world.thearchive.wdl.testsupport.JulCapture;

class RestoreSweepTest {
    @TempDir
    Path saves;

    @RegisterExtension
    final JulCapture warnings = JulCapture.of(RestoreOperation.class);

    @BeforeEach
    void resetSweepState() {
        RestoreSweep.resetForTest();
    }

    @Test
    void installAbsentDeletesUnlockedAsideOnly() throws IOException {
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        liveFolder("World", 9);
        SweepResult result = RestoreSweep.run(saves);
        assertTrue(result.changedDisk());
        assertTrue(result.movedBack().isEmpty());
        assertTrue(result.relocated().isEmpty());
        assertTrue(result.missingDeferred().isEmpty());
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
        assertEquals(9, Files.readAllBytes(saves.resolve("World/level.dat"))[0]);

        Path locked = craftAttempt("World-1");
        putAside(locked, "World");
        Path aside = locked.resolve("aside").resolve("World");
        Path lock = Files.write(aside.resolve("session.lock"), new byte[] { 0x2A });
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            SweepResult deferred = RestoreSweep.run(saves);
            assertFalse(deferred.changedDisk());
            assertTrue(Files.exists(aside.resolve("level.dat")));
        }
        drainParkOf(lock);
    }

    @Test
    void folderMissingMovesTheAsideBack() throws IOException {
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        putInstall(attempt, "World");
        SweepResult result = RestoreSweep.run(saves);
        assertTrue(result.changedDisk());
        assertEquals(List.of(saves.resolve("World")), result.movedBack());
        assertTrue(result.relocated().isEmpty());
        assertTrue(result.missingDeferred().isEmpty());
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat")));
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
    }

    @Test
    void occupiedTargetRelocatesPerTheTerminalDisposition() throws IOException {
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        putInstall(attempt, "World");
        liveFolder("World", 5);
        SweepResult result = RestoreSweep.run(saves);
        assertTrue(result.changedDisk());
        assertEquals(List.of(saves.resolve("World_(2)")), result.relocated());
        assertTrue(result.movedBack().isEmpty());
        assertTrue(Files.exists(saves.resolve("World_(2)/playerdata/u.dat")));
        assertEquals(5, Files.readAllBytes(saves.resolve("World/level.dat"))[0]);
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
    }

    @Test
    void noAsideDeletesTheAttemptAndEmptyRootGoes() throws IOException {
        Path attempt = craftAttempt("World-1");
        putInstall(attempt, "World");
        SweepResult result = RestoreSweep.run(saves);
        assertTrue(result.changedDisk());
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
    }

    @Test
    void liveLockedAttemptIsNeverProcessed() throws IOException {
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        putInstall(attempt, "World");
        try (FileChannel channel = FileChannel.open(attempt.resolve("attempt.lock"), StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            assertTrue(RestoreSweep.hasWork(saves));
            SweepResult result = RestoreSweep.run(saves);
            assertFalse(result.changedDisk());
            assertTrue(result.movedBack().isEmpty());
            assertTrue(result.missingDeferred().isEmpty());
        }
        assertTrue(Files.exists(attempt.resolve("aside").resolve("World").resolve("level.dat")));
        assertTrue(Files.exists(attempt.resolve("install").resolve("World").resolve("level.dat")));
        assertFalse(Files.exists(saves.resolve("World")));
    }

    @Test
    void partCleanupIsAgeGated() throws IOException {
        long[] nowMs = { 10_000_000L };
        RestoreSweep.clock = () -> nowMs[0];
        Path stale = Files.write(saves.resolve("wdl-export-old.part"), new byte[] { 1 });
        Path fresh = Files.write(saves.resolve("wdl-export-new.part"), new byte[] { 2 });
        Files.setLastModifiedTime(stale, FileTime.fromMillis(nowMs[0] - 65L * 60_000));
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(nowMs[0] - 5L * 60_000));
        SweepResult result = RestoreSweep.run(saves);
        assertTrue(result.changedDisk());
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh));
    }

    @Test
    void memoFastPathAndTtl() throws IOException {
        long[] nowMs = { 100_000_000L };
        RestoreSweep.clock = () -> nowMs[0];

        assertFalse(RestoreSweep.hasWork(saves));

        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        liveFolder("World", 9);
        Path aside = attempt.resolve("aside").resolve("World");
        Path lock = Files.write(aside.resolve("session.lock"), new byte[] { 0x2A });
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            assertTrue(RestoreSweep.hasWork(saves));

            SweepResult deferred = RestoreSweep.run(saves);
            assertFalse(deferred.changedDisk());
            assertTrue(Files.exists(aside.resolve("level.dat")));
            assertFalse(RestoreSweep.hasWork(saves));

            // Past the TTL while still locked: no fail-to-pass, still quiet.
            nowMs[0] += RestoreSweep.TTL_MS + 1;
            assertFalse(RestoreSweep.hasWork(saves));
        }
        assertTrue(RestoreSweep.hasWork(saves));
        drainParkOf(lock);

        // A missing folder is an observed-transition bypass: the folder reappearing re-arms within the TTL.
        RestoreSweep.resetForTest();
        RestoreSweep.clock = () -> nowMs[0];
        Path missing = craftAttempt("Ghost-1");
        putAside(missing, "Ghost");
        putInstall(missing, "Ghost");
        Path ghostAside = missing.resolve("aside").resolve("Ghost");
        Path ghostLock = Files.write(ghostAside.resolve("session.lock"), new byte[] { 0x2A });
        try (FileChannel channel = FileChannel.open(ghostLock, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            SweepResult ghostDeferred = RestoreSweep.run(saves);
            assertEquals(List.of(saves.resolve("Ghost")), ghostDeferred.missingDeferred());
            assertFalse(RestoreSweep.hasWork(saves));
            liveFolder("Ghost", 4);
            assertTrue(RestoreSweep.hasWork(saves));
        }
        drainParkOf(ghostLock);
    }

    @Test
    void deepAttemptContentChangeDoesNotAlterTheSignature() throws IOException {
        long[] nowMs = { 100_000_000L };
        RestoreSweep.clock = () -> nowMs[0];

        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        liveFolder("World", 9);
        Path aside = attempt.resolve("aside").resolve("World");
        Path deep = aside.resolve("region/r.0.0/entities/deep.dat");
        write(deep, 1);
        Path lock = Files.write(aside.resolve("session.lock"), new byte[] { 0x2A });
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            assertTrue(RestoreSweep.hasWork(saves));
            SweepResult deferred = RestoreSweep.run(saves);
            assertFalse(deferred.changedDisk());
            assertFalse(RestoreSweep.hasWork(saves));

            Files.write(deep, new byte[] { 2, 2, 2 });
            Files.setLastModifiedTime(deep, FileTime.fromMillis(nowMs[0] + 12_345L));
            assertFalse(RestoreSweep.hasWork(saves));
        }
        drainParkOf(lock);

        nowMs[0] += RestoreSweep.TTL_MS + 1;
        assertTrue(RestoreSweep.hasWork(saves));
        SweepResult swept = RestoreSweep.run(saves);
        assertTrue(swept.changedDisk());
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
        assertEquals(9, Files.readAllBytes(saves.resolve("World/level.dat"))[0]);
    }

    @Test
    void missingDeferredNamesTheLockedAsideOverMissingFolderOnce() throws IOException {
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        putInstall(attempt, "World");
        Path aside = attempt.resolve("aside").resolve("World");
        Path lock = Files.write(aside.resolve("session.lock"), new byte[] { 0x2A });
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            SweepResult first = RestoreSweep.run(saves);
            assertEquals(List.of(saves.resolve("World")), first.missingDeferred());
            assertFalse(first.changedDisk());
            assertFalse(Files.exists(saves.resolve("World")));
            SweepResult second = RestoreSweep.run(saves);
            assertTrue(second.missingDeferred().isEmpty());
        }
        drainParkOf(lock);
    }

    @Test
    void missingDeferredNamesTheFailedMoveBackOverMissingFolderOnce() throws IOException {
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        putInstall(attempt, "World");
        Set<PosixFilePermission> original;
        try {
            original = Files.getPosixFilePermissions(saves);
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("POSIX permissions are unavailable on this filesystem");
            return;
        }
        Files.setPosixFilePermissions(saves,
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            Assumptions.assumeTrue(!Files.isWritable(saves),
                    "this process can write the directory regardless of permissions (root?)");
            SweepResult first = RestoreSweep.run(saves);
            assertEquals(List.of(saves.resolve("World")), first.missingDeferred());
            assertFalse(first.changedDisk());
            assertFalse(Files.exists(saves.resolve("World")));
            SweepResult second = RestoreSweep.run(saves);
            assertTrue(second.missingDeferred().isEmpty());
        } finally {
            Files.setPosixFilePermissions(saves, original);
        }
        int failedMoveBacks = 0;
        for (LogRecord failedMoveBack : warnings.drainAll("sweep move-back of World failed")) {
            assertInstanceOf(AccessDeniedException.class, failedMoveBack.getThrown());
            failedMoveBacks++;
        }
        assertEquals(2, failedMoveBacks, "each sweep logs the failed move-back it defers on");
    }

    @Test
    void sweepNeverTouchesTheLiveFolder() throws IOException {
        Path deleteBranch = craftAttempt("World-1");
        putAside(deleteBranch, "World");
        liveFolder("World", 9);
        RestoreSweep.run(saves);
        assertEquals(9, Files.readAllBytes(saves.resolve("World/level.dat"))[0]);

        Path relocateBranch = craftAttempt("Other-1");
        putAside(relocateBranch, "Other");
        putInstall(relocateBranch, "Other");
        liveFolder("Other", 5);
        RestoreSweep.run(saves);
        assertEquals(5, Files.readAllBytes(saves.resolve("Other/level.dat"))[0]);
        assertTrue(Files.exists(saves.resolve("Other_(2)")));

        Path moveBackBranch = craftAttempt("Third-1");
        putAside(moveBackBranch, "Third");
        putInstall(moveBackBranch, "Third");
        SweepResult result = RestoreSweep.run(saves);
        assertEquals(List.of(saves.resolve("Third")), result.movedBack());
        assertEquals(3, Files.readAllBytes(saves.resolve("Third/level.dat"))[0]);
    }

    @Test
    void locklessOrphanPastThresholdIsSwept() throws IOException {
        long[] nowMs = { 10_000_000L };
        RestoreSweep.clock = () -> nowMs[0];
        Path orphan = saves.resolve(RestoreOperation.TEMPORARY_ROOT).resolve("World-1");
        Files.createDirectories(orphan);
        Files.setLastModifiedTime(orphan, FileTime.fromMillis(nowMs[0] - 65L * 60_000));
        SweepResult result = RestoreSweep.run(saves);
        assertTrue(result.changedDisk());
        assertFalse(Files.exists(orphan));
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
    }

    @Test
    void locklessOrphanWithinTheWindowIsSpared() throws IOException {
        long[] nowMs = { 10_000_000L };
        RestoreSweep.clock = () -> nowMs[0];
        Path fresh = saves.resolve(RestoreOperation.TEMPORARY_ROOT).resolve("World-1");
        Files.createDirectories(fresh);
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(nowMs[0] - 5L * 60_000));
        SweepResult result = RestoreSweep.run(saves);
        assertFalse(result.changedDisk());
        assertTrue(Files.exists(fresh));
    }

    @Test
    void locklessAttemptHoldingWorldCopyIsNeverReaped() throws IOException {
        long[] nowMs = { 10_000_000L };
        RestoreSweep.clock = () -> nowMs[0];
        // A lockless attempt directory (no attempt.lock) that, unlike today's create-lock-before-aside layout, holds an
        // aside world copy. Aged past the reap threshold: the age gate alone would delete it.
        Path attempt = saves.resolve(RestoreOperation.TEMPORARY_ROOT).resolve("World-1");
        Files.createDirectories(attempt);
        write(attempt.resolve("aside").resolve("World").resolve("level.dat"), 3);
        write(attempt.resolve("aside").resolve("World").resolve("playerdata/u.dat"), 7);
        Files.setLastModifiedTime(attempt, FileTime.fromMillis(nowMs[0] - 65L * 60_000));
        SweepResult result = RestoreSweep.run(saves);
        assertFalse(result.changedDisk());
        assertTrue(Files.exists(attempt.resolve("aside").resolve("World").resolve("level.dat")));
        assertTrue(Files.exists(attempt));
    }

    @Test
    void tornAttemptWithLockFileIsProcessedRegardlessOfAge() throws IOException {
        long[] nowMs = { 10_000_000L };
        RestoreSweep.clock = () -> nowMs[0];
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        putInstall(attempt, "World");
        Files.setLastModifiedTime(attempt, FileTime.fromMillis(nowMs[0] - 65L * 60_000));
        SweepResult result = RestoreSweep.run(saves);
        assertEquals(List.of(saves.resolve("World")), result.movedBack());
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat")));
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
    }

    private Path craftAttempt(String attemptName) throws IOException {
        Path attempt = saves.resolve(RestoreOperation.TEMPORARY_ROOT).resolve(attemptName);
        Files.createDirectories(attempt.resolve("aside"));
        Files.createDirectories(attempt.resolve("install"));
        Files.write(attempt.resolve("attempt.lock"), new byte[0]);
        return attempt;
    }

    private void drainParkOf(Path lock) {
        boolean parked = RestoreOperation.parkedChannelForTest(RestoreOperation.parkKey(lock)) != null;
        assertEquals(parked ? 1 : 0, warnings.drainAll("parked a probe channel on " + lock).size());
    }

    private static void putAside(Path attempt, String folderName) throws IOException {
        write(attempt.resolve("aside").resolve(folderName).resolve("level.dat"), 3);
        write(attempt.resolve("aside").resolve(folderName).resolve("playerdata/u.dat"), 7);
    }

    private static void putInstall(Path attempt, String folderName) throws IOException {
        write(attempt.resolve("install").resolve(folderName).resolve("level.dat"), 9);
    }

    private void liveFolder(String folderName, int marker) throws IOException {
        write(saves.resolve(folderName).resolve("level.dat"), marker);
    }

    private static void write(Path file, int contentByte) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, new byte[] { (byte) contentByte });
    }
}
