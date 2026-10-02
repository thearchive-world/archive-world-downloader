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

public final class RestoreOperation {
    private static final Logger LOGGER = Logger.getLogger(RestoreOperation.class.getName());

    /** The per-attempt staging root under the saves directory. */
    public static final String TEMPORARY_ROOT = ".wdl-restore-work";

    private static final String ATTEMPT_LOCK = "attempt.lock";
    private static final String ASIDE = "aside";
    private static final String INSTALL = "install";
    private static final int RETRY_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 200;

    static final long DISK_FULL_FLOOR_BYTES = 16L * 1024 * 1024;

    // POSIX park store: JVM-lifetime strong references so the channel cleaner can never close a parked channel (a
    // GC-closed channel drops the process's locks on the file). probeLocked re-probes THROUGH an already-parked channel
    // instead of opening and parking a new descriptor per occurrence, so periodic re-probing never accumulates
    // descriptors.
    private static final ConcurrentMap<Path, ParkedChannel> parkedChannels = new ConcurrentHashMap<>();

    // Tombstoned parked channels: a stale entry lands here, strongly referenced so the cleaner never GC-closes it
    // (which would drop a live holder's POSIX lock on the moved inode). The queue exists only to keep the reference.
    private static final Queue<FileChannel> graveyard = new ConcurrentLinkedQueue<FileChannel>();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    // The never-published sentinel: a volatile still holding this refuses rather than guessing that no world is loaded.
    private static final Path UNPUBLISHED = Paths.get(".wdl-unpublished-loaded-world");

    private final Path savesDirectory;
    private final String folderName;
    private final RestoreSource pinnedSource;
    private final boolean snapshotFirst;
    private final SnapshotStep snapshotStep;
    private final PhaseHook phaseHook;
    private volatile boolean aborted;
    private volatile boolean swapWindow;
    private Outcome crashOutcome = Outcome.EXTRACT_REFUSED;
    private final AtomicReference<Path> publishedLoadedWorld = new AtomicReference<Path>(UNPUBLISHED);

    interface SnapshotStep {
        void snapshot(Path folder, Path zipTarget) throws IOException;
    }

    interface PhaseHook {
        void at(Phase phase);
    }

    enum Phase {
        EXTRACT_START, BEFORE_ASIDE_MOVE, BETWEEN_MOVES, AFTER_INSTALL_MOVE
    }

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

    public static RestoreOperation create(Path savesDirectory, String folderName,
            RestoreSource pinnedSource, boolean snapshotFirst) {
        return new RestoreOperation(savesDirectory, folderName, pinnedSource, snapshotFirst,
                FolderZipper::zip, phase -> {});
    }

    static RestoreOperation createForTest(Path savesDirectory, String folderName,
            RestoreSource pinnedSource, SnapshotStep snapshotStep, PhaseHook phaseHook) {
        RestoreOperation operation = new RestoreOperation(savesDirectory, folderName, pinnedSource, true,
                snapshotStep, phaseHook);
        operation.publishLoadedWorld(null);
        return operation;
    }

    /** Never throws (Throwable-inclusive); always yields a Result. */
    public Result run() {
        try {
            return replace();
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, "restore of " + folderName + " failed unexpectedly", e);
            return new Result(crashOutcome);
        }
    }

    /** The abort flag; checked between phases and between retry attempts. */
    public void abort() {
        aborted = true;
    }

    /**
     * True from the pre-swap probe through the two renames, including the refusal and rollback cleanup on those arms,
     * until the swap block's finally clears it ahead of the post-swap aside probe.
     */
    public boolean inSwapWindow() {
        return swapWindow;
    }

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
        FileLock attemptHeld = null;
        try {
            attemptLock = FileChannel.open(attempt.resolve(ATTEMPT_LOCK),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            // Held and released in the finally, never discarded: JDK 8's lock table holds a FileLock only weakly
            // (JDK-8166253, fixed in 11), so a dropped one is collected and a same-JVM sweep on POSIX reads it as free.
            attemptHeld = attemptLock.lock();
            Files.createDirectory(attempt.resolve(ASIDE));
            Files.createDirectory(attempt.resolve(INSTALL));
            return replaceThroughAttempt(folder, temporaryRoot, attempt, attemptLock);
        } finally {
            releaseQuietly(attemptHeld);
            closeQuietly(attemptLock);
        }
    }

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
            // The quit path: the restored world is in place.
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
            // The name is occupied again.
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

    private static @Nullable Path disposeKeptAside(Path attempt, Path savesDirectory, String folderName) {
        Path aside = attempt.resolve(ASIDE).resolve(folderName);
        if (probeLocked(aside)) {
            return null;
        }
        Path sibling = nextFreeFolder(savesDirectory, folderName);
        try {
            Files.move(aside, sibling);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "aside relocation failed; deferring the attempt to the next sweep", e);
            return null;
        }
        return sibling;
    }

    private static Path nextFreeFolder(Path savesDirectory, String folderName) {
        for (int counter = 2;; counter++) {
            Path candidate = savesDirectory.resolve(folderName + "_(" + counter + ")");
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

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
            return true;
        }
    }

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
     * A present target fails immediately as {@link FileAlreadyExistsException}, probed explicitly because a POSIX
     * atomic rename would replace an empty target directory silently.
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
     * WRITE only, never CREATE: the probe must never mutate the folder it judges.
     */
    static boolean probeLocked(Path folder) {
        Path lockFile = folder.resolve("session.lock");
        Path key = parkKey(lockFile);
        ParkedChannel parked = parkedChannels.get(key);
        if (parked != null) {
            if (isStaleParked(parked, currentFileKey(lockFile))) {
                // The file at the key path was replaced since we parked: the parked channel holds the dead inode and
                // would answer over a live lock on the new file.
                parkedChannels.remove(key, parked);
                graveyard.add(parked.channel);
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
            return false;
        } catch (OverlappingFileLockException e) {
            // POSIX: park the channel forever (closing ANY channel on the file would drop the holder's lock at the OS
            // level).
            if (WINDOWS) {
                closeQuietly(channel);
            } else if (channel != null) {
                parkProbeChannel(key, channel, lockFile);
            }
            return true;
        } catch (IOException e) {
            closeQuietly(channel);
            return true;
        }
    }

    static void parkProbeChannel(Path key, FileChannel channel, Path lockFile) {
        ParkedChannel existing = parkedChannels.putIfAbsent(key, new ParkedChannel(channel, currentFileKey(lockFile)));
        if (existing == null) {
            LOGGER.warning("parked a probe channel on " + lockFile + " (same-JVM lock holder)");
        } else {
            graveyard.add(channel);
            LOGGER.warning("graveyarded a probe channel on " + lockFile + " (lost the park race)");
        }
    }

    private static final class ParkedChannel {
        private final FileChannel channel;
        private final @Nullable Object fileKey;

        ParkedChannel(FileChannel channel, @Nullable Object fileKey) {
            this.channel = channel;
            this.fileKey = fileKey;
        }
    }

    private static boolean isStaleParked(ParkedChannel parked, @Nullable Object currentFileKey) {
        return parked.fileKey != null && currentFileKey != null && !parked.fileKey.equals(currentFileKey);
    }

    private static @Nullable Object currentFileKey(Path lockFile) {
        try {
            return Files.readAttributes(lockFile, BasicFileAttributes.class).fileKey();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean probeThroughParked(FileChannel parked, Path lockFile) {
        try {
            FileLock lock = parked.tryLock();
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;
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

    static @Nullable FileChannel parkedChannelForTest(Path key) {
        ParkedChannel parked = parkedChannels.get(key);
        return parked == null ? null : parked.channel;
    }

    static boolean graveyardContainsForTest(FileChannel channel) {
        return graveyard.contains(channel);
    }

    /**
     * Whether any attempt under the saves directory's temporary root stages {@code folderName} in its aside or install
     * directory, child names compared case-insensitively. An unreadable scan reports false after one warning.
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

    static Outcome extractFailureOutcome(long usableBytes) {
        return usableBytes < DISK_FULL_FLOOR_BYTES ? Outcome.DISK_FULL : Outcome.EXTRACT_REFUSED;
    }

    private static long usableSpace(Path temporaryRoot) {
        try {
            return Files.getFileStore(temporaryRoot).getUsableSpace();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

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
            // Another entry still lives under the root.
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

    private static void releaseQuietly(@Nullable FileLock lock) {
        if (lock == null || !lock.isValid()) {
            return;
        }
        try {
            lock.release();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "lock release failed", e);
        }
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

    public static final class RestoreSweep {
        /** The memo staleness window: past it, {@code hasWork} re-evaluates the per-attempt blocker kinds. */
        public static final long TTL_MS = 5 * 60_000;

        static final int MAX_DISPATCHES = 8;

        private static final long HOUR_MS = 60L * 60_000;
        private static final String PART_PREFIX = "wdl-export-";
        private static final String PART_SUFFIX = ".part";
        private static final String PART_STATE_PREFIX = "part:";

        static LongSupplier clock = System::currentTimeMillis;

        private static final Runnable NO_RUN_START_BARRIER = () -> {};

        private static volatile Runnable runStartBarrier = NO_RUN_START_BARRIER;

        private static volatile @Nullable Memo memo;

        private static final Set<String> reportedMissing = Collections
                .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

        private RestoreSweep() {}

        public static void resetForTest() {
            memo = null;
            reportedMissing.clear();
            clock = System::currentTimeMillis;
            runStartBarrier = NO_RUN_START_BARRIER;
        }

        public static void runStartBarrierForTest(Runnable barrier) {
            runStartBarrier = barrier;
        }

        enum Kind {
            PROBE_SHAPED, IO_FAILURE, AGE_SHAPED
        }

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

            public boolean changedDisk() {
                return changedDisk;
            }

            public List<Path> movedBack() {
                return movedBack;
            }

            public List<Path> relocated() {
                return relocated;
            }

            public List<Path> missingDeferred() {
                return missingDeferred;
            }
        }

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
                    sweepLocklessAttempt(attempt, attemptNamePath.toString(), now, states, changed);
                    return;
                }
                FileLock lock = lockChannel.tryLock();
                if (lock == null) {
                    return;
                }
                processLocked(savesDirectory, attempt, attemptNamePath.toString(), prior, states,
                        movedBack, relocated, missingDeferred, changed, lockChannel);
            } catch (OverlappingFileLockException e) {
                // This JVM already holds the lock; skip it exactly as the null case does.
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
                // Today's layout creates attempt.lock before aside and install, so a lockless directory holds no world
                // copy; a foreign or future layout with an aside created before its lock could, and that aside/<folder>
                // is a real world copy. Account for it so hasWork does not re-dispatch every TTL.
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
                relocateAside(savesDirectory, attempt, folderName, asideChild, prior, attemptName, states,
                        relocated, changed, lockChannel);
                return;
            }
            try {
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
                    return true;
                }
                String folderName = attemptFolderName(attempt);
                boolean folderMissing = folderName == null || !Files.exists(savesDirectory.resolve(folderName));
                boolean withinBound = state.dispatches < MAX_DISPATCHES || folderMissing;
                if (state.kind == Kind.PROBE_SHAPED) {
                    Path aside = folderName == null ? null : attempt.resolve(ASIDE).resolve(folderName);
                    boolean nowLocked = aside != null && probeLocked(aside);
                    if (state.asideLocked && !nowLocked && withinBound) {
                        return true;
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
                        continue;
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
                return fallbackNow;
            }
        }

        private static boolean sweepMoveBack(Path source, Path target) throws IOException {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target);
            }
            return true;
        }

        private static void closeAndDeleteAttempt(FileChannel lockChannel, Path attempt) throws IOException {
            closeQuietly(lockChannel);
            deleteRecursively(attempt);
        }

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
