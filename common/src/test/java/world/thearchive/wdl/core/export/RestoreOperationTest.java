// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.testsupport.JulCapture;

class RestoreOperationTest {
    @TempDir
    Path saves;

    private RestoreOperation currentOp;

    @RegisterExtension
    final JulCapture warnings = JulCapture.of(RestoreOperation.class);

    @Test
    void probeAbsentLockIsUnlockedAndNeverCreates(@TempDir Path work) throws IOException {
        Path folder = Files.createDirectories(work.resolve("World"));
        assertFalse(RestoreOperation.probeLocked(folder));
        assertFalse(Files.exists(folder.resolve("session.lock")));
    }

    @Test
    void probeSeesTheCrossProcessStyleLock(@TempDir Path work) throws Exception {
        Path folder = Files.createDirectories(work.resolve("World"));
        Path lock = Files.write(folder.resolve("session.lock"), new byte[] { 0x2A });
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            assertTrue(RestoreOperation.probeLocked(folder));
        }
        assertFalse(RestoreOperation.probeLocked(folder));
        drainParkOf(lock);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void probeParksItsChannelUnderTheLockThisJvmHolds(@TempDir Path work) throws Exception {
        Path folder = Files.createDirectories(work.resolve("World"));
        Path lock = Files.write(folder.resolve("session.lock"), new byte[] { 0x2A });
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            assertTrue(RestoreOperation.probeLocked(folder));
        }
        FileChannel parked = RestoreOperation.parkedChannelForTest(RestoreOperation.parkKey(lock));
        assertNotNull(parked, "a same-JVM holder parks the probe channel rather than closing it");
        assertTrue(parked.isOpen());
        warnings.drain("parked a probe channel on " + lock);
    }

    @Test
    void probeReopensPastTheStaleParkedChannelWhenTheFileIsReplaced(@TempDir Path work) throws Exception {
        Path folder = Files.createDirectories(work.resolve("World"));
        Path lock = Files.write(folder.resolve("session.lock"), new byte[] { 0x2A });
        Assumptions.assumeTrue(Files.readAttributes(lock, BasicFileAttributes.class).fileKey() != null,
                "this filesystem reports no fileKey; stale-parked detection is unavailable");
        try (FileChannel original = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock held = original.lock()) {
            assertTrue(RestoreOperation.probeLocked(folder));
        }
        // The parked channel still pins the old inode, so the recreated file necessarily takes a fresh inode and thus a
        // different fileKey.
        Files.delete(lock);
        Files.write(lock, new byte[] { 0x2A });
        try (FileChannel replacement = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock heldNew = replacement.lock()) {
            assertTrue(RestoreOperation.probeLocked(folder));
        }
        assertFalse(RestoreOperation.probeLocked(folder));
        if (RestoreOperation.parkedChannelForTest(RestoreOperation.parkKey(lock)) != null) {
            assertEquals(2, warnings.drainAll("parked a probe channel on " + lock).size());
            warnings.drain("tombstoned a stale parked channel on " + lock);
        }
    }

    @Test
    void concurrentParkKeepsTheWinnerAndGraveyardsTheLoser(@TempDir Path work) throws IOException {
        Path folder = Files.createDirectories(work.resolve("World"));
        Path lock = Files.write(folder.resolve("session.lock"), new byte[] { 0x2A });
        Path key = RestoreOperation.parkKey(lock);
        FileChannel winner = FileChannel.open(lock, StandardOpenOption.WRITE);
        FileChannel loser = FileChannel.open(lock, StandardOpenOption.WRITE);
        RestoreOperation.parkProbeChannel(key, winner, lock);
        RestoreOperation.parkProbeChannel(key, loser, lock);
        assertSame(winner, RestoreOperation.parkedChannelForTest(key));
        assertTrue(RestoreOperation.graveyardContainsForTest(loser));
        assertTrue(loser.isOpen());
        warnings.drain("parked a probe channel on " + lock);
        warnings.drain("graveyarded a probe channel on " + lock);
    }

    @Test
    void happyPathReplacesSnapshotsAndCleansUp() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreSource source = RestoreSource.find(saves, "World").get();
        RestoreOperation operation = RestoreOperation.create(saves, "World", source, true);
        operation.publishLoadedWorld(null);
        RestoreOperation.Result result = operation.run();
        assertEquals(RestoreOperation.Outcome.RESTORED, result.outcome());
        assertEquals(9, Files.readAllBytes(saves.resolve("World/level.dat"))[0]);
        assertFalse(Files.exists(saves.resolve("World/playerdata")));
        assertTrue(Files.exists(saves.resolve("World-singleplayer.zip")));
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
    }

    @Test
    void theAdvertisedSnapshotNameIsTheOneTheNextRestoreWrites() throws IOException {
        taintedWorldWithCleanExport("World");
        assertEquals(RestoreOperation.Outcome.RESTORED, runOp("World", RestoreSource.find(saves, "World").get()));
        assertTrue(Files.exists(saves.resolve("World-singleplayer.zip")));
        write(saves.resolve("World/playerdata/u.dat"), 3);

        String advertised = RestoreOperation.nextSnapshotName(saves, "World");
        assertEquals(RestoreOperation.Outcome.RESTORED, runOp("World", RestoreSource.find(saves, "World").get()));

        assertEquals("World-singleplayer_(2).zip", advertised,
                "the bare stem holds the first restore's snapshot, so the second one is disambiguated");
        assertTrue(Files.exists(saves.resolve(advertised)),
                "the name shown before the restore is the file the restore actually wrote");
    }

    @Test
    void preconditionsRefusePerCause() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreSource source = RestoreSource.find(saves, "World").get();
        deleteRecursively(saves.resolve("World/wdl"));
        assertEquals(RestoreOperation.Outcome.NOT_MANAGED, runOp("World", source));
        deleteRecursively(saves.resolve("World"));
        assertEquals(RestoreOperation.Outcome.FOLDER_MISSING, runOp("World", source));
        Files.write(saves.resolve("World"), new byte[] { 1 });
        assertEquals(RestoreOperation.Outcome.FILE_OCCUPANT, runOp("World", source));
        Files.delete(saves.resolve("World"));
        taintedWorldWithCleanExport("World2");
        deleteRecursively(saves.resolve("World2/playerdata"));
        assertEquals(RestoreOperation.Outcome.NOT_TAINTED, runOp("World2", RestoreSource.find(saves, "World2").get()));
        taintedWorldWithCleanExport("World3");
        RestoreSource pinned = RestoreSource.find(saves, "World3").get();
        Files.write(saves.resolve("World3.zip"), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 });
        assertEquals(RestoreOperation.Outcome.SOURCE_CHANGED, runOp("World3", pinned));
        taintedWorldWithCleanExport("World4");
        RestoreOperation operation = RestoreOperation.create(saves, "World4",
                RestoreSource.find(saves, "World4").get(), true);
        operation.publishLoadedWorld(saves.resolve("World4"));
        assertEquals(RestoreOperation.Outcome.WORLD_IN_USE, operation.run().outcome());
    }

    @Test
    void unverifiableTaintRefusesAsUnknown() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreSource source = RestoreSource.find(saves, "World").get();
        // A definite taint entry outweighs an unreadable sibling, so the unknown arm needs it gone.
        deleteRecursively(saves.resolve("World/playerdata"));
        Path dimensions = Files.createDirectories(saves.resolve("World/dimensions"));
        Set<PosixFilePermission> original;
        try {
            original = Files.getPosixFilePermissions(dimensions);
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("POSIX permissions are unavailable on this filesystem");
            return;
        }
        Files.setPosixFilePermissions(dimensions,
                EnumSet.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        try {
            Assumptions.assumeTrue(!Files.isReadable(dimensions),
                    "this process can list the directory regardless of permissions (root?)");
            assertEquals(RestoreOperation.Outcome.TAINT_UNKNOWN, runOp("World", source));
        } finally {
            Files.setPosixFilePermissions(dimensions, original);
        }
        assertEquals(1, Files.readAllBytes(saves.resolve("World/level.dat"))[0]);
    }

    @Test
    void lockedFolderRefusesWorldInUseBeforeTheSnapshot() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreSource source = RestoreSource.find(saves, "World").get();
        Path lock = Files.write(saves.resolve("World/session.lock"), new byte[] { 0x2A });
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            assertEquals(RestoreOperation.Outcome.WORLD_IN_USE, runOp("World", source));
        }
        assertFalse(Files.exists(saves.resolve("World-singleplayer.zip")));
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat")));
        drainParkOf(lock);
    }

    @Test
    void snapshotFailureAbortsBeforeAnyMutation() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreSource source = RestoreSource.find(saves, "World").get();
        RestoreOperation operation = RestoreOperation.createForTest(saves, "World", source, failingSnapshot(),
                phase -> {});
        assertEquals(RestoreOperation.Outcome.SNAPSHOT_FAILED, operation.run().outcome());
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat")));
        assertEquals("forced", warnings.drain("pre-restore snapshot of World failed").getThrown().getMessage());
    }

    @Test
    void snapshotFirstFalseSkipsTheSnapshotAndStillRestores() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreOperation operation = RestoreOperation.create(saves, "World",
                RestoreSource.find(saves, "World").get(), false);
        operation.publishLoadedWorld(null);
        RestoreOperation.Result result = operation.run();
        assertEquals(RestoreOperation.Outcome.RESTORED, result.outcome());
        assertTrue(result.survivingPaths().isEmpty());
        assertFalse(Files.exists(saves.resolve("World-singleplayer.zip")));
        assertEquals(9, Files.readAllBytes(saves.resolve("World/level.dat"))[0]);
    }

    @Test
    void attemptDirectoryCollisionTakesTheNextCounterName() throws IOException {
        taintedWorldWithCleanExport("World");
        Path occupied = saves.resolve(RestoreOperation.TEMPORARY_ROOT).resolve("World-1");
        write(occupied.resolve("marker"), 5);
        assertEquals(RestoreOperation.Outcome.RESTORED, runOp("World", RestoreSource.find(saves, "World").get()));
        assertArrayEquals(new byte[] { 5 }, Files.readAllBytes(occupied.resolve("marker")));
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT).resolve("World-2")));
        assertEquals(9, Files.readAllBytes(saves.resolve("World/level.dat"))[0]);
    }

    @Test
    void unpublishedLoadedWorldRefusesWorldInUse() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreOperation operation = RestoreOperation.create(saves, "World",
                RestoreSource.find(saves, "World").get(), true);
        assertEquals(RestoreOperation.Outcome.WORLD_IN_USE, operation.run().outcome());
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat")));
    }

    @Test
    void diskFullSplitsFromExtractRefusedAtTheUsableSpaceFloor() {
        assertEquals(RestoreOperation.Outcome.DISK_FULL,
                RestoreOperation.extractFailureOutcome(RestoreOperation.DISK_FULL_FLOOR_BYTES - 1));
        assertEquals(RestoreOperation.Outcome.EXTRACT_REFUSED,
                RestoreOperation.extractFailureOutcome(RestoreOperation.DISK_FULL_FLOOR_BYTES));
    }

    @Test
    void sameStatForgedSourceRefusesAtExtractAndCleansUp() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreSource source = RestoreSource.find(saves, "World").get();
        byte[] bytes = Files.readAllBytes(source.zip());
        for (int index = 45; index < 100 && index < bytes.length; index++) {
            bytes[index] = 0;
        }
        Files.write(source.zip(), bytes);
        Files.setLastModifiedTime(source.zip(), source.mtime());
        assertEquals(RestoreOperation.Outcome.EXTRACT_REFUSED, runOp("World", source));
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat")));
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
        assertInstanceOf(IOException.class, warnings.drain("extract of World.zip refused").getThrown());
    }

    @Test
    void abortBeforeRunRefusesWithoutTouchingAnything() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreOperation operation = RestoreOperation.create(saves, "World",
                RestoreSource.find(saves, "World").get(), true);
        operation.publishLoadedWorld(null);
        operation.abort();
        assertEquals(RestoreOperation.Outcome.ABORTED, operation.run().outcome());
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat")));
        assertFalse(Files.exists(saves.resolve("World-singleplayer.zip")));
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
    }

    @Test
    void attemptReferencesSeesAsideAndInstallStagingCaseInsensitively() throws IOException {
        assertFalse(RestoreOperation.attemptReferences(saves, "World"));
        Path attempt = saves.resolve(RestoreOperation.TEMPORARY_ROOT).resolve("Other-1");
        Files.createDirectories(attempt.resolve("aside").resolve("world"));
        assertTrue(RestoreOperation.attemptReferences(saves, "World"));
        assertFalse(RestoreOperation.attemptReferences(saves, "Region"));
        deleteRecursively(saves.resolve(RestoreOperation.TEMPORARY_ROOT));
        Files.createDirectories(saves.resolve(RestoreOperation.TEMPORARY_ROOT)
                .resolve("Other-1").resolve("install").resolve("WORLD"));
        assertTrue(RestoreOperation.attemptReferences(saves, "World"));
    }

    @Test
    void failureBetweenTheMovesRollsBack() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreOperation operation = opWithMoveHook("World", failInstallMoveOnce());
        RestoreOperation.Result result = operation.run();
        assertEquals(RestoreOperation.Outcome.SWAP_FAILED, result.outcome());
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat")));
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
        assertEquals("forced install-move failure",
                warnings.drain("install move for World failed").getThrown().getMessage());
    }

    @Test
    void occupiedMoveBackTargetTakesTheTerminalDisposition() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreOperation operation = opWithMoveHook("World",
                betweenMoves(() -> Files.createDirectories(saves.resolve("World"))));
        RestoreOperation.Result result = operation.run();
        assertEquals(RestoreOperation.Outcome.RELOCATED, result.outcome());
        assertTrue(Files.exists(saves.resolve("World_(2)/playerdata/u.dat")));
        assertTrue(Files.exists(saves.resolve("World")));
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
        assertInstanceOf(FileAlreadyExistsException.class, warnings.drain("install move for World failed").getThrown());
    }

    @Test
    void abortDuringExtractBacksOutCleanly() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreOperation operation = opWithMoveHook("World", abortDuringExtract());
        assertEquals(RestoreOperation.Outcome.ABORTED, operation.run().outcome());
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat")));
    }

    @Test
    void quitPathNeverTakesTheTerminalDispositionOrTheCleanups() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreOperation operation = opWithMoveHook("World", abortAfterInstallMove());
        RestoreOperation.Result result = operation.run();
        assertEquals(RestoreOperation.Outcome.RESTORED_WITH_REMNANTS, result.outcome());
        assertTrue(Files.exists(saves.resolve("World/level.dat")));
        assertTrue(RestoreOperation.attemptReferences(saves, "World"));
    }

    @Test
    void abortWithRecreatedTargetLeavesTheAttemptIntact() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreOperation operation = opWithMoveHook("World", betweenMoves(() -> {
            currentOp.abort();
            Files.createDirectories(saves.resolve("World"));
        }));
        assertEquals(RestoreOperation.Outcome.ABORTED, operation.run().outcome());
        assertTrue(RestoreOperation.attemptReferences(saves, "World"));
        assertFalse(Files.exists(saves.resolve("World_(2)")));
    }

    @Test
    void abortBetweenTheMovesBacksTheWorldOut() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreOperation operation = opWithMoveHook("World", betweenMoves(() -> currentOp.abort()));
        assertEquals(RestoreOperation.Outcome.ABORTED, operation.run().outcome());
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat")));
    }

    @Test
    void abortBeforeTheAsideMoveLeavesTheWorldAtItsName() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreOperation operation = opWithMoveHook("World", phase -> {
            if (phase == RestoreOperation.Phase.BEFORE_ASIDE_MOVE) {
                currentOp.abort();
            }
        });
        assertEquals(RestoreOperation.Outcome.ABORTED, operation.run().outcome());
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat")));
    }

    @Test
    void asideMoveFailureCleansTheAttemptAndReportsSwapFailed() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreOperation operation = opWithMoveHook("World", failAsideMove());
        RestoreOperation.Result result = operation.run();
        assertEquals(RestoreOperation.Outcome.SWAP_FAILED, result.outcome());
        // The folder never left its name, so nothing survives the attempt teardown.
        assertTrue(Files.exists(saves.resolve("World/playerdata/u.dat")));
        assertFalse(Files.exists(saves.resolve(RestoreOperation.TEMPORARY_ROOT)));
        assertTrue(result.survivingPaths().isEmpty());
        assertEquals("forced keep-aside move failure",
                warnings.drain("keep-aside move for World failed").getThrown().getMessage());
    }

    @Test
    void relocationSkipsOccupiedSiblingsAndReportsTheTarget() throws IOException {
        taintedWorldWithCleanExport("World");
        write(saves.resolve("World_(2)/level.dat"), 7);
        RestoreOperation operation = opWithMoveHook("World",
                betweenMoves(() -> Files.createDirectories(saves.resolve("World"))));
        RestoreOperation.Result result = operation.run();
        assertEquals(RestoreOperation.Outcome.RELOCATED, result.outcome());
        assertEquals(saves.resolve("World_(3)"), result.relocatedTo());
        assertTrue(Files.exists(saves.resolve("World_(3)/playerdata/u.dat")));
        assertArrayEquals(new byte[] { 7 }, Files.readAllBytes(saves.resolve("World_(2)/level.dat")));
        assertInstanceOf(FileAlreadyExistsException.class, warnings.drain("install move for World failed").getThrown());
    }

    @Test
    void lockedAsideDefersTheDispositionAndNamesTheSurvivor() throws IOException {
        taintedWorldWithCleanExport("World");
        Path aside = saves.resolve(RestoreOperation.TEMPORARY_ROOT).resolve("World-1")
                .resolve("aside").resolve("World");
        FileChannel[] holder = new FileChannel[1];
        // The lock is held through this array: on a Java-8 toolchain sun.nio.ch.FileLockTable keeps only a weak
        // reference, so a discarded FileLock can be collected mid-test, the probe's same-JVM
        // OverlappingFileLockException never fires, and the operation relocates over a lock that is still held at the
        // OS level.
        FileLock[] heldLock = new FileLock[1];
        Throwable[] setUpFailure = new Throwable[1];
        RestoreOperation operation = opWithMoveHook("World", betweenMoves(() -> {
            try {
                Files.createDirectories(saves.resolve("World"));
                Path lock = Files.write(aside.resolve("session.lock"), new byte[] { 0x2A });
                holder[0] = FileChannel.open(lock, StandardOpenOption.WRITE);
                heldLock[0] = holder[0].lock();
            } catch (IOException | RuntimeException | Error e) {
                setUpFailure[0] = e;
                throw e;
            }
        }));
        RestoreOperation.Result result;
        try {
            result = operation.run();
        } finally {
            if (heldLock[0] != null) {
                heldLock[0].release();
            }
            if (holder[0] != null) {
                holder[0].close();
            }
        }
        if (setUpFailure[0] != null) {
            fail("the hook never took the aside session lock", setUpFailure[0]);
        }
        assertEquals(RestoreOperation.Outcome.SWAP_FAILED, result.outcome());
        assertEquals(List.of(aside), result.survivingPaths());
        assertFalse(Files.exists(saves.resolve("World_(2)")));
        assertTrue(RestoreOperation.attemptReferences(saves, "World"));
        assertInstanceOf(FileAlreadyExistsException.class, warnings.drain("install move for World failed").getThrown());
        drainParkOf(aside.resolve("session.lock"));
    }

    @Test
    void moveBackFailureLeavesTheAttemptAndNamesTheAside() throws IOException {
        taintedWorldWithCleanExport("World");
        Path aside = saves.resolve(RestoreOperation.TEMPORARY_ROOT).resolve("World-1")
                .resolve("aside").resolve("World");
        RestoreOperation operation = opWithMoveHook("World", betweenMoves(() -> {
            deleteRecursively(aside);
            throw new IllegalStateException("forced install-move failure");
        }));
        RestoreOperation.Result result = operation.run();
        assertEquals(RestoreOperation.Outcome.SWAP_FAILED, result.outcome());
        assertEquals(List.of(aside), result.survivingPaths());
        assertTrue(RestoreOperation.attemptReferences(saves, "World"));
        assertEquals("forced install-move failure",
                warnings.drain("install move for World failed").getThrown().getMessage());
        assertInstanceOf(NoSuchFileException.class, warnings.drain("move-back of World failed").getThrown());
    }

    @Test
    void sweepDuringTheSwapLeavesTheLiveAttemptAlone() throws IOException {
        taintedWorldWithCleanExport("World");
        RestoreOperation.RestoreSweep.SweepResult[] sweep = new RestoreOperation.RestoreSweep.SweepResult[1];
        // The sweep runs from inside the swap window, after collections that would clear a weakly held attempt
        // lock from the JDK 8 lock table; a held lock keeps the sweep's tryLock overlapping and the attempt live.
        RestoreOperation operation = opWithMoveHook("World", betweenMoves(() -> {
            for (int i = 0; i < 10; i++) {
                System.gc();
            }
            sweep[0] = RestoreOperation.RestoreSweep.run(saves);
        }));
        RestoreOperation.Result result = operation.run();
        assertEquals(RestoreOperation.Outcome.RESTORED, result.outcome());
        assertFalse(sweep[0].changedDisk());
        assertTrue(sweep[0].movedBack().isEmpty());
    }

    private RestoreOperation.Outcome runOp(String name, RestoreSource source) {
        RestoreOperation operation = RestoreOperation.create(saves, name, source, true);
        operation.publishLoadedWorld(null);
        return operation.run().outcome();
    }

    private RestoreOperation opWithMoveHook(String name, RestoreOperation.PhaseHook hook) throws IOException {
        currentOp = RestoreOperation.createForTest(saves, name,
                RestoreSource.find(saves, name).get(), FolderZipper::zip, hook);
        return currentOp;
    }

    private void drainParkOf(Path lock) {
        boolean parked = RestoreOperation.parkedChannelForTest(RestoreOperation.parkKey(lock)) != null;
        assertEquals(parked ? 1 : 0, warnings.drainAll("parked a probe channel on " + lock).size());
    }

    private static RestoreOperation.SnapshotStep failingSnapshot() {
        return (folder, zipTarget) -> {
            throw new IOException("forced");
        };
    }

    private interface IoAction {
        void run() throws IOException;
    }

    private RestoreOperation.PhaseHook betweenMoves(IoAction action) {
        return phase -> {
            if (phase == RestoreOperation.Phase.BETWEEN_MOVES) {
                try {
                    action.run();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    private RestoreOperation.PhaseHook failInstallMoveOnce() {
        boolean[] fired = { false };
        return phase -> {
            if (phase == RestoreOperation.Phase.BETWEEN_MOVES && !fired[0]) {
                fired[0] = true;
                throw new IllegalStateException("forced install-move failure");
            }
        };
    }

    private RestoreOperation.PhaseHook failAsideMove() {
        return phase -> {
            if (phase == RestoreOperation.Phase.BEFORE_ASIDE_MOVE) {
                throw new IllegalStateException("forced keep-aside move failure");
            }
        };
    }

    private RestoreOperation.PhaseHook abortDuringExtract() {
        return phase -> {
            if (phase == RestoreOperation.Phase.EXTRACT_START) {
                currentOp.abort();
            }
        };
    }

    private RestoreOperation.PhaseHook abortAfterInstallMove() {
        return phase -> {
            if (phase == RestoreOperation.Phase.AFTER_INSTALL_MOVE) {
                currentOp.abort();
            }
        };
    }

    private Path taintedWorldWithCleanExport(String name) throws IOException {
        Path folder = saves.resolve(name);
        write(folder.resolve("level.dat"), 1);
        write(folder.resolve("region/r.0.0.mca"), 2);
        write(folder.resolve("wdl/download.jsonl"),
                "{\"finishedAt\":\"2026-03-01T10:00:00Z\"}\n".getBytes(StandardCharsets.UTF_8));
        write(folder.resolve("playerdata/u.dat"), 3); // the taint
        Path twin = saves.resolve(".twin").resolve(name);
        write(twin.resolve("level.dat"), 9);
        write(twin.resolve("region/r.0.0.mca"), 8);
        write(twin.resolve("wdl/download.jsonl"),
                "{\"finishedAt\":\"2026-03-01T10:00:00Z\"}\n".getBytes(StandardCharsets.UTF_8));
        FolderZipper.zip(twin, saves.resolve(name + ".zip"));
        deleteRecursively(saves.resolve(".twin"));
        return folder;
    }

    private static void write(Path file, int contentByte) throws IOException {
        write(file, new byte[] { (byte) contentByte });
    }

    private static void write(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
