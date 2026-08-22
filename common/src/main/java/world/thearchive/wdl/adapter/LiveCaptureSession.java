// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
import world.thearchive.wdl.core.CaptureController;
import world.thearchive.wdl.core.CaptureCounts;
import world.thearchive.wdl.core.CaptureOrder;
import world.thearchive.wdl.core.CaptureToggles;
import world.thearchive.wdl.core.CapturedContainers;
import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.ContainerAssociation;
import world.thearchive.wdl.core.CoveredChunkIndex;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.FlushPolicy;
import world.thearchive.wdl.core.GameRuleResolution;
import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.core.OutlineClass;
import world.thearchive.wdl.core.OutlineClassifier;
import world.thearchive.wdl.core.RecaptureMode;
import world.thearchive.wdl.core.RecapturePolicy;
import world.thearchive.wdl.core.RecoveredCoverage;
import world.thearchive.wdl.core.RegionChunkScan;
import world.thearchive.wdl.core.SaveFailureComposer;
import world.thearchive.wdl.core.SaveFailureReason;
import world.thearchive.wdl.core.SaveProgress;
import world.thearchive.wdl.core.SaveStage;
import world.thearchive.wdl.core.SavedChunkIndex;
import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.SendRangeSampler;
import world.thearchive.wdl.core.ToastCopy;
import world.thearchive.wdl.core.VoidChunkPolicy;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.export.FinalizeOutputs;
import world.thearchive.wdl.core.report.DimensionChunks;
import world.thearchive.wdl.core.report.DownloadCounts;
import world.thearchive.wdl.core.report.DownloadCountsBuilder;
import world.thearchive.wdl.core.report.DownloadIdentity;
import world.thearchive.wdl.core.report.DownloadReportStore;
import world.thearchive.wdl.core.report.ReportEnvironment;
import world.thearchive.wdl.core.report.SaveChunks;
import world.thearchive.wdl.core.report.WorldIconWriter;
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
 * in-memory buffer stays bounded by a hot square no matter how far the capture roams. Only fully-encoded immutable tags
 * cross to the writer thread. {@code finish()} drains the remaining buffer, snapshots and encodes the live entities,
 * writes level.dat, and reports the saved world when the background drain completes; none of it on the render thread.
 */
public final class LiveCaptureSession implements CaptureController.Session {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveCaptureSession.class);

    /** Sentinel for {@link #openContainerId} when no container menu is currently open. */
    private static final int NO_MENU = -1;

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
    // Stats refresh cadence: re-request the async stats counter every 3 min so finish reads a
    // near-live copy. Low-stakes value: stats are lifetime-cumulative, so a few minutes' staleness is a
    // rounding error against an hours-plus counter. 3600 ticks = 3 min at 20 tps.
    private static final int STATS_REFRESH_PERIOD_TICKS = 3600;
    private static final int TICKS_PER_SECOND = 20;

    /** The wdl-private subfolder under the save root (mirrors the download report's location). */
    private static final String WDL_SUBFOLDER = "wdl";

    private final VersionAdapter adapter;
    private final PlatformBridge bridge;
    private final BobbyChunkFilter bobbyFilter;
    private final WdlConfig config;
    // The game-thread completion poke (CaptureController.tick), run when the background save completes so the
    // SAVING to IDLE transition lands even while a replay's paused timer has suspended the game tick.
    private final Runnable saveCompletePoke;
    private final SavedChunkIndex overlayIndex;
    // The covered half of the two-tone overlay: the chunks the recording path brought within entity send range,
    // fed a disc at a time from captureTick. The per-crossing disc feed stays unconditional like the saved
    // record-site; only the resume prior-coverage seed is gated on overlayActive.
    private final CoveredChunkIndex coveredIndex;
    // The live send-range estimator (owned by the controller): sizes each coverage disc and gates the cold-start
    // read. One per-dimension running max over the three sampler-gated feeds (arrivals, removals, and the seed
    // sweep in captureTick), clamped to the live render distance at read; the per-center caps recorded with the
    // trail keep the retroactive paint honest when the max grows.
    private final SendRangeEstimator sendRange;
    // The last chunk center a coverage disc was recorded around, so the disc is re-added only when the player
    // crosses into a new chunk rather than every tick. Null at construction and after a dimension rebind, so the
    // first tick in a dimension seeds its disc under the correct live-id partition.
    private @Nullable ChunkPos lastCoveredCenter;
    // The radius the covered trail was last laid at this dimension, re-read every tick so a changed radius (the
    // running max growing on a new sample, or the render-distance clamp dropping) triggers one recompute over
    // the whole trail rather than leaving the earlier path covered at the stale radius. Reset to 0 with
    // lastCoveredCenter on a dimension rebind, since the covered set is per dimension.
    private int lastCoveredRadius;
    // The previous tick's player position, the displacement baseline the sampler's speed gate reads at gate-arm.
    // Invalid at construction and after a dimension rebind, so the first guarded tick (and the first after a
    // cross-dimension jump) arms with displacement 0 instead of a spurious fast tick.
    private double lastTickPlayerX;
    private double lastTickPlayerZ;
    private boolean tickBaselineValid;
    // Weak so a session outliving a disconnect cannot pin the old player, and through it that level's chunks.
    private WeakReference<LocalPlayer> lastPlayer = new WeakReference<>(null);
    // The overlay's resume prior-coverage seed reads region headers off disk only when a map overlay mod
    // (XaeroPlus or JourneyMap) will consume them; the per-chunk record-site add stays unconditional. Seeded
    // once per live dimension id.
    private final boolean overlayActive;
    private final Set<String> overlaySeededDimensions = new HashSet<>();
    // What this download targets: a fresh folder (NEW) or an existing wdl-managed one to add to (RESUME). On a
    // RESUME that does not re-open the ender chest, its prior contents carry forward from the prior level.dat.
    private final DownloadTarget target;
    // The ClientLevel currently being captured. Non-final: the session follows the player across a portal,
    // rebinding to the new dimension's level, so this advances with targetDimension and allCaptured. Null on a
    // session built by the level-free constructor, so dereference it only through level(). The two bound-chunk
    // assert canaries compare it raw instead, because a throwing call inside an assert would make its own
    // evaluation depend on -ea; they are safe only because a level() call already ran earlier in the chain,
    // which for reencode means its callers, since it takes its chunk source as a parameter.
    private @Nullable ClientLevel level;
    // The vanilla single-player dimension this capture is laid out under, chosen by the captured
    // dimension's TYPE so non-standard server level keys (e.g. Multiverse's minecraft:worlds/2b2t/2b2t_1)
    // still write to the vanilla dimension's own folder, not one derived from the custom level key.
    // Non-final: rebound on a dimension change to lay the new dimension out under its own folder.
    private ResourceKey<Level> targetDimension;
    // The server's OWN key for the dimension being captured, as the id string the packet-side per-dimension
    // stores share, which on a Multiverse/Paper server is not the vanilla-mapped targetDimension above. It is
    // the identity the inbound tee stamps each held entity with, so the promote gate compares a held frame
    // against the world it was announced in rather than against a position set from another world. Rebound on
    // a dimension change, like targetDimension.
    private String liveDimensionId;
    // Connection-global (ClientLevel takes it from the packet listener, shared across a respawn's new level),
    // so it stays final and correct across a dimension rebind.
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
     * per-tick time budget drained new-capture-first, then the re-capture floor slice, then the entity encode, so
     * loading a fresh render-distance square or flying fast spills across ticks instead of stuttering one frame. The
     * chunk passes only capture (snapshot) on this thread; the heavy serialize runs on the writer.
     * {@link Long#MAX_VALUE} means unbounded, the state between ticks and at finish (the finish drain must capture
     * every chunk and encode every entity still loaded). The field keeps its name for the unchanged
     * {@code encodeBudgetMillis} config key.
     */
    private long encodeDeadlineNanos = Long.MAX_VALUE;

    /** Cached nearest-first capture offsets, rebuilt only when the render distance changes (a per-tick array). */
    private int @Nullable [] ringOffsetsCache;
    private int ringOffsetsRadius = -1;

    /** The MC-free guard deciding which block a freshly-opened container menu binds to. */
    private final ContainerAssociation association = new ContainerAssociation();

    /** The open menu's change gate: the per-tick stash re-serializes only when this reports a change. */
    private final MenuChangeTracker stashChangeTracker = new MenuChangeTracker();

    /**
     * Captured container {@code "Items"} holders keyed by block pos; last-seen-while-open wins. Each is merged into its
     * chunk's tag (and dropped) just before that chunk is flushed, by {@link ContainerMerge#mergeChunkStash}.
     */
    private final Map<BlockPos, StashHolder> containerStash = new LinkedHashMap<>();

    /**
     * Captured lectern {@code "Book"}/{@code "Page"} holders keyed by block pos; last-seen-while-open wins. The lectern
     * axis beside {@link #containerStash}: a book reaches the client only through the open lectern menu's slot 0, so it
     * is lifted there and merged into its chunk's already-captured lectern block entity (and dropped) just before that
     * chunk is flushed, by {@link ContainerMerge#mergeLecternChunkStash}.
     */
    private final Map<BlockPos, StashHolder> lecternStash = new LinkedHashMap<>();

    /**
     * The captured ender-chest {@code "Items"} holder, last-seen-while-open wins. Unlike the block-keyed stashes the
     * ender chest is the player's single global inventory, so one field suffices; it is merged into the captured player
     * tag's {@code "EnderItems"} at {@link #finish()}, not into a chunk block entity (the ender chest reaches the
     * client only through its open menu, as with the lectern).
     */
    private @Nullable CompoundTag enderChestStash;

    /**
     * Captured container-vehicle {@code "Items"} holders keyed by entity {@link UUID}; last-seen-while-open wins. The
     * entity sibling of {@link #containerStash}: a chest minecart, hopper minecart, chest boat, or chest raft reaches
     * the client only through its open menu, so it is lifted there and merged into its entity's tag in the
     * {@code entities/} region when that entity's chunk flushes (by {@link EntityContainerMerge#mergeEntityStash},
     * incidentally again at {@link #finish()}), not into a chunk block entity.
     */
    private final Map<UUID, CompoundTag> entityContainerStash = new LinkedHashMap<>();
    // Captured villager trades by villager UUID, last-seen-wins, mirroring entityContainerStash so the flush's
    // map-id remap allocates archive ids in a deterministic order. A villager whose offers encode threw is
    // remembered in merchantEncodeFailed so it is not retried every tick the screen is open.
    private final Map<UUID, CompoundTag> merchantStash = new LinkedHashMap<>();
    private final Set<UUID> merchantEncodeFailed = new HashSet<>();

    /**
     * Positions a block placement landed in this session, held per dimension and swapped on a portal like
     * {@link #capturedBlockKeys}. The on-disk carry-forward matches a fresh block entity to its prior copy on position
     * and type, which a same-type replacement satisfies, so without this the archived contents of the block that was
     * there are written onto the one that replaced it on the next visit.
     *
     * <p>Drained by the flush that acts on it, like the stashes. What that flush leaves on disk is already
     * post-placement, so the visit after it carries forward this download's own capture; keeping the position would
     * instead erase that capture on every later pass, which is the shape a permanent refusal always has.
     */
    private final Map<ResourceKey<Level>, LongOpenHashSet> replacedBlockKeysByDimension = new LinkedHashMap<>();
    private LongOpenHashSet replacedBlockKeys = new LongOpenHashSet();

    /**
     * The standing captured-set the unsaved-container outline reads: which loaded containers had their rich contents
     * captured this session, so their rim clears. Populated at the stash content-gate the {@code stash*} family puts at
     * and, for a placed content-bearing container, the instant the interaction recognizer records it, optimistically
     * before the flush confirms it, the way the open-time stash marks a chest on open and
     * {@link #onBookshelfSlotCaptured} marks a bookshelf slot. So membership tracks contents-stashed-for-save, not the
     * live stash (which drains mid-session and would otherwise re-show a flushed container's rim). Removal is rare and
     * each case un-stashes the content it un-marks: a lectern whose book is taken back, and a cell a placement lands
     * in, whose captured block is being replaced ({@link #onBlockPlacedAt}). Block containers, double-chest halves, and
     * placed containers enter by block pos key, held per dimension and swapped on a portal like {@link #allCaptured} so
     * a position in one dimension never dedups another's; borne containers enter by globally-unique entity UUID,
     * staying session-wide, and every ender chest is derived from {@link #enderChestStash} (one shared capture).
     * Main-thread only, like the rest of capture; the cross-seam view contract is {@link CapturedContainers}.
     */
    private final Map<ResourceKey<Level>, LongOpenHashSet> capturedBlockKeysByDimension = new LinkedHashMap<>();
    private LongOpenHashSet capturedBlockKeys = new LongOpenHashSet();
    // The block-entity registry id recorded per captured pos, the one id both staleness gates compare against:
    // each compares it against a like-for-like id to catch a same-position block replacement. Gate 1 rides
    // a copy on the drained holder (wdl_block_entity_id) for the writer thread; Gate 2 reads this map through
    // the outline.
    // Held per dimension and swapped on a portal like capturedBlockKeys so one dimension's pos never shadows
    // another's, last-recorded-wins on re-open.
    private final Map<ResourceKey<Level>, Long2ObjectMap<String>> capturedBlockTypesByDimension = new LinkedHashMap<>();
    private Long2ObjectMap<String> capturedBlockTypes = new Long2ObjectOpenHashMap<>();
    // The chiseled-bookshelf captured-slot masks the outline reads (per pos, bit n = slot n): a bookshelf is
    // captured one slot at a time, so a whole-block flag in capturedBlockKeys cannot express it. Held per
    // dimension and swapped on a portal like capturedBlockKeys, OR-updated when the interaction recognizer
    // records a book insert.
    private final Map<ResourceKey<Level>, Long2IntOpenHashMap> bookshelfSlotsByDimension = new LinkedHashMap<>();
    private Long2IntOpenHashMap bookshelfSlots = new Long2IntOpenHashMap();
    private final Set<UUID> capturedEntityIds = new HashSet<>();

    /**
     * The resume recovered-coverage scan: on a resumed download the writer thread feeds it each prior on-disk chunk it
     * carries forward, and it publishes the positions captured in a prior session for the outline to mark recovered.
     * Wired to the writer in {@link #ensureWriter}; empty on a fresh download.
     */
    private final RecoveredScan recoveredScan = new RecoveredScan();

    // Which chunks have had a recovered-coverage scan requested this dimension, so each on-disk prior is read
    // once; cleared on a portal so the new dimension re-scans its own chunks (coverage is per dimension).
    private final LongOpenHashSet recoveryScanned = new LongOpenHashSet();

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

    /**
     * The interaction-prediction recognizer, published like {@link #packetCapture} so the per-loader use-block hook can
     * reach it. Created only when {@code captureContainers} is on (otherwise the hook no-ops); its recording is coupled
     * to {@code recaptureChunks}, since the reconcile gate can confirm a candidate only against a re-captured snapshot.
     * Null when interaction capture is off.
     */
    private final @Nullable InteractionCapture interactionCapture;

    /**
     * Remembers the block or entity the local player just right-clicked, published like {@link #interactionCapture} so
     * the per-loader use hooks can reach it, so the open-container bind seeds from the clicked target rather than the
     * crosshair, which keeps drifting until the menu freezes the camera. Created only when container capture is on (the
     * bind path's own flag); null otherwise.
     */
    private final @Nullable OpenClickTracker openClickTracker;

    /** Recognize-and-lift for an open container menu: decide what the menu is and lift its slots into a holder. */
    private final ContainerCapture containerCapture;

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
     * removed entity, or a player-only vehicle vanilla persists through the player) or {@code save()} returned false (a
     * non-serializable type: a leash knot, a bobber), which are the non-saves vanilla also skips. Reported so the drop
     * is visible; not a loss, and not part of the packet reconciliation residual (a primed entity has no spawn packet).
     */
    private int primeSinkSkips;

    /** Primed entities the encode, a malformed envelope, or the finish re-offer around it destroyed. */
    private int primeEncodeFailures;

    /** Primed entities lost when their whole entity-chunk threw or nulled out during flush; counted, not residual. */
    private int primeFlushDrops;

    /**
     * The container-vehicle holders already scrubbed and map-remapped, by identity, so each is prepared exactly once
     * before it merges: a re-opened vehicle's fresh holder is a new object and is prepared again, an old discarded
     * holder stays harmlessly.
     */
    private final Set<CompoundTag> preparedEntityContainers = Collections.newSetFromMap(new IdentityHashMap<>());
    // The merchant analog of preparedEntityContainers: an offer holder is scrubbed and map-remapped exactly once,
    // tracked by identity, since the map remap is not idempotent.
    private final Set<CompoundTag> preparedMerchantHolders = Collections.newSetFromMap(new IdentityHashMap<>());

    /** The UUID of the container vehicle the live menu is bound to, set once at bind time; read by the stash. */
    private @Nullable UUID boundEntityUuid;
    private boolean boundMerchantIsVillager; // a villager gets Xp, a wandering trader does not

    /**
     * The seated player's mount, captured at finish before the entity drain and attached to the saved player tag as
     * vanilla's {@code RootVehicle} record ({@link #rootVehicleAttach} is the direct vehicle UUID,
     * {@link #rootVehicleTag} the root vehicle NBT). Both null unless the player finished riding a vehicle carrying
     * only itself. Main-thread-only scratch, deliberately not volatile: the RootVehicle NBT crosses to the writer
     * thread only folded into the immutable {@link CapturedPlayer} through the existing volatile publish.
     */
    private @Nullable UUID rootVehicleAttach;

    private @Nullable CompoundTag rootVehicleTag;

    /**
     * The root vehicle and its non-player passengers, held from the standalone entity write so the RootVehicle copy is
     * not duplicated by a region-file copy on the same UUID.
     */
    private final Set<UUID> excludedRootVehicleUuids = new HashSet<>();

    /**
     * The captured contents of every container vehicle folded into a standalone entity write this session, kept by UUID
     * after the stash drains. A vehicle's items reach the client only through its open menu, so every serialize after
     * that first fold is empty; keeping the holder lets a later flush of the same vehicle, in another entity-chunk or
     * another dimension, be written carrying its contents too, so a reader that reaches any copy finds the loot rather
     * than an empty vehicle. It does not reduce the archive to one copy; see
     * {@link EntityContainerMerge#refoldFlushedContainers} for that residual.
     *
     * <p>Read at finish as well, where {@link #foldRidingVehicleContents} folds it into the ridden mount's
     * {@code RootVehicle} tag when the stash has already drained, which is what lets that mount be written whole rather
     * than skipped.
     *
     * <p>Bounded by the number of container vehicles the player opened, each holding one container's worth of items, so
     * it is a far smaller retention than the block container stash that precedes it.
     */
    private final Map<UUID, CompoundTag> foldedContainerVehicles = new HashMap<>();
    private final Map<UUID, CompoundTag> foldedMerchants = new LinkedHashMap<>();

    /**
     * The finish-snapshot of the local player, assembled on the main thread in {@link #finish()} and read by the writer
     * thread when the finalizer writes level.dat. Volatile because it crosses that boundary; the writer's queue already
     * establishes the happens-before, the keyword documents it. Stays null on a disconnect-flush or when the assembly
     * fails soft, and the level.dat write degrades to the openable void-world output.
     */
    private volatile @Nullable CapturedPlayer capturedPlayer;
    private volatile @Nullable CapturedProgress capturedProgress;

    /**
     * The on-sight map archive: the live remap table (the persisted manifest, the session-to-archive resolution, and
     * the streaming gate) that resolves each filled map as it is seen and streams its data through
     * {@link #streamMapData} at first image, so a map in a container whose chunk flushes mid-roam is captured before
     * the holder drains rather than lost at finish. Created in {@link #ensureWriter} (where the save path, hence the
     * manifest, is known) on the main thread before the writer thread starts, so the writer-thread finalizer sees the
     * reference; the finalizer reads only its idcounts floor, the map files having streamed during capture. The
     * headless suite binds a caller-built one through {@link #bindWorldOpen}, which reproduces that ordering rather
     * than resolving anything itself. Stays null only if the world never opened for writing.
     */
    private @Nullable MapArchive mapArchive;

    /** The wdl-private map-id manifest file ({@code <save>/wdl/map-ids}); resolved in {@link #ensureWriter}. */
    private @Nullable Path mapIdsFile;

    /** The save's level.dat ({@code <save>/level.dat}); read on a resume to carry the ender chest forward. */
    private @Nullable Path levelDatFile;

    /** containerId of the menu currently tracked, or {@link #NO_MENU} when none is open. */
    private int openContainerId = NO_MENU;

    /** The background writer draining captured tags to disk; opened lazily on the first chunk to flush. */
    private @Nullable AsyncSaveWriter writer;

    // The per-dimension save paths, resolved with the writer in ensureWriter and read by the overlay resume seed
    // to find each dimension's region folder. Null until the writer opens.
    private @Nullable WorldPaths worldPaths;

    /**
     * The live finalization phase and fraction for the HUD bar, set on the writer thread as the save drains its phases
     * (chunks, then the maps batched at finish, then the export zip; a map first imaged during capture streams
     * unreported, when no bar is drawn) and read each frame on the main thread.
     */
    private final SaveProgress progress = new SaveProgress();

    /** The error from a failed attempt to open the world for writing; reported at {@link #finish()}. */
    private @Nullable Throwable startError;

    /** Whether the save outcome has been reported to the player (covers the nothing-captured/failure paths). */
    private boolean reported;

    /** The save-directory name (the target's folder, verbatim), used to open the world and report the save. */
    private String saveName;

    /** How many stashed containers were merged into their captured chunk tags (for the saved-world message). */
    private int mergedContainers;

    /** How many stashed lectern books were merged into their captured chunk tags (for the saved-world message). */
    private int mergedLecterns;

    /** How many stashed container vehicles were merged into their captured entity tags (for the saved message). */
    private int mergedEntityContainers;
    private int mergedVillagerTrades;

    /**
     * Captured maps lost to a failed write: the map's own data file, or a map with no writer to stream to, or one
     * imaged after the finish batch closed. Every increment has a matching per-item loss line naming the map, so a
     * reader can reconcile the count against the log. A non-zero tally makes the finish partial, not clean. Atomic so
     * no increment is lost whichever thread runs the write task; that the read sees a complete count is the writer's
     * queue drain and completed future, not this type.
     */
    private final AtomicInteger mapsFailed = new AtomicInteger();

    /**
     * The finalize-time idcounts write, counted apart from {@link #mapsFailed} because it loses no captured map: it is
     * one shared file whose failure risks the reopened world reissuing captured ids. Folding it in would let the
     * map-loss count exceed the per-item lines that explain it. Written on the writer thread.
     */
    private int idCountsFailed;

    /**
     * The voice for a captured map lost to its own write. These three are instance fields on purpose: each bounds its
     * stacks over one download, so hoisting any of them to a static leaves every download after the first with no stack
     * for a cause the first already spent.
     */
    private final CaptureLossLog mapWriteLoss = new CaptureLossLog(LOGGER,
            "failed to write map data {}", "it renders blank in the reopened world");

    /**
     * The voice for a captured container holder whose map-id remap threw. Says "holder" because this path is handed a
     * block position for a block container and a UUID for a container vehicle, and a bare UUID here would be
     * indistinguishable from the framed-item voice's own line.
     */
    private final CaptureLossLog mapRemapLoss = new CaptureLossLog(LOGGER,
            "map remap failed for holder {}", "its map renders blank");

    /**
     * The voice for a framed or dropped map item whose remap threw. Kept apart from the holder voice for its own stack
     * budget, not for its wording: a shared instance would leave whichever path lost second with no stack whenever the
     * two fail the same way.
     */
    private final CaptureLossLog mapEntityRemapLoss = new CaptureLossLog(LOGGER,
            "map remap failed for entity {}", "its map renders blank");

    /**
     * The voice for a chunk whose terrain snapshot threw. One root cause fails every chunk of a kind alike, and the
     * square retries a failing position every tick it stays loaded, so this voice's stack budget rides on top of the
     * per-position dedup in {@link #recordChunkCaptureLoss} rather than instead of it.
     */
    private final CaptureLossLog chunkCaptureLoss = new CaptureLossLog(LOGGER,
            "failed to capture chunk {}", "the reopened world has none of that chunk's terrain");

    /**
     * Where {@link #streamMapData} puts a map write once the finish drain has begun, instead of submitting it as its
     * own uncounted task: the batch is handed to the writer as one unit so it can report the map phase over a known
     * total. Null while capturing, when each map streams on sight and no bar is drawn anyway, and null again once the
     * writer owns the batch, so the batched map tags are released rather than held through the rest of the finalize.
     */
    private @Nullable List<Runnable> finishMapWrites;

    /**
     * Set once the finish batch has been handed to the writer, which is what tells a null {@link #finishMapWrites}
     * after the handover apart from the same null before the drain began. A map imaged past that point has no batch
     * left to join and the writer drops post-finish work, so it is a loss to count.
     */
    private boolean finishBatchClosed;

    /**
     * The map-id manifest read lost to a fault. Its consequence lands in this download, which then re-images every map
     * the folder already holds under fresh ids, and nothing later in the session can undo that, so it counts once and
     * stays counted. Main thread, at world-open.
     */
    private int mapManifestReadFailed;

    /**
     * Whether the manifest on disk is stale as this download ends. A flag rather than a tally because the file is
     * written up to twice, the scheme signal at world-open and the authoritative rewrite at finalize, and the second
     * repairs the first: counting both would report a download partial for a fault that was already healed, and report
     * two losses where there is one file. Written on the main thread at world-open and on the writer thread at
     * finalize.
     *
     * <p>Held apart from {@link #mapsFailed} for the reason {@link #idCountsFailed} is: no captured map is lost here,
     * and folding it in would let the map-loss count exceed the per-item lines that explain it.
     */
    private boolean mapManifestStale;

    /** Block-container merges lost to a throw (writer thread); folds into the partial-finish predicate. */
    private int blockContainersFailed;

    /** Container-vehicle merges lost to a throw (main thread); folds into the partial-finish predicate. */
    private int entityContainersFailed;

    /** Captured map items whose id remap threw and now render blank (main thread); folds into the predicate. */
    private int mapsRemapFailed;

    /** Opened container vehicles whose captured contents were never folded into a saved entity (main thread). */
    private int containerVehiclesLost;
    private int villagerTradesLost;

    /**
     * Predicted interactions (a bookshelf book, a jukebox disc, a placed shulker or beehive) that no chunk flush ever
     * reached, so nothing of them was written (main thread). Counted at the two whole-buffer drains, the dimension
     * rebind and the finish, since only what neither reached is unrecoverable.
     */
    private int interactionCapturesLost;

    /** Entities lost to a whole-entity-chunk flush throw or a create failure, both paths (main thread). */
    private int structuralEntitiesLost;

    /**
     * A prior download's parked mount the resumed release could not place (main thread). At most one per download,
     * since the prior player records at most one RootVehicle, and the whole mount rather than a part of it: this
     * session's own player tag overwrites the only copy it had.
     */
    private int resumedMountsLost;

    /**
     * Chunks whose terrain snapshot threw, so the position reached neither the buffer nor the captured set and the
     * reopened world has none of that chunk's terrain, falling back to its own generator there (main thread). Deduped
     * by {@link #captureFailedByDimension}, since the same position is retried every tick it stays loaded.
     */
    private int chunksCaptureFailed;

    /**
     * Finish-time capture steps {@link #failSoft} degraded to absent (main thread). The degradation is deliberate,
     * since an openable void-world level.dat beats an aborted save; what would not be is stamping the download clean
     * over it.
     */
    private int finishStepsFailed;

    /**
     * The positions {@link #chunksCaptureFailed} has already counted, per dimension: the position space is
     * dimension-local, so one dimension's failing position must not dedup another's. Held as a plain map rather than a
     * swapped current-dimension reference like {@link #allCaptured}, because nothing reads it per tick.
     */
    private final Map<ResourceKey<Level>, LongOpenHashSet> captureFailedByDimension = new LinkedHashMap<>();

    /**
     * The per-download report writer (the sentinel at world-open, the rendering from the writer preflight after the
     * pre-resume backup, complete just before finalize). Fail-soft.
     */
    private final DownloadReportStore report = new DownloadReportStore();

    /**
     * Accumulates the report's dedup-correct counts over the session: containers at bind time (a double chest as one),
     * entities at submit, chunks at finish. Touched only on the client main thread, like the rest of capture. The
     * player count is settled later (it depends on the write succeeding), not through this.
     */
    private final DownloadCountsBuilder reportCounts = new DownloadCountsBuilder();

    /** The save root and stamped identity, set when the report begins at world-open; read at finish. */
    private @Nullable Path reportRoot;
    private @Nullable DownloadIdentity reportIdentity;
    private @Nullable ReportEnvironment reportEnvironment;

    /**
     * The source server's icon bytes, snapshotted on the main thread in {@link #finish()} (where {@code
     * getCurrentServer()} is live) and read by the writer thread, the same discipline as {@link #capturedPlayer}. Null
     * in singleplayer or on a connect that cached no icon, so no icon file is written.
     */
    private volatile byte @Nullable [] reportIconBytes;

    /**
     * The completion inputs frozen at end-of-capture, handed to the writer thread to write the completion marker.
     * Volatile because the writer-thread finalizer reads it; the writer's queue already establishes the happens-before,
     * the keyword documents it (the {@link #capturedPlayer} discipline).
     */
    private volatile @Nullable PendingReport pendingReport;

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
            DownloadTarget target, SavedChunkIndex overlayIndex, CoveredChunkIndex coveredIndex,
            SendRangeEstimator sendRange, boolean overlayActive, boolean cameraDetachedAtStart,
            BobbyChunkFilter bobbyFilter, Runnable saveCompletePoke) {
        this(adapter, bridge, config, level,
                VanillaDimensions.forType(level.dimensionType()),
                level.dimension(), level.registryAccess(), target, overlayIndex, coveredIndex, sendRange,
                overlayActive, cameraDetachedAtStart, bobbyFilter, saveCompletePoke);
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
            RegistryAccess registries, DownloadTarget target, SavedChunkIndex overlayIndex,
            CoveredChunkIndex coveredIndex, SendRangeEstimator sendRange, boolean overlayActive,
            boolean cameraDetachedAtStart, BobbyChunkFilter bobbyFilter, Runnable saveCompletePoke) {
        this.adapter = adapter;
        this.bridge = bridge;
        this.bobbyFilter = bobbyFilter;
        this.config = config;
        this.target = target;
        this.overlayIndex = overlayIndex;
        this.coveredIndex = coveredIndex;
        this.sendRange = sendRange;
        this.overlayActive = overlayActive;
        this.saveCompletePoke = saveCompletePoke;
        this.saveName = target.folderName();
        this.level = level;
        this.targetDimension = targetDimension;
        this.liveDimensionId = liveDimension.location().toString();
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
        this.interactionCapture = config.captureContainers()
                ? new InteractionCapture(adapter.containerSink(), registries,
                        config.recaptureChunks().refreshesHotChunks(), this::isInteractionChunkCapturable,
                        this::onBookshelfSlotCaptured, this::onPlacedContainerCaptured, this::onBlockPlacedAt)
                : null;
        if (this.interactionCapture != null) {
            InteractionCapture.activate(this.interactionCapture);
        }
        this.openClickTracker = config.captureContainers() ? new OpenClickTracker() : null;
        if (this.openClickTracker != null) {
            OpenClickTracker.activate(this.openClickTracker);
        }
        this.containerCapture = new ContainerCapture(adapter, bridge, registries, openClickTracker);
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

    /** The captured-position set for {@code dimension}, created empty on first use (the per-dimension dedup). */
    private LongOpenHashSet capturedFor(ResourceKey<Level> dimension) {
        return capturedByDimension.computeIfAbsent(dimension, key -> new LongOpenHashSet());
    }

    /** The placement-replaced position set for {@code dimension}, created empty on first use. */
    private LongOpenHashSet replacedBlockKeysFor(ResourceKey<Level> dimension) {
        return replacedBlockKeysByDimension.computeIfAbsent(dimension, key -> new LongOpenHashSet());
    }

    /** The outline captured-block set for {@code dimension}, created empty on first use (the per-dimension dedup). */
    private LongOpenHashSet capturedBlockKeysFor(ResourceKey<Level> dimension) {
        return capturedBlockKeysByDimension.computeIfAbsent(dimension, key -> new LongOpenHashSet());
    }

    /** The outline bookshelf captured-slot masks for {@code dimension}, created empty on first use. */
    private Long2IntOpenHashMap bookshelfSlotsFor(ResourceKey<Level> dimension) {
        return bookshelfSlotsByDimension.computeIfAbsent(dimension, key -> new Long2IntOpenHashMap());
    }

    /** The captured block-entity type ids for {@code dimension}, created empty on first use (the staleness gate). */
    private Long2ObjectMap<String> capturedBlockTypesFor(ResourceKey<Level> dimension) {
        return capturedBlockTypesByDimension.computeIfAbsent(dimension, key -> new Long2ObjectOpenHashMap<>());
    }

    /**
     * Whether an interaction predicted in {@code chunk} can still reach disk (the interaction recognizer's gate): the
     * chunk is buffered now, has not been captured yet so its first capture is still coming, or is a revisit this mode
     * re-buffers. False for a chunk already written and frozen, where the reconcile gate has no post-interaction
     * block-state to read and no flush ever drains the candidate. Recording one there clears the outline's rim and
     * counts a container for content that cannot be written, which tells the player the opposite of the truth; refusing
     * it leaves the rim armed, which is what a re-visit needs to see.
     */
    private boolean isInteractionChunkCapturable(ChunkPos chunk) {
        return captured.containsKey(chunk) || !allCaptured.contains(chunk.toLong())
                || config.recaptureChunks().overwritesRevisitedChunks();
    }

    /**
     * Count and name every interaction prediction left unwritten, draining the recognizer's stashes: content the player
     * put in the world that no chunk flush ever reached. Runs at the dimension rebind and at finish, the two
     * whole-buffer drains, so what it sees is only what nothing else could rescue. Reported per position because the
     * aggregate gives no way to learn which shelf or hive is short.
     *
     * <p>At the rebind the drain is mandatory rather than merely honest: an old-dimension candidate's position must not
     * carry into the new dimension's shared {@link ChunkPos} space, where a same-type block could take wrong-dimension
     * content.
     */
    private void countDroppedInteractionCaptures() {
        InteractionCapture capture = this.interactionCapture;
        if (capture == null) {
            return;
        }
        List<BlockPos> dropped = capture.drainResidualPositions();
        if (dropped.isEmpty()) {
            return;
        }
        interactionCapturesLost += dropped.size();
        LOGGER.warn("{} predicted interactions were never written: their chunk was not captured again before the "
                + "download moved on, so the book, disc or placed container the player put there is missing from "
                + "the save", dropped.size());
        for (BlockPos pos : dropped) {
            LOGGER.info("predicted interaction at {} was dropped; its content is missing from the save", pos);
        }
    }

    /**
     * Record an optimistically-captured bookshelf slot (the interaction recognizer's callback): OR the slot into the
     * outline's per-dimension mask and, when this insert completes every occupied slot, count the bookshelf as one
     * downloaded container, deduped by pos so re-cycling the same shelf does not double-count. The clicked slot is
     * empty pre-click, so the full occupancy is the pre-insert mask plus this slot; a bookshelf still missing a slot is
     * not counted, and {@link #tallyInteractionPositions} skips it at flush so a partly-cycled shelf never counts there
     * either.
     */
    private void onBookshelfSlotCaptured(long posKey, int slot, int occupiedBeforeInsert) {
        int capturedMask = bookshelfSlots.get(posKey) | (1 << slot);
        bookshelfSlots.put(posKey, capturedMask);
        if (OutlineClassifier.classifyBookshelf(occupiedBeforeInsert | (1 << slot), capturedMask,
                0) == OutlineClass.CAPTURED) {
            reportCounts.addContainer("i:" + posKey);
        }
    }

    /**
     * Record an optimistically-captured placed container (the interaction recognizer's callback): mark its pos captured
     * this session so the outline clears its rim the moment it is placed, the way {@link #onBookshelfSlotCaptured}
     * marks a bookshelf slot and the open-time stash marks a chest on open, and record its block-entity type so Gate 2
     * re-rims the position if a different container later replaces it (the same pairing the open-time stashes make
     * through {@link #recordBlockType}). The flush reconcile still decides whether the contents reach disk; a placement
     * that loses its cell to a later block is no longer loaded to rim, so the optimistic mark cannot mis-clear a live
     * container.
     */
    private void onPlacedContainerCaptured(long posKey, String blockTypeId) {
        capturedBlockKeys.add(posKey);
        capturedBlockTypes.put(posKey, blockTypeId);
    }

    /**
     * Drop everything this session captured for a cell a placement is landing in (the interaction recognizer's
     * callback): the block those holders were lifted from is being replaced, and a replacement of the same block-entity
     * type leaves both the position key and the merge-time type gate matching, so no downstream guard can tell the
     * stale contents from the new block's. Removing the holder rather than out-ranking it at merge time is what also
     * covers the residual sweep, which folds a still-stashed holder straight onto the on-disk chunk without consulting
     * any place-time prediction. The outline sets go with it, so a rim cleared by the old block's capture does not keep
     * claiming the new one is downloaded.
     */
    private void onBlockPlacedAt(long posKey) {
        BlockPos pos = BlockPos.of(posKey);
        containerStash.remove(pos);
        lecternStash.remove(pos);
        unmarkCaptured(posKey);
        // The sixth store this staleness reaches is on disk, where the carry-forward matches position and type
        // and so cannot tell a same-type replacement from the block it replaced.
        replacedBlockKeys.add(posKey);
    }

    /**
     * Un-mark a position the outline was told carries downloaded contents, for a placement that gives those contents
     * up. Membership tracks contents-stashed-for-save, so a rim left clear over a container nothing will write tells
     * the player the opposite of the truth.
     */
    private void unmarkCaptured(long posKey) {
        capturedBlockKeys.remove(posKey);
        capturedBlockTypes.remove(posKey);
        bookshelfSlots.remove(posKey);
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
     * The entity the capture window, the keep-hot window, the coverage disc, and the edit zone all center on, and the
     * source of the saved world's spawn.
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
        return camera != null && camera.level == this.level() ? camera : player;
    }

    /**
     * The finish-snapshot position anchor: a seated player anchors to its root vehicle's block (a standing-height
     * coordinate over the vehicle's own captured resting floor or water), a standing player to the ordinary camera
     * anchor. Keyed on {@code isPassenger()}, never on whether a RootVehicle was written, so a vehicle carrying more
     * than one player or a save-refused mount still gets the safe vehicle-block Pos instead of the floored
     * passenger-offset seat coordinate. Pure and package-private so the seated-versus-standing choice is
     * headless-testable; {@link #anchorEntity} stays the live-only camera resolver.
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
        if (player != null && lastPlayer.get() != player) {
            lastPlayer = new WeakReference<>(player);
            if (openClickTracker != null) {
                // A respawn builds a fresh LocalPlayer even when the dimension is unchanged, and copies the
                // old entity id onto it, so object identity is the only signal that one happened. Without
                // this, dying with a latched click on a barrel and opening a same-size container at the
                // respawn point inside the window writes it onto the death-site barrel's pos.
                openClickTracker.reset();
            }
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
            int capChunks = Math.max(minecraft.options.renderDistance, 2);
            recordCoveredDisc(hotCenter, capChunks);
            if (capture != null) {
                SendRangeSampler sampler = capture.sampler();
                int sweepGeneration = sampler.sweepBeginGeneration();
                if (sweepGeneration != 0) {
                    // The start-window (and every re-arm's) one-shot seed sweep: window-suppressed primes
                    // registered without sampling; replay the book against the current player position.
                    // Ids gone from the live level are Respawn-race orphans and never sample.
                    String dimensionId = level().dimension().location().toString();
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
            if (config.captureContainers()) {
                captureOpenContainer(minecraft, player);
                compactClosedStashHolders();
            }
            // Warm + refresh the async stats counter on one guarded modulo path. captureTicks starts at 0
            // so this fires on the first guarded tick (warm-up) and every STATS_REFRESH_PERIOD_TICKS after; the
            // counter carries across portals on the same connection. Plain vanilla client API (no mixin).
            if (config.captureStatistics() && captureTicks % STATS_REFRESH_PERIOD_TICKS == 0) {
                ClientPacketListener connection = minecraft.getConnection();
                if (connection != null) {
                    connection.send(new ServerboundClientCommandPacket(
                            ServerboundClientCommandPacket.Action.REQUEST_STATS));
                }
            }
            if (openClickTracker != null) {
                openClickTracker.tick();
                openClickTracker.dismissClickOnMountedVehicle(player.getVehicle());
                openClickTracker.claimOpenInventoryRequest(player.getVehicle());
            }
            captureTicks++;
        }
        // The flush pump runs regardless of the guard above: when capture pauses (the
        // player is gone or in another dimension) the bounded buffer must keep draining to disk instead of
        // accumulating until finish(), or the memory bound is defeated exactly in the roam-then-leave case.
        pumpFlush(hotCenter);
    }

    /**
     * Record this tick's entity-coverage disc for the two-tone overlay: every chunk within the measured send range (in
     * chunks, Euclidean, chunk center) of the player's path is marked covered, so a saved chunk outside every swept
     * disc is entity-suspect. The radius (the estimator's single per-dimension running max over the three sampler-gated
     * feeds, clamped to the live render distance) is re-read every tick, ahead of the same-chunk early return, so a
     * change (the max growing on a new sample, or the clamp dropping with the view distance) triggers one recompute
     * over the whole trail even while the player stands still; the per-center caps recorded with the trail keep that
     * retroactive paint honest. The trail append and the disc add stay gated on the player crossing into a new chunk.
     * Keyed by the live client dimension id so it lines up with the saved record-site and the overlay providers' query.
     * While the range is still uncalibrated the radius is 0 (only the center chunk), which the overlay's cold-start
     * read hides by mirroring the saved set until the range is measured.
     */
    private void recordCoveredDisc(ChunkPos hotCenter, int capChunks) {
        String dimensionId = level().dimension().location().toString();
        int radius = sendRange.radiusChunks(dimensionId, capChunks);
        if (radius != lastCoveredRadius) {
            coveredIndex.recompute(dimensionId, radius); // the range changed: rebuild covered over the whole trail
            lastCoveredRadius = radius;
        }
        if (lastCoveredCenter != null && lastCoveredCenter.equals(hotCenter)) {
            return;
        }
        coveredIndex.recordTrail(dimensionId, hotCenter.x, hotCenter.z, capChunks);
        coveredIndex.addDisc(dimensionId, hotCenter.x, hotCenter.z, radius);
        lastCoveredCenter = hotCenter;
    }

    /**
     * Enroll every store whose contents belong to one dimension with {@link #dimensionRebind}, which then owns both
     * what a dimension change does to each and the order it does it in. A store enrolled here is one whose keys are
     * dimension-local, so the same key names a different thing in the next dimension; the stashes keyed by entity UUID
     * are globally unique and deliberately absent.
     */
    private void registerDimensionScopedStores() {
        dimensionRebind.registerDrain(this::writeOutDimensionBeingLeft);
        dimensionRebind.registerDrain(this::countDroppedInteractionCaptures);
        dimensionRebind.registerSwap(dimension -> this.allCaptured = capturedFor(dimension));
        dimensionRebind.registerSwap(dimension -> this.capturedBlockKeys = capturedBlockKeysFor(dimension));
        dimensionRebind.registerSwap(dimension -> this.replacedBlockKeys = replacedBlockKeysFor(dimension));
        dimensionRebind.registerSwap(dimension -> this.bookshelfSlots = bookshelfSlotsFor(dimension));
        dimensionRebind.registerSwap(dimension -> this.capturedBlockTypes = capturedBlockTypesFor(dimension));
        dimensionRebind.registerClear(capturedThisTick::clear);
        // Stale old-dimension positions; the new dimension refills the queue from its own buffer and re-scans
        // its own on-disk priors for recovered coverage.
        dimensionRebind.registerClear(floorQueue::clear);
        dimensionRebind.registerClear(recoveryScanned::clear);
        dimensionRebind.registerClear(() -> {
            lastCoveredCenter = null; // the first tick in the new dimension seeds its disc under its own key
            lastCoveredRadius = 0; // the covered set is per dimension, so it recomputes from its own trail
        });
        // The cross-dimension jump is not a spurious fast tick; the Respawn already armed the suppression
        // window in-stream.
        dimensionRebind.registerClear(() -> tickBaselineValid = false);
        dimensionRebind.registerClear(() -> {
            if (openClickTracker != null) {
                openClickTracker.reset(); // an old-dimension click must not seed a same-coordinate open here
            }
        });
    }

    /**
     * Follow the player into a new dimension: hand every dimension-scoped store to {@link #dimensionRebind}, whose
     * drains land the old dimension's held entities, buffers and stashes in its own folder before its swaps retarget to
     * the new one. The bound level, writer target and live dimension key advance after that call, since the drains
     * write through them and must reach the dimension being left. The registries are connection-global, so they need no
     * rebind; the open menu closes on a dimension change, so the menu-bound stashes reset on the next tick. Capture
     * resumes this same tick in the new dimension.
     */
    private void rebindDimension(ClientLevel newLevel) {
        rebindDimension(VanillaDimensions.forType(newLevel.dimensionType()), newLevel.dimension());
        this.level = newLevel;
    }

    /**
     * The rebind a headless test can drive: the keys the session takes from its {@link ClientLevel} arrive directly,
     * and the bound level stays the caller's to advance.
     */
    void rebindDimension(ResourceKey<Level> newTarget, ResourceKey<Level> liveDimension) {
        dimensionRebind.rebind(newTarget);
        this.targetDimension = newTarget;
        this.liveDimensionId = liveDimension.location().toString();
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

    /**
     * The live FULL chunk at {@code pos}, or null if none is loaded or it is a Bobby cached chunk. Bobby swaps a cached
     * {@code FakeChunk} into the {@code ClientChunkCache} slot the server left empty, and it passes the
     * {@code getLevel()} canary, so it must be excluded here; treating it as null routes it through the existing
     * "server never sent it" skip.
     */
    private @Nullable LevelChunk liveChunkAt(ClientChunkCache chunkSource, ChunkPos pos) {
        LevelChunk chunk = chunkSource.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
        return (chunk == null || bobbyFilter.isBobbyChunk(chunk)) ? null : chunk;
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
        int radius = minecraft.options.renderDistance;
        int plausibleMaxBlocks = SendRangeSampler.plausibleMaxBlocks(radius);
        ClientChunkCache chunkSource = level().getChunkSource();
        ChunkCodec codec = adapter.chunkCodec();

        // The live client dimension id: on a Multiverse/Paper server this is the server's custom id
        // (e.g. minecraft:worlds/2b2t/2b2t_1), which is what the overlay providers query the overlay under, so
        // the overlay index keys by this rather than the vanilla-mapped disk key. Same for every chunk this call.
        String overlayDimension = level().dimension().location().toString();

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
            LevelChunk chunk = liveChunkAt(chunkSource, pos);
            if (chunk == null) {
                continue; // not a loaded chunk at this position (or an unloaded keep-hot margin chunk)
            }
            if (!hasEncodeBudget()) {
                break; // out of budget: the rest of the square (still uncaptured) spills to a later tick
            }
            // Safety canary: capture only ever touches Minecraft.level (ClientLevel)
            // chunks, which are never persisted, so arming their unsaved flag cannot suppress a real singleplayer
            // save. The chunk comes from the bound level's own source, so this holds for a first capture and a
            // revisit re-buffer alike.
            assert chunk.getLevel() == level : "capture touched a chunk outside the bound ClientLevel";
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
                overlayIndex.add(overlayDimension, posKey);
                // back-fill entities loaded before their AddEntity could be teed
                captureLoadedEntities(pos, player, plausibleMaxBlocks, overlayDimension);
                if (recaptureMode.refreshesHotChunks()) {
                    attachRecapture(chunk, pos);
                }
            } catch (RuntimeException e) {
                // Uncounted on purpose: what a throw here costs is the overlay's record of this position, the
                // tail of its entity prime, whose size this catch cannot see (the entities primed before it
                // are buffered and will write), or the re-capture arm, which costs freshness rather than data.
                LOGGER.warn("chunk {} was captured but its follow-up steps failed; entities that were already "
                        + "loaded when the download started may be missing from it", pos, e);
            }
        }
    }

    /**
     * Record one chunk whose terrain snapshot threw: count it once for that position, however many ticks it re-throws
     * for, and name it once on the loss voice. Neither membership guard above the snapshot call holds for such a
     * position, since it enters neither the keep-hot buffer nor the captured set, so the square retries it every tick
     * it stays loaded and in range and an undeduped tally would inflate without bound. The dedup is held per dimension
     * because the position space is dimension-local.
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
            chunkCaptureLoss.lost(pos, cause);
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
    private void captureLoadedEntities(ChunkPos pos, LocalPlayer player, int plausibleMaxBlocks,
            String overlayDimensionId) {
        EntityPacketCapture capture = this.packetCapture;
        if (capture == null) {
            return; // entity capture is off
        }
        // An entity resting on the topmost placeable block has its box bottom exactly where a build-height bound
        // stops, and the intersection test is strict, so such a bound misses it. Y carries nothing here anyway: the
        // result is narrowed to the chunk's own column below, and vanilla keys a saved entity by that column alone.
        AABB bounds = new AABB(pos.getMinBlockX(), DimensionType.MIN_Y << 4, pos.getMinBlockZ(),
                pos.getMaxBlockX() + 1, DimensionType.MAX_Y << 4, pos.getMaxBlockZ() + 1);
        for (Entity entity : level().getEntitiesOfClass(Entity.class, bounds)) {
            // Under the default recapture config (EVERYWHERE) a revisited chunk re-primes once it leaves the
            // hot buffer, so returning through a portal to captured terrain re-seeds; only the OFF and NEARBY
            // modes skip revisits. The prime loop can also see client-side-only entities spawned by other
            // mods, a mod-compat over-claim edge with no vanilla instance, accepted.
            capture.primeSeed(entity, player.getX(), player.getZ(), plausibleMaxBlocks, overlayDimensionId);
            if (entity instanceof Player || capture.tracks(entity.getId()) || !entity.chunkPosition().equals(pos)) {
                continue; // players are not saved as entities; a tracked entity is the packet path's; a straddling
                         // entity is buffered only by the chunk it sits in (so it is saved exactly once)
            }
            UUID uuid = entity.getUUID();
            if (entity.shouldBeSaved()) {
                reportCounts.addEntity(uuid); // dedup-by-UUID; matches the packet path's count semantics
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
     *
     * <p>Package-private so the loss its own catch counts stays testable.
     */
    /** The client entity with this uuid, or null when none is loaded. The client has no by-uuid index at this band. */
    private @Nullable Entity entityByUuid(UUID uuid) {
        for (Entity entity : level().entitiesForRendering()) {
            if (entity.getUUID().equals(uuid)) {
                return entity;
            }
        }
        return null;
    }

    void retryRefusedPrimes() {
        EntityPacketCapture capture = this.packetCapture;
        if (capture == null) {
            return;
        }
        for (UUID uuid : primeRefusedEntities) {
            // Per entity, matching the encode's own isolation: a modded shouldBeSaved can throw, and one that does
            // must not cost every refusal behind it in the iteration.
            try {
                Entity entity = entityByUuid(uuid);
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
                reportCounts.addEntity(uuid);
                CompoundTag tag = encodeSingleEntity(entity, pos, EntitySource.PRIMED);
                if (tag != null) {
                    entityBuffer.accumulate(uuid, pos, tag);
                    bufferedEntitySources.put(uuid, EntitySource.PRIMED);
                    recordSaved(uuid, entity);
                }
            } catch (RuntimeException e) {
                // The try spans past the buffer write, and recordSaved's passenger walk is modded-overridable
                // too, so a throw after the tag is buffered would otherwise count an entity that reaches disk.
                if (!savedEntities.contains(uuid)) {
                    recordEntityEncodeFailure(EntitySource.PRIMED);
                }
                LOGGER.warn("skipping the refused-entity retry for {}; it may be missing from the save", uuid, e);
            }
        }
        primeRefusedEntities.clear();
    }

    /**
     * Clear the chunk's construction-time {@code unsaved=true} so the first post-capture poll does not flag every
     * freshly captured chunk dirty. Below the 1.21.2 unsaved-listener there is no change callback, so a real
     * post-capture block-STATE change re-sets the flag and {@link #pollDirtyChunks} picks it up on the next tick.
     */
    private void attachRecapture(LevelChunk chunk, ChunkPos pos) {
        capturedThisTick.add(pos.toLong());
        chunk.setUnsaved(false);
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
        pollDirtyChunks(chunkSource, dirtySet);
        drainDirtySlice(dirtySet, slice, codec, chunkSource, reencodedThisTick);
        runFloorSlice(slice, codec, chunkSource, reencodedThisTick);
    }

    /**
     * Populate the change-driven dirty set by polling {@code isUnsaved()} across the keep-hot buffer. Below the 1.21.2
     * {@code setUnsavedListener} push there is no change callback, so this pull replaces it. The buffer is bounded to
     * the keep-hot square around the player, so a full scan per tick stays cheap; it does no encoding, so it spends no
     * encode budget. Only chunks a real block-state change re-flagged since their last re-encode are added, and each
     * re-encode clears the flag again ({@link #reencode}), so a chunk re-enters only on a fresh change.
     */
    private void pollDirtyChunks(ClientChunkCache chunkSource, LongOpenHashSet dirtySet) {
        for (ChunkPos pos : captured.keySet()) {
            LevelChunk chunk = liveChunkAt(chunkSource, pos);
            if (chunk != null && chunk.isUnsaved()) {
                dirtySet.add(pos.toLong());
            }
        }
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
        LevelChunk chunk = liveChunkAt(chunkSource, pos);
        if (!RecapturePolicy.shouldRecapture(captured.containsKey(pos), chunk != null)) {
            dirtyRemove(key); // a flushed chunk's stale dirty entry is dropped so the set stays bounded
            return;
        }
        if (chunk == null) {
            return; // unreachable given shouldRecapture above; the explicit check narrows nullness
        }
        // Safety canary: re-capture must only ever touch Minecraft.level
        // (ClientLevel) chunks, which are never persisted, so clearing their unsaved flag cannot suppress a
        // real singleplayer save. The chunk is fetched from the bound level's own source, so this holds.
        assert chunk.getLevel() == level : "re-capture touched a chunk outside the bound ClientLevel";
        try {
            captured.put(pos, codec.capture(chunk, registries));
            reencodedThisTick.add(key);
            dirtyRemove(key);
            chunk.setUnsaved(false); // re-arm the unsaved flag so the next block-state change re-flags this chunk
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
     * Re-encode the still-loaded chunks holding a pending interaction candidate, just before {@link #detachRecapture}
     * drops the dirty set (the interaction staleness window). {@link #reencode} no-ops an unloaded chunk, whose
     * snapshot is already frozen at its last-loaded authoritative state, so only the loaded ones refresh and the
     * reconcile gate then reads a post-ack snapshot. Skips a chunk the edit-zone burst already refreshed this tick (the
     * shared {@code reencodedThisTick}).
     */
    private void reencodePendingInteractionChunks(ClientChunkCache chunkSource, ChunkCodec codec,
            LongOpenHashSet reencodedThisTick) {
        InteractionCapture capture = this.interactionCapture;
        if (capture == null) {
            return;
        }
        for (ChunkPos pos : capture.pendingCandidateChunks()) {
            reencode(pos, codec, chunkSource, reencodedThisTick);
        }
    }

    @Override
    public CaptureCounts counts() {
        // Containers and entities read the dedup-correct running tally (a double chest as one, the ender chest
        // once, each entity once by UUID), not the stash sums, which double-count a chest pair and never carry an
        // entity figure. Chunks stay the live captured-position total: the report tally only gains chunks at
        // finish, so it is empty here while recording.
        return new CaptureCounts(totalCapturedChunks(), reportCounts.containerCount(), reportCounts.entityCount());
    }

    @Override
    public CapturedContainers capturedContainers() {
        return new CapturedContainers(capturedBlockKeys, capturedEntityIds, enderChestStash != null, bookshelfSlots,
                capturedBlockTypes);
    }

    @Override
    public RecoveredCoverage recoveredCoverage() {
        return recoveredScan.coverage(targetDimension);
    }

    @Override
    public CaptureToggles latchedToggles() {
        return CaptureToggles.latchedBy(config, target.mode() == DownloadMode.RESUME);
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

    /**
     * Capture an open container's contents. Container items arrive only while the player has the container open (via
     * {@code ClientboundContainerSetContentPacket}), never in the chunk packet, so they are stashed here and merged
     * into their target at {@link #finish()}. Each recognition axis has its own bind leg and its own confidence test,
     * and an open that no leg claims confidently is DROPPED: mis-binding would write the wrong items onto a block or
     * entity (a corrupt archive) while an empty container is correct. The ender chest, the double chest, the lectern,
     * the chested animal and the container vehicle each bind through their own leg rather than being dropped; what is
     * dropped is an open whose target the click chain cannot account for, and any open whose slot count fails its leg's
     * size guard.
     *
     * <p>The binding is decided once when the menu first appears, from the target the player clicked (see
     * {@link ContainerCapture#resolveOpenTarget}, since the live crosshair keeps drifting until the menu freezes the
     * camera), and the contents are re-captured every tick while it stays open: last-seen-wins absorbs the one-tick lag
     * before the content packet populates the slots, and any edits the player makes.
     */
    private void captureOpenContainer(Minecraft minecraft, LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu) {
            association.close(); // no container open (the default inventory menu is "nothing")
            stashChangeTracker.reset();
            openContainerId = NO_MENU;
            return;
        }
        if (menu.containerId != openContainerId) {
            openContainerId = menu.containerId; // a new menu just appeared; decide its binding now
            stashChangeTracker.reset(); // the new menu must stash at least once, whatever its slots hold
            // Bind to the target the open resolved to, not the live crosshair: the crosshair keeps tracking the view
            // during the click-to-open round trip (no screen is open yet, so the camera is not frozen), so a nudge
            // while the container opens would otherwise bind the wrong block or drop the open. An open no click
            // accounts for resolves to an empty target and binds nothing, outside spectator.
            ContainerCapture.OpenTarget target = containerCapture.resolveOpenTarget(minecraft, player);
            BlockPos block = target.block();
            Entity entity = target.entity();
            boolean vehicleClaimsOpen = config.captureEntities()
                    && containerCapture.shouldClaimVehicleOpen(player, entity, target.vehicleIntent());
            AbstractChestedHorse chestedAnimal = config.captureEntities()
                    ? containerCapture.chestedAnimal(menu)
                    : null;
            // Branch order is load-bearing: the chested-animal branch precedes the entity-vehicle branch.
            // shouldClaimVehicleOpen is menu-type-blind, so a strength-1 llama's horse menu (5
            // non-player slots) ridden alongside a hopper minecart (size 5) would otherwise be claimed by the
            // vehicle branch and mis-merge the chest into the minecart. Only a mount menu names a chested
            // animal (no vehicle opens one), so peeling it off first cannot starve a vehicle capture.
            if (menu instanceof LecternMenu) {
                bindOpenedLectern(menu, player, block);
            } else if (menu instanceof MerchantMenu) {
                // Villager-exclusive, so this early arm steals no non-merchant open; placed above the
                // menu-type-blind vehicle arm so a villager opened while riding a chest vehicle is not pre-empted.
                if (config.captureEntities()) {
                    bindOpenedMerchant(entity);
                } else {
                    // The trade axis rides captureEntities, so with it off bind nothing and clear any prior bind,
                    // as the ender-chest toggle arm does, or the offers stash onto a stale container's position.
                    association.close();
                }
            } else if (menu instanceof ChestMenu && containerCapture.isEnderChestAt(level(), block)) {
                if (config.savePlayerEnderChest()) {
                    bindOpenedEnderChest(menu, player, block);
                } else {
                    // The finish strips EnderItems under this toggle, so binding would count a container the
                    // download never writes. Every other arm decides the binding through an association leg,
                    // which clears it on refusal; skipping the leg would leave the prior menu's binding live
                    // and stash these ender slots onto that container's position.
                    association.close();
                }
            } else if (containerCapture.isDoubleChestOpen(level(), menu, block)) {
                bindOpenedDoubleChest(menu, player, block);
            } else if (chestedAnimal != null) {
                bindOpenedChestedAnimal(menu, player, chestedAnimal);
            } else if (vehicleClaimsOpen) {
                bindOpenedEntityContainer(menu, player, entity);
            } else {
                bindOpenedContainer(menu, player, block);
            }
        }
        // Dispatch the stash by the remembered bind KIND, not the menu type: an ender chest, a chest minecart,
        // and a normal chest are all a ChestMenu, so only the kind set at bind time tells them apart.
        association.boundPos().ifPresent(posKey -> {
            if (association.boundKind() == ContainerAssociation.BindKind.MERCHANT
                    && menu instanceof MerchantMenu merchant) {
                // The offers and trade experience are disjoint from the three trade slots the change gate keys on,
                // so re-stash every tick on its own path: a switch case behind the gate would drop a mid-open
                // offers change and lose a whole villager whose offers packet lands a tick after the open.
                stashMerchantOffers(merchant);
                return;
            }
            int page = menu instanceof LecternMenu lectern ? lectern.getPage() : 0;
            int[] data = menuDataVector(menu);
            if (!stashChangeTracker.changedSince(menu.slots, page, data)) {
                return; // unchanged since the last stash: last-seen-wins needs no re-serialize this tick
            }
            switch (association.boundKind()) {
                case LECTERN -> stashLecternBook((LecternMenu) menu, posKey);
                case ENDER -> stashEnderItems(menu, player);
                case ENTITY -> stashEntityContainerItems(menu, player);
                case CHESTED_ANIMAL -> stashChestedAnimalItems(menu, player);
                case DOUBLE_CHEST -> stashDoubleChestItems(menu, player);
                case CONTAINER -> stashContainerItems(menu, player, posKey);
                default -> {}
            }
        });
    }

    /** Translate the open target (the block, the menu's slot count, the block's size) into primitives. */
    private void bindOpenedContainer(AbstractContainerMenu menu, LocalPlayer player, @Nullable BlockPos target) {
        boolean atBlock = false;
        long posKey = 0L;
        int menuSlotCount = 0;
        int blockContainerSize = 0;
        if (target != null) {
            atBlock = true;
            posKey = target.asLong();
            menuSlotCount = ContainerCapture.countBlockSlots(menu, player);
            // The client builds the menu from its MenuType with a generic SimpleContainer (never the block's
            // BlockEntity, nor a CompoundContainer for a double chest), so identity can't tie the menu to the
            // block. Instead bind only when the target block has its own storage container (size > 0,
            // excludes non-container blocks and ender chests, which are not BaseContainerBlockEntity) whose
            // size matches the menu's block-slot count; the guard drops the rest (a double chest is a 54-slot
            // menu over a 27-slot half -> mismatch).
            if (level().getBlockEntity(target) instanceof BaseContainerBlockEntity blockContainer) {
                blockContainerSize = blockContainer.getContainerSize();
            }
        }
        OptionalLong bound = association.open(atBlock, posKey, menuSlotCount, blockContainerSize);
        if (bound.isPresent()) {
            reportCounts.addContainer("b:" + bound.getAsLong());
            LOGGER.debug("bound open container to {}", BlockPos.of(bound.getAsLong()));
        } else if (target != null) {
            // Only a real block hit that the size guard rejected is signal (the mis-bind it avoided); a
            // null target is a menu with nothing to bind (targetless GUI, superseded click) and is already
            // explained by resolveOpenTarget's own logging, so it stays silent here.
            LOGGER.info("dropped open container at {}: menuSlots={}, blockContainerSize={}, chunkLoaded={}",
                    target, menuSlotCount, blockContainerSize,
                    level().hasChunk(SectionPos.blockToSectionCoord(target.getX()),
                            SectionPos.blockToSectionCoord(target.getZ())));
        }
    }

    /**
     * Translate the lectern-open target into primitives for {@link ContainerAssociation#openLectern}. A
     * {@code LecternMenu} is a fixed 1-slot lectern-specific menu, so the bind is confident when the open target is a
     * lectern block and the menu carries the lectern's one book slot. The book reaches the client only through this
     * menu, never the chunk packet.
     */
    private void bindOpenedLectern(AbstractContainerMenu menu, LocalPlayer player, @Nullable BlockPos target) {
        boolean atBlock = false;
        long posKey = 0L;
        boolean blockIsLectern = false;
        int menuSlotCount = 0;
        if (target != null) {
            atBlock = true;
            posKey = target.asLong();
            blockIsLectern = level().getBlockEntity(target) instanceof LecternBlockEntity;
            menuSlotCount = ContainerCapture.countBlockSlots(menu, player);
        }
        OptionalLong bound = association.openLectern(atBlock, posKey, blockIsLectern, menuSlotCount,
                ContainerCapture.LECTERN_CONTAINER_SIZE);
        if (bound.isPresent()) {
            reportCounts.addContainer("l:" + bound.getAsLong());
            LOGGER.debug("bound open lectern to {}", BlockPos.of(bound.getAsLong()));
        }
    }

    /**
     * Translate the ender-chest-open signals into primitives for {@link ContainerAssociation#openEnderChest}. An ender
     * chest is a {@code ChestMenu} like a normal single chest, so the target block being an
     * {@code EnderChestBlockEntity} is the discriminator (already checked by the dispatch, so {@code blockIsEnderChest}
     * is true at this call site; the parameter keeps the negative unit-testable). The size compared is the player's own
     * ender inventory, because an ender chest block has no container of its own to report one. The contents still come
     * from the menu, as on every other leg: the client's ender container is never synced, so only its size is
     * trustworthy here. What they describe is that global ender inventory, so the stash merges into the player tag at
     * finish rather than into a chunk block entity.
     */
    private void bindOpenedEnderChest(AbstractContainerMenu menu, LocalPlayer player, @Nullable BlockPos target) {
        boolean atBlock = false;
        long posKey = 0L;
        boolean blockIsEnderChest = false;
        int menuSlotCount = 0;
        if (target != null) {
            atBlock = true;
            posKey = target.asLong();
            blockIsEnderChest = level().getBlockEntity(target) instanceof EnderChestBlockEntity;
            menuSlotCount = ContainerCapture.countBlockSlots(menu, player);
        }
        OptionalLong bound = association.openEnderChest(atBlock, posKey, menu instanceof ChestMenu,
                blockIsEnderChest, menuSlotCount, player.getEnderChestInventory().getContainerSize());
        if (bound.isPresent()) {
            // One shared inventory per session: a single fixed id rides the Set dedup, so re-opening the ender
            // chest never bumps the count past one.
            reportCounts.addContainer("e:");
            LOGGER.debug("bound open ender chest to {}", BlockPos.of(bound.getAsLong()));
        }
    }

    /**
     * Translate the double-chest open signals into primitives for {@link ContainerAssociation#openDoubleChest}. The
     * double-chest analog of {@link #bindOpenedContainer}: a large chest opens a 54-slot {@code ChestMenu} over two
     * 27-slot halves, which the single-block path drops on the 54-vs-27 mismatch. The bind candidate is the target half
     * plus its connected partner (derived from the target block state via {@link ChestBlock#getConnectedBlockPos}); the
     * sum of the two halves' sizes is the mis-bind guard. Which target half is the RIGHT one is the only left/right
     * line here; the core stores the two halves in menu-slot order from it. If the partner is not a loaded chest the
     * combined size stays at one half and the sum guard drops the open (empty, correct); in practice both halves are
     * always loaded when a double opens.
     */
    private void bindOpenedDoubleChest(AbstractContainerMenu menu, LocalPlayer player, @Nullable BlockPos target) {
        boolean atBlock = false;
        boolean atRightHalf = false;
        long targetPosKey = 0L;
        long partnerPosKey = 0L;
        int menuSlotCount = 0;
        int combinedContainerSize = 0;
        if (target != null) {
            BlockState state = level().getBlockState(target);
            if (level().getBlockEntity(target) instanceof ChestBlockEntity targetHalf
                    && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                atBlock = true;
                atRightHalf = state.getValue(ChestBlock.TYPE) == ChestType.RIGHT; // the only left/right line
                targetPosKey = target.asLong();
                BlockPos partner = target.relative(ChestBlock.getConnectedDirection(state));
                partnerPosKey = partner.asLong();
                menuSlotCount = ContainerCapture.countBlockSlots(menu, player); // 54 for a real double open
                combinedContainerSize = targetHalf.getContainerSize(); // 27
                if (level().getBlockEntity(partner) instanceof ChestBlockEntity partnerBlockEntity) {
                    combinedContainerSize += partnerBlockEntity.getContainerSize(); // + 27 = 54
                }
            }
        }
        if (association.openDoubleChest(atBlock, atRightHalf, targetPosKey, partnerPosKey,
                menuSlotCount, combinedContainerSize)) {
            reportCounts.addContainer("d:" + targetPosKey); // a double chest is one container in the count
            LOGGER.debug("bound double chest at {} + {}", BlockPos.of(targetPosKey), BlockPos.of(partnerPosKey));
        }
    }

    /**
     * Translate the container-vehicle open signals into primitives for {@link ContainerAssociation#openEntityContainer}
     * and, on a confident bind, store the entity UUID the finish merge keys on. The bind target is the target vehicle,
     * or, when there is none, the container vehicle the player is riding (the press-E flow: a chest boat opens its menu
     * via {@code player.getVehicle()}, firing no use event). The UUID is read once here (not re-read per tick): the
     * bind key is stable, so a minecart that keeps rolling while open still captures correctly and merges into whatever
     * chunk it ends in. A NULL target vehicle at the bind tick drops the open. A REMOVED one does not: the type test
     * stays true for an entity already taken out of the world and the clicked reference is held strongly, so a
     * destroyed chest minecart still binds by UUID and its stash merges onto an entity the save may not carry.
     */
    private void bindOpenedEntityContainer(AbstractContainerMenu menu, LocalPlayer player, @Nullable Entity target) {
        boolean atEntity = false;
        boolean entityIsVehicle = false;
        int menuSlotCount = 0;
        int entityContainerSize = 0;
        UUID uuid = null;
        Entity vehicle = target instanceof AbstractMinecartContainer ? target : player.getVehicle();
        if (vehicle instanceof AbstractMinecartContainer containerVehicle) {
            atEntity = true;
            entityIsVehicle = true;
            entityContainerSize = containerVehicle.getContainerSize();
            uuid = vehicle.getUUID();
            menuSlotCount = ContainerCapture.countBlockSlots(menu, player);
        }
        if (association.openEntityContainer(atEntity, entityIsVehicle, menuSlotCount, entityContainerSize)
                && uuid != null) {
            boundEntityUuid = uuid;
            reportCounts.addContainer("v:" + uuid);
            LOGGER.debug("bound open entity container to {}", uuid);
        }
    }

    /**
     * Translate the chested-animal open signals into primitives for {@link ContainerAssociation#openChestedAnimal} and,
     * on a confident bind, store the entity UUID the finish merge keys on. The chested-animal analog of
     * {@link #bindOpenedEntityContainer}, with one difference that is the whole point of it: the animal is the one the
     * MENU names, so nothing about the crosshair or the ridden vehicle can put another animal's chest on this open. The
     * chest size is the live size ({@code getInventoryColumns() * 3}; llama strength is synced), and the menu
     * chest-slot count is read the same tick, so the slot-count match still drops a stale or mismatched open. The UUID
     * is read once here; a chested animal that wanders while the menu stays open still merges into whatever chunk it
     * ends in.
     */
    private void bindOpenedChestedAnimal(AbstractContainerMenu menu, LocalPlayer player,
            AbstractChestedHorse animal) {
        int entityChestSize = animal.getInventoryColumns() * 3; // live size; llama strength is synced
        int menuChestSlotCount = ContainerCapture.countChestSlots(menu, player);
        if (association.openChestedAnimal(true, true, menuChestSlotCount, entityChestSize)) {
            UUID uuid = animal.getUUID();
            boundEntityUuid = uuid;
            reportCounts.addContainer("a:" + uuid);
            LOGGER.debug("bound open chested animal to {}", uuid);
        }
    }

    /**
     * Bind a freshly-opened merchant menu to the click-tracked villager, or drop it. Unlike the block and vehicle binds
     * this is identity-only: a merchant menu's offers come from a list with no slotted container to size-match, so the
     * confidence is the menu type (villager-exclusive) plus the villager the open resolved to. The instanceof narrowing
     * sits inside the guard so openMerchant records the drop for a non-villager target either way; a null or
     * non-villager target binds nothing, per the drop-on-uncertainty rule. The trade count is added at real capture in
     * {@link #stashMerchantOffers}, not here, since the offers can land a tick after the open.
     */
    private void bindOpenedMerchant(@Nullable Entity target) {
        if (association.openMerchant(target instanceof AbstractVillager, true)
                && target instanceof AbstractVillager villager) {
            boundEntityUuid = villager.getUUID();
            boundMerchantIsVillager = villager instanceof Villager;
            LOGGER.debug("bound open merchant to {}", villager.getUUID());
        }
    }

    /**
     * Record the captured block-entity type at {@code pos} for both staleness gates: stamp it on the drained
     * {@code holder} as {@code wdl_block_entity_id} for the writer-thread merge gate (Gate 1) and into the
     * per-dimension type map the outline reads for the rim gate (Gate 2). A missing block entity (the open menu's block
     * already gone) leaves the holder untyped, so both gates fall back to their pre-gate behavior.
     */
    @SuppressWarnings("NullAway") // getKey is non-null for a live block entity's registered type
    private void recordBlockType(BlockPos pos, CompoundTag holder) {
        BlockEntity blockEntity = level().getBlockEntity(pos);
        if (blockEntity != null) {
            String typeId = Registry.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString();
            holder.putString("wdl_block_entity_id", typeId);
            capturedBlockTypes.put(pos.asLong(), typeId);
        }
    }

    /** Record the block type, stash the drained holder by pos, and mark the block captured. */
    private void stashBlockHolder(Map<BlockPos, StashHolder> stash, BlockPos pos, CompoundTag holder) {
        recordBlockType(pos, holder);
        stash.put(pos, StashHolder.of(holder));
        capturedBlockKeys.add(pos.asLong());
    }

    /**
     * Serialize the bound block container's current slots (each at its container-slot index) and stash them keyed by
     * block pos, overwriting any earlier capture for the same open menu (last-seen-wins).
     */
    private void stashContainerItems(AbstractContainerMenu menu, LocalPlayer player, long posKey) {
        CompoundTag holder = containerCapture.captureBlockSlots(menu, player);
        if (holder != null) {
            if (menu instanceof BrewingStandMenu brewingStand) {
                // Items capture stays generic; only the rider is menu-typed, and the write itself
                // is the pure tested helper.
                ContainerCapture.putBrewingState(holder, brewingStand.getBrewingTicks(),
                        brewingStand.getFuel());
            }
            stashBlockHolder(containerStash, BlockPos.of(posKey), holder);
        }
    }

    /**
     * The menu-only ContainerData values the change gate tracks beside the slots: the brewing stand's brew time plus
     * fuel. Every other menu tracks no data; the state-less constant keeps the per-tick call allocation-free for them.
     */
    private static int[] menuDataVector(AbstractContainerMenu menu) {
        if (menu instanceof BrewingStandMenu brewingStand) {
            return new int[] { brewingStand.getBrewingTicks(), brewingStand.getFuel() };
        }
        return MenuChangeTracker.NO_DATA;
    }

    /**
     * Split a bound double-chest menu's 54 slots 27/27 and stash each half into the existing block
     * {@link #containerStash} keyed by its own block pos, so the per-chunk {@link ContainerMerge#mergeChunkStash} folds
     * each into its chest block entity unchanged (a chunk-boundary straddle works because each half is keyed by its own
     * pos and matched within its own chunk tag). The two halves are stored in menu-slot order by the core, so
     * {@link ContainerAssociation#boundPos} is the first/RIGHT half (menu slots 0..n/2) and
     * {@link ContainerAssociation#boundSecondaryPos} the second/LEFT half. Last-seen-wins, like every block stash.
     */
    private void stashDoubleChestItems(AbstractContainerMenu menu, LocalPlayer player) {
        OptionalLong first = association.boundPos(); // the first/RIGHT half pos (menu slots 0..n/2)
        OptionalLong second = association.boundSecondaryPos(); // the second/LEFT half pos (menu slots n/2..n)
        if (first.isEmpty() || second.isEmpty()) {
            return; // bound but a half pos is missing (defensive)
        }
        int half = ContainerCapture.countBlockSlots(menu, player) / 2; // 27
        CompoundTag firstHolder = containerCapture.captureHalfSlots(menu, player, 0, half);
        CompoundTag secondHolder = containerCapture.captureHalfSlots(menu, player, half, 2 * half);
        if (firstHolder != null) {
            stashBlockHolder(containerStash, BlockPos.of(first.getAsLong()), firstHolder);
        }
        if (secondHolder != null) {
            stashBlockHolder(containerStash, BlockPos.of(second.getAsLong()), secondHolder);
        }
    }

    /**
     * Serialize the open ender-chest menu's 27 synthetic block slots (the client's synced ender contents) into the
     * single {@link #enderChestStash}, last-seen-wins. Reuses the container serialize; the merge into the player tag's
     * {@code "EnderItems"} happens at {@link #finish()}, not into a chunk.
     */
    private void stashEnderItems(AbstractContainerMenu menu, LocalPlayer player) {
        CompoundTag holder = containerCapture.captureBlockSlots(menu, player);
        if (holder != null) {
            enderChestStash = holder;
        }
    }

    /**
     * Serialize the open container-vehicle menu's synthetic block slots (the client's synced vehicle contents) into the
     * {@link #entityContainerStash} keyed by the bound UUID, last-seen-wins. Reuses the container serialize; the merge
     * into the entity's tag in the {@code entities/} region happens at {@link #finish()}.
     */
    private void stashEntityContainerItems(AbstractContainerMenu menu, LocalPlayer player) {
        UUID uuid = boundEntityUuid;
        if (uuid == null) {
            return; // bound but no uuid (defensive)
        }
        CompoundTag holder = containerCapture.captureBlockSlots(menu, player);
        if (holder != null) {
            entityContainerStash.put(uuid, holder);
            capturedEntityIds.add(uuid);
        }
    }

    /**
     * Serialize the open chested-animal menu's chest slots (the client's synced chest contents) into the
     * {@link #entityContainerStash} keyed by the bound UUID, last-seen-wins. The chested-animal analog of
     * {@link #stashEntityContainerItems}; it lifts the chest slots only ({@link ContainerCapture#captureChestSlots},
     * not the saddle/body), and the merge into the animal's tag in the {@code entities/} region happens when its chunk
     * flushes, through the same {@link EntityContainerMerge#mergeEntityStash} the container vehicles use.
     */
    private void stashChestedAnimalItems(AbstractContainerMenu menu, LocalPlayer player) {
        UUID uuid = boundEntityUuid;
        if (uuid == null) {
            return; // bound but no uuid (defensive)
        }
        CompoundTag holder = containerCapture.captureChestSlots(menu, player);
        if (holder != null) {
            entityContainerStash.put(uuid, holder);
            capturedEntityIds.add(uuid);
        }
    }

    /**
     * Re-serialize the open merchant's offers (and a villager's trade experience) every tick into
     * {@link #merchantStash} keyed by the bound villager UUID, last-seen-wins, skipping an empty offers so a re-open
     * before the offers packet lands never wipes a captured set. Runs outside the slot-keyed change gate (see the
     * caller). The encode is isolated per villager, the discipline every vanilla-serializer encode over client-held
     * state needs: a sell item whose codec rejects would otherwise throw out of the client tick every tick the screen
     * is open, so it is skipped, logged once, and remembered so it is not retried. The captured-set add clears the
     * outline rim and runs only on a successful encode, so a failed encode is never falsely reported as captured.
     */
    private void stashMerchantOffers(MerchantMenu merchant) {
        UUID uuid = boundEntityUuid;
        if (uuid == null || merchantEncodeFailed.contains(uuid)) {
            return; // no uuid, or this villager already threw once: do not retry every tick
        }
        MerchantOffers offers = merchant.getOffers();
        if (offers.isEmpty()) {
            return; // never overwrite a captured non-empty set with an empty tick
        }
        CompoundTag holder;
        try {
            holder = MerchantOfferCapture.serialize(offers, merchant.getTraderXp(), boundMerchantIsVillager);
        } catch (RuntimeException e) {
            merchantEncodeFailed.add(uuid);
            LOGGER.warn("skipping villager {} trades: offers encode failed", uuid, e);
            return;
        }
        merchantStash.put(uuid, holder);
        capturedEntityIds.add(uuid); // clears the outline rim, only on a successful encode
        reportCounts.addContainer("m:" + uuid); // count at real capture, not at bind
    }

    /**
     * Lift the bound lectern's slot-0 book and reading page from the open menu and stash them keyed by block pos,
     * overwriting any earlier capture for the same open menu (last-seen-wins, so a page turn re-stashes the live page).
     * An empty slot 0 removes any stash entry (mirrors {@code saveAdditional}'s {@code !isEmpty()} guard, and is the
     * take-the-book resurrection guard): client-coupled, so the empty-drop branch is not exercised headless (as with
     * {@code stashContainerItems}).
     */
    private void stashLecternBook(LecternMenu menu, long posKey) {
        ItemStack book = menu.getBook();
        BlockPos pos = BlockPos.of(posKey);
        if (book.isEmpty()) {
            lecternStash.remove(pos);
            capturedBlockKeys.remove(posKey);
            return;
        }
        CompoundTag holder = adapter.lecternSink().captureBook(book, menu.getPage(), registries);
        stashBlockHolder(lecternStash, pos, holder);
    }

    /**
     * Stream the chunks that have moved out of the keep-hot window to the background writer and drop them from memory,
     * so the buffer stays bounded as the player roams. Runs every tick (even when capture is paused). When capture is
     * paused ({@code hotCenter} is null, player gone or in another dimension) the whole buffer is drained, since no new
     * nearby chunks will be captured to keep it hot.
     */
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
        int keepHot = Minecraft.getInstance().options.renderDistance + KEEP_HOT_MARGIN;
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
     *
     */
    private void finishCapture() {
        // The finish drain must encode everything still loaded, so the per-tick encode budget does not apply
        // here (the burst below and the entity refresh run unbounded).
        encodeDeadlineNanos = Long.MAX_VALUE;
        // Save-time re-capture burst: refresh the player's immediate area one last time so a
        // just-placed block or edited sign is current at save, the freshness guarantee the coarse-cadence
        // edit zone leaves to here. Bounded to the edit zone (~9 chunks), a trivial main-thread cost. Run
        // before detaching so it sees the live state; skipped if the player is gone (a disconnect-flushed save).
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (config.recaptureChunks().refreshesHotChunks() && player != null && minecraft.level == level()) {
            capturedThisTick.clear(); // finish() is its own moment: the burst refreshes the area unconditionally
            LongOpenHashSet reencodedThisTick = new LongOpenHashSet();
            ChunkPos anchor = anchorEntity(minecraft, player).chunkPosition();
            recaptureEditZone(anchor, adapter.chunkCodec(), level().getChunkSource(), reencodedThisTick);
            // Close the interaction staleness window: re-encode any still-loaded pending-candidate chunk
            // outside the edit zone before the dirty set is dropped, so the reconcile gate reads a post-ack
            // snapshot rather than a stale-present one and cannot persist content the player just reverted.
            reencodePendingInteractionChunks(level().getChunkSource(), adapter.chunkCodec(), reencodedThisTick);
        }
        detachRecapture(); // teardown: release the dirty set; loaded chunks' listeners become inert
        deactivateInteractionCapture(); // stop the use-block hook feeding this session; the drain reads the instance
        deactivateOpenClickTracker(); // stop the use hooks seeding this session's open bind
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
        drainToWriter(activeWriter, minecraft, player);
    }

    /**
     * Everything a finish that opened a world still owes the writer: the last packet-entity drain, the whole-buffer
     * flush, the reconciliation and its loss counts, the saved player and progress snapshots, the resumed mount
     * release, and the frozen report counts, each one submitted or published before end-of-stream. Runs inside
     * {@link #completeThroughWriter}, whose guarantee is that the marker follows this however it ends.
     */
    // Package-private so the orphan loss is unit-testable: it sits in the Minecraft-coupled finish drain, which no
    // headless test can reach. An opened vehicle re-approached after its chunk flushed drains its stash entry before
    // this check, so whatever survives was never folded into a saved top-level entity; the user interacted with this
    // storage and its items are absent, so surface it as a loss.
    void countOrphanedContainerVehicles() {
        if (entityContainerStash.isEmpty()) {
            return;
        }
        containerVehiclesLost = entityContainerStash.size();
        LOGGER.warn("{} opened container vehicles' captured contents were not saved: the vehicle was not "
                + "written as a top-level entity we could fold them into (its terrain was never captured, it "
                + "saved nested as a passenger, or its reconstruct or flush failed), so the items the player "
                + "opened are lost", entityContainerStash.size());
        // Logged directly rather than through a loss voice because no throwable exists to key a stack
        // budget on; the aggregate count above gives no way to learn which mount saved empty.
        for (UUID vehicle : entityContainerStash.keySet()) {
            LOGGER.info("container vehicle {} saved without the contents the player opened; they are missing "
                    + "from the save", vehicle);
        }
    }

    // Package-private so the orphan loss is unit-testable: it sits in the Minecraft-coupled finish drain, which no
    // headless test can reach. mergeMerchantStash drains only villagers written as a top-level entity, so a captured
    // villager whose node was never written survives here, and its rim was already cleared at capture, so without
    // this it would read as captured yet write nothing.
    void countOrphanedMerchantTrades() {
        if (merchantStash.isEmpty()) {
            return;
        }
        villagerTradesLost = merchantStash.size();
        LOGGER.warn("{} opened villagers' captured trades were not saved: the villager was not written as a "
                + "top-level entity we could fold them onto (its terrain was never captured, it saved nested as "
                + "a passenger, or its reconstruct or flush failed), so the trades the player opened are lost",
                merchantStash.size());
        for (UUID villager : merchantStash.keySet()) {
            LOGGER.info("villager {} saved without the trades the player opened; they are missing from the save",
                    villager);
        }
    }

    private void drainToWriter(AsyncSaveWriter activeWriter, Minecraft minecraft, @Nullable LocalPlayer player) {
        // From here every newly imaged map batches instead of streaming alone, so the writer can report the map
        // phase over a known total. Armed before the first finish-time remap below and handed over after the last
        // one; the local spares that handover a null check on the field.
        List<Runnable> mapWrites = new ArrayList<>();
        this.finishMapWrites = mapWrites;
        // Filled maps are captured on-sight (the live remap table streams each one as it is first imaged): a
        // container's maps are remapped and serialized in flushBuffer just before the holder drains, and the
        // still-live sources are handled at finish: the inventory and ender chest in assembleCapturedPlayer, and
        // the packet-captured item frames / dropped items / container vehicles when their tags drain.
        // Stop the inbound tee before the finish drain so no spawn arrives mid-drain to be left unwritten and
        // uncounted; the drain and the reconciliation then see a settled accumulator.
        deactivatePacketCapture();
        // Snapshot a ridden vehicle into the player's RootVehicle before the drain, so its UUID is held from the
        // standalone write below and its stashed contents drain before the not-saved check. The mount is
        // player-state, so this is not gated on captureEntities.
        if (player != null && minecraft.level == level()) {
            prepareRootVehicleCapture(player);
        }
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
        countDroppedInteractionCaptures(); // likewise: only what the whole-buffer drain could not reach
        countOrphanedContainerVehicles();
        countOrphanedMerchantTrades();
        // Snapshot and assemble the local player for the save (skipped on a disconnect-flush). Fail-soft:
        // a serialize or scrub throw here, after chunks have committed, must not abort before
        // activeWriter.finish() and leave a chunks-without-level.dat unopenable world plus a leaked lock, so
        // any throw degrades to a null capturedPlayer (the openable void-world level.dat) and the save runs on.
        if (player != null && minecraft.level == level()) {
            this.capturedPlayer = failSoft("player", () -> assembleCapturedPlayer(player, minecraft));
            this.capturedProgress = failSoft("progress", () -> assembleCapturedProgress(player, minecraft));
            UUID salvageAttach = rootVehicleAttach;
            CompoundTag salvageMount = rootVehicleTag;
            if (this.capturedPlayer == null && salvageMount != null && salvageAttach != null) {
                // The full player assembly threw (the void-world fail-soft), but a seated mount was already
                // excluded from the standalone write and its loot drained from the stash, so a bare void-world
                // save would drop the mount and its capture-once contents from both the entities region and the
                // player record. Salvage a minimal player carrying just the RootVehicle. Its own fail-soft: if even
                // this throws, the void-world save stands and the mount is lost (the accepted worst case).
                this.capturedPlayer = failSoft("salvaged mount",
                        () -> assembleSalvageMountPlayer(player, minecraft, salvageAttach, salvageMount));
            }
        }
        releaseResumedDismountedMount(activeWriter);
        // Snapshot the source server's icon on the main thread (getCurrentServer is live only while connected);
        // the writer thread reads the frozen bytes. Reading at finish, not begin, also catches an icon pushed
        // mid-session after join. Null in singleplayer or with no cached icon, so no icon file is written.
        ServerData iconServer = minecraft.getCurrentServer();
        String iconB64 = iconServer != null ? iconServer.getIconB64() : null;
        this.reportIconBytes = iconB64 != null ? Base64.getDecoder().decode(iconB64) : null;
        prepareReportCompletion(); // freeze the end-of-capture counts before the writer finalizes
        // After every remap site above, so the batch is complete and queues behind the last chunk and entity
        // write: the bar then finishes the chunk phase, advances through the map phase, and only then compresses.
        activeWriter.submitMapBatch(mapWrites);
        this.finishMapWrites = null; // the writer holds its own copy; dropping ours frees the batched map tags
        this.finishBatchClosed = true;
    }

    /**
     * Run {@code finishWork} and signal end-of-stream to any writer this session opened, however that work ends. A
     * writer exists from the first incremental flush onward, and until it takes the marker it sits blocked on its
     * queue: no level.dat, the world's session lock held by a daemon thread that never exits, and the future the
     * controller polls never completed, so the download neither finishes nor reports. That is worse than a reported
     * failure, since nothing surfaces and nothing can be retried. Two ways in, which is why this wraps the whole finish
     * rather than its tail: a throw anywhere in the work, and the nothing-captured exit, which a download whose
     * captured positions were all dropped as void reaches with a writer already open behind it.
     *
     * <p>It signals the one end of stream, the finalizing one, on every path. A download that captured nothing reaches
     * it too, and the finalize then writes level.dat over the one a resumed folder already has, in the void-world form
     * since no player snapshot was assembled. That is deliberate and it is the better of two bad outcomes: vanilla
     * renames the replaced file to level.dat_old rather than dropping it, and lists a folder holding either, so the
     * record is recoverable and the folder stays openable. Skipping the finalize instead leaves a folder that wrote
     * chunks with no level.dat at all, which vanilla's world list does not show, and leaves the map-id floor
     * unpersisted so a new map in the reopened save can overwrite an archived one. Deciding between the two per
     * download needs a predicate for "did this write anything", and the obvious one, whether any captured position is
     * still retained, is not that: the void skip drops positions that were already written.
     *
     * <p>A throw counts as a degraded finish step, and that is what keeps the completion record from calling clean a
     * download whose remaining finish work (the map batch, the frozen report counts, the assembled player) never
     * reached the writer at all. Both halves are needed: the save must reach a terminal state, and a save that
     * completes claiming a success it did not have is worse than the parked thread. A {@link Throwable} is caught
     * rather than a {@link RuntimeException} for the reason the writer's own drain catches one, that reaching the
     * terminal state matters more than which class of failure got in the way, and the signal is enqueued from a
     * {@code finally} so that a throw from the counting arm itself cannot reopen the hole this closes.
     *
     * <p>The counter is incremented before the marker is enqueued, so the writer-thread finalize reads it across the
     * queue's own happens-before edge. Package-private and taking the work as a thunk so the guarantee is
     * headless-testable on one session, as with {@link #failSoft}.
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
                // end-of-stream; the writer drains, finalizes, completes its future, then the marshal pokes the
                // completion back to the game thread (off the tick, so a paused replay does not strand it).
                CompletionMarshal.scheduleCompletionPoke(activeWriter.finish(), mainThread, saveCompletePoke);
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
     * Deactivate the interaction recognizer so the connection-scoped use-block hook stops feeding it (a later session
     * activates its own). Called once at finish-time teardown; the finish drain still reads the instance directly, so
     * the publication can fall before the drain. Idempotent.
     */
    private void deactivateInteractionCapture() {
        InteractionCapture capture = this.interactionCapture;
        if (capture != null) {
            InteractionCapture.deactivate(capture);
        }
    }

    /**
     * Deactivate the open-click tracker so the connection-scoped use hooks stop seeding this session's open bind (a
     * later session activates its own). Called once at finish-time teardown. Idempotent.
     */
    private void deactivateOpenClickTracker() {
        OpenClickTracker tracker = this.openClickTracker;
        if (tracker != null) {
            OpenClickTracker.deactivate(tracker);
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

    /**
     * How many of {@code drained} nothing else saved. A mount captured into the player's RootVehicle is excluded, the
     * same way promoteChunk excludes it, because it reaches disk inside level.dat and counting its abandoned frame
     * would report a loss the download did not take.
     */
    private int abandonedCount(List<? extends PacketEntity<?, ?, ?>> drained) {
        int abandoned = 0;
        for (PacketEntity<?, ?, ?> frame : drained) {
            if (!excludedRootVehicleUuids.contains(frame.uuid())) {
                abandoned++;
            }
        }
        return abandoned;
    }

    /**
     * Reconcile and log the entity packet capture, after the buffer has flushed so the write tally is what reached the
     * writer. Logs the full breakdown (written roots, nested passengers, every counted drop) plus the
     * received-minus-accounted residual, which is reload / id-reuse churn, not a loss, so it is reported, not alarmed.
     * The WARN fires only on a structural loss (an entity-chunk flush loss, a create failure, a throwing single encode,
     * an aborted finish drain, or a remainder held for another dimension), near-zero in a healthy capture. The prime
     * path is reported separately because a primed entity has no spawn packet to reconcile against. Package-private so
     * the structural-loss tally it feeds stays testable.
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
                    + "has none); a flush loss drops a whole entity-chunk, enable dumpReceivedFrames and diff "
                    + "it against the saved frames",
                    reconciliation.flushDrops() + reconciliation.primedFlushDrops(), reconciliation.createDrops(),
                    reconciliation.encodeFailures() + reconciliation.primedEncodeFailures(),
                    reconciliation.abortDrops(), reconciliation.unboundDimensionDrops());
        }
        dumpReceivedFrames(capture);
    }

    /**
     * Diagnostic only: dump the received item-frame keys to {@code <save>/wdl/received-item-frames.txt} so a missing
     * frame can be diffed against what the client actually received. A received-but-missing frame is a capture bug; a
     * missing frame absent from this dump was never sent by the server. Fail-soft.
     */
    private void dumpReceivedFrames(EntityPacketCapture capture) {
        Path root = this.reportRoot;
        if (!config.dumpReceivedFrames() || root == null) {
            return;
        }
        try {
            Path file = root.resolve(WDL_SUBFOLDER);
            Files.createDirectories(file);
            file = file.resolve("received-item-frames.txt");
            Files.write(file, new TreeSet<>(capture.receivedFrames()));
            LOGGER.info("wrote {} received item-frame keys to {}", capture.receivedFrames().size(), file);
        } catch (IOException e) {
            LOGGER.warn("could not dump received item-frame keys", e);
        }
    }

    /**
     * Snapshot a seated player's root vehicle into {@link #rootVehicleTag} before the entity drain, mirroring
     * {@code ServerPlayer.saveParentVehicle}. Client main thread only (it reads live entity relationships and runs
     * {@code entity.save} over the live tree), never from a background/overlay read path. The whole fallible body is
     * per-unit failure-isolated like {@link #encodeSingleEntity}: {@code captureRootVehicle}'s {@code entity.save}
     * throws a {@code ReportedException} on a codec-rejecting modded or rolled-back entity, which without this catch
     * would propagate into {@link #finish()} and abort it before the writer finalizes, hanging the writer with the
     * session lock held. On a throw or a refused save the mount stays on the standard entity paths and the player loads
     * without a mount; the position anchor still fires.
     */
    private void prepareRootVehicleCapture(LocalPlayer player) {
        if (!player.isPassenger()) {
            return; // the common fast path; isPassenger is a field read that cannot throw
        }
        // Everything fallible is inside the try, including the vehicle-tree traversals: a modded Entity could
        // override getVehicle/getRootVehicle/hasExactlyOnePlayerPassenger to throw, and any throw here must not
        // propagate into finish() and hang the writer. A return inside the try is a normal exit (the catch does
        // not fire).
        try {
            Entity root = player.getRootVehicle();
            Entity direct = player.getVehicle();
            if (direct == null || root == player || !root.hasExactlyOnePlayerPassenger()) {
                return; // ServerPlayer.saveParentVehicle's own condition
            }
            Set<UUID> excluded = new HashSet<>();
            root.getSelfAndPassengers().forEach(entity -> {
                if (!(entity instanceof Player)) {
                    excluded.add(entity.getUUID());
                }
            });
            CompoundTag entityTag = adapter.entitySink().captureRootVehicle(root, registries,
                    config.forceMobPersistence());
            if (entityTag == null) {
                return;
            }
            entityTag = foldRidingVehicleContents(entityTag);
            excludedRootVehicleUuids.addAll(excluded);
            this.rootVehicleAttach = direct.getUUID();
            this.rootVehicleTag = entityTag;
        } catch (RuntimeException e) {
            LOGGER.warn("skipping the ridden vehicle capture: the player loads without a mount", e);
        }
    }

    /**
     * Fold the captured open-time contents into the mount record by {@code "Items"}, each node asked for the contents
     * captured against its own {@code "UUID"}, the same scrub + map-remap + merge the standalone vehicle path applies,
     * then scrub the record's item-borne coordinates. The live client mount serializes with empty {@code "Items"} (the
     * menu is a throwaway container, not the entity), so this is required, not redundant. The record holds the whole
     * tree from its root, so the entity whose menu was opened need not be that root, and while the player rides, the
     * record is that tree's only copy on disk. Draining the stash entry keeps the finish-time "contents not saved"
     * warning from counting it. A merge throw is isolated and tallied, the {@link EntityContainerMerge} discipline:
     * that node then saves valid but empty. Package-private so the entity-container tally it feeds stays testable.
     */
    CompoundTag foldRidingVehicleContents(CompoundTag vehicleTag) {
        // Into a copy, because a node below the root is folded in place: the tag handed in stays exactly what
        // the entity serialize produced, the no-mutate discipline the container sink itself keeps.
        CompoundTag result = vehicleTag.copy();
        for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(result).entrySet()) {
            foldEntityContents(node.getKey(), node.getValue());
        }
        if (!config.saveItemCoordinates()) {
            ItemLocationScrub.scrubEntity(result);
        }
        return result;
    }

    /** Fold the open-time contents captured against {@code uuid} into that entity's own node, in place. */
    private void foldEntityContents(UUID uuid, CompoundTag entityTag) {
        CompoundTag holder = entityContainerStash.remove(uuid);
        boolean fromRetention = holder == null;
        if (holder == null) {
            // Reboarded without reopening: the stash drained into an earlier standalone write, but the holder
            // was retained for exactly this, so the mount is written whole instead of being skipped. Use the
            // retained object itself and never a copy of it: the guard below is keyed on identity, and a copy
            // would read as unprepared and re-run the non-idempotent map remap over ids that are already
            // archive ids.
            //
            // This does leave the mount on disk twice, once standalone and once under RootVehicle, under the
            // same-UUID residency rule EntityContainerMerge.refoldFlushedContainers states in full. Both copies
            // carry the contents, so no loot rides on which one a given load keeps. A passenger that boarded
            // after the standalone write does: it exists only in the RootVehicle copy. Still strictly better
            // than skipping the mount, which dropped that passenger every time.
            holder = foldedContainerVehicles.get(uuid);
        }
        if (holder == null) {
            return;
        }
        // Guard by holder identity, exactly as prepareEntityContainers does: while the player rides, a
        // mid-session entity flush already scrubbed+remapped this same holder once, and the map remap is
        // non-idempotent (remapping an already-archived id blanks the map). Prepare only a not-yet-prepared
        // holder; an already-prepared one is scrubbed and remapped correctly from that one call.
        if (preparedEntityContainers.add(holder)) {
            scrubAndRemapItems(holder, uuid);
        }
        try {
            // The sink returns a merged copy and sets only "Items", so a node that lives inside the record takes
            // that one list back rather than replacing itself.
            entityTag.put("Items", adapter.containerSink().merge(entityTag, holder).getList("Items", Tag.TAG_COMPOUND));
            if (!fromRetention) {
                mergedEntityContainers++; // a retained holder was already counted at the write it came from
            }
        } catch (RuntimeException e) {
            entityContainersFailed++;
            LOGGER.warn("skipping ridden vehicle {} container merge: the mount saves without its contents", uuid, e);
        }
    }

    /**
     * Assemble the immutable player finish-snapshot from the live client (main thread): serialize the player, apply the
     * strip knobs and the unconditional death-location strip, the opt-in item-coordinate scrub, the canonical
     * {@code "Dimension"}, and the open-time ender-chest merge, then bundle the spawn position, gamemode, and
     * difficulty. Everything in the returned {@link CapturedPlayer} is finished, so it crosses to the writer thread
     * safely. Throwing is the caller's fail-soft contract.
     */
    private CapturedPlayer assembleCapturedPlayer(LocalPlayer player, Minecraft minecraft) {
        Entity anchor = captureAnchor(player, anchorEntity(minecraft, player));
        CompoundTag raw = adapter.playerSink().capturePlayer(player, registries);
        PlayerTag.applyStripKnobs(raw, config.savePlayerInventory(), config.savePlayerEnderChest());
        PlayerTag.stripDeathLocation(raw);
        if (!config.saveItemCoordinates()) {
            // The player tag is entity-shaped, so the entity scrub covers the Inventory list and the
            // equipment compound (offhand and armor live there, not in Inventory, since 1.21.5).
            ItemLocationScrub.scrubEntity(raw);
        }
        // On-sight map remap of the carried items: rewrite and serialize each carried map on the captured
        // copy, once, at finish (never on the live object). Two passes for the two vanilla homes: the
        // 36-slot Inventory list, then the equipment compound (offhand and armor).
        MapArchive archive = this.mapArchive;
        if (archive != null) {
            archive.remap(raw, "Inventory");
            if (raw.get("equipment") instanceof CompoundTag equipment) {
                for (String slot : equipment.getAllKeys()) {
                    if (equipment.get(slot) instanceof CompoundTag item) {
                        archive.remapItem(item);
                    }
                }
            }
        }
        PlayerTag.setDimension(raw, targetDimension);
        PlayerTag.setPosition(raw, anchor.blockPosition(), anchor.getYRot(), anchor.getXRot());
        if (config.savePlayerEnderChest()) {
            if (enderChestStash != null) {
                if (!config.saveItemCoordinates()) {
                    ItemLocationScrub.scrub(enderChestStash, "Items");
                }
                if (archive != null) {
                    archive.remap(enderChestStash, "Items"); // the ender chest's maps, once, before the merge
                }
                PlayerTag.setEnderItems(raw, enderChestStash);
            } else if (target.mode() == DownloadMode.RESUME) {
                carryForwardPriorEnderChest(raw); // not re-opened this session, so keep the prior download's
            }
        }
        if (rootVehicleTag != null && rootVehicleAttach != null) {
            PlayerTag.setRootVehicle(raw, rootVehicleAttach, rootVehicleTag);
        }
        if (target.mode() == DownloadMode.RESUME) {
            restorePriorMountContents(raw); // restore a prior download's mount contents on a same-mount seated resume
        }
        // Creative only when the world-defaults master imposes it (with its openInCreative knob on); with the
        // master off the world opens in the player's real game mode, matching cheats and time/weather falling
        // back. gameMode is non-null here (a player is present), but the field is @Nullable, so guard and fall
        // back to the survival default.
        GameType gameType = GameType.CREATIVE;
        if (!config.worldOutput().overrideWorldDefaults() || !config.worldOutput().openInCreative()) {
            MultiPlayerGameMode gameMode = minecraft.gameMode;
            gameType = gameMode != null ? gameMode.getPlayerMode() : GameType.SURVIVAL;
            if (gameType == GameType.SPECTATOR) {
                // Vanilla applies the saved game type to every opener, so a spectator stamp opens the world in
                // spectator, and on a world shipped without cheats there is no way back out. Fall back to the
                // mode the viewer held before spectating, or survival when that is unknown or itself spectator.
                GameType previous = gameMode != null ? gameMode.getPreviousPlayerMode() : null;
                gameType = previous != null && previous != GameType.SPECTATOR ? previous : GameType.SURVIVAL;
            }
        }
        Difficulty difficulty = level().getLevelData().getDifficulty();
        return new CapturedPlayer(raw, anchor.blockPosition(), anchor.getYRot(), anchor.getXRot(),
                targetDimension, gameType, difficulty);
    }

    /**
     * Salvage a minimal player finish-snapshot carrying only the seated mount's {@code RootVehicle} record and a safe
     * spawn, for the fail-soft path where {@link #assembleCapturedPlayer} threw after the mount was already excluded
     * from the standalone write and its loot drained from the stash. Rebuilds a fresh tag rather than reusing the
     * partial one the throw left: only the dimension, the safe position, and the RootVehicle, none of the fallible
     * player-state serialization. The real game mode and the rest of the player state are lost with the failed
     * assembly, so it opens survival at the mount, which is strictly better than losing the mount too.
     */
    private CapturedPlayer assembleSalvageMountPlayer(LocalPlayer player, Minecraft minecraft, UUID attach,
            CompoundTag mountTag) {
        Entity anchor = captureAnchor(player, anchorEntity(minecraft, player));
        CompoundTag raw = new CompoundTag();
        PlayerTag.setDimension(raw, targetDimension);
        PlayerTag.setPosition(raw, anchor.blockPosition(), anchor.getYRot(), anchor.getXRot());
        PlayerTag.setRootVehicle(raw, attach, mountTag);
        Difficulty difficulty = level().getLevelData().getDifficulty();
        return new CapturedPlayer(raw, anchor.blockPosition(), anchor.getYRot(), anchor.getXRot(),
                targetDimension, GameType.SURVIVAL, difficulty);
    }

    /**
     * Assemble the immutable progress finish-snapshot from the live client (main thread): the advancement progress
     * (client-held) and, if a stats reply has landed, the enumerated statistics, each rendered to detached JSON bytes
     * so the writer thread never touches the still-mutating live structures. Gated per surface on its config toggle.
     * Throwing is the caller's fail-soft contract.
     */
    private CapturedProgress assembleCapturedProgress(LocalPlayer player, Minecraft minecraft) {
        int dataVersion = currentDataVersion();
        byte[] advancements = null;
        if (config.captureAdvancements()) {
            // Per-surface fail-soft: an advancement-encode throw nulls only this blob, so the sibling stats
            // surface still lands (mirroring the writer's per-file isolation). The outer failSoft on the whole
            // assembly stays the backstop for the shared steps, the data version and the uuid.
            advancements = failSoft("advancements", () -> {
                ClientPacketListener connection = minecraft.getConnection();
                Map<String, AdvancementProgress> byId = connection != null
                        ? AdvancementSnapshot.byId(connection.getAdvancements())
                        : Map.of();
                return PlayerProgressSerializer.advancementsJson(byId, dataVersion);
            });
        }
        boolean captureStatistics = config.captureStatistics();
        byte[] stats = captureStatistics
                ? PlayerProgressSerializer.statsJson(player.getStats(), dataVersion)
                : null;
        if (captureStatistics && stats == null) {
            LOGGER.warn("statistics not captured: no stats reply received before finish");
        }
        return new CapturedProgress(player.getUUID(), advancements, stats);
    }

    /** The data version, read band-stably via the same vanilla stamp {@link MapDataWriter} uses. */
    private static int currentDataVersion() {
        CompoundTag probe = new CompoundTag();
        probe.putInt("DataVersion", SharedConstants.getCurrentVersion().getWorldVersion());
        return probe.getInt("DataVersion");
    }

    /**
     * Carry the prior session's ender chest forward on a resume that did not re-open it: without this the fresh player
     * tag's empty {@code "EnderItems"} would wipe a previously-downloaded ender chest. Carries the prior items when the
     * fresh ones are empty, then applies the item-coordinate scrub per the current knob; the carried items are already
     * archive-remapped from the prior session, so they are not re-remapped. Fail-soft: a missing or unreadable prior
     * player leaves the (empty) fresh ender chest rather than aborting the player assembly.
     */
    private void carryForwardPriorEnderChest(CompoundTag raw) {
        CompoundTag priorPlayer = readPriorPlayerTag();
        if (priorPlayer == null) {
            return;
        }
        if (PlayerTag.carryForwardEnderItems(priorPlayer, raw) && !config.saveItemCoordinates()) {
            ItemLocationScrub.scrub(raw, "EnderItems");
        }
    }

    /**
     * Restore a prior download's captured mount contents on a resume that finished seated in the same mount without
     * reopening its container: the fresh serialize carries empty menu-only contents, and the wholesale rewrite of the
     * saved player would drop the prior download's folded loot. A resume that finished un-seated carries nothing (see
     * {@link PlayerTag#restorePriorMountContents}): a dismounted mount is a normal world entity, captured by the
     * standalone entity path, so writing it into the Player slot would wrongly re-seat the player and collide same-UUID
     * with the standalone copy. Runs as its own RESUME block, ungated by {@link WdlConfig} toggles (the mount is
     * player-state, independent of the ender, inventory, and capture-entities knobs) and after the fresh
     * {@code setRootVehicle}. Scrubs the restored mount's own coordinates per the current knob on the {@code Entity}
     * child (not {@code scrub(raw, key)}, a no-op on a compound, and not {@code scrubEntity(raw)}, which does not
     * descend a RootVehicle child); the prior session already map-remapped it, so it is not re-remapped. Fail-soft on a
     * missing or unreadable prior level.dat.
     */
    private void restorePriorMountContents(CompoundTag raw) {
        CompoundTag priorPlayer = readPriorPlayerTag();
        if (priorPlayer == null) {
            return;
        }
        if (PlayerTag.restorePriorMountContents(priorPlayer, raw) && !config.saveItemCoordinates()
                && raw.get("RootVehicle") instanceof CompoundTag rootVehicle
                && rootVehicle.get("Entity") instanceof CompoundTag entity) {
            ItemLocationScrub.scrubEntity(entity);
        }
    }

    /**
     * Release a prior download's parked mount as a standalone entity on a resume, so a mount the player rode in an
     * earlier download and has since left is preserved as a world entity rather than lost. A mount ridden at a finish
     * is a one-player vehicle the entity capture refuses, so that finish saved it only as the RootVehicle in its saved
     * player, and this resume's own player tag replaces that record wholesale. Nothing else carries it forward.
     *
     * <p>Skipped when this finish already preserved that mount itself, either by writing it as a standalone entity or
     * by capturing it into the player's own RootVehicle, the latter matched by UUID against the whole captured mount
     * tree rather than against its root. The root is not the mount: a mount can be ridden while itself riding another
     * vehicle, and then the RootVehicle record holds the outer vehicle with the mount nested under it. Matching the
     * root alone would release a mount the player is still on, putting a second copy of it in the world.
     *
     * <p>Routed to the dimension the PRIOR tag records, not to the live {@link #targetDimension}: the position comes
     * from the prior tag too, and this session can finish anywhere, so pairing prior coordinates with the current
     * dimension would write the mount into a folder it was never in and leave the one it was parked in empty. The
     * writer opens the named dimension's entities storage on demand exactly as it does for a session that follows the
     * player across a portal, and this submit precedes the end-of-stream marker.
     *
     * <p>Fail-soft. Every exit that reaches the release proper and does not write counts, since the mount and its
     * archived contents are then preserved nowhere this method can see. A write the writer thread then declines is
     * outside that guarantee. Package-private so the one path carrying a prior download's mount forward stays testable;
     * every production caller runs behind the client singleton.
     */
    void releaseResumedDismountedMount(AsyncSaveWriter activeWriter) {
        if (target.mode() != DownloadMode.RESUME) {
            return;
        }
        CompoundTag priorPlayer = readPriorPlayerTag();
        if (priorPlayer == null
                || !(priorPlayer.get("RootVehicle") instanceof CompoundTag priorRoot)
                || !(priorRoot.get("Entity") instanceof CompoundTag priorEntity)) {
            return;
        }
        // Read before this check rather than after, because whether the finish is a no-op is a question about
        // WHICH mount the player ended on, which only the prior tag answers. The exclusion set is this finish's
        // captured mount tree and is empty when no mount was captured, so a null capturedPlayer (the
        // disconnect flush and the failed assembly) falls through to the release, which is where it belongs.
        UUID priorMountUuid = EntityMerge.readUuid(priorEntity);
        if (priorMountUuid != null && (savedEntities.contains(priorMountUuid)
                || (capturedPlayer != null && excludedRootVehicleUuids.contains(priorMountUuid)))) {
            return;
        }
        try {
            ResourceKey<Level> priorDimension = PlayerTag.dimensionOf(priorPlayer);
            if (priorDimension == null) {
                recordResumedMountLoss("its prior level.dat names no dimension this download writes");
                return;
            }
            ChunkPos pos = mountEntityChunk(priorEntity);
            if (pos == null) {
                recordResumedMountLoss("its prior tag carries no readable position");
                return;
            }
            CompoundTag envelope = adapter.entitySink().encodeChunk(List.of(priorEntity.copy()), pos);
            if (envelope == null) {
                recordResumedMountLoss("the entity sink refused its tag");
                return;
            }
            activeWriter.submitEntity(priorDimension, pos, envelope);
        } catch (RuntimeException e) {
            resumedMountsLost++;
            LOGGER.warn("could not release the resumed dismounted mount to a standalone entity; it and the "
                    + "contents the previous download archived in it are absent from the save", e);
        }
    }

    /** Count and surface a prior download's parked mount the release could not place (main thread). */
    private void recordResumedMountLoss(String reason) {
        resumedMountsLost++;
        LOGGER.warn("could not release the resumed dismounted mount to a standalone entity: {}; it and the "
                + "contents the previous download archived in it are absent from the save", reason);
    }

    /**
     * The {@link ChunkPos} an entity tag's {@code "Pos"} (a three-double x, y, z list) lands in, or null when
     * {@code "Pos"} is missing or malformed.
     */
    private @Nullable ChunkPos mountEntityChunk(CompoundTag entity) {
        ListTag pos = entity.getList("Pos", Tag.TAG_DOUBLE);
        if (pos.size() < 3) {
            return null;
        }
        double x = pos.getDouble(0);
        double y = pos.getDouble(1);
        double z = pos.getDouble(2);
        return new ChunkPos(new BlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z)));
    }

    /**
     * The prior download's captured player tag read through this band's own save layout via
     * {@link LevelDataWriter#readPriorPlayer}, or null when the folder is fresh or no player was written (fail-soft).
     * The read mirror of the player write: where the player lives on disk drifts across bands, so the plug that wrote
     * it reads it back. A plain main-thread file read, not MC-state-coupled, so the cross-queue immutability invariant
     * holds.
     */
    private @Nullable CompoundTag readPriorPlayerTag() {
        Path file = levelDatFile;
        if (file == null) {
            return null;
        }
        try {
            return adapter.levelDataWriter().readPriorPlayer(file);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to read the prior player data on resume", e);
            return null;
        }
    }

    /** The prior level.dat's {@code Data} compound, or null when absent; a present but unreadable file throws. */
    private @Nullable CompoundTag readPriorData() throws IOException {
        Path file = levelDatFile;
        if (file == null || !Files.exists(file)) {
            return null;
        }
        CompoundTag root = NbtIo.readCompressed(file.toFile());
        return root.get("Data") instanceof CompoundTag data ? data : null;
    }

    /**
     * At resume init, mark the shared player ender inventory already recovered so the outline draws no rim on any ender
     * chest without the player reopening one. Gated so the rim never claims a save this resume will strip: only on a
     * RESUME with savePlayerEnderChest on this session and a present, non-empty prior {@code "EnderItems"}. If the
     * toggle is off this session the resume drops the ender inventory, so the fact must not be marked; if it was off
     * last session the prior save has no EnderItems, so the non-empty guard leaves the rims red on its own. Mirrors the
     * non-empty guard {@link PlayerTag#carryForwardEnderItems} uses.
     */
    private void markPriorEnderRecovered() {
        if (target.mode() != DownloadMode.RESUME || !config.savePlayerEnderChest()) {
            return;
        }
        CompoundTag priorPlayer = readPriorPlayerTag();
        if (priorPlayer != null && priorPlayer.get("EnderItems") instanceof ListTag prior && !prior.isEmpty()) {
            recoveredScan.markEnderRecovered();
        }
    }

    /**
     * The {@code LevelName} to write into level.dat: a new download uses the target's resolved name, the dated folder
     * name unless the date suffix is off, while a resume preserves the existing world's name read from the prior
     * level.dat (null when absent or unreadable, letting the writer apply its default), so re-running into a folder
     * never renames the world it already produced.
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
                return data.contains("LevelName") ? data.getString("LevelName") : null;
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

    /**
     * Run a finish-time capture assembly and degrade a throw to a null snapshot (fail-soft): a serialize or scrub bug
     * in one step then drops that step to absent (the player path opens at the default spawn with no Player tag, taking
     * the inventory, the ender chest and the game mode with it; the progress path writes no advancement or statistics
     * file) instead of aborting the save after chunks have committed and leaving a chunks-without-level.dat unopenable
     * world.
     *
     * <p>The degradation is deliberate and stays. What does not is reporting the download that took it as clean, so
     * every degraded step counts toward the partial-finish verdict, and {@code step} names on the line which one it
     * was: several steps share this, and a line naming none of them leaves a reader unable to tell a lost player from
     * lost advancements.
     *
     * <p>Package-private and an instance method, so the tally it feeds and the fail-soft contract are both
     * headless-testable on one session.
     */
    <T> @Nullable T failSoft(String step, Supplier<T> assembly) {
        try {
            return assembly.get();
        } catch (RuntimeException e) {
            finishStepsFailed++;
            LOGGER.warn("failed to capture the {} at finish; the world is saved without it", step, e);
            return null;
        }
    }

    /**
     * The {@link MapArchive.ImageResolver}: resolve a session-local map id to its serialized inner data tag, or null if
     * the client never received its colors (a chest-only or nested map, the imageless case). The imaged map is
     * auto-locked by default so the archived image is frozen against the void-world repaint. Runs on the main thread
     * (every {@link MapArchive#archiveIdFor} call is), where the mutable {@code MapItemSavedData} may be read.
     */
    private @Nullable Tag resolveMapImage(int sessionId) {
        MapItemSavedData saved = level().getMapData(MapItem.makeKey(sessionId));
        if (saved == null) {
            return null; // colors never received (imageless): skipped, never fabricated
        }
        MapItemSavedData snapshot = config.lockDownloadedMaps() ? saved.locked() : saved;
        return adapter.mapSink().serializeMap(snapshot, registries);
    }

    /**
     * Load the map-id manifest, degrading a read failure to an empty manifest so a download never stops, and counting
     * that degradation: this download then re-images every map the folder already holds, and its archived ids are no
     * longer distinguishable from fresh ones, which is the same shared-file consequence a failed manifest write leaves
     * behind. The floor is raised outside that catch on purpose: the counter high-water lives in the manifest, so
     * folding the two reads into one try would let a fault in the floor scan discard a manifest that parsed cleanly,
     * and the empty manifest that replaced it would restart at 0 and write this download's first map over the prior
     * download's {@code data/map_0.dat}. Package-private so the tally it feeds stays testable.
     */
    MapManifest loadManifest(Path file, Path dataDirectory) {
        MapManifest manifest;
        try {
            manifest = MapManifest.load(file);
        } catch (IOException | RuntimeException e) {
            mapManifestReadFailed++;
            LOGGER.warn("failed to read the map-id manifest; resuming as if every map is new, with ids above"
                    + " those already on disk", e);
            manifest = MapManifest.empty();
        }
        manifest.raiseCounterAbove(onDiskMapIdFloor(dataDirectory));
        return manifest;
    }

    /**
     * The map archive for this download: on-mode wraps the loaded remap manifest; off-mode keeps the original server
     * ids and reconstructs the idcounts floor from disk (there is no manifest to persist it), seeding past the highest
     * existing data file and any prior idcounts.dat so a resume never re-issues a captured id.
     */
    private MapArchive createMapArchive(Path mapIdsFile, Path dataDirectory) {
        if (config.remapMapIds()) {
            return new MapArchive(loadManifest(mapIdsFile, dataDirectory), this::resolveMapImage,
                    this::streamMapData);
        }
        return new MapArchive(MapManifest.empty(), this::resolveMapImage, this::streamMapData, false,
                onDiskMapIdFloor(dataDirectory));
    }

    /** The highest map id the folder is already known to have used, or -1 if it holds none. */
    private static int onDiskMapIdFloor(Path dataDirectory) {
        return Math.max(dataFileFloor(dataDirectory), idCountsFloor(dataDirectory));
    }

    /**
     * The map-id floor from the existing {@code data/map_<n>.dat} files, or -1 if that read faults. Its own -1 fallback
     * keeps a fault in this source from discarding the idcounts floor read independently below.
     */
    private static int dataFileFloor(Path dataDirectory) {
        try {
            return MapManifest.highestDataFileId(dataDirectory);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("failed to read the map id floor from the data files; ignoring that source", e);
            return -1;
        }
    }

    /**
     * The map-id floor from a prior {@code idcounts.dat}, or -1 if that read faults. Its own -1 fallback keeps a fault
     * in this source from discarding the data-file floor read independently above. It can exceed the data-file source,
     * which is why both are read: an imageless id writes no data file, so a prior download whose highest id was
     * imageless is recorded here and nowhere else. It only reaches disk at finalize, so a prior download that crashed
     * leaves this source empty and the data-file scan carrying the floor alone.
     */
    private static int idCountsFloor(Path dataDirectory) {
        try {
            return MapDataWriter.readIdCounts(dataDirectory);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("failed to read the map id floor from idcounts.dat; ignoring that source", e);
            return -1;
        }
    }

    /**
     * The one map data write task, shared by the on-sight streaming arm and the finish batch. It absorbs its own
     * {@link IOException}, which a {@link Runnable} could not throw anyway, and its own {@link RuntimeException}, which
     * it must absorb because the shared task catches would log a runtime throw without counting it, and an uncounted
     * loss lets the finish report a download that lost maps as clean. {@code failures} is incremented once per failed
     * write and never otherwise, and {@code loss} names every lost map on its own line while carrying the stack once
     * per distinct cause type. Package-private so the counting contract stays testable.
     */
    static Runnable mapWriteTask(Path dataDirectory, String key, Tag dataTag, AtomicInteger failures,
            CaptureLossLog loss) {
        return () -> {
            try {
                MapDataWriter.write(dataDirectory, key, dataTag);
            } catch (IOException | RuntimeException e) {
                failures.incrementAndGet();
                loss.lost(key, e);
            }
        };
    }

    /**
     * The on-sight map stream: hand one first-imaged map's data tag to the writer thread to land as
     * {@code data/map_<id>.dat}. A map imaged during capture is submitted alone, so it reaches disk at once and a
     * crashed capture keeps the maps it already saw. One imaged during the finish drain joins {@link #finishMapWrites}
     * instead, because a display wall can image five figures of them in that one drain and the bar has to advance over
     * them rather than sit frozen on the chunk phase; a crash inside those few seconds loses an unopenable save anyway,
     * so nothing durable is traded for the bar. Called on the main thread from the remap paths, which all run behind
     * {@code ensureWriter}; the write runs on the writer thread, interleaved with the chunk drain and behind a resume's
     * preflight backup. Whatever write is handed to either arm must catch and count its own failure; see
     * {@link #mapWriteTask}. Neither arm counts, both absorb a runtime throw, so a write that does not increment the
     * tally reports a download that lost maps as clean. Package-private so the tally this hands the task stays
     * testable.
     */
    void streamMapData(int archiveId, Tag dataTag) {
        AsyncSaveWriter activeWriter = this.writer;
        WorldPaths paths = this.worldPaths;
        if (activeWriter == null || paths == null) {
            // Unreachable from production today: every remap site runs behind ensureWriter, which sets both.
            // Counted and logged so a future pre-writer remap site becomes visible instead of losing a map the
            // archive's imaged gate already considers streamed. Logged directly rather than through the write
            // voice because no throwable exists here, and inventing one would spend that voice's stack budget
            // for its type on a loss whose cause is the message itself.
            mapsFailed.incrementAndGet();
            LOGGER.info("map data {} had no writer to stream to; it is missing from the save",
                    MapItem.makeKey(archiveId));
            return;
        }
        Path dataDirectory = paths.dataDirectory();
        String key = MapItem.makeKey(archiveId);
        if (finishBatchClosed) {
            // Unreachable today: every remap site precedes the handover, and the inbound hooks are detached
            // before it. Counted and logged so a future late remap site becomes visible instead of losing a map
            // to a writer that drops post-finish work. Logged directly rather than through the write voice for
            // the same reason as the branch above: no throwable exists to key a stack budget on.
            mapsFailed.incrementAndGet();
            LOGGER.info("map data {} was imaged after the finish batch closed; it is missing from the save", key);
            return;
        }
        Runnable write = mapWriteTask(dataDirectory, key, dataTag, mapsFailed, mapWriteLoss);
        List<Runnable> batch = this.finishMapWrites;
        if (batch != null) {
            batch.add(write);
            return;
        }
        activeWriter.submit(write);
    }

    /**
     * Write the finalize-time idcounts, after level.dat: the streamed map files are already on disk, so this is the one
     * remaining data/ write. Runs on the writer thread at finalize; the statements here name no thread, but the
     * {@code mapArchive} they read is non-volatile and is visible there because world-open assigns it before that
     * thread exists. Caught so an idcounts IO failure never aborts the otherwise-complete save; the cost is that the
     * reopened world's allocator may reissue a captured id, logged as such. Package-private so the tally it feeds stays
     * testable.
     */
    void writeIdCounts(WorldPaths paths) {
        MapArchive archive = this.mapArchive;
        if (archive == null) {
            return; // the world never opened for writing
        }
        Tag idCounts = archive.idCountsTag();
        if (idCounts == null) {
            return; // no id was referenced or seeded this session
        }
        try {
            MapDataWriter.writeIdCounts(paths.dataDirectory(), idCounts);
        } catch (IOException | RuntimeException e) {
            idCountsFailed++;
            LOGGER.warn("failed to write idcounts; the reopened world may reissue captured map ids", e);
        }
    }

    /**
     * Rewrite the map-id manifest on the writer thread, after the {@code data/} files, via the atomic-move save so a
     * torn write leaves the prior manifest intact. Fail-soft: a manifest write failure is logged and recorded, never
     * thrown, so it can never abort the otherwise-complete save while a resume that would silently re-image every map
     * in the folder is still reported.
     *
     * <p>The record is the end state rather than a tally of attempts, which is what the success arm clearing it is for:
     * the world-open scheme signal and the finalize rewrite are the same file, so a fault at the first that the second
     * repairs cost the download nothing and must not report it partial. Package-private so the flag it sets stays
     * testable. Runs on the main thread at world-open and on the writer thread at finalize.
     */
    void saveMapManifest() {
        MapArchive archive = this.mapArchive;
        Path file = this.mapIdsFile;
        if (archive == null || file == null) {
            return;
        }
        try {
            if (config.remapMapIds()) {
                archive.manifest().save(file);
            } else {
                // Off-mode writes no manifest; delete a stale one so its presence stays a truthful mode signal.
                Files.deleteIfExists(file);
            }
            mapManifestStale = false;
        } catch (IOException | RuntimeException e) {
            mapManifestStale = true;
            LOGGER.warn("failed to update the map-id manifest; a resume of this folder re-images every map it "
                    + "already holds", e);
        }
    }

    /**
     * The manifest losses this download carries: the read fault, which nothing repairs, plus one for the written file
     * if it is still stale as the download ends.
     */
    private int mapManifestLosses() {
        return mapManifestReadFailed + (mapManifestStale ? 1 : 0);
    }

    /**
     * Bind the three world-open surfaces the map-tally paths read, standing in for the tail of {@link #ensureWriter},
     * which cannot run headlessly because it resolves the level source through the client singleton. It has no
     * production caller and is not an alternative way to open a world: production binds these three in
     * {@code ensureWriter}, which also takes the session lock this one knows nothing about. The writer arrives as a
     * factory rather than an instance so the paths and the archive are published before the writer thread exists, the
     * same edge {@code ensureWriter} gets by constructing the writer last; handing over a running writer instead would
     * race the finalizer's read of them. The other surfaces that step binds ({@code mapIdsFile}, {@code levelDatFile},
     * and the report triple) stay unset, so the manifest and completion-record paths remain closed on such a session.
     *
     * @param writerFactory must construct the writer inside {@code get()}; handing back one that already exists reopens
     *                      the race this parameter shape is here to close
     */
    AsyncSaveWriter bindWorldOpen(WorldPaths paths, MapArchive archive, Supplier<AsyncSaveWriter> writerFactory) {
        this.worldPaths = paths;
        this.mapArchive = archive;
        AsyncSaveWriter bound = writerFactory.get();
        this.writer = bound;
        return bound;
    }

    /**
     * Open the world for writing once and start the background {@link AsyncSaveWriter} (the writer owns the
     * {@link LevelStorageSource.LevelStorageAccess} session lock from here, and releases it when the save finishes).
     * Lazy so a never-captured session creates nothing; idempotent so the flush pump and {@code finish()} share one
     * writer. Returns null (and records {@link #startError}) if the world cannot be opened; that failure is logged once
     * where it is surfaced (reportSaveFailure at finish), not here, so a deferred open error is not dumped to the log
     * twice.
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
            // be a symlink the lexical normalize cannot see). Every saveName that reaches here is single-component
            // (the screen sanitizes separators; resume names are directory leaves).
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
            // The save root is the level directory, not the overworld storage folder. They are the same path only
            // where the overworld sits at the save root (1.21.11 and earlier); at 26.x DimensionType
            // .getStorageFolder puts every dimension under dimensions/minecraft/<name>, so getDimensionPath here
            // would root WorldPaths, the map manifest and the export zip one dimension too deep.
            // getLevelDirectory does not exist, so the level directory is taken from getLevelPath(ROOT) here.
            // LevelResource.ROOT's id is a bare dot, so that path ends in a dot component; normalize it, or the
            // export derives its zip name and parent directory from getFileName and getParent on the dot and lands
            // the archive inside the save folder it is zipping.
            Path saveRoot = access.getLevelPath(LevelResource.ROOT).normalize();
            WorldPaths paths = adapter.worldPaths(saveRoot);
            this.worldPaths = paths;
            this.mapIdsFile = MapManifest.pathIn(saveRoot);
            this.levelDatFile = saveRoot.resolve("level.dat"); // read on a resume to carry the ender chest forward

            // Load the map-id manifest once at world-open (empty for a fresh download; seeded past every used
            // id on a resume). The live remap table streams each map on-sight from here on.
            this.mapArchive = createMapArchive(MapManifest.pathIn(saveRoot), paths.dataDirectory());
            // Plant the scheme signal at open for a NEW download only, before any map data streams:
            // schemeMismatch reads manifest presence as the remap-on marker, and NEW takes no backup, so the
            // plant cannot corrupt one. A resume's map surfaces must not change before the preflight backup
            // snapshots them: a matched resume's manifest is already present, and an off-mode resume's stale
            // manifest is deleted at finalize (saveMapManifest below), not here. The deliberate residue: a
            // mismatch-confirmed remap-on resume of an off-mode folder plants nothing, so a crash there
            // leaves streamed archive-scheme files with no signal, an accepted best-effort residue. The
            // mirror direction is accepted too: a mismatch-confirmed off-mode resume of a remap-on folder
            // crashing before the finalize delete leaves the stale manifest beside verbatim-id files, so
            // the folder misreads as remap-on until the next completed session heals it.
            if (target.mode() == DownloadMode.NEW) {
                saveMapManifest();
            }
            beginReport(minecraft, saveRoot);
            LevelDataWriter levelDataWriter = adapter.levelDataWriter();
            LevelDataWriter.LevelData levelData = levelDataWriter.buildLevelData(registries, config.worldOutput(),
                    resolveWorldName());
            surfaceGameRuleOverrideLoss(levelData.gameRules());
            writer = new AsyncSaveWriter(
                    paths::openRegionStorage,
                    paths::openEntitiesStorage,
                    // Pre-merge safety copy on a resume, on the writer thread before any chunk is written into the
                    // folder; a no-op for a fresh download or with zipOnResume off. The in-progress download.md
                    // regenerates strictly after the backup, so the zip archives the prior session's rendering
                    // untouched (begin wrote only the crash sentinel, which the zipper excludes). saveRoot and
                    // target are settled before the writer starts, so the thunk closes over stable values.
                    () -> {
                        FinalizeOutputs.backupBeforeResume(saveRoot, target.mode(), config.zipOnResume());
                        report.refreshHumanRendering(saveRoot);
                    },
                    // Read the volatile capturedPlayer/capturedProgress LAZILY inside the thunk:
                    // ensureWriter builds this thunk at the first incremental flush, mid-capture,
                    // before finish() sets the fields, so a snapshot taken here would always be null. The thunk runs
                    // on the writer thread strictly after the chunk drain, so the fields set in finish() are visible.
                    // level.dat is written FIRST, then idcounts (the map files themselves streamed during capture),
                    // each write caught, so a map IO failure never aborts before level.dat (an unopenable save) or
                    // fails it.
                    (chunksFailed, entityChunksFailed) -> {
                        levelDataWriter.save(access, levelData, capturedPlayer);
                        PlayerProgressWriter.write(saveRoot, capturedProgress);
                        writeIdCounts(paths);
                        saveMapManifest(); // after the data/ files, so a torn write never precedes them
                        // the finish marker, just before the storages/access close; carries the writer's soft
                        // tally so the record stamps the real clean-or-partial status
                        writeReportCompletion(chunksFailed, entityChunksFailed);
                    },
                    // Finish-time output after the folder is fully written and closed: the export zip
                    // when zipOnFinish is on. The download screen reads each row's size by walking the folder.
                    () -> FinalizeOutputs.exportZip(saveRoot, config.zipOnFinish(), progress),
                    access, progress);
            // Observe each carried-forward on-disk chunk for the outline's recovered-coverage scan.
            // Self-gating: the writer reads the prior chunk only when one exists, so this is a no-op on a fresh
            // download and runs only on a resume, reusing the writer's own read rather than a second region store.
            // The entities-store sibling marks a prior-captured container entity recovered by its UUID the same way.
            writer.observeResumeReads(recoveredScan::record);
            writer.observeEntityResumeReads(recoveredScan::recordEntities);
            // The shared ender inventory is global, not per-chunk, so it is read once here at resume init rather
            // than through the chunk scan: mark it recovered when the prior download already holds it.
            markPriorEnderRecovered();
            return writer;
        } catch (RuntimeException e) {
            closeQuietly(access); // never handed to a writer, so release the session lock here
            startError = e;
            return null;
        }
    }

    /**
     * Surface the cross-band game-rule override loss to the player: a configured override whose id does not exist at
     * the running band is dropped, and because the curated safe set still applies the world still looks safe, so the
     * loss is surfaced rather than only debug-logged. Runs on the client main thread (level.dat is built there), where
     * messaging the player is safe.
     */
    private void surfaceGameRuleOverrideLoss(GameRuleResolution gameRules) {
        if (!gameRules.unknownIds().isEmpty() && config.showChatMessages()) {
            bridge.sendChat(ChatCopy.gameRuleOverridesSkipped(String.join(", ", gameRules.unknownIds())));
        }
    }

    /**
     * Seed the coverage overlay with the current dimension's prior on-disk coverage on a resumed download, once per
     * live dimension id. Gated on a resume and on a map overlay mod being present, so the header scan is skipped when
     * nothing will consume it. It runs on the writer thread in order with the drain, off the render thread and never
     * racing a write, and keys the indexes by the live client id (matching the record-site write and the overlay
     * providers' query) while reading the vanilla-type-routed region folder. The prior chunks seed both the saved and
     * the covered index, so a resumed prior draws in the covered hue rather than the suspect one: the prior session's
     * path was not observed, so marking it suspect would be a guess.
     */
    private void maybeSeedOverlayCoverage(AsyncSaveWriter activeWriter) {
        WorldPaths paths = worldPaths;
        if (!overlayActive || target.mode() != DownloadMode.RESUME || paths == null) {
            return;
        }
        String dimensionId = level().dimension().location().toString();
        if (!overlaySeededDimensions.add(dimensionId)) {
            return;
        }
        ResourceKey<Level> diskDimension = targetDimension;
        activeWriter.submit(() -> {
            long[] priors = RegionChunkScan.presentChunks(paths.regionDirectory(diskDimension));
            overlayIndex.addAll(dimensionId, priors);
            coveredIndex.addAll(dimensionId, priors);
        });
    }

    /**
     * Merge each eligible buffered chunk's pending container stash into its tag, hand the tag to the writer, and drop
     * it from the buffer. With {@code all} the whole buffer is flushed (the finish-time drain and the capture-paused
     * drain); otherwise only chunks farther than {@code keepHot} from the center. Merge-before-flush: a container
     * opened while the chunk was hot is folded in before the tag leaves memory, and distance-gating means no new
     * container can be opened in a chunk being flushed. Package-private so the block-container tally its submit thunks
     * feed stays testable; every production caller is in this class and reaches it behind the client.
     */
    void flushBuffer(AsyncSaveWriter activeWriter, boolean all, int centerX, int centerZ, int keepHot) {
        maybeSeedOverlayCoverage(activeWriter);
        ContainerSink containerSink = adapter.containerSink();
        LecternSink lecternSink = adapter.lecternSink();
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
        Set<ChunkPos> pendingInteractionChunks = skipVoid && interactionCapture != null
                ? interactionCapture.pendingCandidateChunks()
                : Collections.emptySet();
        boolean resumeDownload = target.mode() == DownloadMode.RESUME;
        Iterator<Map.Entry<ChunkPos, ChunkSnapshotSource>> entries = captured.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<ChunkPos, ChunkSnapshotSource> entry = entries.next();
            ChunkPos pos = entry.getKey();
            // On a resume, read this chunk's on-disk prior for recovered coverage while it is still in view, once
            // per chunk, rather than waiting for the flush (by when it has left the outline clamp). The
            // recovered set feeds only the outline, so the read is skipped whole when the outline is off, and each
            // axis is gated on its own capture switch (an off axis draws no rim, so its prior is never consulted).
            if (resumeDownload && config.outline().renderUnsavedOutline() && recoveryScanned.add(pos.toLong())) {
                if (config.captureContainers()) {
                    activeWriter.submitResumeScan(targetDimension, pos);
                }
                if (config.captureEntities()) {
                    activeWriter.submitEntityResumeScan(targetDimension, pos);
                }
            }
            if (!all && !FlushPolicy.shouldFlush(pos.x, pos.z, centerX, centerZ, keepHot)) {
                continue;
            }
            ChunkSnapshotSource snapshot = entry.getValue();
            if (skipVoid
                    && isVoidChunk(pos, snapshot, bufferedEntityChunks, accumulatedEntityChunks,
                            pendingInteractionChunks)) {
                // Lossless: a VOID world regenerates this position as air identically, so dropping it (and its
                // allCaptured position) keeps the count and resume honest. The live overlay keeps the position
                // (the indexes have no per-position remove); presence-only, cleared once the save completes,
                // and a resume re-seeds from disk, so the overstatement never survives the session.
                allCaptured.remove(pos.toLong());
                entries.remove();
                continue;
            }
            // Drain this chunk's open-time holders out of the shared stashes into a per-submit bundle the writer
            // thunk then solely owns. The main thread prepares them (scrub + map remap below) and forgets them,
            // so only immutable, detached data crosses to the writer (the thread-handoff boundary rule).
            Map<BlockPos, CompoundTag> containers = drainChunkHolders(containerStash, pos);
            Map<BlockPos, CompoundTag> lecterns = drainChunkHolders(lecternStash, pos);
            // Scrub and on-sight map remap each drained holder exactly once, here at drain time and
            // never as a whole-stash pass: the stash holds every not-yet-flushed container, which a dense storage
            // room keeps hot for a whole session, so a per-tick pass over it scales with everything opened (with
            // hundreds of shulker-filled chests, one pass made the client tick take tens of milliseconds), while
            // the drain-time prepare costs only the holders actually flushing and still precedes everything
            // writer-bound. A map in a chest whose chunk flushes mid-roam is thereby captured before it leaves
            // memory.
            prepareDrainedItems(containers);
            // Drain and reconcile this chunk's interaction-predicted candidates against the captured snapshot's
            // block-state. The confirmed "Items" (placed shulker, bookshelf books) are scrubbed and map
            // remapped like the open-time path, then folded into the container bundle behind the open-time-wins
            // precedence (an opened container at the same pos supersedes a possibly-stale place snapshot). The
            // jukebox/beehive holders carry to the writer thunk for their own field-copy merge.
            final Map<BlockPos, CompoundTag> holders;
            if (interactionCapture != null) {
                // Placed-shulker durability: drainChunk reconciles each candidate against this snapshot, the very
                // one the writer thunk below encodes and merges against, so a jukebox-then-shulker replacement at
                // one pos is dropped by the confirm predicate before it can merge. Keep the reconcile on the same
                // snapshot as the position merge; a live read or a different snapshot here reopens that hole.
                InteractionCapture.ChunkBundles confirmed = interactionCapture.drainChunk(pos, snapshot);
                // Drop the open-time-wins losers before the remap, since prepareDrainedItems allocates a map id
                // and writes map_<id>.dat per holder: remapping a same-pos loser would orphan that file.
                Map<BlockPos, CompoundTag> placedItems = ContainerMerge.mergePlaceCandidates(containers,
                        confirmed.items());
                prepareDrainedItems(placedItems);
                containers.putAll(placedItems);
                tallyInteractionMerges(placedItems.keySet(), confirmed.holders().keySet());
                holders = confirmed.holders();
            } else {
                holders = Map.of();
            }
            // Count the folds on main against the snapshot's block entities (the x/y/z match the writer-side fold
            // re-makes against the encoded tag), so the live HUD count stays incremental even though the fold
            // itself runs on the writer. A holder that matches a captured block entity here will match the encoded
            // tag on the writer; the rare deferred encode failure then over-counts, accepted for a cosmetic figure.
            List<BlockPos> landingContainers = ChunkFlushPlan.landingHolderPositions(snapshot, containers);
            mergedContainers += landingContainers.size();
            mergedLecterns += ChunkFlushPlan.landingHolderPositions(snapshot, lecterns).size();
            mergedContainers += ChunkFlushPlan.landingHolderPositions(snapshot, holders).size();
            // Defer the heavy serialize plus the pure container/lectern fold to the writer thread:
            // the thunk closes over the detached snapshot, the drained holders, the per-band codec/sinks, and the
            // frozen registries, all immutable, so the render thread never runs SerializableChunkData.write. The
            // target dimension is read here on main (submit time), not in the thunk, so a rebind cannot misroute.
            boolean synthesizeBlending = VanillaDimensions.shouldSynthesizeBlending(config.worldOutput().worldType(),
                    targetDimension);
            // Blank any item-borne coordinate riding a block entity's own NBT (a compass in a decorated pot or on a
            // shelf), the one item-borne surface the item-list scrub above never sees. Done here, on the main
            // thread and only for the chunks draining this pass, so the writer thunk encodes an already-scrubbed
            // snapshot: the block-entity NBT is our detached copy, and this is the block-entity analogue of the
            // per-drained-holder prepare, never a per-tick pass over the whole buffer.
            if (!config.saveItemCoordinates()) {
                for (CompoundTag blockEntity : snapshot.blockEntities()) {
                    ItemLocationScrub.scrubBlockEntity(blockEntity);
                }
            }
            // Consumed by this write, not held: the copy this write leaves on disk is post-placement, so the
            // next visit's carry-forward is reading its own capture rather than the replaced block's. Holding
            // it would suppress forever, which erases what this download archived on every later pass.
            // Committed here, at enqueue, with the residual that follows from it: an encode throw or a failed
            // or preserved write leaves the prior on disk with the suppression already spent, so the next
            // visit carries the replaced block's contents forward. The alternative is not holding it, which is
            // the routine erasure above, but confirming per position across the writer boundary, which is
            // state this side cannot read without a second producer on main-thread data.
            LongSet replacedHere = ChunkFlushPlan.replacedIn(pos, replacedBlockKeys);
            replacedBlockKeys.removeAll(replacedHere);
            activeWriter.submitChunk(targetDimension, pos,
                    () -> {
                        CompoundTag tag = codec.encode(snapshot, registries, synthesizeBlending);
                        blockContainersFailed += ChunkFlushPlan.foldChunkStashes(tag, pos, containerSink,
                                lecternSink, containers, lecterns, holders).failed();
                        return tag;
                    }, ChunkFlushPlan.readMerge(snapshot, landingContainers, replacedHere));
            entries.remove();
        }
        if (all) {
            sweepOrphanedHolders(activeWriter);
        }
        flushEntityBuffer(activeWriter, all, centerX, centerZ, keepHot);
    }

    /**
     * Whether {@code pos}'s captured chunk is void (safe to omit): no non-air blocks, no block-entities, no captured
     * entities (buffered or accumulated), no captured containers, and no pending interaction prediction (else dropping
     * the chunk would leak its undrained stash entry). The decision is taken here, against the artifact about to be
     * written, so it cannot drift from what is thrown away.
     */
    private boolean isVoidChunk(ChunkPos pos, ChunkSnapshotSource snapshot, Set<ChunkPos> bufferedEntityChunks,
            LongSet accumulatedEntityChunks, Set<ChunkPos> pendingInteractionChunks) {
        boolean hasEntities = bufferedEntityChunks.contains(pos) || accumulatedEntityChunks.contains(pos.toLong());
        boolean hasContainers = anyHolderInChunk(containerStash, pos) || anyHolderInChunk(lecternStash, pos)
                || pendingInteractionChunks.contains(pos);
        return VoidChunkPolicy.isVoidChunk(
                hasNonAirBlocks(snapshot), !snapshot.blockEntities().isEmpty(), hasEntities, hasContainers);
    }

    /** Whether any captured section of {@code snapshot} holds a non-air block state. */
    private static boolean hasNonAirBlocks(ChunkSnapshotSource snapshot) {
        for (ChunkSnapshotSource.SectionData section : snapshot.sections()) {
            LevelChunkSection chunkSection = section.chunkSection();
            if (chunkSection != null && !chunkSection.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code stash} holds any open-time container holder located in {@code pos}'s chunk. */
    private static boolean anyHolderInChunk(Map<BlockPos, ?> stash, ChunkPos pos) {
        for (BlockPos holderPos : stash.keySet()) {
            if (new ChunkPos(holderPos).equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drain (remove and return) the stash holders located in {@code pos}'s chunk, unpacked and bundled for the
     * writer-thread fold. Mirrors {@link ContainerMerge}'s per-chunk locator, but on the main thread, so the shared
     * stash is left holding only other chunks' still-open holders. An unpack failure drops that one holder rather than
     * aborting the per-tick flush, and un-marks the position as captured, so the outline re-rims it and a re-open can
     * recover the contents; a retry would deterministically re-fail against the same packed bytes. The bind-time report
     * count stays high by one, accepted for a cannot-happen path.
     */
    private Map<BlockPos, CompoundTag> drainChunkHolders(Map<BlockPos, StashHolder> stash, ChunkPos pos) {
        Map<BlockPos, CompoundTag> bundle = new LinkedHashMap<>();
        Iterator<Map.Entry<BlockPos, StashHolder>> holders = stash.entrySet().iterator();
        while (holders.hasNext()) {
            Map.Entry<BlockPos, StashHolder> holder = holders.next();
            if (new ChunkPos(holder.getKey()).equals(pos)) {
                try {
                    bundle.put(holder.getKey(), holder.getValue().unpack());
                } catch (IOException e) {
                    long posKey = holder.getKey().asLong();
                    capturedBlockKeys.remove(posKey);
                    capturedBlockTypes.remove(posKey);
                    LOGGER.warn("failed to unpack stashed container {}; its contents are lost and its outline re-arms",
                            holder.getKey(), e);
                }
                holders.remove();
            }
        }
        return bundle;
    }

    /**
     * Sweep the container and lectern holders still stashed after a whole-buffer drain: the open-time contents of a
     * container opened in a chunk that had already left the keep-hot buffer. Its chunk is on disk but was never
     * re-buffered (the allCaptured skip in {@link #captureLoadedChunks}), so the per-chunk drain never reached its
     * holder; fold each residual holder into its on-disk chunk through a writer-thread read-modify-write, so
     * backtracking through an already-flushed base still saves what the player opened. Runs only on a whole-buffer
     * drain ({@code all}), never per tick, so it adds no per-tick whole-stash pass. Finish and the dimension rebind
     * always reach it (both call {@link #flushBuffer} directly); the capture-paused drain reaches it only alongside a
     * non-empty keep-hot buffer, since {@link #pumpFlush} returns early on an empty buffer, so finish is the backstop
     * that guarantees every residual holder is swept. The rebind drain runs it before the dimension swap under the old
     * dimension, so a residual holder never crosses a portal into another dimension's {@link ChunkPos} space. The
     * interaction-prediction candidates are deliberately not swept: their reconcile gate confirms a candidate against
     * the live captured snapshot's block-state, which an orphaned chunk no longer holds, so a candidate cannot be
     * confirmed off the on-disk chunk (and a placed shulker or beehive is not even present there).
     */
    private void sweepOrphanedHolders(AsyncSaveWriter activeWriter) {
        if (containerStash.isEmpty() && lecternStash.isEmpty()) {
            return;
        }
        ContainerSink containerSink = adapter.containerSink();
        LecternSink lecternSink = adapter.lecternSink();
        ResourceKey<Level> dimension = targetDimension;
        for (ChunkPos pos : ChunkFlushPlan.residualHolderChunks(containerStash.keySet(),
                lecternStash.keySet())) {
            Map<BlockPos, CompoundTag> containers = drainChunkHolders(containerStash, pos);
            Map<BlockPos, CompoundTag> lecterns = drainChunkHolders(lecternStash, pos);
            prepareDrainedItems(containers); // scrub and on-sight map remap, exactly as the buffered drain does
            activeWriter.submitChunkRewrite(dimension, pos, onDisk -> {
                MergeTally tally = ChunkFlushPlan.foldResidualHolders(onDisk, pos, containerSink,
                        lecternSink, containers, lecterns);
                blockContainersFailed += tally.failed();
                return tally.merged();
            });
        }
    }

    /**
     * Pack at most one closed holder per tick to its gzip bytes, oldest first, skipping the open menu's bound positions
     * (still last-seen-wins overwritten). Without this the stash retains every not-yet-flushed holder as a full tag
     * tree, which a dense storage-room session grows to hundreds of megabytes of heap; one holder per tick bounds the
     * pack cost while still outpacing any human open rate.
     */
    private void compactClosedStashHolders() {
        if (!compactOneStashHolder(containerStash)) {
            compactOneStashHolder(lecternStash);
        }
    }

    private boolean compactOneStashHolder(Map<BlockPos, StashHolder> stash) {
        for (Map.Entry<BlockPos, StashHolder> entry : stash.entrySet()) {
            StashHolder holder = entry.getValue();
            if (!holder.shouldCompact() || isBoundOpen(entry.getKey().asLong())) {
                continue;
            }
            try {
                holder.compact();
                return true;
            } catch (IOException e) {
                // Unreachable in practice: the pack writes to a memory stream. The holder keeps its live
                // tree and retires itself from packing, so this warns once per holder and the pump moves on
                // to the next candidate rather than head-of-line blocking on a permanent failure.
                LOGGER.warn("failed to pack stashed container {}; it stays live in memory", entry.getKey(), e);
            }
        }
        return false;
    }

    /** Whether {@code posKey} is a bound position of the currently-open menu (either double-chest half). */
    private boolean isBoundOpen(long posKey) {
        OptionalLong bound = association.boundPos();
        if (bound.isPresent() && bound.getAsLong() == posKey) {
            return true;
        }
        OptionalLong secondary = association.boundSecondaryPos();
        return secondary.isPresent() && secondary.getAsLong() == posKey;
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
        prepareEntityContainers(); // scrub + map-remap any new vehicle holders once, before the merge drains them
        prepareMerchantHolders(); // and the merchant offer holders, on the same once-per-holder discipline
        for (ChunkPos pos : entityBuffer.bufferedChunks()) {
            if (all || FlushPolicy.shouldFlush(pos.x, pos.z, centerX, centerZ, keepHot)) {
                flushEntityChunk(activeWriter, pos);
            }
        }
    }

    /**
     * Drain one entity-chunk: build the envelope, fold in the vehicle containers, remap framed maps, submit.
     * Package-private so the entity-container and map-remap tallies it feeds stay testable; its only production caller
     * is the entity flush pump.
     */
    void flushEntityChunk(AsyncSaveWriter activeWriter, ChunkPos pos) {
        List<CompoundTag> tags = entityBuffer.drainChunk(pos);
        dropExcludedRootVehicles(tags); // a mount captured into RootVehicle is not also written standalone
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
            entityContainersFailed += EntityContainerMerge.refoldFlushedContainers(adapter.containerSink(),
                    envelope, foldedContainerVehicles, entityContainerStash).failed();
            recordStandaloneFoldedContainers(tags);
            MergeTally entityTally = EntityContainerMerge.mergeEntityStash(adapter.containerSink(),
                    envelope, entityContainerStash);
            mergedEntityContainers += entityTally.merged();
            entityContainersFailed += entityTally.failed();
            // The merchant siblings, same refold-then-record-then-merge order. The refold re-writes trades onto a
            // second chunk copy for its side effect only, so its count is not summed; the fold cannot fail, so
            // there is no failed term to add.
            EntityContainerMerge.refoldFlushedMerchants(envelope, foldedMerchants, merchantStash);
            recordStandaloneFoldedMerchants(tags);
            mergedVillagerTrades += EntityContainerMerge.mergeMerchantStash(envelope, merchantStash).merged();
            if (!config.saveItemCoordinates()) {
                scrubEntityItems(envelope);
            }
            MapArchive archive = this.mapArchive;
            if (archive != null) {
                remapEntityItems(envelope, archive);
            }
            activeWriter.submitEntity(targetDimension, pos, envelope);
            countEntitiesSubmitted(tags); // tally writes here, at successful submit, not at buffer time
        } catch (RuntimeException e) {
            LOGGER.warn("skipping {} entities for chunk {}: encode/merge failed", tags.size(), pos, e);
            countEntityFlushDrop(tags);
        }
    }

    /**
     * Retain the holder of every container vehicle in this drained chunk whose captured contents are about to be folded
     * into the standalone entity write, keyed by UUID, before {@code mergeEntityStash} drains the stash. A reopened
     * menu replaces the retained holder here with its fresher capture.
     */
    private void recordStandaloneFoldedContainers(List<CompoundTag> tags) {
        for (CompoundTag tag : tags) {
            UUID uuid = EntityMerge.readUuid(tag);
            if (uuid == null) {
                continue;
            }
            CompoundTag holder = entityContainerStash.get(uuid);
            if (holder != null) {
                // Retain a copy rather than the holder itself: the sink aliases the list it is handed into the
                // envelope crossing to the writer, and this retention outlives that envelope. Mark the copy
                // prepared in the same breath, because it is a copy of an already-scrubbed, already-remapped
                // holder and the map remap is not idempotent; a later fold must not re-run it.
                CompoundTag retained = holder.copy();
                preparedEntityContainers.add(retained);
                foldedContainerVehicles.put(uuid, retained);
            }
        }
    }

    /**
     * The merchant analog of {@link #recordStandaloneFoldedContainers}: retain a copy of each about-to-drain offer
     * holder for a villager written standalone in this chunk, so {@link EntityContainerMerge#refoldFlushedMerchants}
     * can re-apply it to another chunk copy (a wandering trader that crossed entity-chunks). The copy is marked
     * prepared, since it is a copy of an already-scrubbed, already-remapped holder the non-idempotent remap must not
     * re-run.
     */
    private void recordStandaloneFoldedMerchants(List<CompoundTag> tags) {
        for (CompoundTag tag : tags) {
            UUID uuid = EntityMerge.readUuid(tag);
            if (uuid == null) {
                continue;
            }
            CompoundTag holder = merchantStash.get(uuid);
            if (holder != null) {
                CompoundTag retained = holder.copy();
                preparedMerchantHolders.add(retained);
                foldedMerchants.put(uuid, retained);
            }
        }
    }

    /**
     * Drop from a drained entity-chunk any UUID captured into the player's RootVehicle this finish, so a primed or
     * reconstructed mount is not also written standalone (a same-UUID clash on load). {@code drainChunk} returns a
     * fresh mutable list, so the {@code removeIf} is safe; the source tally is dropped too, since a filtered mount is
     * player-state, not a standalone write. The set is empty until finish, so mid-session flushes are untouched. A
     * primed mount already flushed to disk before finish cannot be retracted here; that is the accepted UUID-collision
     * residual, and it self-heals only when both copies land in one level: a mount the player rode into another
     * dimension leaves a copy behind that no load can drop, which is a standing residual of this path rather than
     * something it handles.
     */
    private void dropExcludedRootVehicles(List<CompoundTag> tags) {
        if (excludedRootVehicleUuids.isEmpty()) {
            return;
        }
        tags.removeIf(tag -> {
            UUID uuid = EntityMerge.readUuid(tag);
            if (uuid == null || !excludedRootVehicleUuids.contains(uuid)) {
                return false;
            }
            bufferedEntitySources.remove(uuid);
            return true;
        });
    }

    /** Tally each submitted entity to its write counter by the source recorded when it was buffered. */
    private void countEntitiesSubmitted(List<CompoundTag> tags) {
        for (CompoundTag tag : tags) {
            UUID uuid = EntityMerge.readUuid(tag);
            if (uuid == null) {
                continue; // every live-encoded tag carries a UUID; defensive, matches EntityContainerMerge
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
     * Scrub the item-borne coordinates and on-sight map-remap each captured vehicle/animal container holder, exactly
     * once per holder (tracked by identity), before {@link EntityContainerMerge} folds it into its entity tag, via the
     * shared {@link #scrubAndRemapItems} sanitization.
     */
    private void prepareEntityContainers() {
        for (Map.Entry<UUID, CompoundTag> entry : entityContainerStash.entrySet()) {
            if (preparedEntityContainers.add(entry.getValue())) {
                scrubAndRemapItems(entry.getValue(), entry.getKey());
            }
        }
    }

    /**
     * Scrub the sell items' coordinates and on-sight map-remap each captured merchant offer holder, exactly once per
     * holder (tracked by identity), before {@link EntityContainerMerge} folds it onto its villager tag. The scrub and
     * remap can throw (the map archive's on-sight image stream is a live IO path), so a throwing holder is isolated the
     * same way the item path isolates a framed map: the villager's trades save without the sanitize this holder needed,
     * the loss is tallied, and the download reads partial.
     */
    private void prepareMerchantHolders() {
        MapArchive archive = this.mapArchive;
        for (Map.Entry<UUID, CompoundTag> entry : merchantStash.entrySet()) {
            if (!preparedMerchantHolders.add(entry.getValue())) {
                continue; // already scrubbed and remapped once; the map remap is not idempotent
            }
            try {
                MerchantOfferCapture.scrubAndRemapOffers(entry.getValue(), !config.saveItemCoordinates(), archive);
            } catch (RuntimeException e) {
                mapsRemapFailed++;
                mapEntityRemapLoss.lost(entry.getKey(), e);
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

    /** Tally one entity the encode, a malformed envelope, or the finish re-offer destroyed, against its path. */
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
            List<PacketEntity<Packet<?>, SynchedEntityData.DataItem<?>, EquipmentEntry>> held) {
        List<Promoted> built = new ArrayList<>();
        Map<Integer, Entity> byId = new HashMap<>();
        for (PacketEntity<Packet<?>, SynchedEntityData.DataItem<?>, EquipmentEntry> frame : held) {
            if (excludedRootVehicleUuids.contains(frame.uuid())) {
                continue; // captured into the player's RootVehicle; not also a standalone entity (same-UUID clash)
            }
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
                    rider.startRiding(promoted.entity(), true); // the vehicle's save then nests the rider
                }
            }
            if (promoted.frame().leashHolderId() != 0 && promoted.entity() instanceof Mob mob) {
                Entity holder = byId.get(promoted.frame().leashHolderId());
                if (holder != null) {
                    mob.setLeashedTo(holder, false); // saved as the holder's UUID, or a fence knot's block pos
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
            if (entity.shouldBeSaved()) {
                reportCounts.addEntity(promoted.frame().uuid()); // dedup-by-UUID
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
            PacketEntity<Packet<?>, SynchedEntityData.DataItem<?>, EquipmentEntry> frame,
            Entity entity) {}

    /**
     * Rebuild a live entity from its accumulated packet state, the way the client does on the spawn packet then the
     * post-spawn packets: create the typed entity ({@link EntityPacketCapture#createSpawnEntity}, which recreates from
     * the packet on the {@code AddEntity} path and constructs directly on the mob/painting/orb paths), stamp its id and
     * UUID, snap it to its last known position (the accumulated move/teleport/position-sync, so a mob saves where it
     * ended), assign the merged synced values, and set the merged equipment per slot. Passengers and the leash are
     * wired by {@link #promoteChunk} once the sibling entities exist. The result is a fresh, never-removed entity
     * {@link EntitySink} saves like any other. Returns null if the type cannot create.
     */
    private @Nullable Entity reconstructPacketEntity(
            PacketEntity<Packet<?>, SynchedEntityData.DataItem<?>, EquipmentEntry> frame) {
        Entity entity = EntityPacketCapture.createSpawnEntity(frame.spawn(), level());
        if (entity == null) {
            return null;
        }
        entity.setId(frame.id());
        entity.setUUID(frame.uuid());
        EntityPos pos = frame.pos();
        entity.moveTo(pos.x(), pos.y(), pos.z(), pos.yRot(), pos.xRot());
        List<SynchedEntityData.DataItem<?>> synced = frame.synced();
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
     * Scrub and map remap a drained {@code "Items"} holder bundle (the open-time containers and the confirmed
     * place/insert holders) before it joins the writer-bound submit: a holder can carry a lodestone compass or a filled
     * map, and an un-remapped map id would collide with an archive id and render the wrong map. Called only on holders
     * that will merge, never on a still-stashed one, so the non-idempotent remap runs exactly once per holder.
     */
    private void prepareDrainedItems(Map<BlockPos, CompoundTag> holders) {
        for (Map.Entry<BlockPos, CompoundTag> entry : holders.entrySet()) {
            scrubAndRemapItems(entry.getValue(), entry.getKey());
        }
    }

    /**
     * Scrub the item-borne coordinates and on-sight {@link MapArchive#remap} a captured container holder's
     * {@code "Items"}, the shared container sanitization the open-time, placed-container, and entity-container paths
     * run. {@link ItemLocationScrub} is null-safe and total, so the scrub cannot throw; the remap is per-holder
     * fail-soft, so a serialize bug renders that one map blank rather than aborting the flush. {@code identifier}
     * labels the holder in the loss line.
     */
    private void scrubAndRemapItems(CompoundTag holder, Object identifier) {
        if (!config.saveItemCoordinates()) {
            ItemLocationScrub.scrub(holder, "Items");
        }
        MapArchive archive = this.mapArchive;
        if (archive != null) {
            try {
                archive.remap(holder, "Items");
            } catch (RuntimeException e) {
                mapsRemapFailed++;
                mapRemapLoss.lost(identifier, e);
            }
        }
    }

    /**
     * Tally each confirmed interaction-prediction merge to the dedup-correct report counter, the way open-time merges
     * tally, keyed by pos so each container counts once. A placed shulker, a jukebox disc, and a beehive count here at
     * confirm (flush) time, so their live count lags until the chunk roams out of the hot window. A bookshelf is the
     * exception: it is counted live at full-cycle ({@link #onBookshelfSlotCaptured}) and skipped here, so a
     * partly-cycled shelf never counts.
     */
    private void tallyInteractionMerges(Set<BlockPos> items, Set<BlockPos> holders) {
        tallyInteractionPositions(items);
        tallyInteractionPositions(holders);
    }

    private void tallyInteractionPositions(Set<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (bookshelfSlots.containsKey(pos.asLong())) {
                continue; // a bookshelf counts live at full-cycle, never here on the any-slot confirm
            }
            reportCounts.addContainer("i:" + pos.asLong());
        }
    }

    /**
     * Blank item-borne coordinates on every entity in an encoded entity-chunk tag (and their passengers), per the
     * item-coordinate knob. Walks the post-1.17 {@code "Entities"} list; {@link ItemLocationScrub#scrubEntity} recurses
     * each entity's own {@code "Passengers"}, so this stays a flat top-level loop. Runs inside the per-chunk try.
     */
    private void scrubEntityItems(CompoundTag entityChunkTag) {
        if (!(entityChunkTag.get("Entities") instanceof ListTag entities)) {
            return;
        }
        for (int i = 0; i < entities.size(); i++) {
            if (entities.get(i) instanceof CompoundTag entity) {
                ItemLocationScrub.scrubEntity(entity);
            }
        }
    }

    /**
     * Remap (and on-sight serialize) the maps of each entity's displayed single {@code "Item"} in an encoded
     * entity-chunk tag: an item frame's framed map and a dropped item entity's map. Walks the post-1.17
     * {@code "Entities"} list; entities with no {@code "Item"} are skipped. Runs inside the per-chunk try.
     */
    private void remapEntityItems(CompoundTag entityChunkTag, MapArchive archive) {
        if (!(entityChunkTag.get("Entities") instanceof ListTag entities)) {
            return;
        }
        for (int i = 0; i < entities.size(); i++) {
            if (entities.get(i) instanceof CompoundTag entity && entity.get("Item") instanceof CompoundTag item) {
                try {
                    archive.remapItem(item);
                } catch (RuntimeException e) {
                    // Per-item fail-soft, the scrubAndRemapItems discipline: a single bad
                    // framed/dropped map item renders blank rather than throwing out of flushEntityChunk
                    // and losing the whole already-drained entity-chunk.
                    mapsRemapFailed++;
                    mapEntityRemapLoss.lost(EntityMerge.readUuid(entity), e);
                }
            }
        }
    }

    /** Report the background save's outcome to the player (called on the main thread, once, when it completes). */
    private void report(AsyncSaveWriter.SaveResult result) {
        if (result.failed()) {
            reportSaveFailure(result.error());
            return;
        }
        // Fallback to the writer-thread finalizer's completion write; idempotent, so at-most-once. Carries the
        // same soft tally so a fallback write stamps the same clean-or-partial status the finalizer would.
        writeReportCompletion(result.chunksFailed(), result.entityChunksFailed());
        int failed = failedWriteCount(result.chunksFailed(), result.entityChunksFailed());
        boolean partial = failed > 0;
        // The chat figures are the dedup'd counts the HUD and downloads screen show: the distinct captured-chunk
        // total, not the writer's new-plus-re-captured write tally, which double-counts a chunk written once then
        // re-flushed on a revisit. The merge fold below stays log-only diagnostics (it overcounts).
        int distinctChunks = totalCapturedChunks();
        int containers = mergedContainers + mergedEntityContainers + result.mergedContainers();
        if (config.showChatMessages()) {
            bridge.sendChat(ChatCopy.downloaded(saveName, distinctChunks,
                    reportCounts.entityCount(), reportCounts.containerCount(), Wdl.elapsedMillis()));
            if (partial) {
                bridge.sendChat(ChatCopy.downloadIncomplete(failed));
            }
            Path saveFolder = Minecraft.getInstance().getLevelSource().getBaseDir().resolve(saveName)
                    .toAbsolutePath();
            bridge.sendChat(ChatCopy.savedTo(saveName, saveFolder.toString()));
        }
        LOGGER.info("saved {} chunks ({} new, {} re-captured, {} failed), {} entity-chunks ({} failed, "
                + "{} carried forward on re-flush), {} containers, {} lecterns, {} villager trades to {}",
                result.chunksWritten(), result.chunksNew(), result.chunksRecaptured(), result.chunksFailed(),
                result.entityChunksWritten(), result.entityChunksFailed(), result.entitiesCarriedForward(),
                containers, mergedLecterns, mergedVillagerTrades, saveName);
        // The terms of the partial-finish sum the line above does not name, so that adding the two it does name
        // reproduces the count the chat reports. Logged even when every term is zero: a reader who cannot tell
        // "nothing else was lost" from "this build had no such line" cannot check the clean verdict at all.
        LOGGER.info("counted capture losses for {}: {} chunk captures, {} maps, {} map remaps, {} idcounts, "
                + "{} map manifest, {} block containers, {} entity containers, {} container vehicles, "
                + "{} villager trades, {} predicted interactions, {} structural entities, {} resumed mounts, "
                + "{} finish steps",
                saveName, chunksCaptureFailed, mapsFailed.get(), mapsRemapFailed, idCountsFailed,
                mapManifestLosses(), blockContainersFailed, entityContainersFailed, containerVehiclesLost,
                villagerTradesLost, interactionCapturesLost, structuralEntitiesLost, resumedMountsLost,
                finishStepsFailed);
        // The destination the toast names is the export zip when one was actually written (the shareable copy),
        // else the openable folder; zipFileName is null on a zip failure, so the toast never names a missing zip.
        String zipFileName = result.zipFileName();
        ToastCopy toast;
        if (partial && zipFileName != null) {
            toast = ToastCopy.partial(config.showToasts(), distinctChunks, Wdl.elapsedMillis(), zipFileName, true);
        } else if (partial) {
            toast = ToastCopy.partial(config.showToasts(), distinctChunks, Wdl.elapsedMillis(), saveName, false);
        } else if (zipFileName != null) {
            toast = ToastCopy.completionZip(config.showToasts(), distinctChunks, Wdl.elapsedMillis(), zipFileName);
        } else {
            toast = ToastCopy.completion(config.showToasts(), distinctChunks, Wdl.elapsedMillis(), saveName);
        }
        if (toast != null) {
            bridge.sendToast(toast);
        }
    }

    /** Surface a failed save in chat, the log, and the error toast, all carrying the same message. */
    private void reportSaveFailure(@Nullable Throwable error) {
        SaveFailureReason reason = SaveFailureComposer.describe(error);
        bridge.sendChat(ChatCopy.saveFailed(reason)); // an error is never suppressed by showChatMessages
        LOGGER.error("save failed", error);
        ToastCopy toast = ToastCopy.downloadError(config.showToasts(), reason);
        if (toast != null) {
            bridge.sendToast(toast);
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            LOGGER.warn("failed to close the world save access", e);
        }
    }

    /**
     * Stamp the download report's identity and settings diff into the save folder before the save begins, marking the
     * folder as wdl-managed. Runs once at world-open ({@link #ensureWriter}) and writes only the crash sentinel; the
     * human rendering follows from the writer preflight, after the pre-resume backup, so the backup archives the prior
     * session's download.md. The store is fail-soft, so a report write failure never blocks the save.
     */
    private void beginReport(Minecraft minecraft, Path saveRoot) {
        DownloadIdentity identity = buildReportIdentity(minecraft);
        ReportEnvironment environment = buildReportEnvironment(minecraft);
        this.reportRoot = saveRoot;
        this.reportIdentity = identity;
        this.reportEnvironment = environment;
        report.begin(saveRoot, identity, environment, config.nonDefaultSettings());
    }

    /** Read the MC-side environment facts (server brand, simulation distance, dimension, MC + mod version). */
    private ReportEnvironment buildReportEnvironment(Minecraft minecraft) {
        String brand = "";
        LocalPlayer player = minecraft.player;
        if (player != null && player.getServerBrand() != null) {
            brand = player.getServerBrand();
        }
        // 1.17.1 has no server simulation distance (a 1.18 addition), so render distance stands in for the report.
        return new ReportEnvironment(brand, minecraft.options.renderDistance,
                targetDimension.location().toString(), Wdl.mcVersion(), bridge.modVersion());
    }

    /** Read the few MC-side identity facts (downloader, source, loader) into the MC-free report identity. */
    private DownloadIdentity buildReportIdentity(Minecraft minecraft) {
        String downloaderName = "";
        String downloaderUuid = "";
        LocalPlayer player = minecraft.player;
        if (player != null) {
            downloaderName = player.getGameProfile().getName();
            downloaderUuid = player.getGameProfile().getId().toString();
        }
        String address = "";
        String sourceName = "";
        String motd = "";
        String sourceKind = "unidentified";
        ServerData server = minecraft.getCurrentServer();
        if (server != null) {
            address = server.ip;
            sourceName = server.name;
            motd = server.motd.getString();
            sourceKind = "";
        }
        String worldName = target.worldName();
        String downloadName = worldName != null ? worldName : "";
        return new DownloadIdentity(UUID.randomUUID().toString(), Instant.now().truncatedTo(ChronoUnit.SECONDS),
                downloaderName, downloaderUuid, address, sourceName, motd, bridge.loaderName(),
                bridge.loaderVersion(), downloadName, sourceKind);
    }

    /**
     * Freeze the chunk, entity, and container counts at the end-of-capture moment into {@link #pendingReport}. The
     * container and entity counts were accumulated during capture; the chunks are fed here from the retained position
     * set.
     */
    private void prepareReportCompletion() {
        Path root = reportRoot;
        DownloadIdentity identity = reportIdentity;
        ReportEnvironment environment = reportEnvironment;
        if (root == null || identity == null || environment == null) {
            return; // the report never began (defensive: ensureWriter stamps it before finish reaches here)
        }
        // Build the per-dimension breakdown straight from the already-deduped position sets: each dimension
        // keeps its own ResourceKey identity and its own count, so a shared packed position in two dimensions
        // counts once in each, as it should.
        List<DimensionChunks> dimensions = new ArrayList<>();
        int chunkTotal = 0;
        for (Map.Entry<ResourceKey<Level>, LongOpenHashSet> dimension : capturedByDimension.entrySet()) {
            int chunks = dimension.getValue().size();
            dimensions.add(new DimensionChunks(dimension.getKey().location().toString(), chunks));
            chunkTotal += chunks;
        }
        this.pendingReport = new PendingReport(root, identity, environment,
                config.nonDefaultSettings(), Instant.now().truncatedTo(ChronoUnit.SECONDS),
                chunkTotal, reportCounts.entityCount(), reportCounts.containerCount(), dimensions);
    }

    /**
     * Write the download report's completion record once, the authoritative finish marker. Reachable from the
     * writer-thread finalizer (the clean-finish path) and again from the main-thread {@link #report} fallback;
     * {@link DownloadReportStore#complete} is idempotent and fail-soft, so exactly one record results and a report
     * write failure never corrupts the save. The save-chunk scan is handed over lazily and runs after the final drain,
     * so it sees every flushed region write and the store's at-most-once latch keeps it from running twice.
     */
    private void writeReportCompletion(int chunksFailed, int entityChunksFailed) {
        PendingReport pending = pendingReport;
        if (pending == null) {
            return; // nothing began, or finish() returned early before freezing the counts
        }
        WorldIconWriter.write(pending.saveRoot(), reportIconBytes); // bytes were snapshotted on the main thread
        boolean clean = !isPartialSave(chunksFailed, entityChunksFailed);
        report.complete(pending.saveRoot(), pending.identity(), pending.environment(), pending.settings(),
                pending.finishedAt(), new DownloadCounts(pending.chunks(), pending.entities(),
                        pending.containers(), pending.dimensions()),
                this::scanSaveChunks, clean);
    }

    /**
     * The in-save chunk totals at this finish instant, from the on-disk region headers; empty without paths. The catch
     * is the supplier-boundary backstop: a scan failure of any kind degrades to a zero total (which the report and the
     * screen row both render as a session-counts fallback) rather than reaching the store's catch and costing the
     * completion record of a fully-written download.
     */
    private SaveChunks scanSaveChunks() {
        try {
            WorldPaths paths = worldPaths;
            return paths == null
                    ? new SaveChunks(0, List.of())
                    : SaveChunks.scan(paths.onDiskRegionDirectories());
        } catch (RuntimeException e) {
            LOGGER.warn("save-total scan failed; the report keeps the session counts only", e);
            return new SaveChunks(0, List.of());
        }
    }

    /**
     * Whether this download lost anything a tally counted, so the finish is partial rather than clean. Sums the
     * writer's chunk and entity-chunk tally (from the finalize step or {@link AsyncSaveWriter.SaveResult}) and the
     * session's own fail-soft tallies (throwing chunk snapshots, map writes and remaps, the finalize-time idcounts
     * write, the map-id manifest, block and vehicle container merges, unrecovered opened vehicles, unwritten
     * interaction predictions, structural entity drops, an unplaceable resumed mount, and the degraded finish-time
     * steps). Heterogeneous units, honest only as a rough magnitude; the log carries the breakdown.
     *
     * <p>A zero is not proof a download lost nothing. Losses reach this sum only where a term was added for them, so
     * read a zero as "no counted term moved" and never as "nothing was lost", and do not add a caller that treats it as
     * an assertion of completeness. Several classes escape it by construction: a loss whose size its own catch cannot
     * see, one that happens at a write rather than at the capture this sum counts, and one dropped before anything
     * holds it. Whether any given loss has a term is answered by reading the terms, not by trusting a list here to be
     * current.
     *
     * <p>Those tallies accrue on two threads: map data writes, the idcounts write and block-container merges land on
     * the writer thread, the manifest record on either (the world-open scheme signal runs on the main thread, the
     * finalize rewrite on the writer's), and the rest on the main thread. Only the map tally is atomic, and the plain
     * ints beside it are correct for the production readers because each sits behind the writer's queue drain or its
     * completed future, not because they are single-threaded. A reader added outside those edges must supply its own,
     * or establish that no writer thread ever ran for the session it reads. Package-private so the verdict the
     * completion record stamps stays testable.
     */
    boolean isPartialSave(int chunksFailed, int entityChunksFailed) {
        return failedWriteCount(chunksFailed, entityChunksFailed) > 0;
    }

    private int failedWriteCount(int chunksFailed, int entityChunksFailed) {
        return chunksFailed + entityChunksFailed + chunksCaptureFailed + mapsFailed.get() + mapsRemapFailed
                + idCountsFailed + mapManifestLosses() + blockContainersFailed + entityContainersFailed
                + containerVehiclesLost + villagerTradesLost + interactionCapturesLost + structuralEntitiesLost
                + resumedMountsLost + finishStepsFailed;
    }

    /** The completion inputs frozen at end-of-capture, immutable so they cross to the writer thread safely. */
    private record PendingReport(Path saveRoot, DownloadIdentity identity, ReportEnvironment environment,
            Map<String, String> settings, Instant finishedAt, int chunks, int entities, int containers,
            List<DimensionChunks> dimensions) {}
}
