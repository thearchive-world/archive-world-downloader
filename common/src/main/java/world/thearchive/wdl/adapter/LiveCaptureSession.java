// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMerchant;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityMinecartContainer;
import net.minecraft.entity.passive.AbstractChestHorse;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.ContainerMerchant;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.Packet;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.network.play.client.CPacketClientStatus;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.tileentity.TileEntityLockable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.village.MerchantRecipeList;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

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

public final class LiveCaptureSession implements CaptureController.Session {
    private static final Logger LOGGER = LogManager.getLogger(LiveCaptureSession.class);

    private static final int NO_MENU = -1;

    private static final int KEEP_HOT_MARGIN = 2;

    private static final int EDIT_ZONE_RADIUS = 1;
    private static final int EDIT_ZONE_PERIOD_TICKS = 20;
    private static final int STATS_REFRESH_PERIOD_TICKS = 3600;
    private static final int TICKS_PER_SECOND = 20;

    private static final String WDL_SUBFOLDER = "wdl";

    private final VersionAdapter adapter;
    private final PlatformBridge bridge;
    private final BobbyChunkFilter bobbyFilter;
    private final WdlConfig config;
    // Run when the background save completes so the SAVING to IDLE transition lands even while a replay's paused timer
    // has suspended the game tick.
    private final Runnable saveCompletePoke;
    private final SavedChunkIndex overlayIndex;
    private final CoveredChunkIndex coveredIndex;
    private final SendRangeEstimator sendRange;
    private @Nullable ChunkPos lastCoveredCenter;
    private int lastCoveredRadius;
    // Invalid at construction and after a dimension rebind, so the first guarded tick (and the first after a
    // cross-dimension jump) arms with displacement 0 instead of a spurious fast tick.
    private double lastTickPlayerX;
    private double lastTickPlayerZ;
    private boolean tickBaselineValid;
    private WeakReference<EntityPlayerSP> lastPlayer = new WeakReference<>(null);
    private final boolean overlayActive;
    private final Set<String> overlaySeededDimensions = new HashSet<>();
    private final DownloadTarget target;
    private @Nullable WorldClient level;
    private DimensionType targetDimension;
    private String liveDimensionId;

    private final DimensionRebind<DimensionType> dimensionRebind = new DimensionRebind<>();

    private final Map<ChunkPos, ChunkSnapshotSource> captured = new LinkedHashMap<>();

    private final Map<DimensionType, LongOpenHashSet> capturedByDimension = new LinkedHashMap<>();

    private LongOpenHashSet allCaptured = new LongOpenHashSet();

    private @Nullable LongOpenHashSet dirty = new LongOpenHashSet();

    /**
     * It catches sign text the server sends, which never marks a chunk unsaved.
     */
    private final ArrayDeque<ChunkPos> floorQueue = new ArrayDeque<>();

    private final LongOpenHashSet capturedThisTick = new LongOpenHashSet();

    private long captureTicks;

    private long encodeDeadlineNanos = Long.MAX_VALUE;

    private boolean encodeQuiesced;

    private int @Nullable [] ringOffsetsCache;
    private int ringOffsetsRadius = -1;

    private final ContainerAssociation association = new ContainerAssociation();

    private final MenuChangeTracker stashChangeTracker = new MenuChangeTracker();

    private final Map<BlockPos, StashHolder> containerStash = new LinkedHashMap<>();

    private final Map<BlockPos, StashHolder> lecternStash = new LinkedHashMap<>();

    private @Nullable NBTTagCompound enderChestStash;

    private final Map<UUID, NBTTagCompound> entityContainerStash = new LinkedHashMap<>();
    // Captured villager trades by villager UUID, last-seen-wins, mirroring entityContainerStash so the flush's
    // map-id remap allocates archive ids in a deterministic order. A villager whose offers encode threw is
    // remembered in merchantEncodeFailed so it is not retried every tick the screen is open.
    private final Map<UUID, NBTTagCompound> merchantStash = new LinkedHashMap<>();
    private final Set<UUID> merchantEncodeFailed = new HashSet<>();

    /**
     * The on-disk carry-forward matches a fresh block entity to its prior copy on position and type, which a same-type
     * replacement satisfies, so without this the archived contents of the block that was there are written onto the one
     * that replaced it on the next visit.
     */
    private final Map<DimensionType, LongOpenHashSet> replacedBlockKeysByDimension = new LinkedHashMap<>();
    private LongOpenHashSet replacedBlockKeys = new LongOpenHashSet();

    private final Map<DimensionType, LongOpenHashSet> capturedBlockKeysByDimension = new LinkedHashMap<>();
    private LongOpenHashSet capturedBlockKeys = new LongOpenHashSet();
    private final Map<DimensionType, Long2ObjectMap<String>> capturedBlockTypesByDimension = new LinkedHashMap<>();
    private Long2ObjectMap<String> capturedBlockTypes = new Long2ObjectOpenHashMap<>();
    private final Map<DimensionType, Long2IntOpenHashMap> bookshelfSlotsByDimension = new LinkedHashMap<>();
    private Long2IntOpenHashMap bookshelfSlots = new Long2IntOpenHashMap();
    private final Set<UUID> capturedEntityIds = new HashSet<>();

    private final RecoveredScan recoveredScan = new RecoveredScan();

    private final RecoveredEntityContent recoveredEntityContent = new RecoveredEntityContent();

    private int inventedMerchantOffersDropped;

    // Cleared on a portal so the new dimension re-scans its own chunks (coverage is per dimension).
    private final LongOpenHashSet recoveryScanned = new LongOpenHashSet();

    private final EntityBuffer entityBuffer = new EntityBuffer();

    private final @Nullable EntityPacketCapture packetCapture;

    private final @Nullable InteractionCapture interactionCapture;

    private final @Nullable OpenClickTracker openClickTracker;

    private final ContainerCapture containerCapture;

    enum EntitySource {
        RECONSTRUCTED, PRIMED
    }

    /**
     * The capture source of each entity buffered but not yet submitted, so the write tally is taken at successful
     * submit, not at buffer time where a later flush throw or null envelope would over-report it.
     */
    private final Map<UUID, EntitySource> bufferedEntitySources = new HashMap<>();

    private final Set<UUID> primeRefusedEntities = new LinkedHashSet<>();

    /**
     * Every entity this session has buffered for the entity region, the passengers a vehicle's serialize nests
     * included, so nothing offers a second copy of one of them.
     */
    private final Set<UUID> savedEntities = new HashSet<>();

    private int packetEntitiesWritten;

    private int primedEntitiesWritten;

    private int droppedUncaptured;

    /**
     * Counted apart from {@link #droppedUncaptured} rather than folded into it, because that counter asserts the
     * privacy gate refused the entity.
     */
    private int unboundDimensionDrops;

    private int nestedPassengers;

    private int createDrops;

    private int reconstructSinkSkips;

    private int reconstructEncodeFailures;

    private int reconstructFlushDrops;

    /**
     * Entities an aborted finish-time packet drain left held in a chunk whose terrain was captured: received, never
     * written, and with no later pass that could reach them, so a loss rather than the reload churn the
     * reconciliation's residual would otherwise fold them into.
     */
    private int drainAbortDrops;

    private int primeSinkSkips;

    private int primeEncodeFailures;

    private int primeFlushDrops;

    private final Set<NBTTagCompound> preparedEntityContainers = Collections.newSetFromMap(new IdentityHashMap<>());
    // An offer holder is scrubbed and map-remapped exactly once, tracked by identity, since the map remap is not
    // idempotent.
    private final Set<NBTTagCompound> preparedMerchantHolders = Collections.newSetFromMap(new IdentityHashMap<>());

    private @Nullable UUID boundEntityUuid;

    private @Nullable UUID rootVehicleAttach;

    private @Nullable NBTTagCompound rootVehicleTag;

    /**
     * The root vehicle and its non-player passengers, held from the standalone entity write so the {@code RootVehicle}
     * copy is not duplicated by a region-file copy on the same UUID.
     */
    private final Set<UUID> excludedRootVehicleUuids = new HashSet<>();

    /**
     * A vehicle's items reach the client only through its open menu, so every serialize after that first fold is empty;
     * keeping the holder lets a later flush of the same vehicle, in another entity-chunk or another dimension, be
     * written carrying its contents too, so a reader that reaches any copy finds the loot rather than an empty vehicle.
     */
    private final Map<UUID, NBTTagCompound> foldedContainerVehicles = new HashMap<>();
    private final Map<UUID, NBTTagCompound> foldedMerchants = new LinkedHashMap<>();

    private volatile @Nullable CapturedPlayer capturedPlayer;
    private volatile @Nullable CapturedProgress capturedProgress;

    /**
     * Created in {@link #ensureWriter} on the main thread before the writer thread starts, so the writer-thread
     * finalizer sees the reference.
     */
    private @Nullable MapArchive mapArchive;

    private @Nullable Path mapIdsFile;

    private @Nullable Path levelDatFile;

    private int openContainerId = NO_MENU;

    private @Nullable AsyncSaveWriter writer;

    private @Nullable WorldPaths worldPaths;

    private final SaveProgress progress = new SaveProgress();

    private @Nullable Throwable startError;

    private boolean reported;

    private String saveName;

    private int mergedContainers;

    private int mergedLecterns;

    private int mergedEntityContainers;
    private int mergedVillagerTrades;

    /**
     * Every increment has a matching per-item loss line naming the map, so a reader can reconcile the count against the
     * log. Atomic so no increment is lost whichever thread runs the write task; that the read sees a complete count is
     * the writer's queue drain and completed future, not this type.
     */
    private final AtomicInteger mapsFailed = new AtomicInteger();

    /**
     * The finalize-time idcounts write, counted apart from {@link #mapsFailed} because it loses no captured map.
     * Folding it in would let the map-loss count exceed the per-item lines that explain it.
     */
    private int idCountsFailed;

    /**
     * These voices are instance fields on purpose: each bounds its stacks over one download, so hoisting any of them to
     * a static leaves every download after the first with no stack for a cause the first already spent.
     */
    private final CaptureLossLog mapWriteLoss = new CaptureLossLog(LOGGER,
            "failed to write map data {}", "it renders blank in the reopened world");

    /**
     * Says "holder" because this path is handed a block position for a block container and a UUID for a container
     * vehicle, and a bare UUID here would be indistinguishable from the framed-item voice's own line.
     */
    private final CaptureLossLog mapRemapLoss = new CaptureLossLog(LOGGER,
            "map remap failed for holder {}", "its map renders blank");

    /**
     * Kept apart from the holder voice for its own stack budget, not for its wording: a shared instance would leave
     * whichever path lost second with no stack whenever the two fail the same way.
     */
    private final CaptureLossLog mapEntityRemapLoss = new CaptureLossLog(LOGGER,
            "map remap failed for entity {}", "its map renders blank");

    /**
     * One root cause fails every chunk of a kind alike, and the square retries a failing position every tick it stays
     * loaded, so this voice's stack budget rides on top of the per-position dedup in {@link #recordChunkCaptureLoss}
     * rather than instead of it.
     */
    private final CaptureLossLog chunkCaptureLoss = new CaptureLossLog(LOGGER,
            "failed to capture chunk {}", "the reopened world has none of that chunk's terrain");

    /**
     * Null while capturing, when each map streams on sight and no bar is drawn anyway, and null again once the writer
     * owns the batch, so the batched map tags are released rather than held through the rest of the finalize.
     */
    private @Nullable List<Runnable> finishMapWrites;

    /**
     * Set once the finish batch has been handed to the writer, which is what tells a null {@link #finishMapWrites}
     * after the handover apart from the same null before the drain began. A map imaged past that point has no batch
     * left to join and the writer drops post-finish work, so it is a loss to count.
     */
    private boolean finishBatchClosed;

    /**
     * Its consequence lands in this download, which then re-images every map the folder already holds under fresh ids,
     * and nothing later in the session can undo that, so it counts once and stays counted.
     */
    private int mapManifestReadFailed;

    /**
     * Held apart from {@link #mapsFailed} for the reason {@link #idCountsFailed} is: no captured map is lost here, and
     * folding it in would let the map-loss count exceed the per-item lines that explain it.
     */
    private boolean mapManifestStale;

    private int blockContainersFailed;

    private int entityContainersFailed;

    private int mapsRemapFailed;

    private int containerVehiclesLost;
    private int villagerTradesLost;

    private int interactionCapturesLost;

    private int structuralEntitiesLost;

    private int resumedMountsLost;

    private int playerRecordsLost;

    private int chunksCaptureFailed;

    private int finishStepsFailed;

    private final Map<DimensionType, LongOpenHashSet> captureFailedByDimension = new LinkedHashMap<>();

    private final DownloadReportStore report = new DownloadReportStore();

    private final DownloadCountsBuilder reportCounts = new DownloadCountsBuilder();

    private @Nullable Path reportRoot;
    private @Nullable DownloadIdentity reportIdentity;
    private @Nullable ReportEnvironment reportEnvironment;

    private volatile byte @Nullable [] reportIconBytes;

    private volatile @Nullable PendingReport pendingReport;

    /**
     * The {@code saveCompletePoke} argument re-polls the controller once the save has completed, and does only that:
     * the session owns its own finalization. It must be idempotent against the tick polls that follow, and it must
     * tolerate running synchronously on the caller's stack, since a {@link #finish()} with nothing to write runs it
     * inline from inside {@link CaptureController#stop()}.
     */
    public LiveCaptureSession(VersionAdapter adapter, PlatformBridge bridge, WdlConfig config, WorldClient level,
            DownloadTarget target, SavedChunkIndex overlayIndex, CoveredChunkIndex coveredIndex,
            SendRangeEstimator sendRange, boolean overlayActive, boolean cameraDetachedAtStart,
            BobbyChunkFilter bobbyFilter, Runnable saveCompletePoke) {
        this(adapter, bridge, config, level,
                VanillaDimensions.forType(level.provider.getDimensionType()),
                level.provider.getDimensionType(), target, overlayIndex, coveredIndex, sendRange,
                overlayActive, cameraDetachedAtStart, bobbyFilter, saveCompletePoke);
    }

    LiveCaptureSession(VersionAdapter adapter, PlatformBridge bridge, WdlConfig config,
            @Nullable WorldClient level, DimensionType targetDimension, DimensionType liveDimension,
            DownloadTarget target, SavedChunkIndex overlayIndex,
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
        this.liveDimensionId = liveDimension.getName();
        registerDimensionScopedStores();
        dimensionRebind.bind(targetDimension);
        SendRangeSampler sampler = new SendRangeSampler(System::nanoTime, cameraDetachedAtStart);
        this.packetCapture = config.captureEntities()
                ? new EntityPacketCapture(config.dumpReceivedFrames(), sendRange, sampler, liveDimensionId)
                : null;
        if (this.packetCapture != null) {
            EntityPacketCapture.activate(this.packetCapture);
        }
        this.interactionCapture = config.captureContainers()
                ? new InteractionCapture(adapter.containerSink(),
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
        this.containerCapture = new ContainerCapture(adapter, bridge, openClickTracker);
    }

    private WorldClient level() {
        WorldClient bound = this.level;
        if (bound == null) {
            throw new IllegalStateException("a capture path ran on a session built without a level");
        }
        return bound;
    }

    private LongOpenHashSet capturedFor(DimensionType dimension) {
        return capturedByDimension.computeIfAbsent(dimension, key -> new LongOpenHashSet());
    }

    private LongOpenHashSet replacedBlockKeysFor(DimensionType dimension) {
        return replacedBlockKeysByDimension.computeIfAbsent(dimension, key -> new LongOpenHashSet());
    }

    private LongOpenHashSet capturedBlockKeysFor(DimensionType dimension) {
        return capturedBlockKeysByDimension.computeIfAbsent(dimension, key -> new LongOpenHashSet());
    }

    private Long2IntOpenHashMap bookshelfSlotsFor(DimensionType dimension) {
        return bookshelfSlotsByDimension.computeIfAbsent(dimension, key -> new Long2IntOpenHashMap());
    }

    private Long2ObjectMap<String> capturedBlockTypesFor(DimensionType dimension) {
        return capturedBlockTypesByDimension.computeIfAbsent(dimension, key -> new Long2ObjectOpenHashMap<>());
    }

    /**
     * False for a chunk already written and frozen, where the reconcile gate has no post-interaction block-state to
     * read and no flush ever drains the candidate. Recording one there clears the outline's rim for content that cannot
     * be written, which tells the player the opposite of the truth; refusing it leaves the rim armed, which is what a
     * re-visit needs to see.
     */
    private boolean isInteractionChunkCapturable(ChunkPos chunk) {
        return captured.containsKey(chunk) || !allCaptured.contains(ChunkPos.asLong(chunk.x, chunk.z))
                || config.recaptureChunks().overwritesRevisitedChunks();
    }

    /**
     * At the rebind the drain is mandatory: an old-dimension candidate's position must not carry into the new
     * dimension's shared {@link ChunkPos} space, where a same-type block could take wrong-dimension content.
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
                + "download moved on, so the disc or placed container the player put there is missing from "
                + "the save", dropped.size());
        for (BlockPos pos : dropped) {
            LOGGER.info("predicted interaction at {} was dropped; its content is missing from the save", pos);
        }
    }

    private void onBookshelfSlotCaptured(long posKey, int slot, int occupiedBeforeInsert) {
        int capturedMask = bookshelfSlots.get(posKey) | (1 << slot);
        bookshelfSlots.put(posKey, capturedMask);
        if (OutlineClassifier.classifyBookshelf(occupiedBeforeInsert | (1 << slot), capturedMask,
                0) == OutlineClass.CAPTURED) {
            reportCounts.addContainer("i:" + posKey);
        }
    }

    private void onPlacedContainerCaptured(long posKey, String blockTypeId) {
        capturedBlockKeys.add(posKey);
        capturedBlockTypes.put(posKey, blockTypeId);
    }

    /**
     * A replacement of the same block-entity type leaves both the position key and the merge-time type gate matching,
     * so no downstream guard can tell the stale contents from the new block's. The outline sets go with it, so a rim
     * cleared by the old block's capture does not keep claiming the new one is downloaded.
     */
    private void onBlockPlacedAt(long posKey) {
        BlockPos pos = BlockPos.fromLong(posKey);
        containerStash.remove(pos);
        lecternStash.remove(pos);
        unmarkCaptured(posKey);
        replacedBlockKeys.add(posKey);
    }

    private void unmarkCaptured(long posKey) {
        capturedBlockKeys.remove(posKey);
        capturedBlockTypes.remove(posKey);
        bookshelfSlots.remove(posKey);
    }

    private int totalCapturedChunks() {
        int total = 0;
        for (LongOpenHashSet positions : capturedByDimension.values()) {
            total += positions.size();
        }
        return total;
    }

    private boolean hasEncodeBudget() {
        return System.nanoTime() < encodeDeadlineNanos;
    }

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
     * The camera entity is what the server centers chunk streaming on: it snaps its own player onto the camera each
     * tick and re-centers tracking there, while sending the owning client no position, so the client's own player
     * parks.
     *
     * <p>A removed camera entity is deliberately kept rather than replaced by the player. The client never clears it;
     * falling back to the player would reintroduce the defect for the length of the gap.
     */
    private Entity anchorEntity(Minecraft minecraft, EntityPlayerSP player) {
        Entity camera = minecraft.getRenderViewEntity();
        return camera != null && camera.world == this.level() ? camera : player;
    }

    /**
     * Keyed on {@code isRiding()}, never on whether a {@code RootVehicle} was written, so a vehicle carrying more than
     * one player or a save-refused mount still gets the safe vehicle-block Pos instead of the floored passenger-offset
     * seat coordinate.
     */
    static Entity captureAnchor(Entity player, Entity cameraAnchor) {
        return player.isRiding() ? player.getLowestRidingEntity() : cameraAnchor;
    }

    @Override
    public void captureTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.player;
        WorldClient current = minecraft.world;
        if (player != null && current != null && current != level()) {
            rebindDimension(current);
        }
        if (player != null && lastPlayer.get() != player) {
            lastPlayer = new WeakReference<>(player);
            if (openClickTracker != null) {
                // A respawn builds a fresh EntityPlayerSP even when the dimension is unchanged, and copies the old
                // entity id onto it, so object identity is the only signal that one happened. Without this, dying with
                // a latched click and opening a same-size container at the respawn point inside the window writes it
                // onto the death-site pos.
                openClickTracker.reset();
            }
        }
        @Nullable
        ChunkPos hotCenter = null;
        if (player != null && minecraft.world == level()) {
            hotCenter = new ChunkPos(new BlockPos(anchorEntity(minecraft, player)));
            capturedThisTick.clear();
            // Gate-arm before anything samples this tick: the speed gate must see the teleport
            // displacement before the prime loop can seed against un-pruned far entities.
            EntityPacketCapture capture = this.packetCapture;
            if (capture != null) {
                double dx = player.posX - lastTickPlayerX;
                double dz = player.posZ - lastTickPlayerZ;
                double displacement = tickBaselineValid ? Math.sqrt(dx * dx + dz * dz) : 0.0;
                capture.sampler().gateArmTick(displacement,
                        minecraft.getRenderViewEntity() != player);
            }
            lastTickPlayerX = player.posX;
            lastTickPlayerZ = player.posZ;
            tickBaselineValid = true;
            int capChunks = Math.max(minecraft.gameSettings.renderDistanceChunks, 2);
            recordCoveredDisc(hotCenter, capChunks);
            if (capture != null) {
                SendRangeSampler sampler = capture.sampler();
                int sweepGeneration = sampler.sweepBeginGeneration();
                if (sweepGeneration != 0) {
                    String dimensionId = level().provider.getDimensionType().getName();
                    int boundBlocks = capChunks * 16;
                    boolean aborted = false;
                    for (int id : sampler.sweepIds()) {
                        if (sampler.suppressed()) {
                            aborted = true;
                            break;
                        }
                        if (level().getEntityByID(id) == null) {
                            continue;
                        }
                        int distanceBlocks = sampler.seedSample(id, player.posX, player.posZ);
                        if (distanceBlocks != SendRangeSampler.NO_SAMPLE && distanceBlocks <= boundBlocks) {
                            sendRange.observe(dimensionId, distanceBlocks);
                        }
                    }
                    if (!aborted) {
                        sampler.sweepComplete(sweepGeneration);
                    }
                }
            }
            long budgetNanos = config.encodeBudgetMillis() * 1_000_000L;
            long tickStartNanos = System.nanoTime();
            if (config.captureEntities()) {
                encodeDeadlineNanos = tickStartNanos + budgetNanos / 2;
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
            if (config.captureStatistics() && captureTicks % STATS_REFRESH_PERIOD_TICKS == 0) {
                NetHandlerPlayClient connection = minecraft.getConnection();
                if (connection != null) {
                    connection.sendPacket(new CPacketClientStatus(
                            CPacketClientStatus.State.REQUEST_STATS));
                }
            }
            if (openClickTracker != null) {
                openClickTracker.tick();
                openClickTracker.dismissClickOnMountedVehicle(player.getRidingEntity());
                openClickTracker.claimOpenInventoryRequest(player.getRidingEntity());
            }
            captureTicks++;
        }
        pumpFlush(hotCenter);
    }

    /**
     * The radius is re-read every tick, ahead of the same-chunk early return, so a change triggers one recompute over
     * the whole trail even while the player stands still.
     */
    private void recordCoveredDisc(ChunkPos hotCenter, int capChunks) {
        String dimensionId = level().provider.getDimensionType().getName();
        int radius = sendRange.radiusChunks(dimensionId, capChunks);
        if (radius != lastCoveredRadius) {
            coveredIndex.recompute(dimensionId, radius);
            lastCoveredRadius = radius;
        }
        if (lastCoveredCenter != null && lastCoveredCenter.equals(hotCenter)) {
            return;
        }
        coveredIndex.recordTrail(dimensionId, hotCenter.x, hotCenter.z, capChunks);
        coveredIndex.addDisc(dimensionId, hotCenter.x, hotCenter.z, radius);
        lastCoveredCenter = hotCenter;
    }

    private void registerDimensionScopedStores() {
        dimensionRebind.registerDrain(this::writeOutDimensionBeingLeft);
        dimensionRebind.registerDrain(this::countDroppedInteractionCaptures);
        dimensionRebind.registerSwap(dimension -> this.allCaptured = capturedFor(dimension));
        dimensionRebind.registerSwap(dimension -> this.capturedBlockKeys = capturedBlockKeysFor(dimension));
        dimensionRebind.registerSwap(dimension -> this.replacedBlockKeys = replacedBlockKeysFor(dimension));
        dimensionRebind.registerSwap(dimension -> this.bookshelfSlots = bookshelfSlotsFor(dimension));
        dimensionRebind.registerSwap(dimension -> this.capturedBlockTypes = capturedBlockTypesFor(dimension));
        dimensionRebind.registerClear(capturedThisTick::clear);
        dimensionRebind.registerClear(floorQueue::clear);
        dimensionRebind.registerClear(recoveryScanned::clear);
        dimensionRebind.registerClear(() -> {
            lastCoveredCenter = null;
            lastCoveredRadius = 0;
        });
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
     * write through them and must reach the dimension being left.
     */
    private void rebindDimension(WorldClient newLevel) {
        rebindDimension(VanillaDimensions.forType(newLevel.provider.getDimensionType()),
                newLevel.provider.getDimensionType());
        this.level = newLevel;
    }

    void rebindDimension(DimensionType newTarget, DimensionType liveDimension) {
        dimensionRebind.rebind(newTarget);
        this.targetDimension = newTarget;
        this.liveDimensionId = liveDimension.getName();
    }

    /**
     * Both halves need the writer, and the promote needs it as much as the flush does: with no writer the flush is a
     * no-op, and a promote ahead of one would move the leaving dimension's entities into a buffer the next flush writes
     * under the entered dimension, which is the misfiling this whole path exists to prevent.
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
     * Distance-blind and unbudgeted, because no later pass reaches the dimension being left (each promotes only the
     * bound dimension), so anything deferred here stays held until a return trip and is lost at finish if none comes.
     *
     * <p>Fail-soft: a throw out of the promote must not abort the rest of the rebind and leave the session half-bound.
     */
    private void promoteHeldEntitiesLeavingDimension() {
        try {
            promotePacketEntities(PromotePass.LEAVING_DIMENSION, 0, 0, 0);
        } catch (RuntimeException e) {
            LOGGER.warn("the packet entity drain for the dimension being left failed; saving what was "
                    + "reconstructed", e);
        }
    }

    private @Nullable Chunk liveChunkAt(ChunkProviderClient chunkSource, ChunkPos pos) {
        Chunk chunk = chunkSource.getLoadedChunk(pos.x, pos.z);
        if (chunk == null || bobbyFilter.isBobbyChunk(chunk)) {
            return null;
        }
        return chunk;
    }

    private void captureLoadedChunks(Minecraft minecraft, EntityPlayerSP player, ChunkPos anchor) {
        captureSquareAround(minecraft, player, anchor);
        ChunkPos playerChunk = new ChunkPos(new BlockPos(player));
        if (!playerChunk.equals(anchor)) {
            captureSquareAround(minecraft, player, playerChunk);
        }
    }

    private void captureSquareAround(Minecraft minecraft, EntityPlayerSP player, ChunkPos center) {
        int radius = minecraft.gameSettings.renderDistanceChunks;
        int plausibleMaxBlocks = SendRangeSampler.plausibleMaxBlocks(radius);
        ChunkProviderClient chunkSource = level().getChunkProvider();
        ChunkCodec codec = adapter.chunkCodec();

        // The live client dimension id, which is what the overlay providers query the overlay under, so the overlay
        // index keys by this rather than the vanilla-mapped disk key.
        String overlayDimension = level().provider.getDimensionType().getName();

        RecaptureMode recaptureMode = config.recaptureChunks();
        int[] offsets = ringOffsets(radius);
        for (int i = 0; i < offsets.length; i += 2) {
            ChunkPos pos = new ChunkPos(center.x + offsets[i], center.z + offsets[i + 1]);
            long posKey = ChunkPos.asLong(pos.x, pos.z);
            if (captured.containsKey(pos)) {
                continue;
            }
            boolean revisit = allCaptured.contains(posKey);
            if (revisit && !recaptureMode.overwritesRevisitedChunks()) {
                continue;
            }
            Chunk chunk = liveChunkAt(chunkSource, pos);
            if (chunk == null) {
                continue;
            }
            if (!hasEncodeBudget()) {
                break;
            }
            assert chunk.getWorld() == level : "capture touched a chunk outside the bound WorldClient";
            // The snapshot stands alone in its own try because it is the only statement here whose failure
            // loses the chunk: past the buffer insert the terrain is already committed to flush, so a throw
            // costs this chunk's entity prime or its re-capture arm instead, which is a different loss and a
            // different count.
            ChunkSnapshotSource snapshot;
            try {
                snapshot = codec.capture(chunk);
            } catch (RuntimeException e) {
                recordChunkCaptureLoss(pos, e);
                continue;
            }
            captured.put(pos, snapshot);
            allCaptured.add(posKey);
            try {
                overlayIndex.add(overlayDimension, posKey);
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
     */
    void recordChunkCaptureLoss(ChunkPos pos, Throwable cause) {
        LongOpenHashSet counted = captureFailedByDimension.computeIfAbsent(targetDimension,
                dimension -> new LongOpenHashSet());
        if (counted.add(ChunkPos.asLong(pos.x, pos.z))) {
            chunksCaptureFailed++;
            chunkCaptureLoss.lost(pos, cause);
        }
    }

    /**
     * The captured-chunk privacy gate holds by construction: this runs only for a chunk just added to
     * {@code allCaptured}.
     */
    private void captureLoadedEntities(ChunkPos pos, EntityPlayerSP player, int plausibleMaxBlocks,
            String overlayDimensionId) {
        EntityPacketCapture capture = this.packetCapture;
        if (capture == null) {
            return;
        }
        // An entity resting on the topmost placeable block has its box bottom exactly where a build-height bound stops,
        // and the intersection test is strict, so such a bound misses it. The result is narrowed to the chunk's own
        // column below, and vanilla keys a saved entity by that column alone.
        AxisAlignedBB bounds = new AxisAlignedBB(pos.getXStart(), -2048, pos.getZStart(),
                pos.getXEnd() + 1, 2048, pos.getZEnd() + 1);
        for (Entity entity : level().getEntitiesWithinAABB(Entity.class, bounds, candidate -> true)) {
            capture.primeSeed(entity, player.posX, player.posZ, plausibleMaxBlocks, overlayDimensionId);
            if (entity instanceof EntityPlayer || capture.tracks(entity.getEntityId())
                    || !new ChunkPos(new BlockPos(entity)).equals(pos)) {
                continue;
            }
            UUID uuid = entity.getUniqueID();
            if (shouldSaveEntity(entity)) {
                reportCounts.addEntity(uuid);
                primeRefusedEntities.remove(uuid);
            } else if (!savedEntities.contains(uuid)) {
                primeRefusedEntities.add(uuid);
            }
            if (entityBuffer.chunkOf(uuid) != null) {
                continue;
            }
            NBTTagCompound tag = encodeSingleEntity(entity, pos, EntitySource.PRIMED);
            if (tag != null) {
                entityBuffer.accumulate(uuid, pos, tag);
                bufferedEntitySources.put(uuid, EntitySource.PRIMED);
                recordSaved(uuid, entity);
            }
        }
    }

    private void recordSaved(UUID uuid, Entity entity) {
        savedEntities.add(uuid);
        for (Entity passenger : entity.getRecursivePassengers()) {
            savedEntities.add(passenger.getUniqueID());
        }
    }

    /**
     * It must run before the finish-time packet drain. That drain empties the accumulator, so
     * {@link EntityPacketAccumulator#tracks} then answers false for everything it just promoted, and a second write of
     * one of those at a different chunk position is the one duplicate the region cannot reconcile, each chunk being
     * merged against its own on-disk copy alone.
     *
     * <p>A still-refused entity is skipped before the encode so the sink skip is not tallied twice.
     */
    void retryRefusedPrimes() {
        EntityPacketCapture capture = this.packetCapture;
        if (capture == null) {
            return;
        }
        for (UUID uuid : primeRefusedEntities) {
            // Per entity: a modded entity's save check can throw, and one that does must not cost every refusal behind
            // it in the iteration.
            try {
                Entity entity = entityByUuid(uuid);
                if (entity == null || !shouldSaveEntity(entity) || capture.tracks(entity.getEntityId())
                        || savedEntities.contains(uuid)) {
                    // Asked again here: one prime pass can reach a passenger before the vehicle whose tag nests it.
                    continue;
                }
                ChunkPos pos = new ChunkPos(new BlockPos(entity));
                if (!allCaptured.contains(ChunkPos.asLong(pos.x, pos.z))) {
                    continue;
                }
                if (entityBuffer.chunkOf(uuid) != null) {
                    continue;
                }
                reportCounts.addEntity(uuid);
                NBTTagCompound tag = encodeSingleEntity(entity, pos, EntitySource.PRIMED);
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

    private @Nullable Entity entityByUuid(UUID uuid) {
        for (Entity entity : level().loadedEntityList) {
            if (entity.getUniqueID().equals(uuid)) {
                return entity;
            }
        }
        return null;
    }

    private void attachRecapture(Chunk chunk, ChunkPos pos) {
        capturedThisTick.add(ChunkPos.asLong(pos.x, pos.z));
        rearmUnsaved(chunk);
    }

    private void recaptureHotChunks(ChunkPos anchor) {
        LongOpenHashSet dirtySet = dirty;
        if (dirtySet == null) {
            return;
        }
        ChunkProviderClient chunkSource = level().getChunkProvider();
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

    private void pollDirtyChunks(ChunkProviderClient chunkSource, LongOpenHashSet dirtySet) {
        for (ChunkPos pos : captured.keySet()) {
            Chunk chunk = liveChunkAt(chunkSource, pos);
            if (chunk != null && chunk.needsSaving(false)) {
                dirtySet.add(ChunkPos.asLong(pos.x, pos.z));
            }
        }
    }

    /**
     * Replacing a buffered value never structurally modifies the key set, so iterating it directly is safe.
     */
    private void recaptureEditZone(ChunkPos center, ChunkCodec codec, ChunkProviderClient chunkSource,
            LongOpenHashSet reencodedThisTick) {
        for (ChunkPos pos : captured.keySet()) {
            if (!RecapturePolicy.isInEditZone(pos.x, pos.z, center.x, center.z, EDIT_ZONE_RADIUS)) {
                continue;
            }
            if (!hasEncodeBudget()) {
                return;
            }
            reencode(pos, codec, chunkSource, reencodedThisTick);
        }
    }

    private void drainDirtySlice(LongOpenHashSet dirtySet, int slice, ChunkCodec codec,
            ChunkProviderClient chunkSource, LongOpenHashSet reencodedThisTick) {
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
                return;
            }
            reencode(new ChunkPos((int) key, (int) (key >> 32)), codec, chunkSource, reencodedThisTick);
        }
    }

    private void runFloorSlice(int slice, ChunkCodec codec, ChunkProviderClient chunkSource,
            LongOpenHashSet reencodedThisTick) {
        for (int step = 0; step < slice; step++) {
            if (!hasEncodeBudget()) {
                return;
            }
            if (floorQueue.isEmpty()) {
                floorQueue.addAll(captured.keySet());
                if (floorQueue.isEmpty()) {
                    return;
                }
            }
            reencode(floorQueue.remove(), codec, chunkSource, reencodedThisTick);
        }
    }

    private void reencode(ChunkPos pos, ChunkCodec codec, ChunkProviderClient chunkSource,
            LongOpenHashSet reencodedThisTick) {
        long key = ChunkPos.asLong(pos.x, pos.z);
        if (reencodedThisTick.contains(key) || capturedThisTick.contains(key)) {
            return;
        }
        Chunk chunk = liveChunkAt(chunkSource, pos);
        if (!RecapturePolicy.shouldRecapture(captured.containsKey(pos), chunk != null)) {
            dirtyRemove(key); // a flushed chunk's stale dirty entry is dropped so the set stays bounded
            return;
        }
        if (chunk == null) {
            return;
        }
        assert chunk.getWorld() == level : "re-capture touched a chunk outside the bound WorldClient";
        try {
            captured.put(pos, codec.capture(chunk));
            reencodedThisTick.add(key);
            dirtyRemove(key);
            rearmUnsaved(chunk);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to re-capture chunk {}", pos, e);
        }
    }

    static void rearmUnsaved(Chunk chunk) {
        chunk.setModified(false);
        // The stamp is load-bearing, not bookkeeping. Nothing but needsSaving reads a client chunk's last-save time,
        // and without it that query's entity arm stays permanently true on a world older than 600 ticks, so the poll
        // would silently re-flag every entity-bearing chunk every tick and spend the encode budget on unchanged ones.
        chunk.setLastSaveTime(chunk.getWorld().getTotalWorldTime());
    }

    private void dirtyRemove(long key) {
        LongOpenHashSet dirtySet = dirty;
        if (dirtySet != null) {
            dirtySet.remove(key);
        }
    }

    private void detachRecapture() {
        dirty = null;
        floorQueue.clear();
    }

    private void reencodePendingInteractionChunks(ChunkProviderClient chunkSource, ChunkCodec codec,
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
        // Containers and entities read the running tally, not the stash sums, which double-count a chest pair and never
        // carry an entity figure. Chunks stay the live captured-position total: the report tally only gains chunks at
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
            return true;
        }
        if (writer == null) {
            return false;
        }
        AsyncSaveWriter.SaveResult result = writer.result().getNow(null);
        if (result == null) {
            return false;
        }
        try {
            report(result);
        } finally {
            // Latched even if report throws: a throw on the completion poke is logged by the executor, and an unlatched
            // session would report again on the next tick's poll.
            reported = true;
        }
        return true;
    }

    /**
     * Each recognition axis has its own bind leg and its own confidence test, and an open that no leg claims
     * confidently is DROPPED: mis-binding would write the wrong items onto a block or entity (a corrupt archive) while
     * an empty container is correct.
     */
    private void captureOpenContainer(Minecraft minecraft, EntityPlayerSP player) {
        Container menu = player.openContainer;
        if (menu == null || menu == player.inventoryContainer) {
            association.close();
            stashChangeTracker.reset();
            openContainerId = NO_MENU;
            return;
        }
        if (menu.windowId != openContainerId) {
            openContainerId = menu.windowId;
            stashChangeTracker.reset(); // the new menu must stash at least once, whatever its slots hold
            // Bind to the target the open resolved to, not the live crosshair: the crosshair keeps tracking the view
            // during the click-to-open round trip (no screen is open yet, so the camera is not frozen), so a nudge
            // while the container opens would otherwise bind the wrong block or drop the open.
            ContainerCapture.OpenTarget target = containerCapture.resolveOpenTarget(minecraft, player);
            BlockPos block = target.block();
            Entity entity = target.entity();
            boolean vehicleClaimsOpen = config.captureEntities()
                    && containerCapture.shouldClaimVehicleOpen(player, entity, target.vehicleIntent());
            AbstractChestHorse chestedAnimal = config.captureEntities()
                    ? containerCapture.chestedAnimal(menu)
                    : null;
            // Branch order is load-bearing: the chested-animal branch precedes the entity-vehicle branch.
            // shouldClaimVehicleOpen is menu-type-blind, so a strength-1 llama's horse menu (5 non-player slots) opened
            // by E while riding it soon after an unanswered click on a hopper minecart (size 5) would otherwise be
            // claimed by the vehicle branch and mis-merge the chest into the minecart. Only a mount menu names a
            // chested animal (no vehicle opens one), so peeling it off first cannot starve a vehicle capture.
            if (menu instanceof ContainerMerchant) {
                if (config.captureEntities()) {
                    bindOpenedMerchant(entity);
                } else {
                    // The trade axis rides captureEntities, so with it off bind nothing and clear any prior bind, as
                    // the ender-chest toggle arm does, or the menu's slots stash onto a stale container's position.
                    association.close();
                }
            } else if (menu instanceof ContainerChest && containerCapture.isEnderChestAt(level(), block)) {
                if (config.savePlayerEnderChest()) {
                    bindOpenedEnderChest(menu, player, block);
                } else {
                    // The finish strips EnderItems under this toggle, so binding would count a container the download
                    // never writes. Every bind arm decides the binding through an association leg, which clears it on
                    // refusal; skipping the leg would leave the prior menu's binding live and stash these ender slots
                    // onto that container's position.
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
        // and a normal chest are all a ContainerChest, so only the kind set at bind time tells them apart.
        association.boundPos().ifPresent(posKey -> {
            if (association.boundKind() == ContainerAssociation.BindKind.MERCHANT
                    && menu instanceof ContainerMerchant) {
                // The offers are disjoint from the three trade slots the change gate keys on, so re-stash every tick on
                // its own path: a switch case behind the gate would drop a mid-open offers change and lose a whole
                // villager whose offers packet lands after the open.
                stashMerchantOffers();
                return;
            }
            int page = 0;
            int[] data = MenuChangeTracker.NO_DATA;
            if (!stashChangeTracker.changedSince(menu.inventorySlots, page, data)) {
                return;
            }
            switch (association.boundKind()) {
                case ENDER:
                    stashEnderItems(menu, player);
                    break;
                case ENTITY:
                    stashEntityContainerItems(menu, player);
                    break;
                case CHESTED_ANIMAL:
                    stashChestedAnimalItems(menu, player);
                    break;
                case DOUBLE_CHEST:
                    stashDoubleChestItems(menu, player);
                    break;
                case CONTAINER:
                    stashContainerItems(menu, player, posKey);
                    break;
                default:
                    break;
            }
        });
    }

    private void bindOpenedContainer(Container menu, EntityPlayerSP player, @Nullable BlockPos target) {
        boolean atBlock = false;
        long posKey = 0L;
        int menuSlotCount = 0;
        int blockContainerSize = 0;
        if (target != null) {
            atBlock = true;
            posKey = target.toLong();
            menuSlotCount = ContainerCapture.countBlockSlots(menu, player);
            if (level().getTileEntity(target) instanceof TileEntityLockable) {
                TileEntityLockable blockContainer = (TileEntityLockable) level().getTileEntity(target);
                blockContainerSize = blockContainer.getSizeInventory();
            }
        }
        OptionalLong bound = association.open(atBlock, posKey, menuSlotCount, blockContainerSize);
        if (bound.isPresent()) {
            reportCounts.addContainer("b:" + bound.getAsLong());
            LOGGER.debug("bound open container to {}", BlockPos.fromLong(bound.getAsLong()));
        } else if (target != null) {
            LOGGER.info("dropped open container at {}: menuSlots={}, blockContainerSize={}, chunkLoaded={}",
                    target, menuSlotCount, blockContainerSize,
                    level().getChunkProvider().getLoadedChunk(target.getX() >> 4, target.getZ() >> 4) != null);
        }
    }

    /**
     * The contents still come from the menu, as on every other leg: the client's ender container is never synced, so
     * only its size is trustworthy here.
     */
    private void bindOpenedEnderChest(Container menu, EntityPlayerSP player, @Nullable BlockPos target) {
        boolean atBlock = false;
        long posKey = 0L;
        boolean blockIsEnderChest = false;
        int menuSlotCount = 0;
        if (target != null) {
            atBlock = true;
            posKey = target.toLong();
            blockIsEnderChest = level().getTileEntity(target) instanceof TileEntityEnderChest;
            menuSlotCount = ContainerCapture.countBlockSlots(menu, player);
        }
        OptionalLong bound = association.openEnderChest(atBlock, posKey, menu instanceof ContainerChest,
                blockIsEnderChest, menuSlotCount, player.getInventoryEnderChest().getSizeInventory());
        if (bound.isPresent()) {
            // One shared inventory per session: a single fixed id rides the Set dedup, so re-opening the ender
            // chest never bumps the count past one.
            reportCounts.addContainer("e:");
            LOGGER.debug("bound open ender chest to {}", BlockPos.fromLong(bound.getAsLong()));
        }
    }

    private void bindOpenedDoubleChest(Container menu, EntityPlayerSP player, @Nullable BlockPos target) {
        boolean atBlock = false;
        boolean atRightHalf = false;
        long targetPosKey = 0L;
        long partnerPosKey = 0L;
        int menuSlotCount = 0;
        int combinedContainerSize = 0;
        if (target != null) {
            TileEntityChest targetHalf = level().getTileEntity(target) instanceof TileEntityChest
                    ? (TileEntityChest) level().getTileEntity(target)
                    : null;
            BlockPos partner = targetHalf == null ? null : ContainerCapture.doubleChestPartner(level(), target);
            if (targetHalf != null && partner != null) {
                atBlock = true;
                // There is no ChestType left/right block-state property before 1.13. The lower-coordinate half takes
                // slots 0-26, matching vanilla InventoryLargeChest, so the target is the right (first) half when its
                // probed partner sits at +X or +Z of it.
                atRightHalf = partner.getX() > target.getX() || partner.getZ() > target.getZ();
                targetPosKey = target.toLong();
                partnerPosKey = partner.toLong();
                menuSlotCount = ContainerCapture.countBlockSlots(menu, player);
                combinedContainerSize = targetHalf.getSizeInventory();
                if (level().getTileEntity(partner) instanceof TileEntityChest) {
                    TileEntityChest partnerBlockEntity = (TileEntityChest) level().getTileEntity(partner);
                    combinedContainerSize += partnerBlockEntity.getSizeInventory();
                }
            }
        }
        if (association.openDoubleChest(atBlock, atRightHalf, targetPosKey, partnerPosKey,
                menuSlotCount, combinedContainerSize)) {
            reportCounts.addContainer("d:" + targetPosKey);
            LOGGER.debug("bound double chest at {} + {}", BlockPos.fromLong(targetPosKey),
                    BlockPos.fromLong(partnerPosKey));
        }
    }

    private void bindOpenedEntityContainer(Container menu, EntityPlayerSP player, @Nullable Entity target) {
        boolean atEntity = false;
        boolean entityIsVehicle = false;
        int menuSlotCount = 0;
        int entityContainerSize = 0;
        UUID uuid = null;
        Entity vehicle = target instanceof EntityMinecartContainer ? target : player.getRidingEntity();
        if (vehicle instanceof EntityMinecartContainer) {
            EntityMinecartContainer containerVehicle = (EntityMinecartContainer) vehicle;
            atEntity = true;
            entityIsVehicle = true;
            entityContainerSize = containerVehicle.getSizeInventory();
            uuid = vehicle.getUniqueID();
            menuSlotCount = ContainerCapture.countBlockSlots(menu, player);
        }
        if (association.openEntityContainer(atEntity, entityIsVehicle, menuSlotCount, entityContainerSize)
                && uuid != null) {
            boundEntityUuid = uuid;
            reportCounts.addContainer("v:" + uuid);
            LOGGER.debug("bound open entity container to {}", uuid);
        }
    }

    private void bindOpenedChestedAnimal(Container menu, EntityPlayerSP player,
            AbstractChestHorse animal) {
        int entityChestSize = animal.getInventoryColumns() * 3;
        int menuChestSlotCount = ContainerCapture.countChestSlots(menu, player);
        if (association.openChestedAnimal(true, true, menuChestSlotCount, entityChestSize)) {
            UUID uuid = animal.getUniqueID();
            boundEntityUuid = uuid;
            reportCounts.addContainer("a:" + uuid);
            LOGGER.debug("bound open chested animal to {}", uuid);
        }
    }

    /**
     * The instanceof narrowing sits inside the guard so {@code openMerchant} records the drop for a non-villager target
     * either way. The trade count is added at real capture in {@link #stashMerchantOffers}, not here, since the offers
     * can land after the open.
     */
    private void bindOpenedMerchant(@Nullable Entity target) {
        if (association.openMerchant(target instanceof EntityVillager, true)
                && target instanceof EntityVillager) {
            EntityVillager villager = (EntityVillager) target;
            boundEntityUuid = villager.getUniqueID();
            LOGGER.debug("bound open merchant to {}", villager.getUniqueID());
        }
    }

    @SuppressWarnings("NullAway") // getKey is non-null for a live block entity's registered type
    private void recordBlockType(BlockPos pos, NBTTagCompound holder) {
        TileEntity blockEntity = level().getTileEntity(pos);
        if (blockEntity != null) {
            String typeId = TileEntity.getKey(blockEntity.getClass()).toString();
            holder.setString("wdl_block_entity_id", typeId);
            capturedBlockTypes.put(pos.toLong(), typeId);
        }
    }

    private void stashBlockHolder(Map<BlockPos, StashHolder> stash, BlockPos pos, NBTTagCompound holder) {
        recordBlockType(pos, holder);
        stash.put(pos, StashHolder.of(holder));
        capturedBlockKeys.add(pos.toLong());
    }

    private void stashContainerItems(Container menu, EntityPlayerSP player, long posKey) {
        NBTTagCompound holder = containerCapture.captureBlockSlots(menu, player);
        if (holder != null) {
            stashBlockHolder(containerStash, BlockPos.fromLong(posKey), holder);
        }
    }

    private void stashDoubleChestItems(Container menu, EntityPlayerSP player) {
        OptionalLong first = association.boundPos();
        OptionalLong second = association.boundSecondaryPos();
        if (!first.isPresent() || !second.isPresent()) {
            return;
        }
        int half = ContainerCapture.countBlockSlots(menu, player) / 2;
        NBTTagCompound firstHolder = containerCapture.captureHalfSlots(menu, player, 0, half);
        NBTTagCompound secondHolder = containerCapture.captureHalfSlots(menu, player, half, 2 * half);
        if (firstHolder != null) {
            stashBlockHolder(containerStash, BlockPos.fromLong(first.getAsLong()), firstHolder);
        }
        if (secondHolder != null) {
            stashBlockHolder(containerStash, BlockPos.fromLong(second.getAsLong()), secondHolder);
        }
    }

    private void stashEnderItems(Container menu, EntityPlayerSP player) {
        NBTTagCompound holder = containerCapture.captureBlockSlots(menu, player);
        if (holder != null) {
            enderChestStash = holder;
        }
    }

    private void stashEntityContainerItems(Container menu, EntityPlayerSP player) {
        UUID uuid = boundEntityUuid;
        if (uuid == null) {
            return;
        }
        NBTTagCompound holder = containerCapture.captureBlockSlots(menu, player);
        if (holder != null) {
            entityContainerStash.put(uuid, holder);
            capturedEntityIds.add(uuid);
        }
    }

    private void stashChestedAnimalItems(Container menu, EntityPlayerSP player) {
        UUID uuid = boundEntityUuid;
        if (uuid == null) {
            return;
        }
        NBTTagCompound holder = containerCapture.captureChestSlots(menu, player);
        if (holder != null) {
            entityContainerStash.put(uuid, holder);
            capturedEntityIds.add(uuid);
        }
    }

    /**
     * Re-serialize the open merchant's offers every tick into {@link #merchantStash} keyed by the bound villager UUID,
     * last-seen-wins, skipping an empty offers so a re-open before the offers packet lands never wipes a captured set.
     * The encode is isolated per villager: a sell item that fails to encode would otherwise throw out of the client
     * tick every tick the screen is open. The captured-set add clears the outline rim and runs only on a successful
     * encode, so a failed encode is never falsely reported as captured.
     */
    private void stashMerchantOffers() {
        UUID uuid = boundEntityUuid;
        if (uuid == null || merchantEncodeFailed.contains(uuid)) {
            return;
        }
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (!(screen instanceof GuiMerchant)) {
            return;
        }
        MerchantRecipeList offers = ((GuiMerchant) screen).getMerchant().getRecipes(Minecraft.getMinecraft().player);
        if (offers == null || offers.isEmpty()) {
            return;
        }
        NBTTagCompound holder;
        try {
            holder = MerchantOfferCapture.serialize(offers);
        } catch (RuntimeException e) {
            merchantEncodeFailed.add(uuid);
            LOGGER.warn("skipping villager {} trades: offers encode failed", uuid, e);
            return;
        }
        merchantStash.put(uuid, holder);
        capturedEntityIds.add(uuid);
        reportCounts.addContainer("m:" + uuid);
    }

    private void pumpFlush(@Nullable ChunkPos hotCenter) {
        if (captured.isEmpty()) {
            return;
        }
        AsyncSaveWriter activeWriter = ensureWriter();
        if (activeWriter == null) {
            return;
        }
        if (hotCenter == null) {
            flushBuffer(activeWriter, true, 0, 0, 0);
            return;
        }
        int keepHot = Minecraft.getMinecraft().gameSettings.renderDistanceChunks + KEEP_HOT_MARGIN;
        flushBuffer(activeWriter, false, hotCenter.x, hotCenter.z, keepHot);
    }

    @Override
    public void finish() {
        completeThroughWriter(runnable -> Minecraft.getMinecraft().addScheduledTask(runnable), this::finishCapture);
    }

    @Override
    public void holdWriterEncoding() {
        // Set for as long as the hold lasts, so a writer opened after it (a download whose chunks all still sit in
        // the buffer opens one inside the drain) is born held rather than free-running into the rebuild.
        encodeQuiesced = true;
        AsyncSaveWriter activeWriter = writer;
        if (activeWriter != null) {
            activeWriter.pauseEncoding();
        }
    }

    @Override
    public void releaseWriterEncoding() {
        encodeQuiesced = false;
        AsyncSaveWriter activeWriter = writer;
        if (activeWriter != null) {
            activeWriter.resumeEncoding();
        }
    }

    private void finishCapture() {
        long finishStartNanos = System.nanoTime();
        // The finish drain must encode everything still loaded, so the per-tick encode budget does not apply
        // here (the burst below and the entity refresh run unbounded).
        encodeDeadlineNanos = Long.MAX_VALUE;
        // Save-time re-capture burst: refresh the player's immediate area one last time so a just-placed block or
        // edited sign is current at save, the freshness guarantee the coarse-cadence edit zone leaves to here.
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.player;
        if (config.recaptureChunks().refreshesHotChunks() && player != null && minecraft.world == level()) {
            capturedThisTick.clear();
            LongOpenHashSet reencodedThisTick = new LongOpenHashSet();
            ChunkPos anchor = new ChunkPos(new BlockPos(anchorEntity(minecraft, player)));
            recaptureEditZone(anchor, adapter.chunkCodec(), level().getChunkProvider(), reencodedThisTick);
            // Re-encode any still-loaded pending-candidate chunk outside the edit zone, so the reconcile gate reads a
            // post-ack snapshot rather than a stale-present one and cannot persist content the player just reverted.
            reencodePendingInteractionChunks(level().getChunkProvider(), adapter.chunkCodec(), reencodedThisTick);
        }
        detachRecapture();
        deactivateInteractionCapture();
        deactivateOpenClickTracker();
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
        drainToWriter(activeWriter, minecraft, player, finishStartNanos);
    }

    void countOrphanedContainerVehicles() {
        if (entityContainerStash.isEmpty()) {
            return;
        }
        containerVehiclesLost = entityContainerStash.size();
        LOGGER.warn("{} opened container vehicles' captured contents were not saved: the vehicle was not "
                + "written as a top-level entity we could fold them into (its terrain was never captured, it "
                + "saved nested as a passenger, or its reconstruct or flush failed), so the items the player "
                + "opened are lost", entityContainerStash.size());
        for (UUID vehicle : entityContainerStash.keySet()) {
            LOGGER.info("container vehicle {} saved without the contents the player opened; they are missing "
                    + "from the save", vehicle);
        }
    }

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

    void countMissingPlayerRecord() {
        playerRecordsLost = 1;
        LOGGER.warn("no player record reached the save: the finish found no live player to read, so this download "
                + "contributed no inventory, ender chest, advancements, statistics or game mode, and a folder it "
                + "opened fresh opens at its default spawn rather than where the download was taken; the download "
                + "reports partial, and resuming it while connected captures the player");
    }

    /**
     * Call it only from a read whose field stays non-null for a whole live session, so that a null there is the
     * teardown and never an ordinary state.
     */
    private void noteLivePlayerStateLost(String surface) {
        playerRecordsLost = 1;
        LOGGER.warn("the player record lost its {}: the client tore that state down before the finish could read "
                + "it, so the save carries a default in its place; the download reports partial, and resuming it "
                + "while connected captures the player again", surface);
    }

    private void drainToWriter(AsyncSaveWriter activeWriter, Minecraft minecraft, @Nullable EntityPlayerSP player,
            long finishStartNanos) {
        List<Runnable> mapWrites = new ArrayList<>();
        this.finishMapWrites = mapWrites;
        // Stop the inbound tee before the finish drain so no spawn arrives mid-drain to be left unwritten and
        // uncounted; the drain and the reconciliation then see a settled accumulator.
        deactivatePacketCapture();
        captureLiveClientState(minecraft, player);
        long liveStateNanos = System.nanoTime();
        retryRefusedPrimes();
        // Fail-soft: a throw out of the drain must not abort the rest of the finish.
        boolean drainAborted = false;
        if (config.captureEntities()) {
            try {
                promotePacketEntities(PromotePass.FINISH, 0, 0, 0);
            } catch (RuntimeException e) {
                LOGGER.warn("the finish-time packet entity drain failed; saving what was reconstructed", e);
                drainAborted = true;
            }
        }
        flushBuffer(activeWriter, true, 0, 0, 0);
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
        long drainDoneNanos = System.nanoTime();
        releaseResumedDismountedMount(activeWriter);
        prepareReportCompletion();
        // After every remap site above, so the batch is complete and queues behind the last chunk and entity write.
        activeWriter.submitMapBatch(mapWrites);
        this.finishMapWrites = null;
        this.finishBatchClosed = true;
        // INFO because a field report of a missing player record is undiagnosable without it, and never has debug.log.
        LOGGER.info("finish: the live client state was read {} ms in, the entity drain and flush finished {} ms in",
                (liveStateNanos - finishStartNanos) / 1_000_000L, (drainDoneNanos - finishStartNanos) / 1_000_000L);
    }

    /**
     * Do not move it behind the entity drain: {@link #prepareRootVehicleCapture} fills the set the drain and the flush
     * consult to hold the ridden mount and its passengers out of the standalone write and folds the mount's opened
     * contents into the record; run after the flush it would leave the mount on disk twice on one UUID, and run after
     * the drain alone it would count the mount as a written entity the flush then drops.
     */
    private void captureLiveClientState(Minecraft minecraft, @Nullable EntityPlayerSP player) {
        if (player != null && minecraft.world == level()) {
            // The mount is player-state, so this is not gated on captureEntities.
            prepareRootVehicleCapture(player);
            this.capturedPlayer = failSoft("player", () -> assembleCapturedPlayer(player, minecraft));
            this.capturedProgress = failSoft("progress", () -> assembleCapturedProgress(player, minecraft));
            UUID salvageAttach = rootVehicleAttach;
            NBTTagCompound salvageMount = rootVehicleTag;
            if (this.capturedPlayer == null && salvageMount != null && salvageAttach != null) {
                // The full player assembly threw (the void-world fail-soft), but a seated mount was already excluded
                // from the standalone write and its loot drained from the stash, so a bare void-world save would drop
                // the mount and its capture-once contents from both the region and the player record.
                this.capturedPlayer = failSoft("salvaged mount",
                        () -> assembleSalvageMountPlayer(player, minecraft, salvageAttach, salvageMount));
            }
        } else {
            countMissingPlayerRecord();
        }
        // Deliberately outside the guard above, so an icon can still survive a finish already too late for the player
        // record.
        ServerData iconServer = minecraft.getCurrentServerData();
        String iconB64 = iconServer != null ? iconServer.getBase64EncodedIconData() : null;
        this.reportIconBytes = iconB64 != null ? Base64.getDecoder().decode(iconB64) : null;
    }

    /**
     * A writer exists from the first incremental flush onward, and until it takes the marker it sits blocked on its
     * queue: no level.dat, a daemon thread that never exits, and the future the controller polls never completed, so
     * the download neither finishes nor reports. Two ways in, which is why this wraps the whole finish rather than its
     * tail: a throw anywhere in the work, and the nothing-captured exit, which a download whose captured positions were
     * all dropped as void reaches with a writer already open behind it.
     *
     * <p>It signals the one end of stream, the finalizing one, on every path. Skipping the finalize instead leaves a
     * folder that wrote chunks with no level.dat at all, which vanilla's world list does not show, and leaves the
     * map-id floor unpersisted so a new map in the reopened save can overwrite an archived one. Deciding between the
     * two per download needs a predicate for "did this write anything", and the obvious one, whether any captured
     * position is still retained, is not that: the void skip drops positions that were already written.
     *
     * <p>A throw counts as a degraded finish step, so the download does not report clean although its remaining finish
     * work (the map batch, the frozen report counts, the assembled player) never reached the writer at all. A
     * {@link Throwable} is caught rather than a {@link RuntimeException} for the reason the writer's own drain catches
     * one, that reaching the terminal state matters more than which class of failure got in the way, and the signal is
     * enqueued from a {@code finally} so that a throw from the counting arm itself cannot reopen the hole this closes.
     *
     * <p>The counter is incremented before the marker is enqueued, so the writer-thread finalize reads it across the
     * queue's own happens-before edge.
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
     * The poke runs inline and must follow {@code reported = true}: with no writer, the reported short-circuit in
     * {@link #isSaveComplete()} is the only completion it can observe.
     */
    private void completeWithoutWriter() {
        reported = true;
        deactivatePacketCapture();
        saveCompletePoke.run();
    }

    private void deactivatePacketCapture() {
        EntityPacketCapture capture = this.packetCapture;
        if (capture != null) {
            EntityPacketCapture.deactivate(capture);
        }
    }

    private void deactivateInteractionCapture() {
        InteractionCapture capture = this.interactionCapture;
        if (capture != null) {
            InteractionCapture.deactivate(capture);
        }
    }

    private void deactivateOpenClickTracker() {
        OpenClickTracker tracker = this.openClickTracker;
        if (tracker != null) {
            OpenClickTracker.deactivate(tracker);
        }
    }

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

    void countUnboundDimensionFrames() {
        EntityPacketCapture capture = this.packetCapture;
        if (capture == null) {
            return;
        }
        int unwritable = 0;
        for (String dimensionId : capture.heldDimensions()) {
            if (dimensionId.equals(liveDimensionId)) {
                continue;
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
     * The captured positions of the dimension a held frame names, or null when this download cannot answer for it. A
     * dimension that resolves but was never bound captured nothing, which is an empty set rather than an absent one.
     */
    private @Nullable LongOpenHashSet capturedPositionsOf(String dimensionId) {
        DimensionType target = VanillaDimensions.forId(dimensionId);
        if (target == null) {
            return null;
        }
        LongOpenHashSet positions = capturedByDimension.get(target);
        return positions != null ? positions : new LongOpenHashSet();
    }

    /**
     * A mount captured into the player's {@code RootVehicle} is excluded because it reaches disk inside level.dat and
     * counting its abandoned frame would report a loss the download did not take.
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

    private void prepareRootVehicleCapture(EntityPlayerSP player) {
        if (!player.isRiding()) {
            return;
        }
        // Everything fallible is inside the try, including the vehicle-tree traversals: a modded Entity could override
        // getRidingEntity/getLowestRidingEntity/getRecursivePassengersByType to throw, and any throw here must not
        // propagate into finish().
        try {
            Entity root = player.getLowestRidingEntity();
            Entity direct = player.getRidingEntity();
            if (direct == null || root == player || root.getRecursivePassengersByType(EntityPlayer.class).size() != 1) {
                return; // vanilla's player save uses the same condition
            }
            Set<UUID> excluded = new HashSet<>();
            if (!(root instanceof EntityPlayer)) {
                excluded.add(root.getUniqueID());
            }
            for (Entity entity : root.getRecursivePassengers()) {
                if (!(entity instanceof EntityPlayer)) {
                    excluded.add(entity.getUniqueID());
                }
            }
            NBTTagCompound entityTag = adapter.entitySink().captureRootVehicle(root, config.forceMobPersistence());
            if (entityTag == null) {
                return;
            }
            entityTag = foldRidingVehicleContents(entityTag);
            excludedRootVehicleUuids.addAll(excluded);
            this.rootVehicleAttach = direct.getUniqueID();
            this.rootVehicleTag = entityTag;
        } catch (RuntimeException e) {
            LOGGER.warn("skipping the ridden vehicle capture: the player loads without a mount", e);
        }
    }

    /**
     * The live client mount serializes with empty {@code "Items"} (the menu is a throwaway container, not the entity),
     * so this is required, not redundant. The record holds the whole tree from its root, so the entity whose menu was
     * opened need not be that root, and while the player rides, the finish writes that tree nowhere else. Draining the
     * stash entry keeps the finish-time "contents not saved" warning from counting it.
     */
    NBTTagCompound foldRidingVehicleContents(NBTTagCompound vehicleTag) {
        NBTTagCompound result = vehicleTag.copy();
        // The entity write never sees this tree, dropExcludedRootVehicles having removed it, so the scrub the flush
        // applies to every other merchant has to be repeated here or a merchant riding with the player is the one
        // that reaches the archive still carrying what the client made up for it.
        inventedMerchantOffersDropped += MerchantOfferScrub.stripInventedOffersFromRecord(result);
        for (Map.Entry<UUID, NBTTagCompound> node : EntityTreeWalk.byUuid(result).entrySet()) {
            foldEntityContents(node.getKey(), node.getValue());
        }
        if (!config.saveItemCoordinates()) {
            ItemLocationScrub.scrubEntity(result);
        }
        return result;
    }

    private void foldEntityContents(UUID uuid, NBTTagCompound entityTag) {
        NBTTagCompound holder = entityContainerStash.remove(uuid);
        boolean fromRetention = holder == null;
        if (holder == null) {
            // Use the retained object itself and never a copy of it: the guard below is keyed on identity, and a copy
            // would read as unprepared and re-run the non-idempotent map remap over ids that are already archive ids.
            holder = foldedContainerVehicles.get(uuid);
        }
        if (holder == null) {
            return;
        }
        // Guard by holder identity: while the player rides, a mid-session entity flush already scrubbed+remapped this
        // same holder once, and the map remap is non-idempotent.
        if (preparedEntityContainers.add(holder)) {
            scrubAndRemapItems(holder, uuid);
        }
        try {
            // The sink returns a merged copy and sets only "Items", so a node that lives inside the record takes
            // that one list back rather than replacing itself.
            entityTag.setTag("Items", adapter.containerSink().merge(entityTag, holder).getTagList("Items", 10));
            if (!fromRetention) {
                mergedEntityContainers++; // a retained holder was already counted at the write it came from
            }
        } catch (RuntimeException e) {
            entityContainersFailed++;
            LOGGER.warn("skipping ridden vehicle {} container merge: the mount saves without its contents", uuid, e);
        }
    }

    private CapturedPlayer assembleCapturedPlayer(EntityPlayerSP player, Minecraft minecraft) {
        Entity anchor = captureAnchor(player, anchorEntity(minecraft, player));
        NBTTagCompound raw = adapter.playerSink().capturePlayer(player);
        PlayerTag.applyStripKnobs(raw, config.savePlayerInventory(), config.savePlayerEnderChest());
        PlayerTag.stripDeathLocation(raw);
        PlayerTag.stripRespawnPoint(raw);
        if (!config.saveItemCoordinates()) {
            // The player tag is entity-shaped, so the entity scrub covers the Inventory list and, where the version
            // writes one, the equipment compound (offhand and armor live there, not in Inventory).
            ItemLocationScrub.scrubEntity(raw);
        }
        MapArchive archive = this.mapArchive;
        if (archive != null) {
            archive.remap(raw, "Inventory");
            if (raw.getTag("equipment") instanceof NBTTagCompound) {
                NBTTagCompound equipment = (NBTTagCompound) raw.getTag("equipment");
                for (String slot : equipment.getKeySet()) {
                    if (equipment.getTag(slot) instanceof NBTTagCompound) {
                        NBTTagCompound item = (NBTTagCompound) equipment.getTag(slot);
                        archive.remapItem(item);
                    }
                }
            }
        }
        PlayerTag.setDimension(raw, targetDimension);
        PlayerTag.setPosition(raw, new BlockPos(anchor), anchor.rotationYaw, anchor.rotationPitch);
        if (config.savePlayerEnderChest()) {
            if (enderChestStash != null) {
                if (!config.saveItemCoordinates()) {
                    ItemLocationScrub.scrub(enderChestStash, "Items");
                }
                if (archive != null) {
                    archive.remap(enderChestStash, "Items");
                }
                PlayerTag.setEnderItems(raw, enderChestStash);
            } else if (target.mode() == DownloadMode.RESUME) {
                carryForwardPriorEnderChest(raw);
            }
        }
        if (rootVehicleTag != null && rootVehicleAttach != null) {
            PlayerTag.setRootVehicle(raw, rootVehicleAttach, rootVehicleTag);
        }
        if (target.mode() == DownloadMode.RESUME) {
            restoreSeatedMountContents(raw);
        }
        GameType gameType = GameType.CREATIVE;
        if (!config.worldOutput().overrideWorldDefaults() || !config.worldOutput().openInCreative()) {
            PlayerControllerMP gameMode = minecraft.playerController;
            if (gameMode == null) {
                noteLivePlayerStateLost("game mode");
            }
            gameType = gameMode != null ? gameMode.getCurrentGameType() : GameType.SURVIVAL;
            if (gameType == GameType.SPECTATOR) {
                // Vanilla applies the saved game type to every opener, so a spectator stamp opens the world in
                // spectator.
                gameType = GameType.SURVIVAL;
            }
        }
        EnumDifficulty difficulty = level().getWorldInfo().getDifficulty();
        return new CapturedPlayer(raw, new BlockPos(anchor), anchor.rotationYaw, anchor.rotationPitch,
                targetDimension, gameType, difficulty);
    }

    private CapturedPlayer assembleSalvageMountPlayer(EntityPlayerSP player, Minecraft minecraft, UUID attach,
            NBTTagCompound mountTag) {
        Entity anchor = captureAnchor(player, anchorEntity(minecraft, player));
        NBTTagCompound raw = new NBTTagCompound();
        PlayerTag.setDimension(raw, targetDimension);
        PlayerTag.setPosition(raw, new BlockPos(anchor), anchor.rotationYaw, anchor.rotationPitch);
        PlayerTag.setRootVehicle(raw, attach, mountTag);
        EnumDifficulty difficulty = level().getWorldInfo().getDifficulty();
        return new CapturedPlayer(raw, new BlockPos(anchor), anchor.rotationYaw, anchor.rotationPitch,
                targetDimension, GameType.SURVIVAL, difficulty);
    }

    private CapturedProgress assembleCapturedProgress(EntityPlayerSP player, Minecraft minecraft) {
        byte[] advancements = null;
        if (config.captureAdvancements()) {
            // Per-surface fail-soft: an advancement-encode throw nulls only this blob, so the sibling stats surface
            // still lands.
            advancements = failSoft("advancements", () -> {
                NetHandlerPlayClient connection = minecraft.getConnection();
                if (connection == null) {
                    noteLivePlayerStateLost("advancements");
                    // Null, not an empty blob: the writer skips a null surface and writes an empty one, so
                    // serializing nothing here would overwrite a resume's prior advancements.
                    return null;
                }
                Map<String, AdvancementProgress> byId = AdvancementSnapshot.byId(connection.getAdvancementManager());
                return PlayerProgressSerializer.advancementsJson(byId);
            });
        }
        boolean captureStatistics = config.captureStatistics();
        byte[] stats = captureStatistics
                ? PlayerProgressSerializer.statsJson(player.getStatFileWriter())
                : null;
        if (captureStatistics && stats == null) {
            LOGGER.warn("statistics not captured: no stats reply received before finish");
        }
        return new CapturedProgress(player.getUniqueID(), advancements, stats);
    }

    /**
     * Carry the prior session's ender chest forward on a resume that did not re-open it: without this the fresh player
     * tag's empty {@code "EnderItems"} would wipe a previously-downloaded ender chest. Carries the prior items when the
     * fresh ones are empty, then applies the item-coordinate scrub per the current knob; the carried items are already
     * archive-remapped from the prior session, so they are not re-remapped. Fail-soft: a missing or unreadable prior
     * player leaves the (empty) fresh ender chest rather than aborting the player assembly.
     */
    private void carryForwardPriorEnderChest(NBTTagCompound raw) {
        NBTTagCompound priorPlayer = readPriorPlayerTag();
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
     * saved player would drop the prior download's folded loot. Scrubs the restored mount's own coordinates per the
     * current knob on the {@code Entity} child (not {@code scrub(raw, key)}, a no-op on a compound, and not
     * {@code scrubEntity(raw)}, which does not descend a {@code RootVehicle} child); the prior session already
     * map-remapped it, so it is not re-remapped.
     */
    private void restorePriorMountContents(NBTTagCompound raw) {
        NBTTagCompound priorPlayer = readPriorPlayerTag();
        if (priorPlayer == null) {
            return;
        }
        if (PlayerTag.restorePriorMountContents(priorPlayer, raw) && !config.saveItemCoordinates()
                && raw.getTag("RootVehicle") instanceof NBTTagCompound
                && ((NBTTagCompound) raw.getTag("RootVehicle")).getTag("Entity") instanceof NBTTagCompound) {
            NBTTagCompound entity = (NBTTagCompound) ((NBTTagCompound) raw.getTag("RootVehicle")).getTag("Entity");
            ItemLocationScrub.scrubEntity(entity);
        }
    }

    /**
     * The prior player record goes first because for a mount ridden at the last finish that wrote a player record it is
     * the fresher copy, unless a later download wrote the mount standalone and ended with no player record (a
     * disconnect flush, a failed assembly or a crash); the entity chunks fill only what it leaves empty. Swapping them
     * lets the entity-chunk copy take any node both hold, since each carries only into a node that is still empty.
     */
    void restoreSeatedMountContents(NBTTagCompound raw) {
        restorePriorMountContents(raw);
        restoreRecoveredMountContents(raw);
    }

    /**
     * Scrubbed like the sibling and not re-remapped, the banked copy having come off disk already remapped.
     */
    private void restoreRecoveredMountContents(NBTTagCompound raw) {
        NBTBase rawRootVehicle = raw.getTag("RootVehicle");
        if (!(rawRootVehicle instanceof NBTTagCompound)) {
            return;
        }
        NBTBase rawEntity = ((NBTTagCompound) rawRootVehicle).getTag("Entity");
        if (!(rawEntity instanceof NBTTagCompound)) {
            return;
        }
        NBTTagCompound entity = (NBTTagCompound) rawEntity;
        boolean restored = false;
        for (Map.Entry<UUID, NBTTagCompound> node : EntityTreeWalk.byUuid(entity).entrySet()) {
            NBTTagCompound recovered = recoveredEntityContent.holderFor(node.getKey());
            if (recovered != null) {
                restored |= NbtMerge.carryList(recovered, node.getValue(), "Items");
            }
        }
        if (restored && !config.saveItemCoordinates()) {
            ItemLocationScrub.scrubEntity(entity);
        }
    }

    /**
     * The root is not the mount: a mount can be ridden while itself riding another vehicle, and then the
     * {@code RootVehicle} record holds the outer vehicle with the mount nested under it. Matching the root alone would
     * release a mount the player is still on, putting a second copy of it in the world.
     *
     * <p>Routed to the dimension the PRIOR tag records, not to the live {@link #targetDimension}: the position comes
     * from the prior tag too, and this session can finish anywhere, so pairing prior coordinates with the current
     * dimension would write the mount into a folder it was never in and leave the one it was parked in empty.
     *
     * <p>Every exit that reaches the release proper and does not write counts, since the mount and its archived
     * contents are then preserved nowhere this method can see.
     */
    void releaseResumedDismountedMount(AsyncSaveWriter activeWriter) {
        if (target.mode() != DownloadMode.RESUME) {
            return;
        }
        NBTTagCompound priorPlayer = readPriorPlayerTag();
        NBTTagCompound priorRoot = priorPlayer != null && priorPlayer.getTag("RootVehicle") instanceof NBTTagCompound
                ? (NBTTagCompound) priorPlayer.getTag("RootVehicle")
                : null;
        NBTTagCompound priorEntity = priorRoot != null && priorRoot.getTag("Entity") instanceof NBTTagCompound
                ? (NBTTagCompound) priorRoot.getTag("Entity")
                : null;
        if (priorPlayer == null || priorRoot == null || priorEntity == null) {
            return;
        }
        // A null capturedPlayer (the disconnect flush and the failed assembly) skips the exclusion check and falls
        // through to the release, since this finish writes no player record of its own.
        UUID priorMountUuid = EntityMerge.readUuid(priorEntity);
        if (priorMountUuid != null && (savedEntities.contains(priorMountUuid)
                || (capturedPlayer != null && excludedRootVehicleUuids.contains(priorMountUuid)))) {
            return;
        }
        try {
            DimensionType priorDimension = PlayerTag.dimensionOf(priorPlayer);
            if (priorDimension == null) {
                recordResumedMountLoss("its prior level.dat names no dimension this download writes");
                return;
            }
            ChunkPos pos = mountEntityChunk(priorEntity);
            if (pos == null) {
                recordResumedMountLoss("its prior tag carries no readable position");
                return;
            }
            NBTTagCompound envelope = adapter.entitySink().encodeChunk(ImmutableList.of(priorEntity.copy()), pos);
            if (envelope == null) {
                recordResumedMountLoss("the entity sink refused its tag");
                return;
            }
            inventedMerchantOffersDropped += MerchantOfferScrub.stripInventedOffers(envelope);
            activeWriter.submitEntity(priorDimension, pos, envelope);
        } catch (RuntimeException e) {
            resumedMountsLost++;
            LOGGER.warn("could not release the resumed dismounted mount to a standalone entity; it and the "
                    + "contents the previous download archived in it are absent from the save", e);
        }
    }

    private void recordResumedMountLoss(String reason) {
        resumedMountsLost++;
        LOGGER.warn("could not release the resumed dismounted mount to a standalone entity: {}; it and the "
                + "contents the previous download archived in it are absent from the save", reason);
    }

    private @Nullable ChunkPos mountEntityChunk(NBTTagCompound entity) {
        NBTTagList pos = entity.getTagList("Pos", 6);
        if (pos.tagCount() < 3) {
            return null;
        }
        double x = pos.getDoubleAt(0);
        double y = pos.getDoubleAt(1);
        double z = pos.getDoubleAt(2);
        return new ChunkPos(new BlockPos(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z)));
    }

    private @Nullable NBTTagCompound readPriorPlayerTag() {
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

    private @Nullable NBTTagCompound readPriorData() throws IOException {
        Path file = levelDatFile;
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(file)) {
            NBTTagCompound root = CompressedStreamTools.readCompressed(input);
            return root.getTag("Data") instanceof NBTTagCompound ? (NBTTagCompound) root.getTag("Data") : null;
        }
    }

    /**
     * If the toggle is off this session the resume drops the ender inventory, so the fact must not be marked; if it was
     * off last session the prior save has no {@code EnderItems}, so the non-empty guard leaves the rims red on its own.
     */
    private void markPriorEnderRecovered() {
        if (target.mode() != DownloadMode.RESUME || !config.savePlayerEnderChest()) {
            return;
        }
        NBTTagCompound priorPlayer = readPriorPlayerTag();
        if (priorPlayer != null && priorPlayer.getTag("EnderItems") instanceof NBTTagList
                && !((NBTTagList) priorPlayer.getTag("EnderItems")).isEmpty()) {
            recoveredScan.markEnderRecovered();
        }
    }

    private @Nullable String resolveWorldName() {
        if (target.mode() == DownloadMode.RESUME) {
            return readPriorLevelName();
        }
        return target.worldName();
    }

    private @Nullable String readPriorLevelName() {
        try {
            NBTTagCompound data = readPriorData();
            if (data != null) {
                return data.hasKey("LevelName") ? data.getString("LevelName") : null;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("failed to read the prior level.dat name on resume; using the default name", e);
            if (config.showChatMessages()) {
                bridge.sendChat(ChatCopy.worldNameFallback());
            }
        }
        return null;
    }

    /**
     * Run a finish-time capture assembly and degrade a throw to a null snapshot (fail-soft): a serialize or scrub bug
     * in one step then drops that step to absent instead of aborting the rest of the finish.
     *
     * <p>The degradation is deliberate and stays. What does not is reporting the download that took it as clean, so
     * every degraded step counts toward the partial-finish verdict, and {@code step} names on the line which one it
     * was: several steps share this, and a line naming none of them leaves a reader unable to tell a lost player from
     * lost advancements.
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

    private @Nullable NBTBase resolveMapImage(int sessionId) {
        MapData saved = (MapData) level().getMapStorage().getOrLoadData(MapData.class, "map_" + sessionId);
        if (saved == null) {
            return null;
        }
        NBTBase mapTag = adapter.mapSink().serializeMap(saved);
        if (config.lockDownloadedMaps() && mapTag instanceof NBTTagCompound) {
            ((NBTTagCompound) mapTag).setBoolean("locked", true);
        }
        return mapTag;
    }

    private static boolean shouldSaveEntity(Entity entity) {
        return !entity.isDead && !entity.isRiding()
                && (!entity.isBeingRidden() || entity.getRecursivePassengersByType(EntityPlayer.class).size() != 1);
    }

    /**
     * The floor is raised outside that catch on purpose: the counter high-water lives in the manifest, so the empty
     * manifest that replaced it would restart at 0 and write this download's first map over the prior download's
     * {@code data/map_0.dat}.
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

    private MapArchive createMapArchive(Path mapIdsFile, Path dataDirectory) {
        if (config.remapMapIds()) {
            return new MapArchive(loadManifest(mapIdsFile, dataDirectory), this::resolveMapImage,
                    this::streamMapData);
        }
        return new MapArchive(MapManifest.empty(), this::resolveMapImage, this::streamMapData, false,
                onDiskMapIdFloor(dataDirectory));
    }

    private static int onDiskMapIdFloor(Path dataDirectory) {
        return Math.max(dataFileFloor(dataDirectory), idCountsFloor(dataDirectory));
    }

    /**
     * Its own -1 fallback keeps a fault in this source from discarding the idcounts floor read independently below.
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
     * Its own -1 fallback keeps a fault in this source from discarding the data-file floor read independently above. It
     * can exceed the data-file source, which is why both are read: an imageless id writes no data file, so a prior
     * download whose highest id was imageless is recorded here and in no data file. It only reaches disk at finalize,
     * so a prior download that crashed leaves this source empty and the data-file scan carrying the floor alone.
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
     * It absorbs its own {@link IOException}, which a {@link Runnable} could not throw anyway, and its own
     * {@link RuntimeException}, which it must absorb because the shared task catches would log a runtime throw without
     * counting it, and an uncounted loss lets the finish report a download that lost maps as clean.
     */
    static Runnable mapWriteTask(Path dataDirectory, String key, NBTBase dataTag, AtomicInteger failures,
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

    void streamMapData(int archiveId, NBTBase dataTag) {
        AsyncSaveWriter activeWriter = this.writer;
        WorldPaths paths = this.worldPaths;
        if (activeWriter == null || paths == null) {
            mapsFailed.incrementAndGet();
            LOGGER.info("map data {} had no writer to stream to; it is missing from the save",
                    ("map_" + archiveId));
            return;
        }
        Path dataDirectory = paths.dataDirectory();
        String key = ("map_" + archiveId);
        if (finishBatchClosed) {
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

    void writeIdCounts(WorldPaths paths) {
        MapArchive archive = this.mapArchive;
        if (archive == null) {
            return;
        }
        NBTBase idCounts = archive.idCountsTag();
        if (idCounts == null) {
            return;
        }
        try {
            MapDataWriter.writeIdCounts(paths.dataDirectory(), idCounts);
        } catch (IOException | RuntimeException e) {
            idCountsFailed++;
            LOGGER.warn("failed to write idcounts; the reopened world may reissue captured map ids", e);
        }
    }

    /**
     * The record is the end state rather than a tally of attempts, which is what the success arm clearing it is for:
     * the world-open scheme signal and the finalize rewrite are the same file, so a fault at the first that the second
     * repairs cost the download nothing and must not report it partial.
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

    private int mapManifestLosses() {
        return mapManifestReadFailed + (mapManifestStale ? 1 : 0);
    }

    /**
     * The writer arrives as a factory rather than an instance so the paths and the archive are published before the
     * writer thread exists, the same edge {@code ensureWriter} gets by constructing the writer last; handing over a
     * running writer instead would race the finalizer's read of them.
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

    private @Nullable AsyncSaveWriter ensureWriter() {
        if (writer != null) {
            return writer;
        }
        if (startError != null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        AsyncSaveWriter opened = openWorld(minecraft.getSaveLoader(), minecraft.gameDir.toPath().resolve("saves"),
                saveRoot -> beginReport(minecraft, saveRoot));
        if (opened != null && encodeQuiesced) {
            opened.pauseEncoding();
        }
        return opened;
    }

    @Nullable
    AsyncSaveWriter openWorld(ISaveFormat source, Path savesDirectory, Consumer<Path> reportStep) {
        ISaveHandler storage;
        try {
            // Path containment: assert the resolved level root stays under the saves base before getSaveLoader, which
            // creates the folder plus its advisory session.lock, so the check must run first or an escape touches disk.
            // toRealPath canonicalizes the base so a symlinked saves directory cannot defeat the lexical check.
            Path savesBase = savesDirectory.toRealPath();
            Path resolved = savesBase.resolve(saveName).normalize();
            // A download folder is a single component directly under saves; requiring the parent to be exactly the
            // saves base rejects both a parent-escape and any multi-component name (whose first segment could be a
            // symlink the lexical normalize cannot see).
            if (!resolved.startsWith(savesBase) || !savesBase.equals(resolved.getParent())) {
                throw new IOException("the download folder escapes the saves directory: " + saveName);
            }
            storage = source.getSaveLoader(saveName, false);
        } catch (IOException | RuntimeException e) {
            startError = e;
            return null;
        }
        try {
            Path saveRoot = storage.getWorldDirectory().toPath();
            WorldPaths paths = adapter.worldPaths(saveRoot);
            this.worldPaths = paths;
            this.mapIdsFile = MapManifest.pathIn(saveRoot);
            this.levelDatFile = saveRoot.resolve("level.dat");

            this.mapArchive = createMapArchive(MapManifest.pathIn(saveRoot), paths.dataDirectory());
            // Plant the scheme signal at open for a NEW download only, before any map data streams: schemeMismatch
            // reads manifest presence as the remap-on marker, and NEW takes no backup, so the plant cannot corrupt one.
            // A resume's map surfaces must not change before the preflight backup snapshots them: a matched resume that
            // holds map data already carries its signal, and an off-mode resume's stale manifest is deleted at finalize
            // (saveMapManifest below), not here.
            if (target.mode() == DownloadMode.NEW) {
                saveMapManifest();
            }
            // A throw here would otherwise record a start error, which short-circuits every later write and
            // costs every captured chunk, for metadata the save itself does not need.
            try {
                reportStep.accept(saveRoot);
            } catch (RuntimeException e) {
                LOGGER.warn("the download report could not be stamped at world-open; the save continues", e);
            }
            LevelDataWriter levelDataWriter = adapter.levelDataWriter();
            LevelDataWriter.LevelData levelData = levelDataWriter.buildLevelData(config.worldOutput(),
                    resolveWorldName());
            surfaceGameRuleOverrideLoss(levelData.gameRules());
            writer = new AsyncSaveWriter(
                    paths::openRegionStorage,
                    // The in-progress download.md regenerates strictly after the backup, so the zip archives the prior
                    // session's rendering untouched.
                    () -> {
                        FinalizeOutputs.backupBeforeResume(saveRoot, target.mode(), config.zipOnResume());
                        report.refreshHumanRendering(saveRoot);
                    },
                    // Read the volatile capturedPlayer/capturedProgress LAZILY inside the thunks: ensureWriter builds
                    // these thunks at the first incremental flush, mid-capture, before finish() sets the fields, so a
                    // snapshot taken here would always be null. The thunks run on the writer thread strictly after the
                    // chunk drain, so the fields set in finish() are visible.
                    () -> levelDataWriter.save(storage, levelData, capturedPlayer),
                    (chunksFailed, entityChunksFailed) -> {
                        PlayerProgressWriter.write(saveRoot, capturedProgress);
                        writeIdCounts(paths);
                        saveMapManifest(); // after the data/ files, so a torn write never precedes them
                        writeReportCompletion(chunksFailed, entityChunksFailed);
                    },
                    () -> FinalizeOutputs.exportZip(saveRoot, config.zipOnFinish(), progress),
                    progress);
            writer.observeResumeReads(recoveredScan::record);
            writer.observeEntityResumeReads((dimension, onDisk) -> {
                recoveredScan.recordEntities(dimension, onDisk);
                recoveredEntityContent.record(onDisk);
            });
            writer.carryRecoveredEntityContent(recoveredEntityContent);
            markPriorEnderRecovered();
            return writer;
        } catch (RuntimeException e) {
            startError = e;
            return null;
        }
    }

    private void surfaceGameRuleOverrideLoss(GameRuleResolution gameRules) {
        if (!gameRules.unknownIds().isEmpty() && config.showChatMessages()) {
            bridge.sendChat(ChatCopy.gameRuleOverridesSkipped(String.join(", ", gameRules.unknownIds())));
        }
    }

    private void maybeSeedOverlayCoverage(AsyncSaveWriter activeWriter) {
        WorldPaths paths = worldPaths;
        if (!overlayActive || target.mode() != DownloadMode.RESUME || paths == null) {
            return;
        }
        String dimensionId = level().provider.getDimensionType().getName();
        if (!overlaySeededDimensions.add(dimensionId)) {
            return;
        }
        DimensionType diskDimension = targetDimension;
        activeWriter.submit(() -> {
            long[] priors = RegionChunkScan.presentChunks(paths.regionDirectory(diskDimension));
            overlayIndex.addAll(dimensionId, priors);
            coveredIndex.addAll(dimensionId, priors);
        });
    }

    void flushBuffer(AsyncSaveWriter activeWriter, boolean all, int centerX, int centerZ, int keepHot) {
        maybeSeedOverlayCoverage(activeWriter);
        ContainerSink containerSink = adapter.containerSink();
        LecternSink lecternSink = adapter.lecternSink();
        ChunkCodec codec = adapter.chunkCodec();
        // Entity presence is taken from both the buffered entities (promoted, awaiting flush) and the still-accumulated
        // ones (held in the packet capture, e.g. budget-deferred this tick), because dropping a chunk's allCaptured
        // position is lossless only when no entity belongs to it: allCaptured is the entity privacy gate, so a void
        // position may be removed but an entity-bearing one must not.
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
            // On a resume, read this chunk's on-disk prior while it is still in view, once per chunk, rather than
            // waiting for the flush (by when it has left the outline clamp). The block read feeds only the outline
            // and is skipped when that is off; the entity read is not, because it also banks the contents an entity
            // that changed chunk can no longer serialize, and losing those to a render toggle would make a
            // cosmetic setting decide whether a villager keeps its trades.
            boolean scanPriorBlocks = config.captureContainers() && config.outline().renderUnsavedOutline();
            boolean scanPriorEntities = config.captureEntities();
            if (resumeDownload && (scanPriorBlocks || scanPriorEntities)
                    && recoveryScanned.add(ChunkPos.asLong(pos.x, pos.z))) {
                if (scanPriorBlocks) {
                    activeWriter.submitResumeScan(targetDimension, pos);
                }
                if (scanPriorEntities) {
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
                allCaptured.remove(ChunkPos.asLong(pos.x, pos.z));
                entries.remove();
                continue;
            }
            // Drain this chunk's open-time holders out of the shared stashes into a per-submit bundle the writer thunk
            // then solely owns. The main thread prepares them and forgets them, so only detached data crosses to the
            // writer.
            Map<BlockPos, NBTTagCompound> containers = drainChunkHolders(containerStash, pos);
            Map<BlockPos, NBTTagCompound> lecterns = drainChunkHolders(lecternStash, pos);
            // Scrub and on-sight map remap each drained holder exactly once, here at drain time and never as a
            // whole-stash pass: the stash holds every not-yet-flushed container, which a dense storage room keeps hot
            // for a whole session, so a per-tick pass over it scales with everything opened, while the drain-time
            // prepare costs only the holders actually flushing and still precedes everything writer-bound.
            prepareDrainedItems(containers);
            final Map<BlockPos, NBTTagCompound> holders;
            if (interactionCapture != null) {
                // Placed-shulker durability: drainChunk reconciles each candidate against this snapshot, the very
                // one the writer thunk below encodes and merges against, so a jukebox-then-shulker replacement at
                // one pos is dropped by the confirm predicate before it can merge. Keep the reconcile on the same
                // snapshot as the position merge; a live read or a different snapshot here reopens that hole.
                InteractionCapture.ChunkBundles confirmed = interactionCapture.drainChunk(pos, snapshot);
                // Drop the open-time-wins losers before the remap, since prepareDrainedItems allocates a map id
                // and writes map_<id>.dat per holder: remapping a same-pos loser would orphan that file.
                Map<BlockPos, NBTTagCompound> placedItems = ContainerMerge.mergePlaceCandidates(containers,
                        confirmed.items());
                prepareDrainedItems(placedItems);
                containers.putAll(placedItems);
                tallyInteractionMerges(placedItems.keySet(), confirmed.holders().keySet());
                holders = confirmed.holders();
            } else {
                holders = ImmutableMap.of();
            }
            List<BlockPos> landingContainers = ChunkFlushPlan.landingHolderPositions(snapshot, containers);
            mergedContainers += landingContainers.size();
            mergedLecterns += ChunkFlushPlan.landingHolderPositions(snapshot, lecterns).size();
            mergedContainers += ChunkFlushPlan.landingHolderPositions(snapshot, holders).size();
            // The target dimension is read here on main (submit time), not in the thunk, so a rebind cannot misroute.
            boolean synthesizeBlending = VanillaDimensions.shouldSynthesizeBlending(config.worldOutput().worldType(),
                    targetDimension);
            // Blank any item-borne coordinate riding a block entity's own NBT, the one item-borne surface the item-list
            // scrub above never sees.
            if (!config.saveItemCoordinates()) {
                for (NBTTagCompound blockEntity : snapshot.blockEntities()) {
                    ItemLocationScrub.scrubBlockEntity(blockEntity);
                }
            }
            // Consumed by this write, not held: the copy this write leaves on disk is post-placement, so the next
            // visit's carry-forward is reading its own capture rather than the replaced block's. Holding it would
            // suppress forever, which erases what this download archived on every later pass.
            LongSet replacedHere = ChunkFlushPlan.replacedIn(pos, replacedBlockKeys);
            replacedBlockKeys.removeAll(replacedHere);
            activeWriter.submitChunk(targetDimension, pos,
                    () -> {
                        NBTTagCompound tag = codec.encode(snapshot, synthesizeBlending);
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
     * the chunk would leak its undrained stash entry).
     */
    private boolean isVoidChunk(ChunkPos pos, ChunkSnapshotSource snapshot, Set<ChunkPos> bufferedEntityChunks,
            LongSet accumulatedEntityChunks, Set<ChunkPos> pendingInteractionChunks) {
        boolean hasEntities = bufferedEntityChunks.contains(pos)
                || accumulatedEntityChunks.contains(ChunkPos.asLong(pos.x, pos.z));
        boolean hasContainers = anyHolderInChunk(containerStash, pos) || anyHolderInChunk(lecternStash, pos)
                || pendingInteractionChunks.contains(pos);
        return VoidChunkPolicy.isVoidChunk(
                hasNonAirBlocks(snapshot), !snapshot.blockEntities().isEmpty(), hasEntities, hasContainers);
    }

    private static boolean hasNonAirBlocks(ChunkSnapshotSource snapshot) {
        for (ChunkSnapshotSource.SectionData section : snapshot.sections()) {
            ExtendedBlockStorage chunkSection = section.chunkSection();
            if (chunkSection != null && !chunkSection.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyHolderInChunk(Map<BlockPos, ?> stash, ChunkPos pos) {
        for (BlockPos holderPos : stash.keySet()) {
            if (new ChunkPos(holderPos).equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An unpack failure drops that one holder rather than aborting the per-tick flush, and un-marks the position as
     * captured, so the outline re-rims it and a re-open can recover the contents; a retry would deterministically
     * re-fail against the same packed bytes.
     */
    private Map<BlockPos, NBTTagCompound> drainChunkHolders(Map<BlockPos, StashHolder> stash, ChunkPos pos) {
        Map<BlockPos, NBTTagCompound> bundle = new LinkedHashMap<>();
        Iterator<Map.Entry<BlockPos, StashHolder>> holders = stash.entrySet().iterator();
        while (holders.hasNext()) {
            Map.Entry<BlockPos, StashHolder> holder = holders.next();
            if (new ChunkPos(holder.getKey()).equals(pos)) {
                try {
                    bundle.put(holder.getKey(), holder.getValue().unpack());
                } catch (IOException e) {
                    long posKey = holder.getKey().toLong();
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

    private void sweepOrphanedHolders(AsyncSaveWriter activeWriter) {
        if (containerStash.isEmpty() && lecternStash.isEmpty()) {
            return;
        }
        ContainerSink containerSink = adapter.containerSink();
        LecternSink lecternSink = adapter.lecternSink();
        DimensionType dimension = targetDimension;
        for (ChunkPos pos : ChunkFlushPlan.residualHolderChunks(containerStash.keySet(),
                lecternStash.keySet())) {
            Map<BlockPos, NBTTagCompound> containers = drainChunkHolders(containerStash, pos);
            Map<BlockPos, NBTTagCompound> lecterns = drainChunkHolders(lecternStash, pos);
            prepareDrainedItems(containers);
            activeWriter.submitChunkRewrite(dimension, pos, onDisk -> {
                MergeTally tally = ChunkFlushPlan.foldResidualHolders(onDisk, pos, containerSink,
                        lecternSink, containers, lecterns);
                blockContainersFailed += tally.failed();
                return tally.merged();
            });
        }
    }

    private void compactClosedStashHolders() {
        if (!compactOneStashHolder(containerStash)) {
            compactOneStashHolder(lecternStash);
        }
    }

    private boolean compactOneStashHolder(Map<BlockPos, StashHolder> stash) {
        for (Map.Entry<BlockPos, StashHolder> entry : stash.entrySet()) {
            StashHolder holder = entry.getValue();
            if (!holder.shouldCompact() || isBoundOpen(entry.getKey().toLong())) {
                continue;
            }
            try {
                holder.compact();
                return true;
            } catch (IOException e) {
                LOGGER.warn("failed to pack stashed container {}; it stays live in memory", entry.getKey(), e);
            }
        }
        return false;
    }

    private boolean isBoundOpen(long posKey) {
        OptionalLong bound = association.boundPos();
        if (bound.isPresent() && bound.getAsLong() == posKey) {
            return true;
        }
        OptionalLong secondary = association.boundSecondaryPos();
        return secondary.isPresent() && secondary.getAsLong() == posKey;
    }

    private void flushEntityBuffer(AsyncSaveWriter activeWriter, boolean all, int centerX, int centerZ,
            int keepHot) {
        if (entityBuffer.isEmpty()) {
            return;
        }
        prepareEntityContainers();
        prepareMerchantHolders();
        for (ChunkPos pos : entityBuffer.bufferedChunks()) {
            if (all || FlushPolicy.shouldFlush(pos.x, pos.z, centerX, centerZ, keepHot)) {
                flushEntityChunk(activeWriter, pos);
            }
        }
    }

    void flushEntityChunk(AsyncSaveWriter activeWriter, ChunkPos pos) {
        List<NBTTagCompound> tags = entityBuffer.drainChunk(pos);
        dropExcludedRootVehicles(tags);
        if (tags.isEmpty()) {
            return;
        }
        try {
            NBTTagCompound envelope = adapter.entitySink().encodeChunk(tags, pos);
            if (envelope == null) {
                LOGGER.warn("skipping {} entities for chunk {}: the entity sink returned no envelope",
                        tags.size(), pos);
                countEntityFlushDrop(tags);
                return;
            }
            // Before any fold: an invented list fills the slot both carry-forwards need empty.
            inventedMerchantOffersDropped += MerchantOfferScrub.stripInventedOffers(envelope);
            entityContainersFailed += EntityContainerMerge.refoldFlushedContainers(adapter.containerSink(),
                    envelope, foldedContainerVehicles, entityContainerStash).failed();
            recordStandaloneFoldedContainers(tags);
            MergeTally entityTally = EntityContainerMerge.mergeEntityStash(adapter.containerSink(),
                    envelope, entityContainerStash);
            mergedEntityContainers += entityTally.merged();
            entityContainersFailed += entityTally.failed();
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
            countEntitiesSubmitted(tags);
        } catch (RuntimeException e) {
            LOGGER.warn("skipping {} entities for chunk {}: encode/merge failed", tags.size(), pos, e);
            countEntityFlushDrop(tags);
        }
    }

    /**
     * Retain the holder of every container vehicle in this drained chunk whose captured contents are about to be folded
     * into the standalone entity write, keyed by UUID, before {@code mergeEntityStash} drains the stash.
     */
    private void recordStandaloneFoldedContainers(List<NBTTagCompound> tags) {
        for (NBTTagCompound tag : tags) {
            UUID uuid = EntityMerge.readUuid(tag);
            if (uuid == null) {
                continue;
            }
            NBTTagCompound holder = entityContainerStash.get(uuid);
            if (holder != null) {
                // Retain a copy rather than the holder itself: the sink aliases the list it is handed into the
                // envelope crossing to the writer, and this retention outlives that envelope. Mark the copy
                // prepared in the same breath, because it is a copy of an already-scrubbed, already-remapped
                // holder and the map remap is not idempotent; a later fold must not re-run it.
                NBTTagCompound retained = holder.copy();
                preparedEntityContainers.add(retained);
                foldedContainerVehicles.put(uuid, retained);
            }
        }
    }

    /**
     * The copy is marked prepared, since it is a copy of an already-scrubbed, already-remapped holder the
     * non-idempotent remap must not re-run.
     */
    private void recordStandaloneFoldedMerchants(List<NBTTagCompound> tags) {
        for (NBTTagCompound tag : tags) {
            UUID uuid = EntityMerge.readUuid(tag);
            if (uuid == null) {
                continue;
            }
            NBTTagCompound holder = merchantStash.get(uuid);
            if (holder != null) {
                NBTTagCompound retained = holder.copy();
                preparedMerchantHolders.add(retained);
                foldedMerchants.put(uuid, retained);
            }
        }
    }

    private void dropExcludedRootVehicles(List<NBTTagCompound> tags) {
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

    private void countEntitiesSubmitted(List<NBTTagCompound> tags) {
        for (NBTTagCompound tag : tags) {
            UUID uuid = EntityMerge.readUuid(tag);
            if (uuid == null) {
                continue;
            }
            if (bufferedEntitySources.remove(uuid) == EntitySource.PRIMED) {
                primedEntitiesWritten++;
            } else {
                packetEntitiesWritten++;
            }
        }
    }

    private void countEntityFlushDrop(List<NBTTagCompound> tags) {
        for (NBTTagCompound tag : tags) {
            UUID uuid = EntityMerge.readUuid(tag);
            if (uuid != null && bufferedEntitySources.remove(uuid) == EntitySource.PRIMED) {
                primeFlushDrops++;
            } else {
                reconstructFlushDrops++;
            }
        }
    }

    private void prepareEntityContainers() {
        for (Map.Entry<UUID, NBTTagCompound> entry : entityContainerStash.entrySet()) {
            if (preparedEntityContainers.add(entry.getValue())) {
                scrubAndRemapItems(entry.getValue(), entry.getKey());
            }
        }
    }

    private void prepareMerchantHolders() {
        MapArchive archive = this.mapArchive;
        for (Map.Entry<UUID, NBTTagCompound> entry : merchantStash.entrySet()) {
            if (!preparedMerchantHolders.add(entry.getValue())) {
                continue;
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
     * Returns null two ways and tallies which of the two it was against {@code source}, because only one of them is a
     * loss. The throw is caught for per-entity failure isolation: an entity can carry state the vanilla save rejects
     * and we cannot always pre-sanitize it, so one entity is skipped rather than crashing the download.
     */
    @Nullable
    NBTTagCompound encodeSingleEntity(Entity entity, ChunkPos pos, EntitySource source) {
        NBTTagCompound envelope;
        try {
            envelope = adapter.entitySink().encodeChunk(ImmutableList.of(entity), pos,
                    config.forceMobPersistence());
        } catch (RuntimeException e) {
            recordEntityEncodeFailure(source);
            LOGGER.warn("skipping entity {}: capture failed", entity.getUniqueID(), e);
            return null;
        }
        if (envelope == null) {
            recordEntitySinkSkip(source);
            return null;
        }
        NBTTagList entities = envelope.getTag("Entities") instanceof NBTTagList
                ? (NBTTagList) envelope.getTag("Entities")
                : null;
        if (entities != null && !entities.isEmpty() && entities.get(0) instanceof NBTTagCompound) {
            return (NBTTagCompound) entities.get(0);
        }
        // A non-null envelope means the sink did save this entity, so failing to lift it back out loses one it was
        // willing to write.
        recordEntityEncodeFailure(source);
        LOGGER.warn("skipping entity {}: the entity sink returned an envelope without it", entity.getUniqueID());
        return null;
    }

    private void recordEntityEncodeFailure(EntitySource source) {
        if (source == EntitySource.PRIMED) {
            primeEncodeFailures++;
        } else {
            reconstructEncodeFailures++;
        }
    }

    private void recordEntitySinkSkip(EntitySource source) {
        if (source == EntitySource.PRIMED) {
            primeSinkSkips++;
        } else {
            reconstructSinkSkips++;
        }
    }

    /**
     * Only the bound dimension's entities are touched, at every step: the chunk keys, the privacy-gate test against
     * them, and the drain. A chunk key means nothing outside the dimension it was announced in, so comparing one from
     * another dimension against this dimension's captured positions decides the fate of an entity by a coincidence of
     * numbers, in the direction of writing it into the wrong world's folder. The budget is checked per chunk on the
     * per-tick pass, so a drained chunk is always encoded whole and a spill defers the remaining chunks safely (an
     * entity cannot unload out from under the deferral).
     */
    void promotePacketEntities(PromotePass pass, int centerX, int centerZ, int keepHot) {
        EntityPacketCapture capture = this.packetCapture;
        if (capture == null) {
            return;
        }
        LongIterator chunkKeys = capture.chunks(liveDimensionId).iterator();
        while (chunkKeys.hasNext()) {
            long chunkKey = chunkKeys.nextLong();
            ChunkPos pos = new ChunkPos((int) chunkKey, (int) (chunkKey >> 32));
            if (pass == PromotePass.KEEP_HOT
                    && !FlushPolicy.shouldFlush(pos.x, pos.z, centerX, centerZ, keepHot)) {
                continue;
            }
            if (!allCaptured.contains(chunkKey)) {
                if (pass != PromotePass.FINISH) {
                    // The captured-chunk privacy gate: we never write an entity in a chunk whose terrain we have not
                    // captured, so hold it and drop it unwritten only at finish, which is the only pass with no later
                    // one behind it. The dimension's captured positions persist for the whole session and are restored
                    // by the rebind on a return trip, so a chunk captured on a second visit still writes what was held
                    // from the first.
                    continue;
                }
                droppedUncaptured += capture.dropChunk(liveDimensionId, chunkKey).size();
                continue;
            }
            if (pass == PromotePass.KEEP_HOT && !hasEncodeBudget()) {
                break;
            }
            promoteChunk(pos, capture.dropChunk(liveDimensionId, chunkKey));
        }
    }

    enum PromotePass {
        KEEP_HOT,
        LEAVING_DIMENSION,
        FINISH
    }

    private void promoteChunk(ChunkPos pos,
            List<PacketEntity<Packet<?>, EntityDataManager.DataEntry<?>, EquipmentEntry>> held) {
        List<Promoted> built = new ArrayList<>();
        Map<Integer, Entity> byId = new HashMap<>();
        for (PacketEntity<Packet<?>, EntityDataManager.DataEntry<?>, EquipmentEntry> frame : held) {
            if (excludedRootVehicleUuids.contains(frame.uuid())) {
                continue;
            }
            Entity entity;
            try {
                entity = reconstructPacketEntity(frame);
            } catch (RuntimeException e) {
                // The chunk is already drained from the accumulator, so a throw escaping this loop would lose every
                // sibling with it and skip the pass's remaining chunks.
                createDrops++;
                LOGGER.warn("skipping entity {}: reconstruct failed", frame.uuid(), e);
                continue;
            }
            if (entity != null) {
                built.add(new Promoted(frame, entity));
                byId.put(frame.id(), entity);
            } else {
                createDrops++;
            }
        }
        for (Promoted promoted : built) {
            for (int riderId : promoted.frame().passengers()) {
                Entity rider = byId.get(riderId);
                if (rider != null) {
                    rider.startRiding(promoted.entity(), true);
                }
            }
            if (promoted.frame().leashHolderId() != 0 && promoted.entity() instanceof EntityLiving) {
                EntityLiving mob = (EntityLiving) promoted.entity();
                Entity holder = byId.get(promoted.frame().leashHolderId());
                if (holder != null) {
                    mob.setLeashHolder(holder, false);
                }
            }
        }
        for (Promoted promoted : built) {
            Entity entity = promoted.entity();
            if (entity.isRiding()) {
                nestedPassengers++;
                continue;
            }
            if (shouldSaveEntity(entity)) {
                reportCounts.addEntity(promoted.frame().uuid());
            }
            NBTTagCompound tag = encodeSingleEntity(entity, pos, EntitySource.RECONSTRUCTED);
            if (tag != null) {
                entityBuffer.accumulate(promoted.frame().uuid(), pos, tag);
                bufferedEntitySources.put(promoted.frame().uuid(), EntitySource.RECONSTRUCTED);
                recordSaved(promoted.frame().uuid(), entity);
            }
        }
    }

    private static final class Promoted {
        private final PacketEntity<Packet<?>, EntityDataManager.DataEntry<?>, EquipmentEntry> frame;
        private final Entity entity;

        Promoted(PacketEntity<Packet<?>, EntityDataManager.DataEntry<?>, EquipmentEntry> frame,
                Entity entity) {
            this.frame = frame;
            this.entity = entity;
        }

        PacketEntity<Packet<?>, EntityDataManager.DataEntry<?>, EquipmentEntry> frame() {
            return frame;
        }

        Entity entity() {
            return entity;
        }
    }

    private @Nullable Entity reconstructPacketEntity(
            PacketEntity<Packet<?>, EntityDataManager.DataEntry<?>, EquipmentEntry> frame) {
        Entity entity = EntityPacketCapture.createSpawnEntity(frame.spawn(), level());
        if (entity == null) {
            return null;
        }
        entity.setEntityId(frame.id());
        entity.setUniqueId(frame.uuid());
        EntityPos pos = frame.pos();
        entity.setLocationAndAngles(pos.x(), pos.y(), pos.z(), pos.yRot(), pos.xRot());
        List<EntityDataManager.DataEntry<?>> synced = frame.synced();
        if (!synced.isEmpty()) {
            entity.getDataManager().setEntryValues(synced);
        }
        if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            for (EquipmentEntry equipment : frame.equipment()) {
                living.setItemStackToSlot(equipment.slot(), equipment.stack());
            }
        }
        return entity;
    }

    /**
     * Called only on drained holders, never on a still-stashed one, so the non-idempotent remap runs exactly once per
     * holder.
     */
    private void prepareDrainedItems(Map<BlockPos, NBTTagCompound> holders) {
        for (Map.Entry<BlockPos, NBTTagCompound> entry : holders.entrySet()) {
            scrubAndRemapItems(entry.getValue(), entry.getKey());
        }
    }

    private void scrubAndRemapItems(NBTTagCompound holder, Object identifier) {
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

    private void tallyInteractionMerges(Set<BlockPos> items, Set<BlockPos> holders) {
        tallyInteractionPositions(items);
        tallyInteractionPositions(holders);
    }

    private void tallyInteractionPositions(Set<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (bookshelfSlots.containsKey(pos.toLong())) {
                continue;
            }
            reportCounts.addContainer("i:" + pos.toLong());
        }
    }

    private void scrubEntityItems(NBTTagCompound entityChunkTag) {
        if (!(entityChunkTag.getTag("Entities") instanceof NBTTagList)) {
            return;
        }
        NBTTagList entities = (NBTTagList) entityChunkTag.getTag("Entities");
        for (int i = 0; i < entities.tagCount(); i++) {
            if (entities.get(i) instanceof NBTTagCompound) {
                NBTTagCompound entity = (NBTTagCompound) entities.get(i);
                ItemLocationScrub.scrubEntity(entity);
            }
        }
    }

    private void remapEntityItems(NBTTagCompound entityChunkTag, MapArchive archive) {
        if (!(entityChunkTag.getTag("Entities") instanceof NBTTagList)) {
            return;
        }
        NBTTagList entities = (NBTTagList) entityChunkTag.getTag("Entities");
        for (int i = 0; i < entities.tagCount(); i++) {
            NBTTagCompound entity = entities.get(i) instanceof NBTTagCompound ? (NBTTagCompound) entities.get(i) : null;
            if (entity != null && entity.getTag("Item") instanceof NBTTagCompound) {
                NBTTagCompound item = (NBTTagCompound) entity.getTag("Item");
                try {
                    archive.remapItem(item);
                } catch (RuntimeException e) {
                    // Per-item fail-soft: a single bad framed/dropped map item keeps its unremapped id rather than
                    // throwing into flushEntityChunk's catch, which drops the whole already-drained entity-chunk.
                    mapsRemapFailed++;
                    mapEntityRemapLoss.lost(EntityMerge.readUuid(entity), e);
                }
            }
        }
    }

    private void report(AsyncSaveWriter.SaveResult result) {
        if (result.failed()) {
            reportSaveFailure(result.error());
            return;
        }
        writeReportCompletion(result.chunksFailed(), result.entityChunksFailed());
        int failed = failedWriteCount(result.chunksFailed(), result.entityChunksFailed());
        boolean partial = failed > 0;
        // The chat figures are the dedup'd counts the HUD shows: the distinct captured-chunk total, not the writer's
        // new-plus-re-captured write tally, which double-counts a chunk written once then re-flushed on a revisit.
        int distinctChunks = totalCapturedChunks();
        int containers = mergedContainers + mergedEntityContainers + result.mergedContainers();
        if (config.showChatMessages()) {
            bridge.sendChat(ChatCopy.downloaded(saveName, distinctChunks,
                    reportCounts.entityCount(), reportCounts.containerCount(), Wdl.elapsedMillis()));
            if (partial) {
                bridge.sendChat(ChatCopy.downloadIncomplete(failed));
            }
            Path saveFolder = Minecraft.getMinecraft().gameDir.toPath().resolve("saves").resolve(saveName)
                    .toAbsolutePath();
            bridge.sendChat(ChatCopy.savedTo(saveName, saveFolder.toString()));
        }
        LOGGER.info("saved {} chunks ({} new, {} re-captured, {} failed), {} entity-chunks ({} failed, "
                + "{} carried forward on re-flush, {} recovered from an earlier download), {} containers, "
                + "{} lecterns, {} villager trades ({} client-invented trade lists dropped) to {}",
                result.chunksWritten(), result.chunksNew(), result.chunksRecaptured(), result.chunksFailed(),
                result.entityChunksWritten(), result.entityChunksFailed(), result.entitiesCarriedForward(),
                result.entitiesRecovered(), containers, mergedLecterns, mergedVillagerTrades,
                inventedMerchantOffersDropped, saveName);
        // The terms of the partial-finish sum the line above does not name, so that adding the two it does name
        // reproduces the count the chat reports. Logged even when every term is zero: a reader who cannot tell
        // "nothing else was lost" from "this build had no such line" cannot check the clean verdict at all.
        LOGGER.info("counted capture losses for {}: {} chunk captures, {} maps, {} map remaps, {} idcounts, "
                + "{} map manifest, {} block containers, {} entity containers, {} container vehicles, "
                + "{} villager trades, {} predicted interactions, {} structural entities, {} resumed mounts, "
                + "{} player records, {} finish steps",
                saveName, chunksCaptureFailed, mapsFailed.get(), mapsRemapFailed, idCountsFailed,
                mapManifestLosses(), blockContainersFailed, entityContainersFailed, containerVehiclesLost,
                villagerTradesLost, interactionCapturesLost, structuralEntitiesLost, resumedMountsLost,
                playerRecordsLost, finishStepsFailed);
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

    private void reportSaveFailure(@Nullable Throwable error) {
        SaveFailureReason reason = SaveFailureComposer.describe(error);
        bridge.sendChat(ChatCopy.saveFailed(reason)); // an error is never suppressed by showChatMessages
        LOGGER.error("save failed", error);
        ToastCopy toast = ToastCopy.downloadError(config.showToasts(), reason);
        if (toast != null) {
            bridge.sendToast(toast);
        }
    }

    private void beginReport(Minecraft minecraft, Path saveRoot) {
        stampReport(saveRoot, () -> buildReportIdentity(minecraft), () -> buildReportEnvironment(minecraft));
    }

    /**
     * Never throwing is the contract: the stamp writes the sentinel that marks a folder a wdl download, so a propagated
     * read failure leaves a fully written save neither listed nor resumable.
     */
    void stampReport(Path saveRoot, Supplier<DownloadIdentity> readIdentity,
            Supplier<ReportEnvironment> readEnvironment) {
        DownloadIdentity identity;
        try {
            identity = readIdentity.get();
        } catch (RuntimeException e) {
            LOGGER.warn("the download report could not read its source; recording an unidentified one", e);
            identity = unidentifiedIdentity();
        }
        ReportEnvironment environment;
        try {
            environment = readEnvironment.get();
        } catch (RuntimeException e) {
            LOGGER.warn("the download report could not read its world facts; recording them absent", e);
            environment = unidentifiedEnvironment();
        }
        this.reportRoot = saveRoot;
        this.reportIdentity = identity;
        this.reportEnvironment = environment;
        report.begin(saveRoot, identity, environment, config.nonDefaultSettings());
    }

    DownloadIdentity unidentifiedIdentity() {
        String worldName = target.worldName();
        return new DownloadIdentity(UUID.randomUUID().toString(), Instant.now().truncatedTo(ChronoUnit.SECONDS),
                "", "", "", "", "", bridge.loaderName(), bridge.loaderVersion(),
                worldName != null ? worldName : "", "unidentified");
    }

    private ReportEnvironment unidentifiedEnvironment() {
        return new ReportEnvironment("", 0, targetDimension.getName().toString(), Wdl.mcVersion(),
                bridge.modVersion());
    }

    private ReportEnvironment buildReportEnvironment(Minecraft minecraft) {
        String brand = "";
        EntityPlayerSP player = minecraft.player;
        if (player != null && player.getServerBrand() != null) {
            brand = player.getServerBrand();
        }
        return new ReportEnvironment(brand, minecraft.gameSettings.renderDistanceChunks,
                targetDimension.getName().toString(), Wdl.mcVersion(), bridge.modVersion());
    }

    private DownloadIdentity buildReportIdentity(Minecraft minecraft) {
        String downloaderName = "";
        String downloaderUuid = "";
        EntityPlayerSP player = minecraft.player;
        if (player != null) {
            downloaderName = player.getGameProfile().getName();
            downloaderUuid = player.getGameProfile().getId().toString();
        }
        String address = "";
        String sourceName = "";
        String motd = "";
        String sourceKind = "unidentified";
        ServerData server = minecraft.getCurrentServerData();
        if (server != null) {
            address = server.serverIP;
            sourceName = server.serverName;
            motd = server.serverMOTD != null ? server.serverMOTD : "";
            sourceKind = "";
        }
        String worldName = target.worldName();
        String downloadName = worldName != null ? worldName : "";
        return new DownloadIdentity(UUID.randomUUID().toString(), Instant.now().truncatedTo(ChronoUnit.SECONDS),
                downloaderName, downloaderUuid, address, sourceName, motd, bridge.loaderName(),
                bridge.loaderVersion(), downloadName, sourceKind);
    }

    private void prepareReportCompletion() {
        Path root = reportRoot;
        DownloadIdentity identity = reportIdentity;
        ReportEnvironment environment = reportEnvironment;
        if (root == null || identity == null || environment == null) {
            return;
        }
        List<DimensionChunks> dimensions = new ArrayList<>();
        int chunkTotal = 0;
        for (Map.Entry<DimensionType, LongOpenHashSet> dimension : capturedByDimension.entrySet()) {
            int chunks = dimension.getValue().size();
            dimensions.add(new DimensionChunks(dimension.getKey().getName().toString(), chunks));
            chunkTotal += chunks;
        }
        this.pendingReport = new PendingReport(root, identity, environment,
                config.nonDefaultSettings(), Instant.now().truncatedTo(ChronoUnit.SECONDS),
                chunkTotal, reportCounts.entityCount(), reportCounts.containerCount(), dimensions);
    }

    private void writeReportCompletion(int chunksFailed, int entityChunksFailed) {
        PendingReport pending = pendingReport;
        if (pending == null) {
            return;
        }
        WorldIconWriter.write(pending.saveRoot(), reportIconBytes);
        report.complete(pending.saveRoot(), pending.identity(), pending.environment(), pending.settings(),
                pending.finishedAt(), new DownloadCounts(pending.chunks(), pending.entities(),
                        pending.containers(), pending.dimensions()),
                this::scanSaveChunks, countedLosses(chunksFailed, entityChunksFailed));
    }

    /**
     * The catch is the supplier-boundary backstop: a scan failure of any kind degrades to a zero total rather than
     * reaching the store's catch and costing the completion record of a fully-written download.
     */
    private SaveChunks scanSaveChunks() {
        try {
            WorldPaths paths = worldPaths;
            return paths == null
                    ? new SaveChunks(0, ImmutableList.of())
                    : SaveChunks.scan(paths.onDiskRegionDirectories());
        } catch (RuntimeException e) {
            LOGGER.warn("save-total scan failed; the report keeps the session counts only", e);
            return new SaveChunks(0, ImmutableList.of());
        }
    }

    /**
     * A zero is not proof a download lost nothing. Losses reach this sum only where a term was added for them, so read
     * a zero as "no counted term moved" and never as "nothing was lost", and do not add a caller that treats it as an
     * assertion of completeness.
     *
     * <p>Only the map tally is atomic, and the plain ints beside it are correct for the production readers because each
     * sits behind the writer's queue drain or its completed future, not because they are single-threaded. A reader
     * added outside those edges must supply its own, or establish that no writer thread ever ran for the session it
     * reads.
     */
    boolean isPartialSave(int chunksFailed, int entityChunksFailed) {
        return failedWriteCount(chunksFailed, entityChunksFailed) > 0;
    }

    private int failedWriteCount(int chunksFailed, int entityChunksFailed) {
        int total = 0;
        for (int axis : countedLosses(chunksFailed, entityChunksFailed).values()) {
            total += axis;
        }
        return total;
    }

    /**
     * The keys reach the completion record verbatim, so they are as durable as it is.
     */
    private Map<String, Integer> countedLosses(int chunksFailed, int entityChunksFailed) {
        Map<String, Integer> losses = new LinkedHashMap<>();
        putLoss(losses, "chunks", chunksFailed);
        putLoss(losses, "entity_chunks", entityChunksFailed);
        putLoss(losses, "chunk_captures", chunksCaptureFailed);
        putLoss(losses, "maps", mapsFailed.get());
        putLoss(losses, "map_remaps", mapsRemapFailed);
        putLoss(losses, "idcounts", idCountsFailed);
        putLoss(losses, "map_manifest", mapManifestLosses());
        putLoss(losses, "block_containers", blockContainersFailed);
        putLoss(losses, "entity_containers", entityContainersFailed);
        putLoss(losses, "container_vehicles", containerVehiclesLost);
        putLoss(losses, "villager_trades", villagerTradesLost);
        putLoss(losses, "predicted_interactions", interactionCapturesLost);
        putLoss(losses, "structural_entities", structuralEntitiesLost);
        putLoss(losses, "resumed_mounts", resumedMountsLost);
        putLoss(losses, "player_records", playerRecordsLost);
        putLoss(losses, "finish_steps", finishStepsFailed);
        return losses;
    }

    private static void putLoss(Map<String, Integer> losses, String axis, int count) {
        if (count > 0) {
            losses.put(axis, count);
        }
    }

    private static final class PendingReport {
        private final Path saveRoot;
        private final DownloadIdentity identity;
        private final ReportEnvironment environment;
        private final Map<String, String> settings;
        private final Instant finishedAt;
        private final int chunks;
        private final int entities;
        private final int containers;
        private final List<DimensionChunks> dimensions;

        PendingReport(Path saveRoot, DownloadIdentity identity, ReportEnvironment environment,
                Map<String, String> settings, Instant finishedAt, int chunks, int entities, int containers,
                List<DimensionChunks> dimensions) {
            this.saveRoot = saveRoot;
            this.identity = identity;
            this.environment = environment;
            this.settings = settings;
            this.finishedAt = finishedAt;
            this.chunks = chunks;
            this.entities = entities;
            this.containers = containers;
            this.dimensions = dimensions;
        }

        Path saveRoot() {
            return saveRoot;
        }

        DownloadIdentity identity() {
            return identity;
        }

        ReportEnvironment environment() {
            return environment;
        }

        Map<String, String> settings() {
            return settings;
        }

        Instant finishedAt() {
            return finishedAt;
        }

        int chunks() {
            return chunks;
        }

        int entities() {
            return entities;
        }

        int containers() {
            return containers;
        }

        List<DimensionChunks> dimensions() {
            return dimensions;
        }
    }
}
