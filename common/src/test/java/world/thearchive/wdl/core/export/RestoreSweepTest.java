// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.core.export.RestoreOperation.RestoreSweep;
import world.thearchive.wdl.core.export.RestoreOperation.RestoreSweep.SweepResult;

/** The roll-back-only sweep over crafted attempt layouts: the branch table, part cleanup, memo and TTL. */
class RestoreSweepTest {
    @TempDir
    Path saves;

    @BeforeEach
    void resetSweepState() {
        RestoreSweep.resetForTest();
    }

    @Test
    void installAbsentDeletesUnlockedAsideOnly() throws IOException {
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World"); // aside present, install absent
        liveFolder("World", 9); // folder present: the install already landed
        SweepResult result = RestoreSweep.run(saves);
        assertTrue(result.changedDisk());
        assertTrue(result.movedBack().isEmpty());
        assertTrue(result.relocated().isEmpty());
        assertTrue(result.missingDeferred().isEmpty());
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT))); // aside and attempt gone
        assertEquals(9, Files.readAllBytes(saves.resolve("World/level.dat"))[0]); // live folder untouched

        // With the aside's session.lock HELD, the attempt is skipped and nothing is deleted.
        Path locked = craftAttempt("World-1");
        putAside(locked, "World");
        Path aside = locked.resolve("aside").resolve("World");
        Path lock = Files.write(aside.resolve("session.lock"), new byte[] { 0x2A });
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            SweepResult deferred = RestoreSweep.run(saves);
            assertFalse(deferred.changedDisk());
            assertTrue(Files.exists(aside.resolve("level.dat"))); // nothing deleted
        }
    }

    @Test
    void folderMissingMovesTheAsideBack() throws IOException {
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        putInstall(attempt, "World");
        // folder missing: an unlocked aside rolls back to the folder name.
        SweepResult result = RestoreSweep.run(saves);
        assertTrue(result.changedDisk());
        assertEquals(List.of(saves.resolve("World")), result.movedBack());
        assertTrue(result.relocated().isEmpty());
        assertTrue(result.missingDeferred().isEmpty());
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat"))); // aside moved back
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT))); // attempt cleaned
    }

    @Test
    void occupiedTargetRelocatesPerTheTerminalDisposition() throws IOException {
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        putInstall(attempt, "World");
        liveFolder("World", 5); // the name was reoccupied while the install was still staged
        SweepResult result = RestoreSweep.run(saves);
        assertTrue(result.changedDisk());
        assertEquals(List.of(saves.resolve("World_(2)")), result.relocated());
        assertTrue(result.movedBack().isEmpty());
        assertTrue(Files.exists(saves.resolve("World_(2)/playerdata/u.dat"))); // aside relocated
        assertEquals(5, Files.readAllBytes(saves.resolve("World/level.dat"))[0]); // occupant untouched
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
    }

    @Test
    void noAsideDeletesTheAttemptAndEmptyRootGoes() throws IOException {
        Path attempt = craftAttempt("World-1");
        putInstall(attempt, "World"); // install only, no aside to preserve
        SweepResult result = RestoreSweep.run(saves);
        assertTrue(result.changedDisk());
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT))); // attempt and empty root gone
    }

    @Test
    void liveLockedAttemptIsNeverProcessed() throws IOException {
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        putInstall(attempt, "World");
        // folder missing: an unlocked attempt WOULD move the aside back; the held attempt.lock forbids it.
        try (FileChannel channel = FileChannel.open(attempt.resolve("attempt.lock"), StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            assertTrue(RestoreSweep.hasWork(saves)); // hasWork may be true
            SweepResult result = RestoreSweep.run(saves);
            assertFalse(result.changedDisk());
            assertTrue(result.movedBack().isEmpty());
            assertTrue(result.missingDeferred().isEmpty());
        }
        // The attempt survives untouched: both stagings still present, no branch fired.
        assertTrue(Files.exists(attempt.resolve("aside").resolve("World").resolve("level.dat")));
        assertTrue(Files.exists(attempt.resolve("install").resolve("World").resolve("level.dat")));
        assertFalse(Files.exists(saves.resolve("World"))); // no move-back happened
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
        assertFalse(Files.exists(stale)); // older than an hour: deleted
        assertTrue(Files.exists(fresh)); // fresh: spared
    }

    @Test
    void memoFastPathAndTtl() throws IOException {
        long[] nowMs = { 100_000_000L };
        RestoreSweep.clock = () -> nowMs[0];

        // An empty temporary root is no work.
        assertFalse(RestoreSweep.hasWork(saves));

        // A deferred locked aside over a present folder with no install is a pure probe-shaped block.
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        liveFolder("World", 9);
        Path aside = attempt.resolve("aside").resolve("World");
        Path lock = Files.write(aside.resolve("session.lock"), new byte[] { 0x2A });
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            assertTrue(RestoreSweep.hasWork(saves)); // the layout changed: work

            SweepResult deferred = RestoreSweep.run(saves);
            assertFalse(deferred.changedDisk());
            assertTrue(Files.exists(aside.resolve("level.dat"))); // deferred, untouched
            assertFalse(RestoreSweep.hasWork(saves)); // signature unchanged, within the TTL: quiet

            // Past the TTL while still locked: the probe re-run stays failed, no fail-to-pass, still quiet.
            nowMs[0] += RestoreSweep.TTL_MS + 1;
            assertFalse(RestoreSweep.hasWork(saves));
        }
        // The released lock is a fail-to-pass transition of the aside probe: hasWork re-arms past the TTL.
        assertTrue(RestoreSweep.hasWork(saves));

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
            assertFalse(RestoreSweep.hasWork(saves)); // within the TTL, unchanged: quiet
            liveFolder("Ghost", 4); // the folder reappears: a folder-existence flip
            assertTrue(RestoreSweep.hasWork(saves)); // the transition re-arms within the TTL
        }
    }

    @Test
    void deepAttemptContentChangeDoesNotAlterTheSignature() throws IOException {
        long[] nowMs = { 100_000_000L };
        RestoreSweep.clock = () -> nowMs[0];

        // A torn attempt whose aside holds a DEEPLY-NESTED world file, with its session.lock held so the
        // sweep defers and the attempt persists (the memo is stamped over it). The bounded signature reads
        // the attempt-directory level only, so the world tree's deep contents are outside it by construction.
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        liveFolder("World", 9);
        Path aside = attempt.resolve("aside").resolve("World");
        Path deep = aside.resolve("region/r.0.0/entities/deep.dat");
        write(deep, 1);
        Path lock = Files.write(aside.resolve("session.lock"), new byte[] { 0x2A });
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            assertTrue(RestoreSweep.hasWork(saves)); // the torn attempt is detected as work
            SweepResult deferred = RestoreSweep.run(saves); // locked aside: deferred, the memo is stamped
            assertFalse(deferred.changedDisk());
            assertFalse(RestoreSweep.hasWork(saves)); // signature stamped, within the TTL: quiet

            // Mutate a deeply-nested file's content and mtime. A deep walk would alter the signature and
            // re-arm within the TTL; the attempt-directory-bounded signature does not descend, so it is invariant.
            Files.write(deep, new byte[] { 2, 2, 2 });
            Files.setLastModifiedTime(deep, FileTime.fromMillis(nowMs[0] + 12_345L));
            assertFalse(RestoreSweep.hasWork(saves));
        }

        // Correctness preserved: with the lock released, the torn attempt is still swept away, the deep
        // change notwithstanding. Past the TTL the aside probe flips fail-to-pass and re-arms.
        nowMs[0] += RestoreSweep.TTL_MS + 1;
        assertTrue(RestoreSweep.hasWork(saves));
        SweepResult swept = RestoreSweep.run(saves);
        assertTrue(swept.changedDisk());
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT))); // attempt swept away
        assertEquals(9, Files.readAllBytes(saves.resolve("World/level.dat"))[0]); // live folder untouched
    }

    @Test
    void missingDeferredNamesTheLockedAsideOverMissingFolderOnce() throws IOException {
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        putInstall(attempt, "World");
        // aside locked + install present + folder missing: the deferral leaves saves/World absent.
        Path aside = attempt.resolve("aside").resolve("World");
        Path lock = Files.write(aside.resolve("session.lock"), new byte[] { 0x2A });
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            SweepResult first = RestoreSweep.run(saves);
            assertEquals(List.of(saves.resolve("World")), first.missingDeferred());
            assertFalse(first.changedDisk());
            assertFalse(Files.exists(saves.resolve("World"))); // still missing (deferred)
            // Non-repeating per attempt per session: a second sweep does not re-name it.
            SweepResult second = RestoreSweep.run(saves);
            assertTrue(second.missingDeferred().isEmpty());
        }
    }

    @Test
    void missingDeferredNamesTheFailedMoveBackOverMissingFolderOnce() throws IOException {
        Path attempt = craftAttempt("World-1");
        putAside(attempt, "World");
        putInstall(attempt, "World");
        // aside unlocked + install present + folder missing: the move-back would roll the aside back,
        // but a read-only saves directory fails the rename, so the deferral leaves saves/World absent.
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
            assertFalse(Files.exists(saves.resolve("World"))); // still missing (move-back failed, deferred)
            // Non-repeating per attempt per session: a second sweep does not re-name it.
            SweepResult second = RestoreSweep.run(saves);
            assertTrue(second.missingDeferred().isEmpty());
        } finally {
            Files.setPosixFilePermissions(saves, original);
        }
    }

    @Test
    void sweepNeverTouchesTheLiveFolder() throws IOException {
        // The install-absent delete branch never rewrites the restored live folder.
        Path deleteBranch = craftAttempt("World-1");
        putAside(deleteBranch, "World");
        liveFolder("World", 9);
        RestoreSweep.run(saves);
        assertEquals(9, Files.readAllBytes(saves.resolve("World/level.dat"))[0]);

        // The occupied-relocate branch never overwrites the occupant.
        Path relocateBranch = craftAttempt("Other-1");
        putAside(relocateBranch, "Other");
        putInstall(relocateBranch, "Other");
        liveFolder("Other", 5);
        RestoreSweep.run(saves);
        assertEquals(5, Files.readAllBytes(saves.resolve("Other/level.dat"))[0]);
        assertTrue(Files.exists(saves.resolve("Other_(2)")));

        // The move-back branch is the only pinned mutation of a folder name: the aside's bytes land there.
        Path moveBackBranch = craftAttempt("Third-1");
        putAside(moveBackBranch, "Third");
        putInstall(moveBackBranch, "Third");
        SweepResult result = RestoreSweep.run(saves);
        assertEquals(List.of(saves.resolve("Third")), result.movedBack());
        assertEquals(3, Files.readAllBytes(saves.resolve("Third/level.dat"))[0]); // the aside's bytes
    }

    @Test
    void locklessOrphanPastThresholdIsSwept() throws IOException {
        long[] nowMs = { 10_000_000L };
        RestoreSweep.clock = () -> nowMs[0];
        // A crash in the gap between creating the attempt directory and its attempt.lock: a lockless orphan.
        Path orphan = saves.resolve(RestoreOperation.TEMPORARY_ROOT).resolve("World-1");
        Files.createDirectories(orphan);
        Files.setLastModifiedTime(orphan, FileTime.fromMillis(nowMs[0] - 65L * 60_000));
        SweepResult result = RestoreSweep.run(saves);
        assertTrue(result.changedDisk());
        assertFalse(Files.exists(orphan)); // past the create-before-lock window: reaped
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT))); // emptied root removed
    }

    @Test
    void locklessOrphanWithinTheWindowIsSpared() throws IOException {
        long[] nowMs = { 10_000_000L };
        RestoreSweep.clock = () -> nowMs[0];
        // A live instance's just-created directory, still inside the brief create-before-lock window.
        Path fresh = saves.resolve(RestoreOperation.TEMPORARY_ROOT).resolve("World-1");
        Files.createDirectories(fresh);
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(nowMs[0] - 5L * 60_000));
        SweepResult result = RestoreSweep.run(saves);
        assertFalse(result.changedDisk());
        assertTrue(Files.exists(fresh)); // spared: a live instance may still be about to take the lock
    }

    @Test
    void locklessAttemptHoldingWorldCopyIsNeverReaped() throws IOException {
        long[] nowMs = { 10_000_000L };
        RestoreSweep.clock = () -> nowMs[0];
        // A lockless attempt directory (no attempt.lock) that, unlike today's create-lock-before-aside layout,
        // holds an aside world copy. Aged well past the reap threshold: the age gate alone would delete it
        // and destroy the only copy of the original download. The guard must refuse.
        Path attempt = saves.resolve(RestoreOperation.TEMPORARY_ROOT).resolve("World-1");
        Files.createDirectories(attempt);
        write(attempt.resolve("aside").resolve("World").resolve("level.dat"), 3);
        write(attempt.resolve("aside").resolve("World").resolve("playerdata/u.dat"), 7);
        Files.setLastModifiedTime(attempt, FileTime.fromMillis(nowMs[0] - 65L * 60_000));
        SweepResult result = RestoreSweep.run(saves);
        // without the guard the age gate deletes the attempt; the sweep must report no disk change
        assertFalse(result.changedDisk());
        assertTrue(Files.exists(attempt.resolve("aside").resolve("World").resolve("level.dat")));
        assertTrue(Files.exists(attempt)); // left for a future sweep, never reaped
    }

    @Test
    void tornAttemptWithLockFileIsProcessedRegardlessOfAge() throws IOException {
        long[] nowMs = { 10_000_000L };
        RestoreSweep.clock = () -> nowMs[0];
        Path attempt = craftAttempt("World-1"); // an unheld attempt.lock file present: a torn attempt
        putAside(attempt, "World");
        putInstall(attempt, "World");
        Files.setLastModifiedTime(attempt, FileTime.fromMillis(nowMs[0] - 65L * 60_000));
        // Folder missing: the normal torn roll-back owns it, never the lockless age gate.
        SweepResult result = RestoreSweep.run(saves);
        assertEquals(List.of(saves.resolve("World")), result.movedBack());
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat"))); // aside rolled back, not deleted
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
    }

    private Path craftAttempt(String attemptName) throws IOException {
        Path attempt = saves.resolve(RestoreOperation.TEMPORARY_ROOT).resolve(attemptName);
        Files.createDirectories(attempt.resolve("aside"));
        Files.createDirectories(attempt.resolve("install"));
        Files.write(attempt.resolve("attempt.lock"), new byte[0]);
        return attempt;
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
