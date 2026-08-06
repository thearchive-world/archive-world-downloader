// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.browse.DownloadFolders;
import world.thearchive.wdl.core.browse.SinglePlayerTaint;
import world.thearchive.wdl.core.browse.SinglePlayerTaint.TaintState;

/**
 * The guarded replace of a tainted download folder from its pinned clean export. One run re-proves the preconditions on
 * the worker (managed folder, tainted, source unchanged, not the loaded world, not locked), optionally snapshots the
 * folder beside itself first, extracts the export into a per-attempt staging directory under
 * {@code saves/.wdl-restore-work/}, and swaps the extracted copy in with atomic moves, keeping the original as a
 * kept-aside until the install is in place.
 *
 * <p>{@link #run()} never throws (Throwable-inclusive) and always yields a {@link Result}; every phase is preceded by
 * an abort check so a quitting client backs out instead of racing the shutdown. A restore whose world already swapped
 * in but whose leftovers could not be removed still reports success, as {@link Outcome#RESTORED_WITH_REMNANTS}; a later
 * sweep owns whatever stayed behind. A rollback that finds the folder's name occupied again never overwrites the
 * occupant: the kept-aside original moves to the next free visible sibling ({@link Outcome#RELOCATED}) or, under a live
 * session or a failed move, stays staged for the sweep.
 */
public final class RestoreOperation {
    private static final Logger LOGGER = Logger.getLogger(RestoreOperation.class.getName());

    /** The per-attempt staging root under the saves directory. */
    public static final String TEMPORARY_ROOT = ".wdl-restore-work";

    private static final String ATTEMPT_LOCK = "attempt.lock";
    private static final String ASIDE = "aside";
    private static final String INSTALL = "install";
    private static final int RETRY_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 200;

    /** Usable-space floor under which an extract failure reads as a full disk rather than a refusal. */
    static final long DISK_FULL_FLOOR_BYTES = 16L * 1024 * 1024;

    // POSIX park store: JVM-lifetime strong references so the channel cleaner can never close a parked
    // channel (a GC-closed channel drops the process's locks on the file, empirically reproduced).
    // Keyed by the lock file's real path: probeLocked re-probes THROUGH an already-parked channel
    // (tryLock on it again; release-never-close on success) instead of opening and parking a new
    // descriptor per occurrence, so periodic re-probing never accumulates descriptors.
    // The parked value carries the file's fileKey (device+inode identity) captured at park time. A
    // parked channel is inode-bound while its key is path-bound: if the file at the key path is
    // replaced (a restore swap recreates session.lock, an attempt-counter reuse), a later probe would
    // re-probe the DEAD inode and could answer unlocked over a live lock on the new file. On consult
    // the current file's fileKey is compared; a mismatch tombstones the stale entry (never closing it,
    // which could drop a live holder's POSIX lock on the moved inode) and falls through to a fresh open.
    private static final ConcurrentMap<Path, ParkedChannel> PARKED = new ConcurrentHashMap<Path, ParkedChannel>();

    // Tombstoned parked channels: a stale entry lands here, strongly referenced so the cleaner never
    // GC-closes it (which would drop a live holder's POSIX lock on the moved inode) and never closed by
    // us. The queue exists only to keep the reference; nothing reads it back.
    private static final Queue<FileChannel> GRAVEYARD = new ConcurrentLinkedQueue<FileChannel>();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    // The never-published sentinel: a handler seeds the loaded world before dispatch, so a volatile
    // still holding this refuses rather than guessing that no world is loaded.
    private static final Path UNPUBLISHED = Paths.get(".wdl-unpublished-loaded-world");

    private final Path savesDirectory;
    private final String folderName;
    private final RestoreSource pinnedSource;
    private final boolean snapshotFirst;
    private final SnapshotStep snapshotStep;
    private final PhaseHook phaseHook;
    private volatile boolean aborted;
    private volatile boolean swapWindow;
    // What an unexpected crash reports depends on how far the run got: before the swap the download
    // folder is untouched, inside the swap it may be mid-rename, and past the install move the
    // restore itself already landed.
    private Outcome crashOutcome = Outcome.EXTRACT_REFUSED;
    private final AtomicReference<Path> publishedLoadedWorld = new AtomicReference<Path>(UNPUBLISHED);

    /** The pre-replace snapshot write, injectable so a test can force its failure. */
    interface SnapshotStep {
        void snapshot(Path folder, Path zipTarget) throws IOException;
    }

    /** The phase-boundary hook a test injects; production wires a no-op. */
    interface PhaseHook {
        void at(Phase phase);
    }

    /**
     * The hook points. The abort flag is re-checked immediately after every hook return, and a hook that throws
     * unchecked reads as a failure of the step at its point.
     */
    enum Phase {
        EXTRACT_START, BEFORE_ASIDE_MOVE, BETWEEN_MOVES, AFTER_INSTALL_MOVE
    }

    /** Per-cause refusal/failure/success result the completion poll consumes. */
    public enum Outcome {
        RESTORED, RESTORED_WITH_REMNANTS, NOT_MANAGED, NOT_TAINTED, TAINT_UNKNOWN,
        FOLDER_MISSING, FILE_OCCUPANT, SOURCE_CHANGED, WORLD_IN_USE, SNAPSHOT_FAILED, DISK_FULL,
        EXTRACT_REFUSED, SWAP_FAILED, RELOCATED, ABORTED
    }

    public static final class Result {
        private final Outcome outcome;
        private final List<Path> survivingPaths;
        private final @Nullable Path relocatedTo;

        Result(Outcome outcome) {
            this(outcome, Collections.<Path>emptyList(), null);
        }

        private Result(Outcome outcome, List<Path> survivingPaths, @Nullable Path relocatedTo) {
            this.outcome = outcome;
            this.survivingPaths = survivingPaths;
            this.relocatedTo = relocatedTo;
        }

        static Result swapFailed(Path keptAside) {
            return new Result(Outcome.SWAP_FAILED, Collections.singletonList(keptAside), null);
        }

        static Result relocated(Path sibling) {
            return new Result(Outcome.RELOCATED, Collections.<Path>emptyList(), sibling);
        }

        public Outcome outcome() {
            return outcome;
        }

        /**
         * The kept-aside paths a failure notice names; empty when nothing stays staged, which covers a plain success
         * and a RELOCATED move alike (the relocated sibling is reported by relocatedTo).
         */
        public List<Path> survivingPaths() {
            return survivingPaths;
        }

        /** The visible sibling the kept-aside moved to; non-null only for {@link Outcome#RELOCATED}. */
        public @Nullable Path relocatedTo() {
            return relocatedTo;
        }
    }

    private RestoreOperation(Path savesDirectory, String folderName, RestoreSource pinnedSource,
            boolean snapshotFirst, SnapshotStep snapshotStep, PhaseHook phaseHook) {
        this.savesDirectory = savesDirectory;
        this.folderName = folderName;
        this.pinnedSource = pinnedSource;
        this.snapshotFirst = snapshotFirst;
        this.snapshotStep = snapshotStep;
        this.phaseHook = phaseHook;
    }

    /** The file name the pre-replace snapshot of {@code folderName} would take right now, counter and all. */
    public static String nextSnapshotName(Path savesDirectory, String folderName) {
        return ZipName.nextFreeSinglePlayer(savesDirectory, folderName).getFileName().toString();
    }

    /** Immutable inputs pinned at confirm time. */
    public static RestoreOperation create(Path savesDirectory, String folderName,
            RestoreSource pinnedSource, boolean snapshotFirst) {
        return new RestoreOperation(savesDirectory, folderName, pinnedSource, snapshotFirst,
                FolderZipper::zip, phase -> {});
    }

    static RestoreOperation createForTest(Path savesDirectory, String folderName,
            RestoreSource pinnedSource, SnapshotStep snapshotStep, PhaseHook phaseHook) {
        RestoreOperation operation = new RestoreOperation(savesDirectory, folderName, pinnedSource, true,
                snapshotStep, phaseHook);
        // Seeded as the dispatching handler would, so a test exercises the phases past the volatile.
        operation.publishLoadedWorld(null);
        return operation;
    }

    /** Runs on the worker thread; never throws (Throwable-inclusive); always yields a Result. */
    public Result run() {
        try {
            return replace();
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, "restore of " + folderName + " failed unexpectedly", e);
            return new Result(crashOutcome);
        }
    }

    /** The shutdown hook's abort flag; checked between phases and between retry attempts. */
    public void abort() {
        aborted = true;
    }

    /**
     * True from the pre-swap probe through the two renames, including the refusal and rollback cleanup on those arms,
     * until the swap block's finally clears it ahead of the post-swap aside probe. This is the shutdown hook's
     * bounded-join window.
     */
    public boolean inSwapWindow() {
        return swapWindow;
    }

    /** The volatile the client tick seeds and republishes; consulted only for saves/&lt;folder&gt; probes. */
    public void publishLoadedWorld(@Nullable Path loadedWorldRoot) {
        publishedLoadedWorld.set(loadedWorldRoot);
    }

    private Result replace() throws IOException {
        if (aborted) {
            return new Result(Outcome.ABORTED);
        }
        Path folder = savesDirectory.resolve(folderName);
        Outcome refusal = checkPreconditions(folder);
        if (refusal != null) {
            return new Result(refusal);
        }

        if (aborted) {
            return new Result(Outcome.ABORTED);
        }
        if (snapshotFirst) {
            try {
                snapshotStep.snapshot(folder, ZipName.nextFreeSinglePlayer(savesDirectory, folderName));
            } catch (IOException e) {
                // Never proceed to destroy what was not backed up.
                LOGGER.log(Level.WARNING, "pre-restore snapshot of " + folderName + " failed", e);
                return new Result(Outcome.SNAPSHOT_FAILED);
            }
        }

        if (aborted) {
            return new Result(Outcome.ABORTED);
        }
        Path temporaryRoot = savesDirectory.resolve(TEMPORARY_ROOT);
        Path attempt = createAttemptDirectory(temporaryRoot);
        FileChannel attemptLock = null;
        try {
            attemptLock = FileChannel.open(attempt.resolve(ATTEMPT_LOCK),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            attemptLock.lock();
            Files.createDirectory(attempt.resolve(ASIDE));
            Files.createDirectory(attempt.resolve(INSTALL));
            return replaceThroughAttempt(folder, temporaryRoot, attempt, attemptLock);
        } finally {
            closeQuietly(attemptLock);
        }
    }

    /** The phases that own the attempt directory: extract, swap, aside delete, cleanup. */
    private Result replaceThroughAttempt(Path folder, Path temporaryRoot, Path attempt,
            FileChannel attemptLock) throws IOException {
        if (aborted) {
            return new Result(Outcome.ABORTED);
        }
        try {
            phaseHook.at(Phase.EXTRACT_START);
            if (aborted) {
                return new Result(Outcome.ABORTED);
            }
            // The pre-open re-stat: the source may have changed since the precondition pass.
            if (!RestoreSource.stillIdentical(pinnedSource)) {
                cleanUpAttempt(attemptLock, attempt, temporaryRoot);
                return new Result(Outcome.SOURCE_CHANGED);
            }
            FolderUnzipper.extract(pinnedSource.zip(), folderName, attempt.resolve(INSTALL));
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "extract of " + pinnedSource.zip().getFileName() + " refused", e);
            Outcome outcome = extractFailureOutcome(usableSpace(temporaryRoot));
            cleanUpAttempt(attemptLock, attempt, temporaryRoot);
            return new Result(outcome);
        }

        if (aborted) {
            return new Result(Outcome.ABORTED);
        }
        Path asideFolder = attempt.resolve(ASIDE).resolve(folderName);
        crashOutcome = Outcome.SWAP_FAILED;
        swapWindow = true;
        try {
            if (probeLocked(folder)) {
                cleanUpAttempt(attemptLock, attempt, temporaryRoot);
                return new Result(Outcome.WORLD_IN_USE);
            }
            try {
                phaseHook.at(Phase.BEFORE_ASIDE_MOVE);
                if (aborted) {
                    return new Result(Outcome.ABORTED);
                }
                retriedMove(folder, asideFolder);
            } catch (IOException | RuntimeException e) {
                // The folder never left its name, so no move-back is owed, only the attempt teardown.
                LOGGER.log(Level.WARNING, "keep-aside move for " + folderName + " failed", e);
                cleanUpAttempt(attemptLock, attempt, temporaryRoot);
                return new Result(Outcome.SWAP_FAILED);
            }
            if (probeLocked(asideFolder)) {
                // A live session traveled with the folder between the probe and the move: undo and refuse.
                return rollBack(folder, asideFolder, temporaryRoot, attempt, attemptLock, Outcome.WORLD_IN_USE);
            }
            try {
                phaseHook.at(Phase.BETWEEN_MOVES);
                if (aborted) {
                    return rollBack(folder, asideFolder, temporaryRoot, attempt, attemptLock, Outcome.ABORTED);
                }
                retriedMove(attempt.resolve(INSTALL).resolve(folderName), folder);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "install move for " + folderName + " failed; rolling back", e);
                return rollBack(folder, asideFolder, temporaryRoot, attempt, attemptLock, Outcome.SWAP_FAILED);
            }
        } finally {
            swapWindow = false;
        }

        crashOutcome = Outcome.RESTORED_WITH_REMNANTS;
        phaseHook.at(Phase.AFTER_INSTALL_MOVE);
        if (aborted) {
            // The quit path: the restored world is in place, the leftovers are the next sweep's.
            return new Result(Outcome.RESTORED_WITH_REMNANTS);
        }
        if (probeLocked(asideFolder)) {
            return new Result(Outcome.RESTORED_WITH_REMNANTS);
        }
        try {
            deleteRecursively(asideFolder);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "kept-aside delete for " + folderName + " failed", e);
            return new Result(Outcome.RESTORED_WITH_REMNANTS);
        }

        if (aborted) {
            return new Result(Outcome.RESTORED_WITH_REMNANTS);
        }
        // Close before delete: Windows cannot remove a file with an open handle.
        closeQuietly(attemptLock);
        try {
            deleteRecursively(attempt);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "attempt directory cleanup for " + folderName + " failed", e);
            return new Result(Outcome.RESTORED_WITH_REMNANTS);
        }
        deleteTemporaryRootIfEmpty(temporaryRoot);
        return new Result(Outcome.RESTORED);
    }

    /**
     * The pre-install rollback: the kept-aside goes back to the folder's name, then the attempt is torn down and the
     * failure reported per its cause. A present move-back target (something recreated the name mid-swap) takes the
     * terminal disposition instead; on the quit path both the disposition and the teardown are deferred to the next
     * launch's sweep.
     */
    private Result rollBack(Path folder, Path asideFolder, Path temporaryRoot, Path attempt,
            FileChannel attemptLock, Outcome rolledBackOutcome) {
        try {
            retriedMove(asideFolder, folder);
            if (aborted) {
                return new Result(Outcome.ABORTED);
            }
            cleanUpAttempt(attemptLock, attempt, temporaryRoot);
            return new Result(rolledBackOutcome);
        } catch (FileAlreadyExistsException e) {
            // The name is occupied again; the disposition below owns the kept-aside.
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "move-back of " + folderName + " failed", e);
            return Result.swapFailed(asideFolder);
        }
        if (aborted) {
            return new Result(Outcome.ABORTED);
        }
        Path sibling = disposeKeptAside(attempt, savesDirectory, folderName);
        if (sibling == null) {
            return Result.swapFailed(asideFolder);
        }
        cleanUpAttempt(attemptLock, attempt, temporaryRoot);
        return Result.relocated(sibling);
    }

    /**
     * The kept-aside terminal disposition: probe first (a locked aside defers to a later sweep, never a relocation
     * under a live session), then a SUCCESSFUL relocation to the next-free visible sibling cleans the attempt
     * directory; a failed relocation leaves the attempt for the next sweep, never cleaning over a surviving aside.
     * Returns the relocated folder or null (deferred).
     */
    private static @Nullable Path disposeKeptAside(Path attempt, Path savesDirectory, String folderName) {
        Path aside = attempt.resolve(ASIDE).resolve(folderName);
        if (probeLocked(aside)) {
            return null;
        }
        Path sibling = nextFreeFolder(savesDirectory, folderName); // <folder>_(2), _(3) ... never overwrite
        try {
            Files.move(aside, sibling);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "aside relocation failed; deferring the attempt to the next sweep", e);
            return null;
        }
        return sibling;
    }

    /** The next free visible sibling directory, folderName_(2) then _(3) and so on, never overwriting. */
    private static Path nextFreeFolder(Path savesDirectory, String folderName) {
        for (int counter = 2;; counter++) {
            Path candidate = savesDirectory.resolve(folderName + "_(" + counter + ")");
            // Link-following existence, unlike retriedMove's NOFOLLOW check: a symlink at the candidate name
            // counts as occupied, so the relocation never targets a name a link already claims.
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    /** The pinned refusal order: managed split, taint, source identity, loaded world, folder probe. */
    private @Nullable Outcome checkPreconditions(Path folder) {
        if (!DownloadFolders.isWdlManaged(folder)) {
            if (!Files.exists(folder)) {
                return Outcome.FOLDER_MISSING;
            }
            if (!Files.isDirectory(folder)) {
                return Outcome.FILE_OCCUPANT;
            }
            return Outcome.NOT_MANAGED;
        }
        TaintState taint = SinglePlayerTaint.classify(folder);
        if (taint == TaintState.CLEAN) {
            return Outcome.NOT_TAINTED;
        }
        if (taint == TaintState.UNKNOWN) {
            return Outcome.TAINT_UNKNOWN;
        }
        if (!RestoreSource.stillIdentical(pinnedSource)) {
            return Outcome.SOURCE_CHANGED;
        }
        Path loadedWorld = publishedLoadedWorld.get();
        if (loadedWorld == UNPUBLISHED) {
            return Outcome.WORLD_IN_USE;
        }
        if (loadedWorld != null && isSameExistingFolder(folder, loadedWorld)) {
            return Outcome.WORLD_IN_USE;
        }
        if (probeLocked(folder)) {
            return Outcome.WORLD_IN_USE;
        }
        return null;
    }

    private static boolean isSameExistingFolder(Path folder, Path loadedWorld) {
        try {
            return Files.exists(loadedWorld) && Files.isSameFile(folder, loadedWorld);
        } catch (IOException e) {
            return true; // an unverifiable identity refuses, matching the probe's fail-soft answer
        }
    }

    /**
     * The next free attempt directory under the temporary root, created: folderName-1, then -2 on collision. A vanished
     * temporary root (another instance emptied and removed it between our create calls) is re-created and the same name
     * retried.
     */
    private Path createAttemptDirectory(Path temporaryRoot) throws IOException {
        Files.createDirectories(temporaryRoot);
        int counter = 1;
        while (true) {
            try {
                return Files.createDirectory(temporaryRoot.resolve(folderName + "-" + counter));
            } catch (FileAlreadyExistsException e) {
                counter++;
            } catch (NoSuchFileException e) {
                Files.createDirectories(temporaryRoot);
            }
        }
    }

    /**
     * A move with bounded retry for transient holds (an indexer or scanner briefly pinning the tree): atomic when the
     * filesystem supports it, up to {@link #RETRY_ATTEMPTS} tries with {@link #RETRY_DELAY_MS} sleeps, giving up early
     * on abort. A present target fails immediately as {@link FileAlreadyExistsException}, probed explicitly because a
     * POSIX atomic rename would replace an empty target directory silently: the occupant is a branch decision for the
     * caller, never a transient.
     */
    private void retriedMove(Path source, Path target) throws IOException {
        for (int attempt = 1;; attempt++) {
            try {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileAlreadyExistsException(target.toString());
                }
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(source, target);
                }
                return;
            } catch (FileAlreadyExistsException e) {
                throw e;
            } catch (IOException e) {
                if (attempt >= RETRY_ATTEMPTS || aborted || !sleepBetweenAttempts()) {
                    throw e;
                }
            }
        }
    }

    private static boolean sleepBetweenAttempts() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * One point-in-time folder-open probe; an absent lock file is unlocked (vanilla isLocked semantics). WRITE only,
     * never CREATE: the probe must never mutate the folder it judges. Shared with the sweep.
     */
    static boolean probeLocked(Path folder) {
        Path lockFile = folder.resolve("session.lock");
        Path key = parkKey(lockFile);
        ParkedChannel parked = PARKED.get(key);
        if (parked != null) {
            if (isStaleParked(parked, currentFileKey(lockFile))) {
                // The file at the key path was replaced since we parked: the parked channel holds the
                // dead inode and would answer over a live lock on the new file. Tombstone it (never
                // close: that could drop a live holder's POSIX lock on the moved inode) and re-open.
                PARKED.remove(key, parked);
                GRAVEYARD.add(parked.channel);
                LOGGER.warning("tombstoned a stale parked channel on " + lockFile + " (file replaced)");
            } else {
                return probeThroughParked(parked.channel, lockFile);
            }
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return true;
            }
            lock.release();
            channel.close();
            return false;
        } catch (NoSuchFileException e) {
            closeQuietly(channel);
            return false; // a file that does not exist cannot be locked (vanilla isLocked semantics)
        } catch (OverlappingFileLockException e) {
            // Same-JVM holder. POSIX: park the channel forever (closing ANY channel on the file would
            // drop the holder's lock at the OS level). Windows: locks are per handle, close normally.
            if (WINDOWS) {
                closeQuietly(channel);
            } else if (channel != null) {
                parkProbeChannel(key, channel, lockFile);
            }
            return true;
        } catch (IOException e) {
            closeQuietly(channel);
            return true; // any other failure means locked (fail-soft refusal)
        }
    }

    /**
     * Parks a freshly opened probe channel for the key, or, when a concurrent probe already parked one, keeps that
     * winner and retains ours in the never-closed graveyard instead. The park is a compare-and-set so a lost race never
     * overwrites the live winner, and the loser is retained rather than closed: closing any channel on the file could
     * drop a live holder's POSIX lock on the inode.
     */
    static void parkProbeChannel(Path key, FileChannel channel, Path lockFile) {
        ParkedChannel existing = PARKED.putIfAbsent(key, new ParkedChannel(channel, currentFileKey(lockFile)));
        if (existing == null) {
            LOGGER.warning("parked a probe channel on " + lockFile + " (same-JVM lock holder)");
        } else {
            GRAVEYARD.add(channel);
            LOGGER.warning("graveyarded a probe channel on " + lockFile + " (lost the park race)");
        }
    }

    /** A parked probe channel paired with the fileKey of the file it was opened on (may be null). */
    private static final class ParkedChannel {
        private final FileChannel channel;
        private final @Nullable Object fileKey;

        ParkedChannel(FileChannel channel, @Nullable Object fileKey) {
            this.channel = channel;
            this.fileKey = fileKey;
        }
    }

    /**
     * Whether a parked entry no longer identifies the file now at its key path. Only a definite mismatch of two known
     * fileKeys is stale; a null on either side (a filesystem that reports none, a vanished file) leaves the stale check
     * impossible, so the conservative answer is not-stale and the existing re-probe-through behavior stands.
     */
    private static boolean isStaleParked(ParkedChannel parked, @Nullable Object currentFileKey) {
        return parked.fileKey != null && currentFileKey != null && !parked.fileKey.equals(currentFileKey);
    }

    private static @Nullable Object currentFileKey(Path lockFile) {
        try {
            return Files.readAttributes(lockFile, BasicFileAttributes.class).fileKey();
        } catch (IOException e) {
            return null; // unreadable or absent: identity is unknowable, keep the current behavior
        }
    }

    private static boolean probeThroughParked(FileChannel parked, Path lockFile) {
        try {
            FileLock lock = parked.tryLock();
            if (lock == null) {
                return true;
            }
            lock.release(); // release, never close: the parked channel serves every later probe
            return false;
        } catch (OverlappingFileLockException e) {
            return true; // the same-JVM holder still holds it
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "re-probe through the parked channel on " + lockFile + " failed", e);
            return true;
        }
    }

    static Path parkKey(Path lockFile) {
        try {
            return lockFile.toRealPath();
        } catch (IOException e) {
            return lockFile.toAbsolutePath().normalize();
        }
    }

    /** Test-only: the channel currently parked for the key, or null when none is parked. */
    static @Nullable FileChannel parkedChannelForTest(Path key) {
        ParkedChannel parked = PARKED.get(key);
        return parked == null ? null : parked.channel;
    }

    /** Test-only: whether the channel is retained in the never-closed graveyard. */
    static boolean graveyardContainsForTest(FileChannel channel) {
        return GRAVEYARD.contains(channel);
    }

    /**
     * Whether any attempt under the saves directory's temporary root stages folderName in its aside or install
     * directory, child names compared case-insensitively. An unreadable scan reports false after one warning: the
     * caller fails open.
     */
    public static boolean attemptReferences(Path savesDirectory, String folderName) {
        Path temporaryRoot = savesDirectory.resolve(TEMPORARY_ROOT);
        if (!Files.isDirectory(temporaryRoot)) {
            return false;
        }
        try (DirectoryStream<Path> attempts = Files.newDirectoryStream(temporaryRoot)) {
            for (Path attempt : attempts) {
                if (stagesName(attempt.resolve(ASIDE), folderName)
                        || stagesName(attempt.resolve(INSTALL), folderName)) {
                    return true;
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "restore-attempt scan failed; treating as no reference", e);
        }
        return false;
    }

    private static boolean stagesName(Path staging, String folderName) throws IOException {
        if (!Files.isDirectory(staging)) {
            return false;
        }
        String wanted = folderName.toLowerCase(Locale.ROOT);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(staging)) {
            for (Path child : children) {
                Path childName = child.getFileName();
                if (childName != null && childName.toString().toLowerCase(Locale.ROOT).equals(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The extract-failure split: a nearly-full staging filesystem reads as a full disk, else a refusal. */
    static Outcome extractFailureOutcome(long usableBytes) {
        return usableBytes < DISK_FULL_FLOOR_BYTES ? Outcome.DISK_FULL : Outcome.EXTRACT_REFUSED;
    }

    private static long usableSpace(Path temporaryRoot) {
        try {
            return Files.getFileStore(temporaryRoot).getUsableSpace();
        } catch (IOException e) {
            return Long.MAX_VALUE; // unknowable space cannot prove a full disk; report the refusal
        }
    }

    /** Best-effort attempt teardown on a refusal or failure exit: close the lock first, then delete. */
    private static void cleanUpAttempt(FileChannel attemptLock, Path attempt, Path temporaryRoot) {
        closeQuietly(attemptLock);
        try {
            deleteRecursively(attempt);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "attempt directory cleanup failed; leaving it to the sweep", e);
            return;
        }
        deleteTemporaryRootIfEmpty(temporaryRoot);
    }

    private static void deleteTemporaryRootIfEmpty(Path temporaryRoot) {
        try {
            Files.deleteIfExists(temporaryRoot);
        } catch (DirectoryNotEmptyException e) {
            // Another instance's attempt still lives under the root; that owner removes it later.
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "temporary root removal failed", e);
        }
    }

    /** Recursive delete that never follows symbolic links: a link is deleted, its target never entered. */
    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, @Nullable IOException error)
                    throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void closeQuietly(@Nullable FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "channel close failed", e);
        }
    }

    /**
     * The roll-back-only sweep of torn restore attempts left under the saves temporary root, plus the age-gated cleanup
     * of orphaned export {@code .part} files. It lives beside the operation because one file owns the attempt-directory
     * layout. {@link #hasWork} is a main-thread-cheap dispatch predicate (a signature memo with a TTL and per-attempt
     * blocker kinds; it may probe session locks but never opens {@code attempt.lock} and never takes the RESTORING
     * flip); {@link #run} is the mutating pass a worker thread performs under that flip.
     *
     * <p>The sweep only ever rolls a torn attempt back toward its pre-restore state or disposes an unreachable
     * kept-aside; it never installs a clean export (an interrupted restore is re-driven by the operation, not completed
     * here) and never overwrites a live world folder.
     */
    public static final class RestoreSweep {
        /** The memo staleness window: past it, hasWork re-evaluates the per-attempt blocker kinds. */
        public static final long TTL_MS = 5 * 60_000;

        /** The shared per-attempt dispatch ceiling; a blocked attempt waits for relaunch past it. */
        static final int MAX_DISPATCHES = 8;

        private static final long HOUR_MS = 60L * 60_000;
        private static final String PART_PREFIX = "wdl-export-";
        private static final String PART_SUFFIX = ".part";
        private static final String PART_STATE_PREFIX = "part:";

        /** Injectable wall clock; production reads the system clock, a test drives it deterministically. */
        static LongSupplier clock = System::currentTimeMillis;

        private static final Runnable NO_RUN_START_BARRIER = () -> {};

        // A test-only barrier the sweep worker awaits once as it starts, so a client gametest can park the sweep
        // and hold the single-flight RESTORING flip while it observes the screen. Production leaves the no-op
        // default in place; resetForTest restores it.
        private static volatile Runnable runStartBarrier = NO_RUN_START_BARRIER;

        // Refreshed only by a completed run(); hasWork (main thread) reads it, run() (worker) publishes it.
        private static volatile @Nullable Memo memo;

        // Missing-folder notices already surfaced this session, keyed by attempt identity, so the notice
        // stays non-repeating per attempt per session across successive sweeps.
        private static final Set<String> reportedMissing = Collections
                .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

        private RestoreSweep() {}

        /**
         * Drops the memo, the missing-folder notice set, the injected clock, and the run-start barrier back to
         * production.
         */
        public static void resetForTest() {
            memo = null;
            reportedMissing.clear();
            clock = System::currentTimeMillis;
            runStartBarrier = NO_RUN_START_BARRIER;
        }

        /**
         * Installs the barrier the next {@link #run(Path)} awaits as it starts, the seam a client gametest uses to park
         * the sweep worker and hold the single-flight RESTORING flip while it drives the screen.
         */
        public static void runStartBarrierForTest(Runnable barrier) {
            runStartBarrier = barrier;
        }

        /** The blocker that keeps a swept attempt (or a spared part) around, driving hasWork past the TTL. */
        enum Kind {
            PROBE_SHAPED, IO_FAILURE, AGE_SHAPED
        }

        /** The notices a completed sweep surfaces, plus whether it touched the disk at all. */
        public static final class SweepResult {
            private final boolean changedDisk;
            private final List<Path> movedBack;
            private final List<Path> relocated;
            private final List<Path> missingDeferred;

            SweepResult(boolean changedDisk, List<Path> movedBack, List<Path> relocated,
                    List<Path> missingDeferred) {
                this.changedDisk = changedDisk;
                this.movedBack = Collections.unmodifiableList(new ArrayList<Path>(movedBack));
                this.relocated = Collections.unmodifiableList(new ArrayList<Path>(relocated));
                this.missingDeferred = Collections.unmodifiableList(new ArrayList<Path>(missingDeferred));
            }

            /** Whether this run mutated the disk; the caller re-pulls its entry list only when true. */
            public boolean changedDisk() {
                return changedDisk;
            }

            /** Kept-asides moved back to their original folder names. */
            public List<Path> movedBack() {
                return movedBack;
            }

            /** Kept-asides relocated to visible siblings because the original name was reoccupied. */
            public List<Path> relocated() {
                return relocated;
            }

            /** Attempts whose deferral (a locked aside over a missing folder) leaves the folder absent. */
            public List<Path> missingDeferred() {
                return missingDeferred;
            }
        }

        /**
         * Whether a mutating sweep should be dispatched. Main-thread-cheap: a per-attempt-directory signature (each
         * attempt's own mtime and which of aside, install, and attempt.lock exist inside it, never its world-tree
         * contents), the export part-name set, and the per-attempt folder-existence bits, compared against the last
         * completed run's memo. A changed layout re-arms immediately; an unchanged layout stays quiet until the TTL,
         * past which the per-attempt blocker kinds decide (a probe-shaped block dispatches only on a fail-to-pass flip
         * of its aside session lock, an io-failure block dispatches while its bound remains, an age-shaped part
         * dispatches once it crosses the hour). This never opens {@code attempt.lock} and never takes the RESTORING
         * flip; it may open session locks.
         */
        public static boolean hasWork(Path savesDirectory) {
            long now = clock.getAsLong();
            Path temporaryRoot = savesDirectory.resolve(TEMPORARY_ROOT);
            Signature signature = computeSignature(savesDirectory);
            Memo current = memo;
            if (current == null || !current.savesDirectory.equals(savesDirectory)
                    || !current.signature.equals(signature)) {
                return signatureHasWork(savesDirectory, temporaryRoot, now);
            }
            if (now - current.stamp < TTL_MS) {
                return false;
            }
            return anyKindDispatches(savesDirectory, temporaryRoot, current, now);
        }

        /**
         * The mutating sweep: per attempt under the temporary root, take {@code attempt.lock} (a held lock means a live
         * attempt, skipped this pass) and branch on whether the aside, the install, and the live folder exist, rolling
         * the torn attempt back or disposing the kept-aside; then delete orphaned export parts older than an hour.
         * Refreshes the memo from the resulting layout.
         */
        public static SweepResult run(Path savesDirectory) {
            runStartBarrier.run();
            long now = clock.getAsLong();
            List<Path> movedBack = new ArrayList<Path>();
            List<Path> relocated = new ArrayList<Path>();
            List<Path> missingDeferred = new ArrayList<Path>();
            boolean[] changed = { false };
            Map<String, AttemptState> states = new HashMap<String, AttemptState>();
            Memo prior = memo;

            Path temporaryRoot = savesDirectory.resolve(TEMPORARY_ROOT);
            for (Path attempt : listAttempts(temporaryRoot)) {
                processAttempt(savesDirectory, attempt, now, prior, states,
                        movedBack, relocated, missingDeferred, changed);
            }
            deleteTemporaryRootIfEmpty(temporaryRoot);
            if (cleanUpStaleParts(savesDirectory, now, prior, states)) {
                changed[0] = true;
            }

            memo = new Memo(savesDirectory, computeSignature(savesDirectory), now, states);
            return new SweepResult(changed[0], movedBack, relocated, missingDeferred);
        }

        private static void processAttempt(Path savesDirectory, Path attempt, long now, @Nullable Memo prior,
                Map<String, AttemptState> states, List<Path> movedBack, List<Path> relocated,
                List<Path> missingDeferred, boolean[] changed) {
            Path attemptNamePath = attempt.getFileName();
            if (attemptNamePath == null) {
                return;
            }
            FileChannel lockChannel = null;
            try {
                try {
                    lockChannel = FileChannel.open(attempt.resolve(ATTEMPT_LOCK), StandardOpenOption.WRITE);
                } catch (NoSuchFileException e) {
                    // No attempt.lock file: a crash in the gap between creating the attempt directory and its
                    // attempt.lock left a lockless orphan that would otherwise pin the temporary root indefinitely.
                    // Reap it once it is safely past the brief create-before-lock window, age-gated so a live
                    // instance's just-created directory is never removed.
                    sweepLocklessAttempt(attempt, attemptNamePath.toString(), now, states, changed);
                    return;
                }
                FileLock lock = lockChannel.tryLock();
                if (lock == null) {
                    return; // a live attempt holds the lock (cross-process); never processed this pass
                }
                processLocked(savesDirectory, attempt, attemptNamePath.toString(), prior, states,
                        movedBack, relocated, missingDeferred, changed, lockChannel);
            } catch (OverlappingFileLockException e) {
                // A live attempt in this same JVM holds the lock; skip it exactly as the null case does.
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "sweep failed to process attempt " + attempt, e);
            } finally {
                closeQuietly(lockChannel);
            }
        }

        private static void sweepLocklessAttempt(Path attempt, String attemptName, long now,
                Map<String, AttemptState> states, boolean[] changed) {
            if (now - lastModifiedMillis(attempt, now) < HOUR_MS) {
                // Still inside the create-before-lock window: a live instance may yet take the lock, so keep
                // it and account for it so hasWork does not re-dispatch a sweep every TTL until it ages out.
                states.put(attemptName, new AttemptState(Kind.AGE_SHAPED, 0, false));
                return;
            }
            if (Files.isDirectory(attempt.resolve(ASIDE)) || Files.isDirectory(attempt.resolve(INSTALL))) {
                // A lockless attempt is only ever reaped when truly empty. Today's layout creates
                // attempt.lock before aside and install, so a lockless directory holds no world copy; a foreign
                // or future layout with an aside created before its lock could, and that aside/<folder> is
                // a real world copy. Never deleteRecursively it without a move-back: leave it for a future
                // sweep and account for it so hasWork does not re-dispatch every TTL.
                LOGGER.log(Level.INFO,
                        "sweep left a lockless attempt holding a world copy in place: " + attempt);
                states.put(attemptName, new AttemptState(Kind.AGE_SHAPED, 0, false));
                return;
            }
            try {
                deleteRecursively(attempt);
                changed[0] = true;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "sweep delete of the lockless attempt " + attempt + " failed", e);
            }
        }

        private static void processLocked(Path savesDirectory, Path attempt, String attemptName,
                @Nullable Memo prior, Map<String, AttemptState> states, List<Path> movedBack,
                List<Path> relocated, List<Path> missingDeferred, boolean[] changed,
                FileChannel lockChannel) throws IOException {
            Path asideChild = firstChild(attempt.resolve(ASIDE));
            if (asideChild == null) {
                // No kept-aside to preserve: an install-only leftover or an empty attempt. Delete it.
                closeAndDeleteAttempt(lockChannel, attempt);
                changed[0] = true;
                return;
            }
            Path folderNamePath = asideChild.getFileName();
            if (folderNamePath == null) {
                return;
            }
            String folderName = folderNamePath.toString();
            Path folder = savesDirectory.resolve(folderName);
            boolean folderPresent = Files.exists(folder);

            if (probeLocked(asideChild)) {
                // A live session travels with the kept-aside: defer, never touching it.
                if (!folderPresent) {
                    recordMissingDeferred(attempt, folder, missingDeferred);
                }
                states.put(attemptName, deferState(Kind.PROBE_SHAPED, prior, attemptName, true));
                return;
            }
            if (!folderPresent) {
                moveBackOrRelocate(savesDirectory, attempt, folderName, folder, asideChild, prior,
                        attemptName, states, movedBack, relocated, missingDeferred, changed, lockChannel);
                return;
            }
            if (firstChild(attempt.resolve(INSTALL)) != null) {
                // The name was reoccupied while the install was still staged: never overwrite it, relocate.
                relocateAside(savesDirectory, attempt, folderName, asideChild, prior, attemptName, states,
                        relocated, changed, lockChannel);
                return;
            }
            try {
                // The install already landed at the folder; the aside is the stale original, delete it.
                deleteRecursively(asideChild);
                closeAndDeleteAttempt(lockChannel, attempt);
                changed[0] = true;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "sweep delete of the kept-aside for " + folderName + " failed", e);
                states.put(attemptName, deferState(Kind.IO_FAILURE, prior, attemptName, false));
            }
        }

        private static void moveBackOrRelocate(Path savesDirectory, Path attempt, String folderName,
                Path folder, Path asideChild, @Nullable Memo prior, String attemptName,
                Map<String, AttemptState> states, List<Path> movedBack, List<Path> relocated,
                List<Path> missingDeferred, boolean[] changed, FileChannel lockChannel) throws IOException {
            try {
                if (sweepMoveBack(asideChild, folder)) {
                    movedBack.add(folder);
                    closeAndDeleteAttempt(lockChannel, attempt);
                    changed[0] = true;
                    return;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "sweep move-back of " + folderName + " failed", e);
                // A failed move-back leaves the folder absent under a permanent blocker: name it once, so
                // a vanished download folder is never a silent state even when the aside stays recoverable.
                recordMissingDeferred(attempt, folder, missingDeferred);
                states.put(attemptName, deferState(Kind.IO_FAILURE, prior, attemptName, false));
                return;
            }
            relocateAside(savesDirectory, attempt, folderName, asideChild, prior, attemptName, states,
                    relocated, changed, lockChannel);
        }

        private static void relocateAside(Path savesDirectory, Path attempt, String folderName,
                Path asideChild, @Nullable Memo prior, String attemptName,
                Map<String, AttemptState> states, List<Path> relocated, boolean[] changed,
                FileChannel lockChannel) throws IOException {
            Path sibling = disposeKeptAside(attempt, savesDirectory, folderName);
            if (sibling == null) {
                // Deferred: either a lock reappeared on the aside or the relocation move failed. A still
                // locked aside is probe-shaped, a failed move is io-failure; re-probe to classify.
                boolean locked = probeLocked(asideChild);
                Kind kind = locked ? Kind.PROBE_SHAPED : Kind.IO_FAILURE;
                states.put(attemptName, deferState(kind, prior, attemptName, locked));
                return;
            }
            relocated.add(sibling);
            closeAndDeleteAttempt(lockChannel, attempt);
            changed[0] = true;
        }

        private static boolean cleanUpStaleParts(Path savesDirectory, long now, @Nullable Memo prior,
                Map<String, AttemptState> states) {
            boolean changed = false;
            if (!Files.isDirectory(savesDirectory)) {
                return false;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(savesDirectory)) {
                for (Path entry : entries) {
                    Path namePath = entry.getFileName();
                    if (namePath == null) {
                        continue;
                    }
                    String name = namePath.toString();
                    if (!isPartName(name) || !Files.isRegularFile(entry)) {
                        continue;
                    }
                    if (now - lastModifiedMillis(entry, now) < HOUR_MS) {
                        // A fresh part is spared; it becomes dispatchable once its mtime crosses the hour.
                        states.put(PART_STATE_PREFIX + name, new AttemptState(Kind.AGE_SHAPED, 0, false));
                        continue;
                    }
                    try {
                        Files.delete(entry);
                        changed = true;
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "sweep delete of stale export part " + name + " failed", e);
                        states.put(PART_STATE_PREFIX + name,
                                deferState(Kind.IO_FAILURE, prior, PART_STATE_PREFIX + name, false));
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "sweep export-part scan failed", e);
            }
            return changed;
        }

        private static boolean signatureHasWork(Path savesDirectory, Path temporaryRoot, long now) {
            return hasAnyAttempt(temporaryRoot) || hasStalePart(savesDirectory, now);
        }

        private static boolean anyKindDispatches(Path savesDirectory, Path temporaryRoot, Memo current,
                long now) {
            for (Path attempt : listAttempts(temporaryRoot)) {
                Path attemptNamePath = attempt.getFileName();
                if (attemptNamePath == null) {
                    continue;
                }
                AttemptState state = current.attempts.get(attemptNamePath.toString());
                if (state == null) {
                    return true; // an attempt the last run did not account for is unexplained work
                }
                String folderName = attemptFolderName(attempt);
                boolean folderMissing = folderName == null || !Files.exists(savesDirectory.resolve(folderName));
                boolean withinBound = state.dispatches < MAX_DISPATCHES || folderMissing;
                if (state.kind == Kind.PROBE_SHAPED) {
                    Path aside = folderName == null ? null : attempt.resolve(ASIDE).resolve(folderName);
                    boolean nowLocked = aside != null && probeLocked(aside);
                    if (state.asideLocked && !nowLocked && withinBound) {
                        return true; // the aside session lock flipped from held to free
                    }
                } else if (state.kind == Kind.IO_FAILURE && withinBound) {
                    return true;
                }
            }
            return anyStalePartDispatches(savesDirectory, current, now);
        }

        private static boolean anyStalePartDispatches(Path savesDirectory, Memo current, long now) {
            if (!Files.isDirectory(savesDirectory)) {
                return false;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(savesDirectory)) {
                for (Path entry : entries) {
                    Path namePath = entry.getFileName();
                    if (namePath == null) {
                        continue;
                    }
                    String name = namePath.toString();
                    if (!isPartName(name) || !Files.isRegularFile(entry)) {
                        continue;
                    }
                    if (now - lastModifiedMillis(entry, now) < HOUR_MS) {
                        continue;
                    }
                    AttemptState state = current.attempts.get(PART_STATE_PREFIX + name);
                    if (state != null && state.kind == Kind.IO_FAILURE
                            && state.dispatches >= MAX_DISPATCHES) {
                        continue; // this part's delete bound has drained; wait for relaunch
                    }
                    return true;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "sweep export-part dispatch scan failed", e);
            }
            return false;
        }

        private static AttemptState deferState(Kind kind, @Nullable Memo prior, String stateKey,
                boolean asideLocked) {
            int priorDispatches = 0;
            if (prior != null) {
                AttemptState priorState = prior.attempts.get(stateKey);
                if (priorState != null) {
                    priorDispatches = priorState.dispatches;
                }
            }
            return new AttemptState(kind, priorDispatches + 1, asideLocked);
        }

        private static void recordMissingDeferred(Path attempt, Path folder, List<Path> missingDeferred) {
            if (reportedMissing.add(attemptIdentity(attempt))) {
                missingDeferred.add(folder);
            }
        }

        private static String attemptIdentity(Path attempt) {
            try {
                return attempt.toRealPath().toString();
            } catch (IOException e) {
                return attempt.toAbsolutePath().normalize().toString();
            }
        }

        private static Signature computeSignature(Path savesDirectory) {
            Path temporaryRoot = savesDirectory.resolve(TEMPORARY_ROOT);
            Map<String, String> attemptLayout = new TreeMap<String, String>();
            Map<String, Boolean> folderBits = new TreeMap<String, Boolean>();
            if (Files.isDirectory(temporaryRoot)) {
                for (Path attempt : listAttempts(temporaryRoot)) {
                    Path attemptNamePath = attempt.getFileName();
                    if (attemptNamePath == null) {
                        continue;
                    }
                    String attemptName = attemptNamePath.toString();
                    attemptLayout.put(attemptName, attemptFingerprint(attempt));
                    String folderName = attemptFolderName(attempt);
                    boolean present = folderName != null && Files.exists(savesDirectory.resolve(folderName));
                    folderBits.put(attemptName, Boolean.valueOf(present));
                }
            }
            return new Signature(attemptLayout.toString(), listPartNames(savesDirectory), folderBits);
        }

        /**
         * The attempt-directory-bounded fingerprint: the attempt's own mtime plus which of aside, install, and
         * attempt.lock exist directly inside it, never its world-tree contents. The deep contents of a torn
         * aside/&lt;folder&gt; or install/&lt;folder&gt; never change once the attempt is created (extract writes
         * install once, then it is swapped away or left torn untouched; an aside is moved wholesale, never edited in
         * place), so recursing them would stat tens of thousands of dead-weight files on the main thread to detect a
         * change that cannot happen.
         */
        private static String attemptFingerprint(Path attempt) {
            StringBuilder fingerprint = new StringBuilder();
            fingerprint.append(lastModifiedMillis(attempt, 0L)).append(':');
            fingerprint.append(Files.isDirectory(attempt.resolve(ASIDE)) ? 'a' : '-');
            fingerprint.append(Files.isDirectory(attempt.resolve(INSTALL)) ? 'i' : '-');
            fingerprint.append(Files.exists(attempt.resolve(ATTEMPT_LOCK)) ? 'l' : '-');
            return fingerprint.toString();
        }

        private static List<Path> listAttempts(Path temporaryRoot) {
            List<Path> result = new ArrayList<Path>();
            if (!Files.isDirectory(temporaryRoot)) {
                return result;
            }
            try (DirectoryStream<Path> attempts = Files.newDirectoryStream(temporaryRoot)) {
                for (Path attempt : attempts) {
                    if (Files.isDirectory(attempt)) {
                        result.add(attempt);
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "sweep attempt listing failed", e);
            }
            return result;
        }

        private static boolean hasAnyAttempt(Path temporaryRoot) {
            for (Path attempt : listAttempts(temporaryRoot)) {
                if (Files.exists(attempt.resolve(ATTEMPT_LOCK))) {
                    return true;
                }
            }
            return false;
        }

        private static @Nullable Path firstChild(Path directory) {
            if (!Files.isDirectory(directory)) {
                return null;
            }
            try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
                for (Path child : children) {
                    return child;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "sweep child scan of " + directory + " failed", e);
            }
            return null;
        }

        private static @Nullable String attemptFolderName(Path attempt) {
            String fromAside = childName(attempt.resolve(ASIDE));
            return fromAside != null ? fromAside : childName(attempt.resolve(INSTALL));
        }

        private static @Nullable String childName(Path directory) {
            Path child = firstChild(directory);
            if (child == null) {
                return null;
            }
            Path name = child.getFileName();
            return name == null ? null : name.toString();
        }

        private static Set<String> listPartNames(Path savesDirectory) {
            Set<String> names = new HashSet<String>();
            if (!Files.isDirectory(savesDirectory)) {
                return names;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(savesDirectory)) {
                for (Path entry : entries) {
                    Path name = entry.getFileName();
                    if (name != null && isPartName(name.toString()) && Files.isRegularFile(entry)) {
                        names.add(name.toString());
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "sweep export-part name scan failed", e);
            }
            return names;
        }

        private static boolean hasStalePart(Path savesDirectory, long now) {
            if (!Files.isDirectory(savesDirectory)) {
                return false;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(savesDirectory)) {
                for (Path entry : entries) {
                    Path name = entry.getFileName();
                    if (name == null || !isPartName(name.toString()) || !Files.isRegularFile(entry)) {
                        continue;
                    }
                    if (now - lastModifiedMillis(entry, now) >= HOUR_MS) {
                        return true;
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "sweep export-part age scan failed", e);
            }
            return false;
        }

        private static boolean isPartName(String name) {
            return name.startsWith(PART_PREFIX) && name.endsWith(PART_SUFFIX);
        }

        private static long lastModifiedMillis(Path file, long fallbackNow) {
            try {
                FileTime mtime = Files.getLastModifiedTime(file);
                return mtime.toMillis();
            } catch (IOException e) {
                return fallbackNow; // an unreadable mtime reads as fresh, sparing the entry rather than deleting
            }
        }

        private static boolean sweepMoveBack(Path source, Path target) throws IOException {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return false; // the name reappeared; the caller takes the occupied disposition
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target);
            }
            return true;
        }

        private static void closeAndDeleteAttempt(FileChannel lockChannel, Path attempt) throws IOException {
            closeQuietly(lockChannel); // close before delete: Windows cannot remove a file with an open handle
            deleteRecursively(attempt);
        }

        /** The last completed run's layout snapshot with the per-attempt blocker states and its clock stamp. */
        private static final class Memo {
            private final Path savesDirectory;
            private final Signature signature;
            private final long stamp;
            private final Map<String, AttemptState> attempts;

            Memo(Path savesDirectory, Signature signature, long stamp, Map<String, AttemptState> attempts) {
                this.savesDirectory = savesDirectory;
                this.signature = signature;
                this.stamp = stamp;
                this.attempts = attempts;
            }
        }

        /** A blocked attempt (or spared part): its kind, consumed dispatch count, and last probe result. */
        private static final class AttemptState {
            private final Kind kind;
            private final int dispatches;
            private final boolean asideLocked;

            AttemptState(Kind kind, int dispatches, boolean asideLocked) {
                this.kind = kind;
                this.dispatches = dispatches;
                this.asideLocked = asideLocked;
            }
        }

        /** The value-compared layout fingerprint: the per-attempt layout, the part-name set, the folder bits. */
        private static final class Signature {
            private final String attemptLayout;
            private final Set<String> partNames;
            private final Map<String, Boolean> folderBits;

            Signature(String attemptLayout, Set<String> partNames, Map<String, Boolean> folderBits) {
                this.attemptLayout = attemptLayout;
                this.partNames = partNames;
                this.folderBits = folderBits;
            }

            @Override
            public boolean equals(@Nullable Object other) {
                if (this == other) {
                    return true;
                }
                if (!(other instanceof Signature)) {
                    return false;
                }
                Signature that = (Signature) other;
                return attemptLayout.equals(that.attemptLayout) && partNames.equals(that.partNames)
                        && folderBits.equals(that.folderBits);
            }

            @Override
            public int hashCode() {
                return Objects.hash(attemptLayout, partNames, folderBits);
            }
        }
    }
}
