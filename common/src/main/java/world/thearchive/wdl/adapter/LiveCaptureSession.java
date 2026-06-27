// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.core.CaptureController;
import world.thearchive.wdl.core.CaptureCounts;
import world.thearchive.wdl.core.CaptureOrder;
import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.FlushPolicy;
import world.thearchive.wdl.core.SaveProgress;
import world.thearchive.wdl.core.SaveStage;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.platform.PlatformBridge;

/**
 * The live, MC-typed capture session behind the MC-free {@link CaptureController.Session} seam.
 *
 * <p>Bound to one {@link ClientLevel}. Each tick (client main thread) it snapshots each loaded chunk of that level
 * once, by walking the render-distance square around the player and reading the client chunk cache directly (no packet
 * Mixin). The codec reads the chunk's server-sent light from the client light engine, gated per chunk on the engine's
 * initial-light-applied bit, so a captured chunk skips vanilla's first-open relight; a chunk whose light has not yet
 * applied falls back to {@code lightCorrect=false} and vanilla relights just that chunk. The whole per-chunk snapshot
 * runs here on the main thread.
 *
 * <p>Captured chunk tags do not accumulate to the end: each tick the flush pump streams every tag that has moved out of
 * the keep-hot window around the player to a background {@link AsyncSaveWriter} and drops it from memory, so the
 * in-memory buffer stays bounded by a hot square no matter how far the capture roams. Only detached, immutable
 * snapshots cross to the writer thread. {@code finish()} drains the remaining buffer, writes level.dat, and reports the
 * saved world when the background drain completes; none of it on the render thread.
 */
public final class LiveCaptureSession implements CaptureController.Session {
    private static final Logger LOGGER = LogUtils.getLogger();

    // How many chunks past the render-distance capture square to keep buffered before flushing to disk.
    // Containers are opened far inside render distance, so any non-negative margin keeps merge-before-flush
    // correct; +2 just keeps a small band past the capture square so a chunk is not flushed the instant it
    // leaves it. The buffer is therefore bounded by the (renderDistance + this) square around the player,
    // independent of how far the capture roams.
    private static final int KEEP_HOT_MARGIN = 2;

    private final VersionAdapter adapter;
    private final PlatformBridge bridge;
    private final WdlConfig config;
    // The game-thread completion poke (CaptureController.tick), run when the background save completes so the
    // SAVING to IDLE transition lands even while the game tick is suspended.
    private final Runnable saveCompletePoke;
    // What this download targets: a fresh folder (NEW) or an existing wdl-managed one to add to (RESUME).
    private final DownloadTarget target;
    // The ClientLevel being captured. Null on a session built by the level-free constructor, so dereference it
    // only through level().
    private final @Nullable ClientLevel level;
    // The dimension this capture is laid out under, which is the level's own key: on a vanilla world that
    // is also the folder key, and a server that names its worlds itself lays out under its own name.
    private final ResourceKey<Level> targetDimension;
    // Connection-global (ClientLevel takes it from the packet listener, shared across a respawn's new level).
    private final RegistryAccess registries;

    /**
     * The bounded keep-hot working buffer: captured chunk snapshots not yet flushed to disk, keyed by position
     * (insertion order preserved). A chunk leaves here once it is farther than the keep-hot radius from the player and
     * is streamed to the writer (which runs the heavy encode), so this never grows with the area roamed. It holds
     * snapshots, not encoded tags, because the serialize is deferred to the writer thread.
     */
    private final Map<ChunkPos, ChunkSnapshotSource> captured = new LinkedHashMap<>();

    /**
     * Captured chunk positions (as {@link ChunkPos#toLong()}), retained for the whole session: it dedups re-captures
     * and survives a flush-and-drop of the chunk tag. Positions are tiny, so this stays small even when the chunk tags
     * themselves must stream to disk.
     */
    private final LongOpenHashSet allCaptured = new LongOpenHashSet();

    /**
     * The wall-clock deadline ({@link System#nanoTime}) for this tick's chunk capture: one per-tick time budget, so
     * loading a fresh render-distance square or flying fast spills across ticks instead of stuttering one frame. The
     * chunk passes only capture (snapshot) on this thread; the heavy serialize runs on the writer.
     * {@link Long#MAX_VALUE} means unbounded, the state between ticks and at finish (the finish drain must capture
     * every chunk still loaded).
     */
    private long encodeDeadlineNanos = Long.MAX_VALUE;

    /** Cached nearest-first capture offsets, rebuilt only when the render distance changes (a per-tick array). */
    private int @Nullable [] ringOffsetsCache;
    private int ringOffsetsRadius = -1;

    /** The background writer draining captured tags to disk; opened lazily on the first chunk to flush. */
    private @Nullable AsyncSaveWriter writer;

    /** The save's level.dat ({@code <save>/level.dat}); read on a resume to keep the world's existing name. */
    private @Nullable Path levelDatFile;

    /**
     * The live finalization phase and fraction, set on the writer thread as the save drains and read on the main
     * thread.
     */
    private final SaveProgress progress = new SaveProgress();

    /** The error from a failed attempt to open the world for writing; reported at {@link #finish()}. */
    private @Nullable Throwable startError;

    /** Whether the save outcome has been reported to the player (covers the nothing-captured/failure paths). */
    private boolean reported;

    /** The save-directory name (the target's folder, verbatim), used to open the world and report the save. */
    private final String saveName;

    /**
     * Chunks whose terrain snapshot threw, so the position reached neither the buffer nor the captured set and the
     * reopened world has none of that chunk's terrain, falling back to its own generator there (main thread). Deduped
     * by {@link #captureFailed}, since the same position is retried every tick it stays loaded.
     */
    private int chunksCaptureFailed;

    /**
     * The positions {@link #chunksCaptureFailed} has already counted: the square retries a failing position every tick
     * it stays loaded, so an undeduped tally would inflate without bound.
     */
    private final LongOpenHashSet captureFailed = new LongOpenHashSet();

    /**
     * Finish-time work the end-of-stream guard degraded to skipped (main thread). The degradation is deliberate, since
     * a finalized save beats an aborted one; what would not be is reporting the download that took it as clean.
     */
    private int finishStepsFailed;

    /**
     * The {@code saveCompletePoke} argument re-polls the controller once the save has completed, and does only that:
     * the session owns its own finalization. It must be idempotent against the tick polls that follow, and it must
     * tolerate running synchronously on the caller's stack, since a {@link #finish()} with nothing to write runs it
     * inline from inside {@link CaptureController#stop()}.
     */
    public LiveCaptureSession(VersionAdapter adapter, PlatformBridge bridge, WdlConfig config, ClientLevel level,
            DownloadTarget target, Runnable saveCompletePoke) {
        this(adapter, bridge, config, level, level.dimension(), level.registryAccess(), target, saveCompletePoke);
    }

    /**
     * The construction a headless test can drive: the values the session takes from its {@link ClientLevel} arrive
     * directly, so {@code level} may be null and dereferencing it then fails loudly (see {@link #level()}).
     * Package-private because a null level is a test-only state; production always builds through the public
     * constructor, which derives the same values from the level it binds.
     */
    LiveCaptureSession(VersionAdapter adapter, PlatformBridge bridge, WdlConfig config,
            @Nullable ClientLevel level, ResourceKey<Level> targetDimension, RegistryAccess registries,
            DownloadTarget target, Runnable saveCompletePoke) {
        this.adapter = adapter;
        this.bridge = bridge;
        this.config = config;
        this.target = target;
        this.saveCompletePoke = saveCompletePoke;
        this.saveName = target.folderName();
        this.level = level;
        this.targetDimension = targetDimension;
        this.registries = registries;
    }

    /**
     * The bound level. Never throws in production, where every session carries one; on a level-free session it turns
     * the first capture dereference into a loud failure instead of letting the tick rebind the session to whatever
     * dimension the client happens to be in.
     */
    private ClientLevel level() {
        ClientLevel bound = this.level;
        if (bound == null) {
            throw new IllegalStateException("a capture path ran on a session built without a level");
        }
        return bound;
    }

    /** The distinct chunk positions captured this session. */
    private int totalCapturedChunks() {
        return allCaptured.size();
    }

    /** Whether this tick's budget still has time left; gates each chunk snapshot. */
    private boolean hasEncodeBudget() {
        return System.nanoTime() < encodeDeadlineNanos;
    }

    /** The nearest-first capture offsets for {@code radius}, rebuilt only when the render distance changes. */
    private int[] ringOffsets(int radius) {
        int[] cached = ringOffsetsCache;
        if (cached == null || ringOffsetsRadius != radius) {
            cached = CaptureOrder.nearestFirstOffsets(radius);
            ringOffsetsCache = cached;
            ringOffsetsRadius = radius;
        }
        return cached;
    }

    /**
     * The entity the capture window and the keep-hot window both center on.
     *
     * <p>The camera entity is what the server centers chunk streaming on: it snaps its own player onto the camera each
     * tick and re-centers tracking there, while sending the owning client no position, so the client's own player
     * parks. In ordinary play this returns that same player, so nothing moves.
     *
     * <p>A removed camera entity is deliberately kept rather than replaced by the player. The client never clears it,
     * and the region the client retains has not moved either, so holding its last position is correct; falling back to
     * the player would reintroduce the defect for the length of the gap.
     *
     * <p>Client main thread only: it reads live MC state. It must never be reached from an overlay read path, which
     * runs on a background pool.
     */
    private Entity anchorEntity(Minecraft minecraft, LocalPlayer player) {
        Entity camera = minecraft.getCameraEntity();
        // The level guard is mod-compatibility armor, not a live case: vanilla resolves the camera entity in
        // the current level and nulls it on a level swap.
        return camera != null && camera.level() == this.level() ? camera : player;
    }

    /**
     * Where a player stands for the purpose of a position snapshot: a seated player resolves to its root vehicle's
     * block (a standing-height coordinate over the vehicle's own resting floor or water), a standing player to the
     * ordinary camera anchor. Keyed on {@code isPassenger()}, so a vehicle carrying more than one player still gets the
     * safe vehicle-block coordinate instead of the floored passenger-offset seat one. Pure and package-private so the
     * seated-versus-standing choice is headless-testable; {@link #anchorEntity} stays the live-only camera resolver.
     */
    static Entity captureAnchor(Entity player, Entity cameraAnchor) {
        return player.isPassenger() ? player.getRootVehicle() : cameraAnchor;
    }

    @Override
    public void captureTick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        @Nullable
        ChunkPos hotCenter = null;
        if (player != null && minecraft.level == level()) {
            hotCenter = anchorEntity(minecraft, player).chunkPosition();
            // One per-tick budget bounds the first-tick render-distance burst and the fast fly-over. A chunk the
            // budget does not reach spills to a later tick rather than stuttering this frame, which costs nothing
            // because terrain re-offers itself while it stays loaded.
            encodeDeadlineNanos = System.nanoTime() + config.encodeBudgetMillis() * 1_000_000L;
            captureLoadedChunks(minecraft, player, hotCenter);
        }
        // The flush pump runs regardless of the guard above: when capture pauses (the player is gone or in
        // another dimension) the bounded buffer must keep draining to disk instead of accumulating until
        // finish(), or the memory bound is defeated exactly in the roam-then-leave case.
        pumpFlush(hotCenter);
    }

    private void captureLoadedChunks(Minecraft minecraft, LocalPlayer player, ChunkPos anchor) {
        captureSquareAround(minecraft, anchor);
        ChunkPos playerChunk = player.chunkPosition();
        // One square is retained by ClientChunkCache at a time, so once the camera is more than
        // renderDistance + 3 chunks away the player square lies outside it and this pass returns null
        // everywhere, costing only the probes. While the two squares still overlap during the handoff it
        // captures at most a thin trailing ring, already covered by the superset guarantee that keeps this
        // change unable to see less than the old behavior did. Skipped entirely whenever the camera is the
        // player, which is every tick of ordinary play.
        if (!playerChunk.equals(anchor)) {
            captureSquareAround(minecraft, playerChunk);
        }
    }

    /** Snapshot each loaded chunk in the render-distance square that this session has not captured yet. */
    private void captureSquareAround(Minecraft minecraft, ChunkPos center) {
        int radius = minecraft.options.getEffectiveRenderDistance();
        ClientChunkCache chunkSource = level().getChunkSource();
        ChunkCodec codec = adapter.chunkCodec();

        // Nearest-to-player first (by Chebyshev ring), so when the encode budget spills the square across ticks
        // the visible area fills first and the lag never concentrates on one side.
        int[] offsets = ringOffsets(radius);
        for (int i = 0; i < offsets.length; i += 2) {
            ChunkPos pos = new ChunkPos(center.x + offsets[i], center.z + offsets[i + 1]);
            long posKey = pos.toLong();
            // The cheap in-memory check comes before getChunk, so a stationary player in a captured area never
            // pays a per-tick getChunk.
            if (allCaptured.contains(posKey)) {
                continue;
            }
            LevelChunk chunk = chunkSource.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
            if (chunk == null) {
                continue; // not a loaded chunk at this position (or an unloaded keep-hot margin chunk)
            }
            if (!hasEncodeBudget()) {
                break; // out of budget: the rest of the square (still uncaptured) spills to a later tick
            }
            ChunkSnapshotSource snapshot;
            try {
                snapshot = codec.capture(chunk, registries);
            } catch (RuntimeException e) {
                recordChunkCaptureLoss(pos, e);
                continue;
            }
            captured.put(pos, snapshot);
            allCaptured.add(posKey);
        }
    }

    /**
     * Record one chunk whose terrain snapshot threw: count it once for that position, however many ticks it re-throws
     * for. Neither membership guard above the snapshot call holds for such a position, since it enters neither the
     * keep-hot buffer nor the captured set, so the square retries it every tick it stays loaded and in range and an
     * undeduped tally would inflate without bound.
     *
     * <p>A count is never retracted, so a position whose snapshot threw once and succeeded on a later tick still reads
     * as one loss and reports the download partial. That is the safe direction on a path a healthy capture never takes
     * at all, and retracting would put a second set read on every first capture.
     *
     * <p>Package-private so the tally it feeds stays testable.
     */
    void recordChunkCaptureLoss(ChunkPos pos, Throwable cause) {
        if (captureFailed.add(pos.toLong())) {
            chunksCaptureFailed++;
            LOGGER.warn("failed to capture chunk {}; the reopened world has none of that chunk's terrain", pos, cause);
        }
    }

    @Override
    public CaptureCounts counts() {
        return new CaptureCounts(totalCapturedChunks(), 0, 0);
    }

    @Override
    public SaveStage saveStage() {
        return progress.stage();
    }

    @Override
    public float saveProgress() {
        return progress.fraction();
    }

    @Override
    public boolean isSaveComplete() {
        if (reported) {
            return true; // nothing captured, a setup failure, or the async result was already reported
        }
        if (writer == null) {
            return false; // finish() has not started the background write yet
        }
        AsyncSaveWriter.SaveResult result = writer.result().getNow(null);
        if (result == null) {
            return false; // the writer thread is still draining
        }
        try {
            report(result); // on the main thread (the controller polls here), so messaging the player is safe
        } finally {
            // Latched even if report throws: the controller re-polls every tick, and an unlatched throw would
            // re-report forever and strand the session in SAVING. Losing one message beats never leaving.
            reported = true;
        }
        return true;
    }

    private void pumpFlush(@Nullable ChunkPos hotCenter) {
        if (captured.isEmpty()) {
            return;
        }
        AsyncSaveWriter activeWriter = ensureWriter();
        if (activeWriter == null) {
            return; // the world could not be opened for writing; keep buffering and report at finish()
        }
        if (hotCenter == null) {
            flushBuffer(activeWriter, true, 0, 0, 0);
            return;
        }
        int keepHot = Minecraft.getInstance().options.getEffectiveRenderDistance() + KEEP_HOT_MARGIN;
        flushBuffer(activeWriter, false, hotCenter.x, hotCenter.z, keepHot);
    }

    @Override
    public void finish() {
        completeThroughWriter(Minecraft.getInstance(), this::finishCapture);
    }

    /**
     * The finish proper: the exits that write nothing, and the drain that hands the writer everything it still needs.
     * Leaves the end-of-stream signal to {@link #completeThroughWriter}, which owes it whether this returns or throws.
     */
    private void finishCapture() {
        // The finish drain must write everything still buffered, so the per-tick encode budget does not apply here.
        encodeDeadlineNanos = Long.MAX_VALUE;
        if (totalCapturedChunks() == 0) {
            if (chunksCaptureFailed > 0) {
                // This exit reports no tally, so without this a capture that threw on every chunk is
                // indistinguishable from one that found nothing worth capturing.
                LOGGER.warn("nothing reached the save and {} chunks were lost to a throwing snapshot; the "
                        + "download is empty because the capture failed, not because there was nothing there",
                        chunksCaptureFailed);
            }
            if (config.showChatMessages()) {
                bridge.sendChat(ChatCopy.nothingCaptured());
            }
            completeWithoutWriter();
            return;
        }
        AsyncSaveWriter activeWriter = ensureWriter();
        if (activeWriter == null) {
            reportSaveFailure(startError);
            completeWithoutWriter();
            return;
        }
        flushBuffer(activeWriter, true, 0, 0, 0); // drain whatever is still buffered
    }

    /**
     * Run {@code finishWork} and signal end-of-stream to any writer this session opened, however that work ends. A
     * writer exists from the first incremental flush onward, and until it takes the marker it sits blocked on its
     * queue: no level.dat, the world's session lock held by a daemon thread that never exits, and the future the
     * controller polls never completed, so the download neither finishes nor reports. That is worse than a reported
     * failure, since nothing surfaces and nothing can be retried. Two ways in, which is why this wraps the whole finish
     * rather than its tail: a throw anywhere in the work, and the nothing-captured exit, which a download reaches with
     * a writer already open behind it.
     *
     * <p>It signals the one end of stream, the finalizing one, on every path. A download that captured nothing reaches
     * it too, and the finalize then writes level.dat over the one a resumed folder already has. That is deliberate and
     * it is the better of two bad outcomes: vanilla renames the replaced file to level.dat_old rather than dropping it,
     * and lists a folder holding either, so the record is recoverable and the folder stays openable. Skipping the
     * finalize instead leaves a folder that wrote chunks with no level.dat at all, which vanilla's world list does not
     * show.
     *
     * <p>A throw counts as a degraded finish step, and that is what keeps a download whose remaining finish work never
     * reached the writer from reading clean. Both halves are needed: the save must reach a terminal state, and a save
     * that completes claiming a success it did not have is worse than the parked thread. A {@link Throwable} is caught
     * rather than a {@link RuntimeException} for the reason the writer's own drain catches one, that reaching the
     * terminal state matters more than which class of failure got in the way, and the signal is enqueued from a
     * {@code finally} so that a throw from the counting arm itself cannot reopen the hole this closes.
     *
     * <p>The counter is incremented before the marker is enqueued, so the writer-thread finalize reads it across the
     * queue's own happens-before edge. Package-private and taking the work as a thunk so the guarantee is
     * headless-testable on one session.
     */
    void completeThroughWriter(Executor mainThread, Runnable finishWork) {
        @Nullable
        Throwable failure = null;
        try {
            finishWork.run();
        } catch (Throwable e) {
            failure = e;
            finishStepsFailed++;
            LOGGER.error("the finish threw before it could tell the writer to finalize; the save is finalized "
                    + "with whatever already reached the writer and reports partial", e);
        } finally {
            AsyncSaveWriter activeWriter = writer;
            if (activeWriter != null) {
                // End-of-stream; the writer drains, finalizes, completes its future, and the poke then reaches the
                // game thread off the tick, so a suspended tick cannot strand the save.
                activeWriter.finish().whenComplete((result, error) -> mainThread.execute(saveCompletePoke));
            } else if (failure != null) {
                // No writer means nothing was written and no lock is held, but it also means no future for the
                // controller to poll, so this finish has to end itself or the download never leaves saving.
                reportSaveFailure(failure);
                completeWithoutWriter();
            }
        }
    }

    /**
     * Close out a {@link #finish()} that never started a background write (nothing was captured, or the world could not
     * be opened), once the caller has surfaced its own outcome.
     *
     * <p>The poke runs inline on the game thread and must follow {@code reported = true}: with no writer, the reported
     * short-circuit in {@link #isSaveComplete()} is the only completion it can observe.
     */
    private void completeWithoutWriter() {
        reported = true;
        saveCompletePoke.run();
    }

    /**
     * The {@code LevelName} to write into level.dat: a new download uses the target's resolved name, while a resume
     * preserves the existing world's name read from the prior level.dat (null when absent or unreadable, letting the
     * writer apply its default), so re-running into a folder never renames the world it already produced.
     */
    private @Nullable String resolveWorldName() {
        if (target.mode() == DownloadMode.RESUME) {
            return readPriorLevelName();
        }
        return target.worldName();
    }

    /** The prior level.dat's {@code Data.LevelName}, or null when it is absent or unreadable (fail-soft). */
    private @Nullable String readPriorLevelName() {
        try {
            CompoundTag data = readPriorData();
            if (data != null) {
                return data.getString("LevelName").orElse(null);
            }
        } catch (IOException | RuntimeException e) {
            // The level.dat is present but unreadable, so the resume cannot preserve the world's name and falls
            // back to the default. Surface it rather than renaming the world silently (the read can fail on a
            // transient file lock, which the Windows gate has shown is real).
            LOGGER.warn("failed to read the prior level.dat name on resume; using the default name", e);
            if (config.showChatMessages()) {
                bridge.sendChat(ChatCopy.worldNameFallback());
            }
        }
        return null;
    }

    /** The prior level.dat's {@code Data} compound, or null when absent; a present but unreadable file throws. */
    private @Nullable CompoundTag readPriorData() throws IOException {
        Path file = levelDatFile;
        if (file == null || !Files.exists(file)) {
            return null;
        }
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.uncompressedQuota());
        return root.get("Data") instanceof CompoundTag data ? data : null;
    }

    /**
     * Bind the writer a world open would have installed, standing in for the tail of {@link #ensureWriter}, which
     * cannot run headlessly because it resolves the level source through the client singleton. It has no production
     * caller and is not an alternative way to open a world: production opens one in {@code ensureWriter}, which also
     * takes the session lock this one knows nothing about.
     */
    void bindWorldOpen(AsyncSaveWriter bound) {
        this.writer = bound;
    }

    /**
     * Open the world for writing once and start the background {@link AsyncSaveWriter} (the writer owns the
     * {@link LevelStorageSource.LevelStorageAccess} session lock from here, and releases it when the save finishes).
     * Lazy so a never-captured session creates nothing; idempotent so the flush pump and finish() share one writer.
     * Returns null (and records {@link #startError}) if the world cannot be opened; that failure is logged once where
     * it is surfaced (reportSaveFailure at finish), not here, so a deferred open error is not dumped to the log twice.
     */
    private @Nullable AsyncSaveWriter ensureWriter() {
        if (writer != null) {
            return writer;
        }
        if (startError != null) {
            return null; // a prior open attempt failed; the failure is reported at finish()
        }
        Minecraft minecraft = Minecraft.getInstance();
        LevelStorageSource source = minecraft.getLevelSource();
        LevelStorageSource.LevelStorageAccess access;
        try {
            // Path containment: assert the resolved level root stays under the saves base before createAccess.
            // createAccess does no validation (validateAndCreateAccess only checks symlinks on this band) and it
            // creates the folder plus its session.lock, so the check must run first or an escape touches disk.
            // toRealPath canonicalizes the base so a symlinked saves directory cannot defeat the lexical check.
            Path savesBase = source.getBaseDir().toRealPath();
            Path resolved = savesBase.resolve(saveName).normalize();
            // A download folder is a single component directly under saves; requiring the parent to be exactly
            // the saves base rejects both a parent-escape and any multi-component name (whose first segment could
            // be a symlink the lexical normalize cannot see).
            if (!resolved.startsWith(savesBase) || !savesBase.equals(resolved.getParent())) {
                throw new IOException("the download folder escapes the saves directory: " + saveName);
            }
            access = source.createAccess(saveName);
        } catch (IOException | RuntimeException e) {
            startError = e;
            return null;
        }
        try {
            // level.dat: a superflat VOID world derived from the client reg. The version-coupled saveDataTag
            // call lives behind LevelDataWriter.save() (its vanilla signature drifts across bands: it drops
            // RegistryAccess at 26.1.2), so this shared session stays cherry-pickable. Built here on the main
            // thread; the writer thread only writes the finished data.
            Path saveRoot = access.getDimensionPath(Level.OVERWORLD);
            WorldPaths paths = adapter.worldPaths(saveRoot);
            this.levelDatFile = saveRoot.resolve("level.dat"); // read on a resume to keep the world's name
            LevelDataWriter levelDataWriter = adapter.levelDataWriter();
            LevelDataWriter.LevelData levelData = levelDataWriter.buildLevelData(registries, resolveWorldName());
            writer = new AsyncSaveWriter(
                    dimension -> new SimpleRegionStorage(paths.regionStorageInfo(dimension),
                            paths.regionDirectory(dimension), DataFixers.getDataFixer(), false, DataFixTypes.CHUNK),
                    dimension -> new SimpleRegionStorage(paths.entitiesStorageInfo(dimension),
                            paths.entitiesDirectory(dimension), DataFixers.getDataFixer(), false,
                            DataFixTypes.ENTITY_CHUNK),
                    () -> {},
                    (chunksFailed, entityChunksFailed) -> levelDataWriter.save(access, levelData),
                    () -> null,
                    access, progress);
            return writer;
        } catch (RuntimeException e) {
            closeQuietly(access); // never handed to a writer, so release the session lock here
            startError = e;
            return null;
        }
    }

    /**
     * Hand every eligible buffered chunk to the writer and drop it from the buffer. With {@code all} the whole buffer
     * is flushed (the finish-time drain and the capture-paused drain); otherwise only chunks farther than
     * {@code keepHot} from the center. Package-private so the submit its thunks feed stays testable; every production
     * caller is in this class and reaches it behind the client.
     */
    void flushBuffer(AsyncSaveWriter activeWriter, boolean all, int centerX, int centerZ, int keepHot) {
        ChunkCodec codec = adapter.chunkCodec();
        Iterator<Map.Entry<ChunkPos, ChunkSnapshotSource>> entries = captured.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<ChunkPos, ChunkSnapshotSource> entry = entries.next();
            ChunkPos pos = entry.getKey();
            if (!all && !FlushPolicy.shouldFlush(pos.x, pos.z, centerX, centerZ, keepHot)) {
                continue;
            }
            ChunkSnapshotSource snapshot = entry.getValue();
            // Defer the heavy serialize to the writer thread: the thunk closes over the detached snapshot, the
            // per-band codec and the frozen registries, all immutable, so the render thread never runs
            // SerializableChunkData.write. The target dimension is read here on main, at submit time.
            activeWriter.submitChunk(targetDimension, pos,
                    () -> codec.encode(snapshot, registries, false),
                    // Terrain is read whole on every capture, so a copy a resumed folder already holds says
                    // nothing this snapshot does not, and the fresh one stands alone.
                    (onDisk, fresh) -> 0);
            entries.remove();
        }
    }

    /**
     * Whether this download lost anything, over the writer's own tallies and this session's. A write that missed disk
     * and a snapshot that threw before one was even offered are the same event from the save's point of view, and a
     * download reporting either as clean would be asserting something untrue about what reached disk. Package-private
     * so the verdict stays testable.
     *
     * <p>The two tallies accrue on different threads: the writer's arrive as arguments across its completed future or
     * its finalize call, and the session's are main-thread. A reader added outside those edges must supply its own
     * ordering.
     */
    boolean isPartialSave(int chunksFailed, int entityChunksFailed) {
        return failedWriteCount(chunksFailed, entityChunksFailed) > 0;
    }

    private int failedWriteCount(int chunksFailed, int entityChunksFailed) {
        return chunksFailed + entityChunksFailed + chunksCaptureFailed + finishStepsFailed;
    }

    /** Report the background save's outcome to the player (called on the main thread, once, when it completes). */
    private void report(AsyncSaveWriter.SaveResult result) {
        if (result.failed()) {
            reportSaveFailure(result.error());
            return;
        }
        // The chat figure is the distinct captured-chunk total rather than the writer's write tally, which
        // counts a position twice when a resume rewrites one this session also captured.
        CaptureCounts counts = counts();
        if (config.showChatMessages()) {
            bridge.sendChat(ChatCopy.downloaded(saveName, counts.chunks(), counts.entities(), counts.containers(),
                    Wdl.elapsedMillis()));
            Path saveFolder = Minecraft.getInstance().getLevelSource().getBaseDir().resolve(saveName)
                    .toAbsolutePath();
            bridge.sendChat(ChatCopy.savedTo(saveName, saveFolder.toString()));
        }
        LOGGER.info("saved {} chunks ({} new, {} re-captured, {} failed), {} entity-chunks ({} failed) to {}",
                result.chunksWritten(), result.chunksNew(), result.chunksRecaptured(), result.chunksFailed(),
                result.entityChunksWritten(), result.entityChunksFailed(), saveName);
        // Every term of the partial-finish sum, so a reader can add them up and reach the verdict the completion
        // surfaces report. Logged even when each is zero: a reader who cannot tell "nothing else was lost" from
        // "this build had no such line" cannot check the clean verdict at all.
        LOGGER.info("counted capture losses for {}: {} chunk writes, {} entity-chunk writes, {} chunk captures, "
                + "{} finish steps", saveName, result.chunksFailed(), result.entityChunksFailed(),
                chunksCaptureFailed, finishStepsFailed);
    }

    /** Surface a failed save in the log, which is where the cause a player can act on lives. */
    private void reportSaveFailure(@Nullable Throwable error) {
        LOGGER.error("save failed", error);
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            LOGGER.warn("failed to close the world save access", e);
        }
    }
}
