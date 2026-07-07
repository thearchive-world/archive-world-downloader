// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.AABB;
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
import world.thearchive.wdl.core.RecaptureMode;
import world.thearchive.wdl.core.RecapturePolicy;
import world.thearchive.wdl.core.SaveProgress;
import world.thearchive.wdl.core.SaveStage;
import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.SendRangeSampler;
import world.thearchive.wdl.core.VoidChunkPolicy;
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

    // Re-capture: the immediate edit zone is the (2*radius+1) square around the player, re-encoded
    // unconditionally so the player's own nearby edits (including block-entity-data edits like sign text,
    // which never flip a chunk's unsaved flag) stay current. Radius 1 is the 3x3 surroundings.
    private static final int EDIT_ZONE_RADIUS = 1;
    // The edit zone runs on a coarse cadence (once per second) rather than every tick: a per-encode cost
    // measurement showed an every-tick 3x3 is a continuous main-thread cost out of step with the "few-second
    // latency is immaterial" premise, so the immediate-area freshness guarantee is instead carried by a
    // save-time re-capture burst, and this cadence only lowers in-session latency for the player's vicinity.
    private static final int EDIT_ZONE_PERIOD_TICKS = 20;
    private static final int TICKS_PER_SECOND = 20;

    private final VersionAdapter adapter;
    private final PlatformBridge bridge;
    private final WdlConfig config;
    // The game-thread completion poke (CaptureController.tick), run when the background save completes so the
    // SAVING to IDLE transition lands even while the game tick is suspended.
    private final Runnable saveCompletePoke;
    // The live send-range estimator (owned by the controller): the per-dimension running max over the three
    // sampler-gated feeds, the packet capture's arrivals and removals and the seed sweep in captureTick.
    private final SendRangeEstimator sendRange;
    // The previous tick's player position, the displacement baseline the sampler's speed gate reads at gate-arm.
    // Invalid at construction and after a dimension rebind, so the first guarded tick (and the first after a
    // cross-dimension jump) arms with displacement 0 instead of a spurious fast tick.
    private double lastTickPlayerX;
    private double lastTickPlayerZ;
    private boolean tickBaselineValid;
    // What this download targets: a fresh folder (NEW) or an existing wdl-managed one to add to (RESUME).
    private final DownloadTarget target;
    // The ClientLevel being captured. Null on a session built by the level-free constructor, so dereference it
    // only through level(). Non-final: rebound when the player follows a portal into another dimension.
    private @Nullable ClientLevel level;
    // The vanilla single-player dimension this capture is laid out under, chosen by the captured
    // dimension's TYPE so non-standard server level keys (e.g. Multiverse's minecraft:worlds/2b2t/2b2t_1)
    // still write to the vanilla ./region / DIM-1 / DIM1 folders, not a nested dimensions/ns/path one.
    // Non-final: rebound on a dimension change to lay the new dimension out under its own folder.
    private ResourceKey<Level> targetDimension;
    // The server's OWN key for the dimension being captured, as the id string the packet-side per-dimension
    // stores share, which on a Multiverse/Paper server is not the vanilla-mapped targetDimension above. It is
    // the identity the inbound tee stamps each held entity with, so the promote gate compares a held frame
    // against the world it was announced in rather than against a position set from another world. Rebound on
    // a dimension change, like targetDimension.
    private String liveDimensionId;
    // Connection-global (ClientLevel takes it from the packet listener, shared across a respawn's new level).
    private final RegistryAccess registries;

    /**
     * What a dimension change does to every store whose contents belong to one dimension. The stores register with it
     * at construction and it owns the order they are drained, swapped, and cleared in.
     */
    private final DimensionRebind<ResourceKey<Level>> dimensionRebind = new DimensionRebind<>();

    /**
     * The bounded keep-hot working buffer: captured chunk snapshots not yet flushed to disk, keyed by position
     * (insertion order preserved). A chunk leaves here once it is farther than the keep-hot radius from the player and
     * is streamed to the writer (which runs the heavy encode), so this never grows with the area roamed. It holds
     * snapshots, not encoded tags, because the serialize is deferred to the writer thread.
     */
    private final Map<ChunkPos, ChunkSnapshotSource> captured = new LinkedHashMap<>();

    /**
     * Captured chunk positions per dimension (as {@link ChunkPos#toLong()}), retained for the whole session: the
     * position space is dimension-local (the overworld and the nether share positions), so following the player across
     * a portal must not let one dimension's captures dedup the other's. {@link #allCaptured} references the current
     * dimension's set; this map backs the per-dimension chunk count at finish.
     */
    private final Map<ResourceKey<Level>, LongOpenHashSet> capturedByDimension = new LinkedHashMap<>();

    /**
     * The current dimension's captured-position set (a reference into {@link #capturedByDimension}, swapped on a
     * dimension rebind): it dedups re-captures, backs the entity-chunk {@code ∩} terrain-chunk privacy gate, and
     * survives a flush-and-drop of the chunk tag. Positions are tiny, so this stays small even when the chunk tags
     * themselves must stream to disk.
     *
     * <p>The empty instance here is a placeholder that definite initialization needs and nothing reads: the
     * constructor's {@link DimensionRebind#bind} replaces it with the starting dimension's own set, which is also the
     * only place the swap is expressed, so the reset list cannot fall out of step with the stores.
     */
    private LongOpenHashSet allCaptured = new LongOpenHashSet();

    /**
     * Positions a block-STATE change marked unsaved since the last re-encode, pushed here by the
     * {@link LevelChunk.UnsavedListener} installed at first capture (change-driven, low-latency rung). A bounded slice
     * is drained and re-encoded each tick. Nulled at {@link #finish()} teardown so the listeners still attached to
     * loaded chunks become no-ops and stop pinning the finished session's set.
     */
    private @Nullable LongOpenHashSet dirty = new LongOpenHashSet();

    /**
     * The always-on round-robin floor's work queue over the hot buffer: refilled from {@link #captured}'s keys when
     * drained, so every hot chunk is re-encoded within the configured period. It is the only rung that catches
     * block-entity-data edits (sign/lectern/banner text), which never mark a chunk unsaved.
     */
    private final ArrayDeque<ChunkPos> floorQueue = new ArrayDeque<>();

    /** Positions first-captured on the current tick, so the edit zone does not redundantly re-encode them. */
    private final LongOpenHashSet capturedThisTick = new LongOpenHashSet();

    /** Client ticks elapsed while capturing, driving the edit zone's coarse cadence. */
    private long captureTicks;

    /**
     * The wall-clock deadline ({@link System#nanoTime}) for this tick's chunk capture and entity encode: one shared
     * per-tick time budget, taken by the entity pass's reserved half first and then by new capture and the re-capture
     * rungs, so loading a fresh render-distance square or flying fast spills across ticks instead of stuttering one
     * frame. The chunk passes only capture (snapshot) on this thread; the heavy serialize runs on the writer.
     * {@link Long#MAX_VALUE} means unbounded, the state between ticks and at finish (the finish drain must capture
     * every chunk and encode every entity still loaded). The field keeps its name for the unchanged
     * {@code encodeBudgetMillis} config key.
     */
    private long encodeDeadlineNanos = Long.MAX_VALUE;

    /** Cached nearest-first capture offsets, rebuilt only when the render distance changes (a per-tick array). */
    private int @Nullable [] ringOffsetsCache;
    private int ringOffsetsRadius = -1;

    /**
     * The per-tick entity accumulation buffer of already-serialized entity tags, keyed by UUID and drained by
     * flush-eligibility alongside the terrain so it stays bounded as the player roams. Fed by two sources: the packet
     * path ({@link #promotePacketEntities}, reconstructing every non-player entity held independent of unload) and the
     * prime poll ({@link #captureLoadedEntities}, back-filling entities already loaded when the download started).
     * Touched only on the main thread.
     */
    private final EntityBuffer entityBuffer = new EntityBuffer();

    /**
     * The inbound entity packet accumulator, or null when entity capture is off. Every non-player entity that streams
     * in or reloads after the download starts is captured from this, held independent of unload (the prime poll
     * {@link #captureLoadedEntities} back-fills those already loaded before it): the per-loader inbound tee feeds it
     * the spawn, synced-data, equipment, passenger, leash, and movement packets, and the main thread reconstructs and
     * writes each entity as its chunk leaves the keep-hot window. Published as the connection-scoped active capture so
     * the tee, which has no session reference, can reach it.
     */
    private final @Nullable EntityPacketCapture packetCapture;

    /** Which path is capturing an entity, so its write, skip, or drop is tallied to the right counter. */
    enum EntitySource {
        RECONSTRUCTED, PRIMED
    }

    /**
     * The capture source of each entity buffered but not yet submitted, so the write tally is taken at successful
     * submit, not at buffer time where a later flush throw or null envelope would over-report it. Keyed by the buffer's
     * UUID; an entry is recorded when an entity is buffered and consumed when its chunk flushes, either to disk
     * (counted written) or to a flush drop. Main-thread only, like the rest of capture.
     */
    private final Map<UUID, EntitySource> bufferedEntitySources = new HashMap<>();

    /**
     * The entities the sink refused at prime time and no prime has since saved, re-offered once at finish. Main-thread
     * only, like the rest of capture.
     */
    private final Set<UUID> primeRefusedEntities = new LinkedHashSet<>();

    /**
     * Every entity this session has put into a tag bound for disk, the passengers a vehicle's serialize nests included,
     * so nothing offers a second copy of one of them.
     */
    private final Set<UUID> savedEntities = new HashSet<>();

    /** Reconstructed root entities written this session, tallied at successful submit. */
    private int packetEntitiesWritten;

    /** Primed root entities (loaded before their AddEntity) written this session, tallied at successful submit. */
    private int primedEntitiesWritten;

    /** Held entities dropped at finish because their chunk's terrain was never captured, for the diagnostic. */
    private int droppedUncaptured;

    /**
     * Entities the download ended holding for a dimension other than the one it was bound to, and whose own dimension
     * had captured the terrain they stand on, so the privacy gate would have let them be written and nothing did.
     * Ordinarily the stream announced them after the packet that moved it into a world but before the next tick could
     * follow the player there, so the finish caught them one tick short of the rebind that would have written them; a
     * rebind drain that threw leaves its remainder here too.
     *
     * <p>Counted apart from {@link #droppedUncaptured} rather than folded into it, because that counter asserts the
     * privacy gate refused the entity. A held frame whose own dimension never captured that terrain IS such a refusal
     * and goes there instead; only the writable ones, and those whose dimension this download cannot resolve a position
     * set for at all, are counted here.
     */
    private int unboundDimensionDrops;

    /** Reconstructed entities saved nested in a vehicle (not a written root), for the finish reconciliation. */
    private int nestedPassengers;

    /** Reconstructed entities dropped because the typed entity could not be created. */
    private int createDrops;

    /** Reconstructed entities the sink refused: one of vanilla's own non-saves, so reported and not a loss. */
    private int reconstructSinkSkips;

    /** Reconstructed entities lost because their encode threw or its envelope came back malformed. */
    private int reconstructEncodeFailures;

    /** Reconstructed entities lost when their whole entity-chunk threw or nulled out during flush. */
    private int reconstructFlushDrops;

    /**
     * Entities an aborted finish-time packet drain left held in a chunk whose terrain was captured: received, never
     * written, and with no later pass that could reach them, so a loss rather than the reload churn the
     * reconciliation's residual would otherwise fold them into.
     */
    private int drainAbortDrops;

    /**
     * Primed entities the sink refused. An unresolvable leash never lands here (the sink strips it and saves the mob
     * unleashed), so a refusal means shouldBeSaved returned false (a live passenger saved nested under its vehicle, a
     * removed entity, or a player-only vehicle vanilla persists through the player) or save() returned false (a
     * non-serializable type: a leash knot, a bobber), which are the non-saves vanilla also skips. Reported so the drop
     * is visible; not a loss, and not part of the packet reconciliation residual (a primed entity has no spawn packet).
     */
    private int primeSinkSkips;

    /** Primed entities lost because their encode threw or its envelope came back malformed. */
    private int primeEncodeFailures;

    /** Primed entities lost when their whole entity-chunk threw or nulled out during flush; counted, not residual. */
    private int primeFlushDrops;

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
     * by {@link #captureFailedByDimension}, since the same position is retried every tick it stays loaded.
     */
    private int chunksCaptureFailed;

    /**
     * The positions {@link #chunksCaptureFailed} has already counted, per dimension: the square retries a failing
     * position every tick it stays loaded, so an undeduped tally would inflate without bound, and the position space is
     * dimension-local, so one dimension's failing position must not dedup another's. Held as a plain map rather than a
     * swapped current-dimension reference like {@link #allCaptured}, because nothing reads it per tick.
     */
    private final Map<ResourceKey<Level>, LongOpenHashSet> captureFailedByDimension = new LinkedHashMap<>();

    /** Entities lost to a whole-entity-chunk flush throw or a create failure, both paths (main thread). */
    private int structuralEntitiesLost;

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
     *
     * <p>{@code cameraDetachedAtStart} is the camera-versus-player state at the instant capture begins, the starting
     * edge the send-range sampler needs. It arrives as a value rather than being read here so that constructing a
     * session touches no client singleton at all.
     */
    public LiveCaptureSession(VersionAdapter adapter, PlatformBridge bridge, WdlConfig config, ClientLevel level,
            DownloadTarget target, SendRangeEstimator sendRange, boolean cameraDetachedAtStart,
            Runnable saveCompletePoke) {
        this(adapter, bridge, config, level,
                VanillaDimensions.forType(level.dimensionTypeRegistration().unwrapKey().orElse(null)),
                level.dimension(), level.registryAccess(), target, sendRange, cameraDetachedAtStart,
                saveCompletePoke);
    }

    /**
     * The construction a headless test can drive: the values the session takes from its {@link ClientLevel} arrive
     * directly, so {@code level} may be null and dereferencing it then fails loudly (see {@link #level()}).
     * Package-private because a null level is a test-only state; production always builds through the public
     * constructor, which derives the same values from the level it binds. {@code liveDimension} is the server's own key
     * for that level, which the vanilla-mapped {@code targetDimension} equals in every vanilla world and differs from
     * on a server that names its worlds itself.
     */
    LiveCaptureSession(VersionAdapter adapter, PlatformBridge bridge, WdlConfig config,
            @Nullable ClientLevel level, ResourceKey<Level> targetDimension, ResourceKey<Level> liveDimension,
            RegistryAccess registries, DownloadTarget target, SendRangeEstimator sendRange,
            boolean cameraDetachedAtStart, Runnable saveCompletePoke) {
        this.adapter = adapter;
        this.bridge = bridge;
        this.config = config;
        this.target = target;
        this.sendRange = sendRange;
        this.saveCompletePoke = saveCompletePoke;
        this.saveName = target.folderName();
        this.level = level;
        this.targetDimension = targetDimension;
        this.liveDimensionId = liveDimension.identifier().toString();
        this.registries = registries;
        registerDimensionScopedStores();
        dimensionRebind.bind(targetDimension);
        // The packet accumulator is the authoritative source for every non-player entity; publish it
        // as the active capture so the connection-scoped inbound tee feeds it. Created only when entity capture is
        // on, so the tee no-ops otherwise and the accumulator never grows outside a capture.
        SendRangeSampler sampler = new SendRangeSampler(System::nanoTime, cameraDetachedAtStart);
        this.packetCapture = config.captureEntities()
                ? new EntityPacketCapture(config.dumpReceivedFrames(), sendRange, sampler, liveDimensionId)
                : null;
        if (this.packetCapture != null) {
            EntityPacketCapture.activate(this.packetCapture);
        }
    }

    /**
     * Enroll every store whose contents belong to one dimension with {@link #dimensionRebind}, which then owns both
     * what a dimension change does to each and the order it does it in. A store enrolled here is one whose keys are
     * dimension-local, so the same key names a different thing in the next dimension.
     */
    private void registerDimensionScopedStores() {
        dimensionRebind.registerDrain(this::writeOutDimensionBeingLeft);
        dimensionRebind.registerSwap(dimension -> this.allCaptured = capturedFor(dimension));
        dimensionRebind.registerClear(capturedThisTick::clear);
        // Stale old-dimension positions; the new dimension refills the queue from its own buffer.
        dimensionRebind.registerClear(floorQueue::clear);
        // The cross-dimension jump is not a spurious fast tick; the Respawn already armed the suppression
        // window in-stream.
        dimensionRebind.registerClear(() -> tickBaselineValid = false);
    }

    /**
     * Follow the player into a new dimension: hand every dimension-scoped store to {@link #dimensionRebind}, whose
     * drain lands the old dimension's held entities and buffered chunks in its own folder before its swap retargets to
     * the new one. The writer target, the bound level and the live dimension key advance after that call, since the
     * drain writes through them and must reach the dimension being left. The registries are connection-global, so they
     * need no rebind. Capture resumes this same tick in the new dimension.
     */
    private void rebindDimension(ClientLevel newLevel) {
        ResourceKey<Level> newTarget = VanillaDimensions
                .forType(newLevel.dimensionTypeRegistration().unwrapKey().orElse(null));
        dimensionRebind.rebind(newTarget);
        this.level = newLevel;
        this.targetDimension = newTarget;
        this.liveDimensionId = newLevel.dimension().identifier().toString();
    }

    /**
     * Write out the dimension being left: promote everything the accumulator still holds for it into the entity buffer,
     * then flush the whole buffer, which submits both under the writer target that still names it.
     *
     * <p>Both halves need the writer, and the promote needs it as much as the flush does: with no writer the flush is a
     * no-op, and a promote ahead of one would move the leaving dimension's entities into a buffer the next flush writes
     * under the entered dimension, which is the misfiling this whole path exists to prevent. Nothing is opened on
     * demand here, since a rebind before the first flush pump means the world could not be opened at all; what stays
     * held is then held for a dimension the session leaves behind, and the finish counts it.
     */
    private void writeOutDimensionBeingLeft() {
        AsyncSaveWriter activeWriter = this.writer;
        if (activeWriter == null) {
            return;
        }
        promoteHeldEntitiesLeavingDimension();
        flushBuffer(activeWriter, true, 0, 0, 0);
    }

    /**
     * Promote everything the packet accumulator still holds for the dimension being left, so it reaches that
     * dimension's entity storage through the buffer flush that follows.
     *
     * <p>Distance-blind and unbudgeted, because unlike the per-tick promote there is no later tick that can finish the
     * work: the next one reconstructs against another world and submits under another folder, so anything deferred here
     * is misfiled rather than delayed. The cost is one unbounded promote on the tick a player changes dimension, over
     * at most the entities inside the keep-hot window, on a tick that already flushes the whole buffer. What the
     * privacy gate refuses is still held rather than dropped, since this dimension's captured positions come back with
     * the player on a return trip, and the finish counts what no return trip reclaimed against those same positions.
     *
     * <p>Fail-soft for the same reason the finish drain is: a reconstruct that throws must not abort the rest of the
     * rebind and leave the session half-bound. What a throw leaves held is then held for a dimension the session is no
     * longer bound to, which the finish counts rather than silently drops.
     */
    private void promoteHeldEntitiesLeavingDimension() {
        try {
            promotePacketEntities(PromotePass.LEAVING_DIMENSION, 0, 0, 0);
        } catch (RuntimeException e) {
            LOGGER.warn("the packet entity drain for the dimension being left failed; saving what was "
                    + "reconstructed", e);
        }
    }

    /** The captured-position set for {@code dimension}, created empty on first use (the per-dimension dedup). */
    private LongOpenHashSet capturedFor(ResourceKey<Level> dimension) {
        return capturedByDimension.computeIfAbsent(dimension, key -> new LongOpenHashSet());
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

    /** Total captured chunks across every dimension followed this session (the live and headline count). */
    private int totalCapturedChunks() {
        int total = 0;
        for (LongOpenHashSet positions : capturedByDimension.values()) {
            total += positions.size();
        }
        return total;
    }

    /** Whether this tick's encode budget still has time left; gates each chunk/entity encode. */
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
        ClientLevel current = minecraft.level;
        if (player != null && current != null && current != level()) {
            rebindDimension(current); // follow the player across a portal; capture resumes below
        }
        @Nullable
        ChunkPos hotCenter = null;
        if (player != null && minecraft.level == level()) {
            hotCenter = anchorEntity(minecraft, player).chunkPosition();
            capturedThisTick.clear();
            // Gate-arm before anything samples this tick: the speed gate must see the teleport
            // displacement before the prime loop can seed against un-pruned far entities.
            EntityPacketCapture capture = this.packetCapture;
            if (capture != null) {
                double dx = player.getX() - lastTickPlayerX;
                double dz = player.getZ() - lastTickPlayerZ;
                double displacement = tickBaselineValid ? Math.sqrt(dx * dx + dz * dz) : 0.0;
                capture.sampler().gateArmTick(displacement,
                        minecraft.getCameraEntity() != player);
            }
            lastTickPlayerX = player.getX();
            lastTickPlayerZ = player.getZ();
            tickBaselineValid = true;
            int capChunks = Math.max(minecraft.options.getEffectiveRenderDistance(), 2);
            if (capture != null) {
                SendRangeSampler sampler = capture.sampler();
                int sweepGeneration = sampler.sweepBeginGeneration();
                if (sweepGeneration != 0) {
                    // The start-window (and every re-arm's) one-shot seed sweep: window-suppressed primes
                    // registered without sampling; replay the book against the current player position.
                    // Ids gone from the live level are Respawn-race orphans and never sample.
                    String dimensionId = level().dimension().identifier().toString();
                    int boundBlocks = capChunks * 16;
                    boolean aborted = false;
                    for (int id : sampler.sweepIds()) {
                        if (sampler.suppressed()) {
                            aborted = true; // a mid-sweep re-arm defers the rest; the generation stays owed
                            break;
                        }
                        if (level().getEntity(id) == null) {
                            continue;
                        }
                        int distanceBlocks = sampler.seedSample(id, player.getX(), player.getZ());
                        if (distanceBlocks != SendRangeSampler.NO_SAMPLE && distanceBlocks <= boundBlocks) {
                            sendRange.observe(dimensionId, distanceBlocks);
                        }
                    }
                    if (!aborted) {
                        sampler.sweepComplete(sweepGeneration);
                    }
                }
            }
            // One shared per-tick budget (bounds the first-tick render-distance burst and the
            // fast-fly-over: chunk capture plus the on-main entity encode), but the entity pass takes a reserved
            // half FIRST: the per-tick pass is the only place an entity is savable: once removed it is
            // RemovalReason.DISCARDED, which vanilla's save path refuses, so an unloaded entity cannot be caught
            // later, and a missed one is lost, whereas terrain re-captures while still loaded and spills
            // harmlessly across ticks. The reserve gives entities a floor a heavy new-terrain tick (fast flight
            // into fresh chunks) cannot starve; a sparse tick returns at once, so
            // the unused reserve flows to the absolute terrain deadline below. Running before terrain costs only
            // one tick of latency for an entity in a chunk first-captured this tick (the privacy gate keys on
            // allCaptured, which persists).
            long budgetNanos = config.encodeBudgetMillis() * 1_000_000L;
            long tickStartNanos = System.nanoTime();
            if (config.captureEntities()) {
                encodeDeadlineNanos = tickStartNanos + budgetNanos / 2;
                // Packet path: reconstruct and buffer every non-player entity whose chunk has left the keep-hot
                // window, so the flush pump below writes it this tick. Held in the accumulator until then, so a
                // budget spill defers it safely (unlike a poll, the entity cannot unload out from under the
                // deferral). Entities already loaded before the download began are back-filled once by the prime
                // poll in captureLoadedChunks.
                int keepHot = capChunks + KEEP_HOT_MARGIN;
                promotePacketEntities(PromotePass.KEEP_HOT, hotCenter.x, hotCenter.z, keepHot);
            }
            encodeDeadlineNanos = tickStartNanos + budgetNanos;
            captureLoadedChunks(minecraft, player, hotCenter);
            if (config.recaptureChunks().refreshesHotChunks()) {
                recaptureHotChunks(hotCenter);
            }
            captureTicks++;
        }
        // The flush pump runs regardless of the guard above: when capture pauses (the player is gone or in
        // another dimension) the bounded buffer must keep draining to disk instead of accumulating until
        // finish(), or the memory bound is defeated exactly in the roam-then-leave case.
        pumpFlush(hotCenter);
    }

    private void captureLoadedChunks(Minecraft minecraft, LocalPlayer player, ChunkPos anchor) {
        captureSquareAround(minecraft, player, anchor);
        ChunkPos playerChunk = player.chunkPosition();
        // One square is retained by ClientChunkCache at a time, so once the camera is more than
        // renderDistance + 3 chunks away the player square lies outside it and this pass returns null
        // everywhere, costing only the probes. While the two squares still overlap during the handoff it
        // captures at most a thin trailing ring, already covered by the superset guarantee that keeps this
        // change unable to see less than the old behavior did. Skipped entirely whenever the camera is the
        // player, which is every tick of ordinary play.
        if (!playerChunk.equals(anchor)) {
            captureSquareAround(minecraft, player, playerChunk);
        }
    }

    /**
     * Snapshot each loaded chunk in the render-distance square: a chunk not captured this session is a first capture,
     * and a chunk captured earlier and since flushed from the keep-hot buffer is re-buffered on revisit when the mode
     * overwrites revisited areas, so its terrain re-flushes current the next time it leaves the keep-hot window. A
     * still-hot chunk is left to the hot re-capture path.
     */
    private void captureSquareAround(Minecraft minecraft, LocalPlayer player, ChunkPos center) {
        int radius = minecraft.options.getEffectiveRenderDistance();
        int plausibleMaxBlocks = SendRangeSampler.plausibleMaxBlocks(radius);
        ClientChunkCache chunkSource = level().getChunkSource();
        ChunkCodec codec = adapter.chunkCodec();

        // Nearest-to-player first (by Chebyshev ring), so when the encode budget spills the square across ticks
        // the visible area fills first and the lag never concentrates on one side.
        RecaptureMode recaptureMode = config.recaptureChunks();
        int[] offsets = ringOffsets(radius);
        for (int i = 0; i < offsets.length; i += 2) {
            ChunkPos pos = new ChunkPos(center.x + offsets[i], center.z + offsets[i + 1]);
            long posKey = pos.toLong();
            // The cheap in-memory checks come before getChunk, so a stationary player in a captured area never
            // pays a per-tick getChunk: a still-hot chunk is left to the hot re-capture path, and a chunk captured
            // earlier and since flushed is re-buffered only on revisit, and only when the mode overwrites
            // revisited areas.
            if (captured.containsKey(pos)) {
                continue;
            }
            boolean revisit = allCaptured.contains(posKey);
            if (revisit && !recaptureMode.overwritesRevisitedChunks()) {
                continue;
            }
            LevelChunk chunk = chunkSource.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
            if (chunk == null) {
                continue; // not a loaded chunk at this position (or an unloaded keep-hot margin chunk)
            }
            if (!hasEncodeBudget()) {
                break; // out of budget: the rest of the square (still uncaptured) spills to a later tick
            }
            // The snapshot stands alone in its own try because it is the only statement here whose failure
            // loses the chunk: past the buffer insert the terrain is already committed to flush, so a throw
            // costs this chunk's entity prime or its re-capture arm instead, which is a different loss and a
            // different count.
            ChunkSnapshotSource snapshot;
            try {
                snapshot = codec.capture(chunk, registries);
            } catch (RuntimeException e) {
                recordChunkCaptureLoss(pos, e);
                continue;
            }
            captured.put(pos, snapshot);
            allCaptured.add(posKey);
            try {
                // back-fill entities loaded before their AddEntity could be teed
                captureLoadedEntities(pos, player, plausibleMaxBlocks);
                if (recaptureMode.refreshesHotChunks()) {
                    attachRecapture(chunk, pos);
                }
            } catch (RuntimeException e) {
                // Uncounted on purpose: what a throw here costs is the tail of this chunk's entity prime, whose
                // size this catch cannot see (the entities primed before it are buffered and will write), or the
                // re-capture arm, which costs freshness rather than data.
                LOGGER.warn("chunk {} was captured but its follow-up steps failed; entities that were already "
                        + "loaded when the download started may be missing from it", pos, e);
            }
        }
    }

    /**
     * Prime the entities of a freshly-captured loaded chunk, the entity analog of {@link #captureLoadedChunks}: encode
     * each non-player entity the packet accumulator does not already track, so an entity that was already in range when
     * the download started (its one-shot AddEntity fired before the inbound tee was feeding a capture) is still
     * written. The packet path owns everything that streams in or reloads afterward (tracked, and held independent of
     * unload), so this only back-fills the pre-existing set: it skips tracked and already-buffered entities, and skips
     * an entity straddling the border unless this is its home chunk. The live entity is encoded directly (it is loaded,
     * so no reconstruction is needed); its post-prime movement is not packet-tracked, so a pre-existing mob that then
     * wanders is saved at its prime-time position. The captured-chunk privacy gate holds by construction: this runs
     * only for a chunk just added to allCaptured.
     */
    private void captureLoadedEntities(ChunkPos pos, LocalPlayer player, int plausibleMaxBlocks) {
        EntityPacketCapture capture = this.packetCapture;
        if (capture == null) {
            return; // entity capture is off
        }
        // An entity resting on the topmost placeable block has its box bottom exactly where a build-height bound
        // stops, and the intersection test is strict, so such a bound misses it. Y carries nothing here anyway: the
        // result is narrowed to the chunk's own column below, and vanilla keys a saved entity by that column alone.
        AABB bounds = new AABB(pos.getMinBlockX(), DimensionType.WAY_BELOW_MIN_Y, pos.getMinBlockZ(),
                pos.getMaxBlockX() + 1, DimensionType.WAY_ABOVE_MAX_Y, pos.getMaxBlockZ() + 1);
        for (Entity entity : level().getEntitiesOfClass(Entity.class, bounds)) {
            // Under the default recapture config (EVERYWHERE) a revisited chunk re-primes once it leaves the
            // hot buffer, so returning through a portal to captured terrain re-seeds; only the OFF and NEARBY
            // modes skip revisits. The prime loop can also see client-side-only entities spawned by other
            // mods, a mod-compat over-claim edge with no vanilla instance, accepted.
            capture.primeSeed(entity, player.getX(), player.getZ(), plausibleMaxBlocks, liveDimensionId);
            if (entity instanceof Player || capture.tracks(entity.getId()) || !entity.chunkPosition().equals(pos)) {
                continue; // players are not saved as entities; a tracked entity is the packet path's; a straddling
                         // entity is buffered only by the chunk it sits in (so it is saved exactly once)
            }
            UUID uuid = entity.getUUID();
            if (entity.shouldBeSaved()) {
                primeRefusedEntities.remove(uuid);
            } else if (!savedEntities.contains(uuid)) {
                primeRefusedEntities.add(uuid);
            }
            if (entityBuffer.chunkOf(uuid) != null) {
                continue; // already buffered by an earlier prime or a packet promote; dedup
            }
            CompoundTag tag = encodeSingleEntity(entity, pos, EntitySource.PRIMED);
            if (tag != null) {
                entityBuffer.accumulate(uuid, pos, tag);
                bufferedEntitySources.put(uuid, EntitySource.PRIMED); // counted at submit, not here
                recordSaved(uuid, entity);
            }
        }
    }

    /**
     * Record what a tag bound for disk holds: the entity itself, and every passenger its serialize nests under it.
     */
    private void recordSaved(UUID uuid, Entity entity) {
        savedEntities.add(uuid);
        for (Entity passenger : entity.getIndirectPassengers()) {
            savedEntities.add(passenger.getUUID());
        }
    }

    /**
     * Re-offer at finish the entities {@link #captureLoadedEntities} was refused: the standalone write turns away a
     * vehicle carrying exactly one player, and the player-data record that would otherwise hold it is written only
     * while the player is still seated. Only the encode half runs, since the range sampler's seed returns early for a
     * vehicle and so never sampled these on the way in.
     *
     * <p>It must run before the finish-time packet drain. That drain empties the accumulator, so
     * {@link EntityPacketAccumulator#tracks} then answers false for everything it just promoted, and a second write of
     * one of those at a different chunk position is the one duplicate the entities region cannot reconcile, each chunk
     * being merged against its own on-disk copy alone.
     *
     * <p>A still-refused entity is skipped before the encode so the sink skip is not tallied twice. A recovered one
     * leaves its earlier prime-time skip standing, so that diagnostic over-counts; it is a log figure, not an input to
     * the download's verdict.
     */
    private void retryRefusedPrimes() {
        EntityPacketCapture capture = this.packetCapture;
        if (capture == null) {
            return;
        }
        for (UUID uuid : primeRefusedEntities) {
            // Per entity, matching the encode's own isolation: a modded shouldBeSaved can throw, and one that does
            // must not cost every refusal behind it in the iteration.
            try {
                Entity entity = level().getEntity(uuid);
                if (entity == null || !entity.shouldBeSaved() || capture.tracks(entity.getId())
                        || savedEntities.contains(uuid)) {
                    // Asked again here: one prime pass can reach a passenger before the vehicle whose tag nests it.
                    continue;
                }
                ChunkPos pos = entity.chunkPosition();
                if (!allCaptured.contains(pos.toLong())) {
                    continue; // the captured-chunk privacy gate, which the prime got from the chunk it ran for
                }
                if (entityBuffer.chunkOf(uuid) != null) {
                    continue;
                }
                CompoundTag tag = encodeSingleEntity(entity, pos, EntitySource.PRIMED);
                if (tag != null) {
                    entityBuffer.accumulate(uuid, pos, tag);
                    bufferedEntitySources.put(uuid, EntitySource.PRIMED);
                    recordSaved(uuid, entity);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("skipping the refused-entity retry for {}; it may be missing from the save", uuid, e);
            }
        }
        primeRefusedEntities.clear();
    }

    /**
     * Clear-then-attach: clear the chunk's construction-time {@code unsaved=true} FIRST, THEN install the listener, so
     * the immediate-fire on a still-unsaved chunk does not flag every first-captured chunk dirty. After this the
     * listener fires only on a real post-capture block-STATE change. The lambda guards on {@link #dirty} being non-null
     * so it is inert after {@link #finish()} teardown.
     */
    private void attachRecapture(LevelChunk chunk, ChunkPos pos) {
        capturedThisTick.add(pos.toLong());
        chunk.tryMarkSaved();
        chunk.setUnsavedListener(changed -> {
            LongOpenHashSet dirtySet = dirty;
            if (dirtySet != null) {
                dirtySet.add(changed.toLong());
            }
        });
    }

    /**
     * Keep the chunks near the player current as they change while recording, by re-encoding their buffered tags in
     * place. Three rungs run together: the always-fresh edit zone (the player's own nearby edits, on a coarse cadence),
     * a bounded drain of the change-driven dirty set (low-latency block-state changes), and the always-on round-robin
     * floor (the mandatory backstop for block-entity-data edits the change-driven rung structurally cannot see). Each
     * only ever replaces a still-hot chunk's buffered tag: it never touches {@link #allCaptured}, never writes, and
     * never revives a flushed chunk.
     */
    private void recaptureHotChunks(ChunkPos anchor) {
        LongOpenHashSet dirtySet = dirty;
        if (dirtySet == null) {
            return; // torn down (defensive: captureTick stops before this once finish() runs)
        }
        ClientChunkCache chunkSource = level().getChunkSource();
        ChunkCodec codec = adapter.chunkCodec();
        LongOpenHashSet reencodedThisTick = new LongOpenHashSet();

        if (captureTicks % EDIT_ZONE_PERIOD_TICKS == 0) {
            recaptureEditZone(anchor, codec, chunkSource, reencodedThisTick);
        }
        int slice = RecapturePolicy.floorSliceSize(captured.size(), config.recaptureSeconds(), TICKS_PER_SECOND);
        drainDirtySlice(dirtySet, slice, codec, chunkSource, reencodedThisTick);
        runFloorSlice(slice, codec, chunkSource, reencodedThisTick);
    }

    /**
     * Re-encode the edit zone around {@code center} unconditionally (not dirty-gated): a block-entity-data edit such as
     * sign text sets no dirty signal, so the edit zone is the load-bearing catcher for the player's own nearby
     * block-entity-data edits. Iterates the keep-hot buffer (bounded by the keep-hot square) and picks its edit-zone
     * members; replacing a buffered value never structurally modifies the key set, so iterating it directly is safe.
     */
    private void recaptureEditZone(ChunkPos center, ChunkCodec codec, ClientChunkCache chunkSource,
            LongOpenHashSet reencodedThisTick) {
        for (ChunkPos pos : captured.keySet()) {
            if (!RecapturePolicy.isInEditZone(pos.x, pos.z, center.x, center.z, EDIT_ZONE_RADIUS)) {
                continue;
            }
            if (!hasEncodeBudget()) {
                return; // the shared per-tick encode budget is spent; finish runs it unbounded
            }
            reencode(pos, codec, chunkSource, reencodedThisTick);
        }
    }

    /** Re-encode up to {@code slice} of the change-driven dirty positions; the rest wait for later ticks. */
    private void drainDirtySlice(LongOpenHashSet dirtySet, int slice, ChunkCodec codec,
            ClientChunkCache chunkSource, LongOpenHashSet reencodedThisTick) {
        if (slice <= 0 || dirtySet.isEmpty()) {
            return;
        }
        int batchSize = Math.min(slice, dirtySet.size());
        long[] batch = new long[batchSize]; // snapshot first: reencode removes from dirtySet as it goes
        LongIterator keys = dirtySet.iterator();
        for (int i = 0; i < batchSize; i++) {
            batch[i] = keys.nextLong();
        }
        for (long key : batch) {
            if (!hasEncodeBudget()) {
                return; // the shared per-tick encode budget is spent
            }
            reencode(new ChunkPos(key), codec, chunkSource, reencodedThisTick);
        }
    }

    /**
     * Re-encode up to {@code slice} hot chunks blindly, advancing the round-robin cursor and refilling it from the
     * current buffer when drained, so the whole hot set is refreshed within the configured period. Budget is per cursor
     * step, not per encode, so a step that skips an already-handled or departed chunk still bounds the work; stale
     * entries for flushed chunks are dropped as the cursor passes them.
     */
    private void runFloorSlice(int slice, ChunkCodec codec, ClientChunkCache chunkSource,
            LongOpenHashSet reencodedThisTick) {
        for (int step = 0; step < slice; step++) {
            if (!hasEncodeBudget()) {
                return; // the shared per-tick encode budget is spent
            }
            if (floorQueue.isEmpty()) {
                floorQueue.addAll(captured.keySet());
                if (floorQueue.isEmpty()) {
                    return; // nothing buffered to refresh
                }
            }
            reencode(floorQueue.remove(), codec, chunkSource, reencodedThisTick);
        }
    }

    /**
     * Replace one still-hot chunk's buffered tag with a fresh encode of its current live state, then re-arm the dirty
     * listener. Skips a chunk already re-encoded this tick or first-captured this tick, and a candidate that is no
     * longer eligible (flushed, so never revived; or its live chunk has unloaded past the keep-hot margin, so the last
     * buffered snapshot stands). A throwing capture is logged and the prior buffered snapshot is kept, isolating the
     * failure to one chunk.
     */
    private void reencode(ChunkPos pos, ChunkCodec codec, ClientChunkCache chunkSource,
            LongOpenHashSet reencodedThisTick) {
        long key = pos.toLong();
        if (reencodedThisTick.contains(key) || capturedThisTick.contains(key)) {
            return;
        }
        LevelChunk chunk = chunkSource.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
        if (!RecapturePolicy.shouldRecapture(captured.containsKey(pos), chunk != null)) {
            dirtyRemove(key); // a flushed chunk's stale dirty entry is dropped so the set stays bounded
            return;
        }
        if (chunk == null) {
            return; // unreachable given shouldRecapture above; the explicit check narrows nullness
        }
        try {
            captured.put(pos, codec.capture(chunk, registries));
            reencodedThisTick.add(key);
            dirtyRemove(key);
            chunk.tryMarkSaved(); // re-arm the listener's false->true transition for the next change
        } catch (RuntimeException e) {
            LOGGER.warn("failed to re-capture chunk {}", pos, e);
        }
    }

    private void dirtyRemove(long key) {
        LongOpenHashSet dirtySet = dirty;
        if (dirtySet != null) {
            dirtySet.remove(key);
        }
    }

    /**
     * Detach the re-capture change tracking at session teardown: drop the dirty set so the
     * {@link LevelChunk.UnsavedListener}s still attached to loaded {@link ClientLevel} chunks become inert (they guard
     * on it being non-null) and stop pinning this finished session's set until those chunks unload. A later session
     * re-installs its own listeners at its own first capture.
     */
    private void detachRecapture() {
        dirty = null;
        floorQueue.clear();
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
        LongOpenHashSet counted = captureFailedByDimension.computeIfAbsent(targetDimension,
                dimension -> new LongOpenHashSet());
        if (counted.add(pos.toLong())) {
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
     * The finish proper: the save-time re-capture burst, the capture teardown, the exits that write nothing, and the
     * drain that hands the writer everything it still needs. Leaves the end-of-stream signal to
     * {@link #completeThroughWriter}, which owes it whether this returns or throws.
     */
    private void finishCapture() {
        // The finish drain must encode everything still loaded, so the per-tick encode budget does not apply
        // here (the burst below runs unbounded).
        encodeDeadlineNanos = Long.MAX_VALUE;
        // Save-time re-capture burst: refresh the player's immediate area one last time so a
        // just-placed block or edited sign is current at save, the freshness guarantee the coarse-cadence
        // edit zone leaves to here. Bounded to the edit zone (~9 chunks), a trivial main-thread cost. Run
        // before detaching so it sees the live state; skipped if the player is gone (a disconnect-flushed save).
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (config.recaptureChunks().refreshesHotChunks() && player != null && minecraft.level == level()) {
            capturedThisTick.clear(); // finish() is its own moment: the burst refreshes the area unconditionally
            ChunkPos anchor = anchorEntity(minecraft, player).chunkPosition();
            recaptureEditZone(anchor, adapter.chunkCodec(), level().getChunkSource(), new LongOpenHashSet());
        }
        detachRecapture(); // teardown: release the dirty set; loaded chunks' listeners become inert
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
        // Stop the inbound tee before the finish drain so no spawn arrives mid-drain to be left unwritten and
        // uncounted; the drain and the reconciliation then see a settled accumulator.
        deactivatePacketCapture();
        retryRefusedPrimes();
        // Drain the held packet accumulator: every non-player entity in a captured chunk, including the fly-past
        // tail that unloaded long ago and even after a disconnect-flush, at its last
        // known position. Fail-soft so a reconstruct throw never aborts before the level.dat finalize.
        boolean drainAborted = false;
        if (config.captureEntities()) {
            try {
                promotePacketEntities(PromotePass.FINISH, 0, 0, 0);
            } catch (RuntimeException e) {
                LOGGER.warn("the finish-time packet entity drain failed; saving what was reconstructed", e);
                drainAborted = true;
            }
        }
        flushBuffer(activeWriter, true, 0, 0, 0); // drain whatever is still buffered (chunks and entities)
        if (drainAborted) {
            // After the flush, not in the catch above: the void-chunk skip reads the accumulator as one of its
            // keep-this-chunk signals, so emptying it before the drain would let a chunk holding nothing but
            // abandoned entities be dropped as void, losing terrain the drain's failure had not itself lost.
            // Gated on the abort rather than run always, because a spawn the inbound tee was already routing
            // when the capture was deactivated can land after a successful drain, and that entity arrived past
            // the end of the download rather than being lost by it.
            countAbandonedFrames();
        }
        countUnboundDimensionFrames();
        reportEntityReconciliation(); // after the flush, so the at-submit write tally is complete
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
        deactivatePacketCapture();
        saveCompletePoke.run();
    }

    /**
     * Deactivate the packet capture so the connection-scoped inbound tee stops feeding it (a later session activates
     * its own). Called before the finish drain so no spawn arrives mid-drain to be left unwritten and uncounted, and on
     * the nothing-captured / save-failed paths that never drain. Idempotent.
     */
    private void deactivatePacketCapture() {
        EntityPacketCapture capture = this.packetCapture;
        if (capture != null) {
            EntityPacketCapture.deactivate(capture);
        }
    }

    /**
     * Classify the frames an aborted finish drain left held, so the reconciliation counts them instead of folding them
     * into the residual it reports as reload churn. A held entity whose chunk was captured is a real loss: it was
     * received, nothing will write it now, and the finish is the last pass. One whose chunk was not captured is the
     * ordinary privacy-gate drop a completed drain would also have made. The accumulator is drained either way, so the
     * same frames cannot inflate that residual as well.
     *
     * <p>Runs after the finish flush and only when the drain aborted, both for reasons its call site states. Whatever
     * the accumulator still holds at that point is what the abort abandoned.
     *
     * <p>What this cannot see is the chunk that was mid-promotion when the throw came: its frames left the accumulator
     * before the throw, so whatever of them had not yet reached the buffer is lost outside this count, the one residue
     * of counting an abort from the outside.
     *
     * <p>Package-private so the tally it feeds stays testable.
     */
    void countAbandonedFrames() {
        EntityPacketCapture capture = this.packetCapture;
        if (capture == null) {
            return;
        }
        LongIterator chunkKeys = capture.chunks(liveDimensionId).iterator();
        while (chunkKeys.hasNext()) {
            long chunkKey = chunkKeys.nextLong();
            int held = abandonedCount(capture.dropChunk(liveDimensionId, chunkKey));
            if (allCaptured.contains(chunkKey)) {
                drainAbortDrops += held;
            } else {
                droppedUncaptured += held;
            }
        }
    }

    /**
     * Drain and count what the accumulator still holds for a dimension other than the bound one, so the finish accounts
     * for it instead of leaving it to inflate the residual its own line calls reload churn. The ordinary source is the
     * tick of a dimension change: the inbound stream stamps arriving entities with the world its dimension marker last
     * named, so entities of the world the player just entered can already be held when a stop lands before the rebind
     * that would follow them there.
     *
     * <p>Settled one dimension at a time, because the question that decides the bucket is per dimension: did THAT
     * dimension capture the terrain the entity stands on. Where the answer is no, this is the privacy gate refusing the
     * entity exactly as it would have in its own dimension, so it is the benign drop and nothing about it is a loss the
     * user should be told about. Where the answer is yes, or where the dimension's own position set cannot be reached
     * at all, the entity was writable and nothing wrote it, which is the loss this counter reports.
     *
     * <p>Runs after the inbound tee is deactivated and before the reconciliation, which is the whole of its ordering
     * constraint: a spawn arriving between the finish drain and that deactivation would otherwise be drained by neither
     * sweep and counted by nothing. Unlike the abandoned-frame sweep beside it, it cannot disturb the void-chunk skip
     * whatever its position: that skip reads the bound dimension's chunks and this drains only other dimensions'.
     * Package-private so the tally it feeds stays testable.
     */
    void countUnboundDimensionFrames() {
        EntityPacketCapture capture = this.packetCapture;
        if (capture == null) {
            return;
        }
        int unwritable = 0;
        for (String dimensionId : capture.heldDimensions()) {
            if (dimensionId.equals(liveDimensionId)) {
                continue; // the bound dimension is the finish drain's, and it has already run
            }
            LongOpenHashSet captured = capturedPositionsOf(dimensionId);
            LongIterator chunkKeys = capture.chunks(dimensionId).iterator();
            while (chunkKeys.hasNext()) {
                long chunkKey = chunkKeys.nextLong();
                int held = abandonedCount(capture.dropChunk(dimensionId, chunkKey));
                if (captured != null && !captured.contains(chunkKey)) {
                    droppedUncaptured += held;
                } else {
                    unwritable += held;
                }
            }
        }
        if (unwritable == 0) {
            return;
        }
        unboundDimensionDrops += unwritable;
        LOGGER.warn("{} entities the download received stood on terrain it did capture, in a dimension other than "
                + "the one it ended bound to, and nothing wrote them: ordinarily they arrived after the world "
                + "changed and before the download followed the player there, and a drain of the world it left "
                + "that failed leaves its remainder here too", unwritable);
    }

    /**
     * The captured positions of the dimension a held frame names, or null when this download cannot answer for it. The
     * stamp is the server's own level key, so it resolves for the three keys a capture lays out under and for nothing
     * else: a server that names its worlds itself yields an id no folder of ours corresponds to, and there the privacy
     * gate's question has no answer rather than the answer no. A dimension that resolves but was never bound captured
     * nothing, which is an empty set rather than an absent one.
     */
    private @Nullable LongOpenHashSet capturedPositionsOf(String dimensionId) {
        ResourceKey<Level> target = VanillaDimensions.forId(dimensionId);
        if (target == null) {
            return null;
        }
        LongOpenHashSet positions = capturedByDimension.get(target);
        return positions != null ? positions : new LongOpenHashSet();
    }

    private int abandonedCount(List<? extends PacketEntity<?, ?, ?>> drained) {
        return drained.size();
    }

    /**
     * Reconcile and log the entity packet capture, after the buffer has flushed so the write tally is what reached the
     * writer. Logs the full breakdown (written roots, nested passengers, every counted drop) plus the
     * received-minus-accounted residual, which is reload / id-reuse churn, not a loss, so it is reported, not alarmed.
     * The WARN fires only on a structural loss (an entity-chunk flush loss, a create failure, a throwing single encode,
     * an aborted finish drain, or a remainder held for another dimension), near-zero in a healthy capture. The prime
     * path is reported separately because a primed entity has no spawn packet to reconcile against. Package-private so
     * the reconciliation it composes stays testable.
     */
    void reportEntityReconciliation() {
        EntityPacketCapture capture = this.packetCapture;
        if (capture == null) {
            return;
        }
        EntityReconciliation reconciliation = new EntityReconciliation(capture.spawnCount(), packetEntitiesWritten,
                nestedPassengers, droppedUncaptured, reconstructSinkSkips, createDrops, reconstructEncodeFailures,
                reconstructFlushDrops, drainAbortDrops, unboundDimensionDrops, primeEncodeFailures,
                primeFlushDrops);
        LOGGER.info("entity capture: received {} spawn packets; wrote {} reconstructed roots (+{} nested "
                + "passengers); dropped {} uncaptured + {} sink-refused + {} create-fail + {} encode-fail + {} "
                + "flush-loss + {} abandoned by a failed drain + {} held for another dimension; primed {} "
                + "written ({} sink-refused, {} encode-fail, {} flush-loss); {} not written as a root "
                + "(reload/id-reuse churn, not loss); {} spawns skipped at the tracking ceiling",
                reconciliation.received(), reconciliation.reconstructedWritten(), reconciliation.nestedPassengers(),
                reconciliation.droppedUncaptured(), reconciliation.sinkSkips(), reconciliation.createDrops(),
                reconciliation.encodeFailures(), reconciliation.flushDrops(), reconciliation.abortDrops(),
                reconciliation.unboundDimensionDrops(), primedEntitiesWritten, primeSinkSkips, primeEncodeFailures,
                primeFlushDrops, reconciliation.unaccounted(), capture.droppedAtCapacity());
        if (reconciliation.hasStructuralLoss()) {
            structuralEntitiesLost = reconciliation.structuralLossCount();
            LOGGER.warn("entity capture: structural loss this download, {} entities in flush-failed chunks + {} "
                    + "create failures + {} failed encodes + {} abandoned by a failed drain + {} held for a "
                    + "dimension other than the one the download ended bound to (a healthy capture "
                    + "has none); a flush loss drops a whole entity-chunk",
                    reconciliation.flushDrops() + reconciliation.primedFlushDrops(), reconciliation.createDrops(),
                    reconciliation.encodeFailures() + reconciliation.primedEncodeFailures(),
                    reconciliation.abortDrops(), reconciliation.unboundDimensionDrops());
        }
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
            LevelDataWriter.LevelData levelData = levelDataWriter.buildLevelData(registries, config.worldOutput(),
                    resolveWorldName());
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
     * Hand every eligible buffered chunk to the writer and drop it from the buffer, then drain the entity buffer on the
     * same eligibility. With {@code all} the whole buffer is flushed (the finish-time drain and the capture-paused
     * drain); otherwise only chunks farther than {@code keepHot} from the center. Package-private so the submit its
     * thunks feed stays testable; every production caller is in this class and reaches it behind the client.
     */
    void flushBuffer(AsyncSaveWriter activeWriter, boolean all, int centerX, int centerZ, int keepHot) {
        ChunkCodec codec = adapter.chunkCodec();
        // skipVoidChunks (default off): omit a captured chunk that carries nothing worth saving. Honored under
        // every generator (a user choice); under DEFAULT/FLAT a dropped void position regenerates as terrain, an
        // accepted trade stated in the config copy, so this is deliberately not gated on the world type. Entity
        // presence is taken from both the buffered entities (promoted, awaiting flush) and the still-accumulated
        // ones (held in the packet capture, e.g. budget-deferred this tick), because dropping a chunk's
        // allCaptured position is lossless only when no entity belongs to it: allCaptured is the
        // entity privacy gate, so a void position may be removed but an entity-bearing one must not.
        boolean skipVoid = config.worldOutput().skipVoidChunks();
        Set<ChunkPos> bufferedEntityChunks = skipVoid ? entityBuffer.bufferedChunks() : Collections.emptySet();
        LongSet accumulatedEntityChunks = skipVoid && packetCapture != null ? packetCapture.chunks(liveDimensionId)
                : LongSets.EMPTY_SET;
        Iterator<Map.Entry<ChunkPos, ChunkSnapshotSource>> entries = captured.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<ChunkPos, ChunkSnapshotSource> entry = entries.next();
            ChunkPos pos = entry.getKey();
            if (!all && !FlushPolicy.shouldFlush(pos.x, pos.z, centerX, centerZ, keepHot)) {
                continue;
            }
            ChunkSnapshotSource snapshot = entry.getValue();
            if (skipVoid && isVoidChunk(pos, snapshot, bufferedEntityChunks, accumulatedEntityChunks)) {
                // Lossless: a VOID world regenerates this position as air identically, so dropping it (and its
                // allCaptured position) keeps the count and resume honest.
                allCaptured.remove(pos.toLong());
                entries.remove();
                continue;
            }
            // Defer the heavy serialize to the writer thread: the thunk closes over the detached snapshot, the
            // per-band codec and the frozen registries, all immutable, so the render thread never runs
            // SerializableChunkData.write. The target dimension is read here on main, at submit time.
            boolean synthesizeBlending = VanillaDimensions.shouldSynthesizeBlending(config.worldOutput().worldType(),
                    targetDimension);
            activeWriter.submitChunk(targetDimension, pos,
                    () -> codec.encode(snapshot, registries, synthesizeBlending),
                    ChunkMerge::merge);
            entries.remove();
        }
        flushEntityBuffer(activeWriter, all, centerX, centerZ, keepHot);
    }

    /**
     * Whether {@code pos}'s captured chunk is void (safe to omit): no non-air blocks, no block-entities, and no
     * captured entities, buffered or still accumulated. The decision is taken here, against the artifact about to be
     * written, so it cannot drift from what is thrown away.
     */
    private boolean isVoidChunk(ChunkPos pos, ChunkSnapshotSource snapshot, Set<ChunkPos> bufferedEntityChunks,
            LongSet accumulatedEntityChunks) {
        boolean hasEntities = bufferedEntityChunks.contains(pos) || accumulatedEntityChunks.contains(pos.toLong());
        return VoidChunkPolicy.isVoidChunk(
                hasNonAirBlocks(snapshot), !snapshot.blockEntities().isEmpty(), hasEntities, false);
    }

    /** Whether any captured section of {@code snapshot} holds a non-air block state. */
    private static boolean hasNonAirBlocks(ChunkSnapshotSource snapshot) {
        for (SerializableChunkData.SectionData section : snapshot.sections()) {
            LevelChunkSection chunkSection = section.chunkSection();
            if (chunkSection != null && !chunkSection.hasOnlyAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stream the accumulated entities: drain each flush-eligible entity-chunk to the {@code entities/} target, the
     * entity analog of the terrain keep-hot flush, so the buffer stays bounded as the player roams. Driven by
     * flush-eligibility, not by the terrain buffer, so an entity in an already-flushed chunk still drains. The
     * {@code allCaptured} privacy gate is enforced at accumulation, so every buffered entity is in a chunk whose
     * terrain we captured.
     */
    private void flushEntityBuffer(AsyncSaveWriter activeWriter, boolean all, int centerX, int centerZ,
            int keepHot) {
        if (entityBuffer.isEmpty()) {
            return;
        }
        for (ChunkPos pos : entityBuffer.bufferedChunks()) {
            if (all || FlushPolicy.shouldFlush(pos.x, pos.z, centerX, centerZ, keepHot)) {
                flushEntityChunk(activeWriter, pos);
            }
        }
    }

    /**
     * Drain one entity-chunk: build the envelope and submit it. Package-private so the write and flush-drop tallies it
     * feeds stay testable; its only production caller is the entity flush pump.
     */
    void flushEntityChunk(AsyncSaveWriter activeWriter, ChunkPos pos) {
        List<CompoundTag> tags = entityBuffer.drainChunk(pos);
        if (tags.isEmpty()) {
            return;
        }
        try {
            CompoundTag envelope = adapter.entitySink().encodeChunk(tags, pos);
            if (envelope == null) {
                // A null envelope drops the whole drained chunk; log it and count the loss by source so it
                // cannot read as written.
                LOGGER.warn("skipping {} entities for chunk {}: the entity sink returned no envelope",
                        tags.size(), pos);
                countEntityFlushDrop(tags);
                return;
            }
            activeWriter.submitEntity(targetDimension, pos, envelope);
            countEntitiesSubmitted(tags); // tally writes here, at successful submit, not at buffer time
        } catch (RuntimeException e) {
            LOGGER.warn("skipping {} entities for chunk {}: encode/merge failed", tags.size(), pos, e);
            countEntityFlushDrop(tags);
        }
    }

    /** Tally each submitted entity to its write counter by the source recorded when it was buffered. */
    private void countEntitiesSubmitted(List<CompoundTag> tags) {
        for (CompoundTag tag : tags) {
            UUID uuid = EntityMerge.readUuid(tag);
            if (uuid == null) {
                continue; // every live-encoded tag carries a UUID; defensive
            }
            if (bufferedEntitySources.remove(uuid) == EntitySource.PRIMED) {
                primedEntitiesWritten++;
            } else {
                packetEntitiesWritten++;
            }
        }
    }

    /**
     * Count a whole entity-chunk lost during flush (its encode/merge threw, or the sink returned no envelope) by the
     * source of each drained entity, so the loss shows in the reconciliation rather than over-reported as written. The
     * drained tags are already removed from the buffer; the caller logs the chunk.
     */
    private void countEntityFlushDrop(List<CompoundTag> tags) {
        for (CompoundTag tag : tags) {
            UUID uuid = EntityMerge.readUuid(tag);
            if (uuid != null && bufferedEntitySources.remove(uuid) == EntitySource.PRIMED) {
                primeFlushDrops++;
            } else {
                reconstructFlushDrops++;
            }
        }
    }

    /**
     * Serialize one entity to its standalone entity tag, reusing the per-band {@link EntitySink}: encode a singleton
     * chunk and lift the one element. Returns null two ways and tallies which of the two it was against {@code source},
     * because only one of them is a loss. The throw is caught for per-entity failure isolation: an entity can carry
     * state the vanilla save codec rejects and we cannot always pre-sanitize it, so one entity is skipped rather than
     * crashing the download.
     *
     * <p>The sink refusing the entity is not: it drops a live passenger that saves nested under its vehicle instead, a
     * removed entity, a vehicle carrying exactly one player, and a non-serializable type, all of which vanilla also
     * skips, so that path feeds only the reported skip tally. An encode that throws, or an envelope that comes back
     * without the entity the sink was willing to write, loses one, so those feed the structural loss the finish verdict
     * reads. Conflating the two would make either an unmountable donkey report a partial download or a lost entity
     * report a clean one, depending on which way the shared counter was read.
     *
     * <p>One refusal is a loss anyway and is knowingly left in the skip tally: vanilla's one-player-vehicle gate counts
     * any player, not only the local one, and it persists such a mount through that player's own data, which a download
     * carrying no other player's data does not reproduce. So a remote player's mount, loaded before the download began
     * and never re-announced, is refused here and reports clean. Separating it needs a signal this method does not
     * have, since the sink returns the same empty envelope either way.
     *
     * <p>Package-private so the split it makes stays testable.
     */
    @Nullable
    CompoundTag encodeSingleEntity(Entity entity, ChunkPos pos, EntitySource source) {
        CompoundTag envelope;
        try {
            envelope = adapter.entitySink().encodeChunk(List.of(entity), pos, registries, config.forceMobPersistence());
        } catch (RuntimeException e) {
            recordEntityEncodeFailure(source);
            LOGGER.warn("skipping entity {}: capture failed", entity.getUUID(), e);
            return null;
        }
        if (envelope == null) {
            recordEntitySinkSkip(source);
            return null;
        }
        if (envelope.get("Entities") instanceof ListTag entities && !entities.isEmpty()
                && entities.get(0) instanceof CompoundTag tag) {
            return tag;
        }
        // A non-null envelope means the sink did save this entity, so failing to lift it back out loses one it
        // was willing to write. That is a band sink defect rather than one of the refusals above, so it counts
        // as the loss it is.
        recordEntityEncodeFailure(source);
        LOGGER.warn("skipping entity {}: the entity sink returned an envelope without it", entity.getUUID());
        return null;
    }

    /** Tally one entity lost to a throwing or malformed encode against the path that was capturing it. */
    private void recordEntityEncodeFailure(EntitySource source) {
        if (source == EntitySource.PRIMED) {
            primeEncodeFailures++;
        } else {
            reconstructEncodeFailures++;
        }
    }

    /** Tally one entity the sink refused (no loss) against the path that was capturing it. */
    private void recordEntitySinkSkip(EntitySource source) {
        if (source == EntitySource.PRIMED) {
            primeSinkSkips++;
        } else {
            reconstructSinkSkips++;
        }
    }

    /**
     * Reconstruct and buffer the accumulated entities whose chunk is ready to write. An entity is held in the packet
     * accumulator independent of unload, then taken a whole chunk at a time as that chunk leaves the keep-hot window;
     * {@code pass} says which drain this is and, with it, what may be thrown away (see {@link PromotePass}).
     *
     * <p>Only the bound dimension's entities are touched, at every step: the chunk keys, the privacy-gate test against
     * them, and the drain. A chunk key means nothing outside the dimension it was announced in, so comparing one from
     * another dimension against this dimension's captured positions decides the fate of an entity by a coincidence of
     * numbers, in the direction of writing it into the wrong world's folder. The captured-chunk privacy gate is
     * enforced here: an entity whose terrain we have not captured is left held (it may still be captured, on this visit
     * or a later one), and only the finish drops it unwritten. The budget is checked per chunk on the per-tick pass, so
     * a drained chunk is always encoded whole and a spill defers the remaining chunks safely (an entity cannot unload
     * out from under the deferral). Runs on the main thread (it builds entities against the live level).
     */
    // Package-private so the pass distinction, which decides whether a refused entity is held or dropped,
    // stays testable without a client.
    void promotePacketEntities(PromotePass pass, int centerX, int centerZ, int keepHot) {
        EntityPacketCapture capture = this.packetCapture;
        if (capture == null) {
            return;
        }
        LongIterator chunkKeys = capture.chunks(liveDimensionId).iterator();
        while (chunkKeys.hasNext()) {
            long chunkKey = chunkKeys.nextLong();
            ChunkPos pos = new ChunkPos(chunkKey);
            if (pass == PromotePass.KEEP_HOT
                    && !FlushPolicy.shouldFlush(pos.x, pos.z, centerX, centerZ, keepHot)) {
                continue; // still near the player: hold it, it has not left the keep-hot window yet
            }
            if (!allCaptured.contains(chunkKey)) {
                if (pass != PromotePass.FINISH) {
                    // The captured-chunk privacy gate: we never write an entity in a chunk whose terrain we have
                    // not captured, so hold it and drop it unwritten only at finish, which is the only pass with
                    // no later one behind it. The dimension's captured positions persist for the whole session
                    // and are restored by the rebind on a return trip, so a chunk captured on a second visit
                    // still writes what was held from the first. This cannot grow into an unbounded uncaptured
                    // tail: vanilla broadcasts an entity only for a chunk already in the client tracking view,
                    // the same chunks terrain is sent for (ChunkMap.updatePlayer gates on isChunkTracked), so a
                    // packet arrives for an uncaptured chunk only transiently, while capture is budget-starved
                    // on a fast flight, and that self-heals once movement slows.
                    continue;
                }
                droppedUncaptured += capture.dropChunk(liveDimensionId, chunkKey).size();
                continue;
            }
            if (pass == PromotePass.KEEP_HOT && !hasEncodeBudget()) {
                break; // budget spent; the accumulator holds the rest safely for a later tick
            }
            promoteChunk(pos, capture.dropChunk(liveDimensionId, chunkKey));
        }
    }

    /**
     * Which pass is draining the accumulator. The three differ in what they may write and, more importantly, in what
     * they may throw away: only {@link #FINISH} has no later pass behind it, so only it may turn a privacy-gate refusal
     * into a drop.
     */
    enum PromotePass {
        /** The per-tick pass: distance-gated and budgeted, holding everything it may not write. */
        KEEP_HOT,
        /**
         * The dimension rebind: every chunk of the dimension being left, ignoring distance and the budget because the
         * next tick would reconstruct against another world, but still holding what the privacy gate refuses, since
         * this session can write it on a return trip; with no return trip the finish settles it against that
         * dimension's own captured positions.
         */
        LEAVING_DIMENSION,
        /** The finish: every chunk, ignoring distance and the budget, dropping and counting what it cannot write. */
        FINISH
    }

    /**
     * Reconstruct one drained chunk's entities, wire the relationships that need sibling entities (passengers nest
     * under their vehicle, a leash links to its holder), then encode each root entity. Passengers and leash holders are
     * resolved within this chunk's batch by their int id: the accumulator re-homes a vehicle's riders to its chunk so
     * they drain here together, and an unresolved holder leaves the link unset (the entity is still saved). A wired
     * passenger saves nested in its vehicle's tag, so it is skipped standalone and is not counted (it reaches disk
     * inside the vehicle).
     */
    private void promoteChunk(ChunkPos pos,
            List<PacketEntity<ClientboundAddEntityPacket, SynchedEntityData.DataValue<?>, EquipmentEntry>> held) {
        List<Promoted> built = new ArrayList<>();
        Map<Integer, Entity> byId = new HashMap<>();
        for (PacketEntity<ClientboundAddEntityPacket, SynchedEntityData.DataValue<?>, EquipmentEntry> frame : held) {
            Entity entity = reconstructPacketEntity(frame);
            if (entity != null) {
                built.add(new Promoted(frame, entity));
                byId.put(frame.id(), entity);
            } else {
                createDrops++; // the typed entity could not be created
            }
        }
        for (Promoted promoted : built) {
            for (int riderId : promoted.frame().passengers()) {
                Entity rider = byId.get(riderId);
                if (rider != null) {
                    rider.startRiding(promoted.entity(), true, false); // the vehicle's save then nests the rider
                }
            }
            if (promoted.frame().leashHolderId() != 0 && promoted.entity() instanceof Leashable leashable) {
                Entity holder = byId.get(promoted.frame().leashHolderId());
                if (holder != null) {
                    leashable.setLeashedTo(holder, false); // saved as the holder's UUID, or a fence knot's block pos
                }
                // A holder in a different drained chunk is not resolved here (resolution is within this batch by
                // int id), so a cross-chunk leashed mob saves unleashed, the leash sibling of the
                // cross-chunk rider edge. The mob is still saved, only the link is lost.
            }
        }
        for (Promoted promoted : built) {
            Entity entity = promoted.entity();
            if (entity.isPassenger()) {
                nestedPassengers++; // saved nested in its vehicle's tag, a received spawn that is not a written root
                continue;
            }
            CompoundTag tag = encodeSingleEntity(entity, pos, EntitySource.RECONSTRUCTED);
            if (tag != null) {
                entityBuffer.accumulate(promoted.frame().uuid(), pos, tag);
                bufferedEntitySources.put(promoted.frame().uuid(), EntitySource.RECONSTRUCTED); // counted at submit
                recordSaved(promoted.frame().uuid(), entity);
            }
        }
    }

    /** A drained entity paired with the entity reconstructed from it, for the in-batch relationship wiring. */
    private record Promoted(
            PacketEntity<ClientboundAddEntityPacket, SynchedEntityData.DataValue<?>, EquipmentEntry> frame,
            Entity entity) {}

    /**
     * Rebuild a live entity from its accumulated packet state, the way the client does on {@code AddEntity} then the
     * post-spawn packets: create the typed entity against the live level, apply the spawn packet (the spawn position,
     * rotation, and the data int such as a hanging facing), snap it to its last known position (the accumulated
     * move/teleport/position-sync, so a mob saves where it ended), assign the merged synced values, and set the merged
     * equipment per slot. Passengers and the leash are wired by {@link #promoteChunk} once the sibling entities exist.
     * The result is a fresh, never-removed entity {@link EntitySink} saves like any other. Returns null if the type
     * cannot create.
     */
    private @Nullable Entity reconstructPacketEntity(
            PacketEntity<ClientboundAddEntityPacket, SynchedEntityData.DataValue<?>, EquipmentEntry> frame) {
        EntityType<?> type = frame.spawn().getType();
        Entity entity = type.create(level(), EntitySpawnReason.LOAD);
        if (entity == null) {
            return null;
        }
        entity.recreateFromPacket(frame.spawn());
        EntityPos pos = frame.pos();
        entity.snapTo(pos.x(), pos.y(), pos.z(), pos.yRot(), pos.xRot());
        List<SynchedEntityData.DataValue<?>> synced = frame.synced();
        if (!synced.isEmpty()) {
            entity.getEntityData().assignValues(synced);
        }
        if (entity instanceof LivingEntity living) {
            for (EquipmentEntry equipment : frame.equipment()) {
                living.setItemSlot(equipment.slot(), equipment.stack());
            }
        }
        return entity;
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
        return chunksFailed + entityChunksFailed + chunksCaptureFailed + structuralEntitiesLost
                + finishStepsFailed;
    }

    /** Report the background save's outcome to the player (called on the main thread, once, when it completes). */
    private void report(AsyncSaveWriter.SaveResult result) {
        if (result.failed()) {
            reportSaveFailure(result.error());
            return;
        }
        // The chat figure is the distinct captured-chunk total rather than the writer's write tally, which
        // double-counts a chunk written once then re-flushed on a revisit.
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
                + "{} structural entities, {} finish steps", saveName, result.chunksFailed(),
                result.entityChunksFailed(), chunksCaptureFailed, structuralEntitiesLost, finishStepsFailed);
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
