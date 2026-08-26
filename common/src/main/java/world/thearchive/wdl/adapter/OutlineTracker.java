// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecartContainer;
import net.minecraft.entity.passive.AbstractChestHorse;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.tileentity.TileEntityLockable;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
import world.thearchive.wdl.core.CaptureToggles;
import world.thearchive.wdl.core.CapturedContainers;
import world.thearchive.wdl.core.OutlineClamp;
import world.thearchive.wdl.core.OutlineClass;
import world.thearchive.wdl.core.OutlineClassifier;
import world.thearchive.wdl.core.OutlineConfig;
import world.thearchive.wdl.core.RecoveredCoverage;
import world.thearchive.wdl.core.RimFace;
import world.thearchive.wdl.core.TimingWindow;

/**
 * Maintains the unsaved-container outline draw-set on the client tick: each tick it walks the loaded containers inside
 * the camera-centered clamp, pairs a double chest into one logical container, applies the pure
 * {@link OutlineClassifier} against the session captured-set and the prior-session recovered-set, stamps each
 * still-rimmed container's hue and exposed face, and buckets it into its section in the {@link OutlineDrawSet}. A
 * captured container drops out; the render reads the prebuilt draw-set and its stamped faces, touching no world state
 * of its own.
 *
 * <p>The expensive part, the per-chunk block-entity enumeration with its double-chest pairing and shape bounds, is
 * cached per chunk: a chunk is scanned when it first enters the clamp, its cached container geometry (and the reused
 * rim objects) is replayed each tick through the cheap distance filter and classification, and the chunk is evicted
 * when it leaves the clamp. Because there is no mixin-free per-block change signal, the cache is refreshed on a
 * staggered period instead, so a placed or broken container is picked up within a couple of seconds; a capture still
 * drops its rim immediately, since classification runs every tick and is never cached. Entity-borne containers (chest
 * vehicles and chested animals) move, so they are enumerated fresh each tick from their live bounding box and
 * classified by UUID; their rim sits on the box top face, since a vehicle in the open has no block neighbors to seal
 * against.
 */
public final class OutlineTracker {
    private static final long[] NO_CELLS = new long[0];
    // A reused scratch position for the seal-test neighbor reads, so no probe allocates. Client thread only.
    private static final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
    private static final Logger LOGGER = LogManager.getLogger(OutlineTracker.class);
    private static final int TIMING_WINDOW_TICKS = 100;

    // Re-scan a cached chunk's block entities at most this often: the enumeration is the dominant tick cost on
    // a dense base, so it is cached and only refreshed to pick up a placed or broken container, staggered by
    // chunk so a whole region does not re-scan on one tick.
    private static final int RESCAN_PERIOD_TICKS = 30;

    // Bound the per-tick block-entity enumeration: a cold cache (download start, a config toggle, or a dimension
    // change) would otherwise scan the whole clamp square in one tick. Over-budget chunks fall to the next tick,
    // so the bulk scan spreads while an incremental walk (a few new chunks a tick) still fills at once.
    private static final int MAX_RESCANS_PER_TICK = 32;

    // A chested animal's bounding box is its full standing height, so rimming the box top would float the rim
    // far above the chest, which hangs on the flanks at the animal's back. Across the chested equines the chest
    // top sits near this fraction of the box height (donkey 0.74, mule 0.76, llama 0.70), so the rim box is
    // capped there. A container vehicle keeps its true box: a boat or minecart is short enough that its top
    // already meets the chest it carries.
    private static final double CHESTED_RIM_HEIGHT_FRACTION = 0.74;
    private static final double MERCHANT_RIM_HEIGHT_FRACTION = 0.45;

    private final OutlineDrawSet drawSet = new OutlineDrawSet();
    private final Long2ObjectMap<ChunkContainers> chunkCache = new Long2ObjectOpenHashMap<>();
    private @Nullable WorldClient lastLevel;
    private long tickCounter;
    private final TimingWindow tickTiming = new TimingWindow(TIMING_WINDOW_TICKS);
    private boolean errorLogged;
    private BobbyChunkFilter bobbyFilter = BobbyChunkFilter.INACTIVE;

    /** The cached geometry of one logical block container: its reused rim, the ender flag, and the cheap keys. */
    private static final class CachedContainer {
        final OutlineRim rim;
        final boolean ender;
        // The live block-entity type id at scan time, compared against the recorded capture type so a
        // same-position block replacement re-rims the stale capture (Gate 2).
        final String liveTypeId;
        final long sectionKey;
        final long secondSectionKey;
        final double centerX;
        final double centerY;
        final double centerZ;

        CachedContainer(OutlineRim rim, boolean ender, String liveTypeId,
                long sectionKey, long secondSectionKey, double centerX, double centerY, double centerZ) {
            this.rim = rim;
            this.ender = ender;
            this.liveTypeId = liveTypeId;
            this.sectionKey = sectionKey;
            this.secondSectionKey = secondSectionKey;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
        }
    }

    /** One chunk's cached containers, the tick it is next due a re-scan, and the tick it was last in clamp. */
    private static final class ChunkContainers {
        final List<CachedContainer> containers = new ArrayList<>();
        long nextRescanTick;
        long lastTouchedTick = -1L;
    }

    /** The draw-set to render this frame, rebuilt by the most recent {@link #tick}. */
    public OutlineDrawSet drawSet() {
        return drawSet;
    }

    /** Empty the draw-set, the chunk cache, and the level anchor when not recording, so nothing lingers. */
    public void clear() {
        drawSet.clear();
        chunkCache.clear();
        lastLevel = null;
    }

    /** Injected once from Wdl.initialize so the outline scan skips Bobby's cached chunks. */
    public void useBobbyFilter(BobbyChunkFilter filter) {
        this.bobbyFilter = filter;
    }

    /**
     * Rebuild the draw-set for the current camera position. Clears it first, so an off toggle or a zero distance leaves
     * an empty query (no rim drawn). Runs on the client tick, the same thread the render reads on.
     *
     * <p>{@code config} carries the display treatment only, live from the settings; every gate on whether a rim may be
     * drawn at all comes from {@code toggles}, which the caller has already reconciled against what the running
     * download latched, so no rim is drawn for an axis this download is not capturing.
     */
    public void tick(WorldClient level, Vec3d cameraPos, OutlineConfig config, CaptureToggles toggles,
            CapturedContainers captured, RecoveredCoverage recovered) {
        try {
            if (!toggles.renderUnsavedOutline()) {
                clear();
                return;
            }
            drawSet.clear();
            if (level != lastLevel) {
                chunkCache.clear(); // a dimension change must not replay the prior dimension's cached geometry
                lastLevel = level;
            }
            boolean debugTiming = config.debugTiming();
            long startNanos = debugTiming ? System.nanoTime() : 0L;
            double clamp = config.outlineDistance();
            // Gate each enumeration on its capture axis: a container the player cannot capture (its axis off) must
            // not draw a to-do rim that opening it can never clear.
            if (toggles.captureContainers()) {
                buildBlockContainers(level, cameraPos, clamp, config, toggles, captured, recovered);
            } else {
                chunkCache.clear();
            }
            if (toggles.captureEntities()) {
                enumerateEntityContainers(level, cameraPos, clamp, config, captured, recovered);
            }
            tickCounter++;
            if (debugTiming) {
                recordTickTiming(System.nanoTime() - startNanos);
            }
        } catch (RuntimeException e) {
            drawSet.clear(); // a cosmetic overlay must never crash the client tick or abort the download
            if (!errorLogged) {
                errorLogged = true;
                LOGGER.warn("outline tick failed; suppressing further errors", e);
            }
        }
    }

    private void buildBlockContainers(WorldClient level, Vec3d cameraPos, double clamp, OutlineConfig config,
            CaptureToggles toggles, CapturedContainers captured, RecoveredCoverage recovered) {
        int chunkRadius = MathHelper.ceil(clamp / 16.0);
        int cameraChunkX = MathHelper.floor(cameraPos.x) >> 4;
        int cameraChunkZ = MathHelper.floor(cameraPos.z) >> 4;
        ChunkProviderClient chunkSource = level.getChunkProvider();
        int rescans = 0;
        for (int cx = cameraChunkX - chunkRadius; cx <= cameraChunkX + chunkRadius; cx++) {
            for (int cz = cameraChunkZ - chunkRadius; cz <= cameraChunkZ + chunkRadius; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                ChunkContainers entry = chunkCache.get(key);
                boolean dueForScan;
                if (entry == null) {
                    entry = new ChunkContainers();
                    chunkCache.put(key, entry);
                    dueForScan = true;
                } else {
                    dueForScan = tickCounter >= entry.nextRescanTick;
                }
                if (dueForScan) {
                    if (rescans < MAX_RESCANS_PER_TICK) {
                        rescan(level, chunkSource, cx, cz, key, config, entry);
                        rescans++;
                    } else {
                        entry.nextRescanTick = tickCounter; // over budget this tick; due again next tick
                    }
                }
                entry.lastTouchedTick = tickCounter;
                emit(level, entry, cameraPos, clamp, config, toggles, captured, recovered);
            }
        }
        evictUntouched();
    }

    /** Drop cache entries the camera no longer covers (not visited this tick), bounding the cache to the clamp. */
    private void evictUntouched() {
        ObjectIterator<Long2ObjectMap.Entry<ChunkContainers>> entries = chunkCache.long2ObjectEntrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue().lastTouchedTick != tickCounter) {
                entries.remove();
            }
        }
    }

    private void rescan(WorldClient level, ChunkProviderClient chunkSource, int cx, int cz, long key,
            OutlineConfig config, ChunkContainers entry) {
        entry.containers.clear();
        // This band's client chunk source has no status-taking getChunk; getLoadedChunk(x, z)
        // returns the live full chunk or null, which is what ChunkStatus.FULL with no-create meant.
        Chunk chunk = chunkSource.getLoadedChunk(cx, cz);
        if (chunk == null) {
            entry.nextRescanTick = tickCounter + 1L; // not loaded yet; retry next tick so its containers appear
            return;
        }
        if (bobbyFilter.isBobbyChunk(chunk)) {
            // Bobby-cached chunk: no live containers to outline. Rescan on the normal cadence, not next tick.
            entry.nextRescanTick = tickCounter + RESCAN_PERIOD_TICKS + Math.floorMod(key, RESCAN_PERIOD_TICKS);
            return;
        }
        entry.nextRescanTick = tickCounter + RESCAN_PERIOD_TICKS + Math.floorMod(key, RESCAN_PERIOD_TICKS);
        for (Map.Entry<BlockPos, TileEntity> blockEntity : chunk.getTileEntityMap().entrySet()) {
            cacheContainer(level, blockEntity.getKey(), blockEntity.getValue(), config, entry);
        }
    }

    private void cacheContainer(WorldClient level, BlockPos pos, TileEntity blockEntity, OutlineConfig config,
            ChunkContainers entry) {
        long[] cells;
        AxisAlignedBB box;
        boolean ender = false;
        // Invariant, kept in sync by hand: every type outlined here (and in isContainerEntity and
        // isTradeableMerchant) must have an enabled capture path, or its rim is a to-do the player can never
        // clear. Contents arrive either on open, bound in LiveCaptureSession.captureOpenContainer (a container
        // menu, or a merchant menu whose non-empty offers clear a tradeable villager's rim), or on interaction,
        // recorded in InteractionCapture. The invariant is one-way; a capturable type need not be outlined.
        // Two deliberate asymmetries: an ender chest reaches the save through the player tag rather than this
        // position, so its rim is suppressed in emit on the toggle that write is gated on; a jukebox is captured on
        // interaction but deliberately not outlined (not a TileEntityLockable, it falls through to the else
        // return below).
        if (blockEntity instanceof TileEntityChest) {
            // The double-chest partner is the capture side's inventory pairing (ContainerCapture.doubleChestPartner),
            // so the coverage outline reflects exactly what an open would capture, not vanilla's render-shape pairing.
            BlockPos partner = ContainerCapture.doubleChestPartner(level, pos);
            if (partner != null) {
                if (pos.toLong() > partner.toLong()) {
                    return; // the lower-keyed half caches the merged rim, so the double chest is one entry
                }
                cells = new long[] { pos.toLong(), partner.toLong() };
                box = boxOf(level, pos).union(boxOf(level, partner));
            } else {
                cells = new long[] { pos.toLong() };
                box = boxOf(level, pos);
            }
        } else if (blockEntity instanceof TileEntityEnderChest) {
            cells = new long[] { pos.toLong() };
            box = boxOf(level, pos);
            ender = true;
            // The lectern (LecternBlock / LecternBlockEntity) is a 1.14 addition and cannot exist at this band, so
            // its outline branch is dropped; there is nothing to lose here.
        } else if (blockEntity instanceof TileEntityLockable) {
            cells = new long[] { pos.toLong() };
            box = boxOf(level, pos);
        } else {
            return; // not a container whose contents arrive only on open
        }
        // The hue is a placeholder restamped from the live classification each tick; the geometry is what is cached.
        OutlineRim rim = new OutlineRim(config.unscannedColor(), box, cells);
        long sectionKey = SectionKey.blockToSection(cells[0]);
        long secondSectionKey = cells.length > 1 ? SectionKey.blockToSection(cells[1]) : sectionKey;
        entry.containers.add(new CachedContainer(rim, ender, blockEntityTypeId(blockEntity), sectionKey,
                secondSectionKey, (box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5,
                (box.minZ + box.maxZ) * 0.5));
    }

    // The block-entity registry id string, the key the chunk tag writes as "id" and the same one the recorded
    // capture type holds. Kept as a String, never the band-renamed id type (ResourceLocation vs Identifier), so
    // this shared file stays band-portable.
    @SuppressWarnings("NullAway") // getKey is non-null for a live block entity's registered type
    private static String blockEntityTypeId(TileEntity blockEntity) {
        return TileEntity.getKey(blockEntity.getClass()).toString();
    }

    private void emit(WorldClient level, ChunkContainers entry, Vec3d cameraPos, double clamp, OutlineConfig config,
            CaptureToggles toggles, CapturedContainers captured, RecoveredCoverage recovered) {
        for (int i = 0; i < entry.containers.size(); i++) {
            CachedContainer container = entry.containers.get(i);
            if (!OutlineClamp.isWithin(cameraPos.x, cameraPos.y, cameraPos.z, container.centerX, container.centerY,
                    container.centerZ, clamp)) {
                continue;
            }
            if (container.ender && !toggles.savePlayerEnderChest()) {
                continue; // the finish strips EnderItems, so this rim would be a to-do no open can clear
            }
            OutlineClass classification = OutlineClassifier.classify(container.rim.cells(), container.liveTypeId, null,
                    container.ender, captured, recovered);
            if (classification == OutlineClass.CAPTURED) {
                continue;
            }
            container.rim.hue(OutlineClassifier.hueFor(classification, config));
            container.rim.face(faceOf(level, container.rim.cells()));
            drawSet.add(container.sectionKey, container.rim);
            // A double chest that straddles a section boundary is bucketed in both halves' sections, so the
            // per-section frustum cull cannot drop a rim whose visible half is in the section the other is not.
            if (container.secondSectionKey != container.sectionKey) {
                drawSet.add(container.secondSectionKey, container.rim);
            }
        }
    }

    private void enumerateEntityContainers(WorldClient level, Vec3d cameraPos, double clamp, OutlineConfig config,
            CapturedContainers captured, RecoveredCoverage recovered) {
        AxisAlignedBB clampBox = new AxisAlignedBB(cameraPos.x - clamp, cameraPos.y - clamp, cameraPos.z - clamp,
                cameraPos.x + clamp,
                cameraPos.y + clamp, cameraPos.z + clamp);
        for (Entity entity : level.getEntitiesWithinAABB(Entity.class, clampBox,
                candidate -> isContainerEntity(candidate) || isTradeableMerchant(candidate))) {
            if (entity.isInvisible()) {
                continue; // an invisible mob's body does not render, so its rim would reveal it: a fairness leak
            }
            Vec3d center = new Vec3d(entity.posX, entity.posY, entity.posZ);
            if (!OutlineClamp.isWithin(cameraPos.x, cameraPos.y, cameraPos.z, center.x, center.y, center.z, clamp)) {
                continue;
            }
            OutlineClass classification = OutlineClassifier.classify(NO_CELLS, null, entity.getUniqueID(), false,
                    captured, recovered);
            if (classification == OutlineClass.CAPTURED) {
                continue;
            }
            long sectionKey = SectionKey.asLong(MathHelper.floor(center.x) >> 4, MathHelper.floor(center.y) >> 4,
                    MathHelper.floor(center.z) >> 4);
            OutlineRim rim = new OutlineRim(OutlineClassifier.hueFor(classification, config), rimBox(entity),
                    NO_CELLS);
            rim.face(RimFace.TOP); // a vehicle in the open has no block neighbors to seal, so it rims its box top
            drawSet.add(sectionKey, rim);
        }
    }

    /**
     * The rim box for a container entity: a chested animal's box drops to its chest and a tradeable villager's below
     * its head, so a trading-hall roof or a trapdoor at head height does not hide the rim; a vehicle keeps its own box.
     */
    private static AxisAlignedBB rimBox(Entity entity) {
        AxisAlignedBB box = entity.getEntityBoundingBox();
        if (entity instanceof AbstractChestHorse) {
            return chestedRimBox(box);
        }
        if (entity instanceof EntityVillager) {
            return merchantRimBox(box);
        }
        return box;
    }

    /**
     * {@code box} with its top capped to the chest height (see {@link #CHESTED_RIM_HEIGHT_FRACTION}), footprint kept.
     */
    static AxisAlignedBB chestedRimBox(AxisAlignedBB box) {
        return cappedRimBox(box, CHESTED_RIM_HEIGHT_FRACTION);
    }

    /**
     * {@code box} with its top dropped below the villager's head (see {@link #MERCHANT_RIM_HEIGHT_FRACTION}), footprint
     * kept, so a trading-hall roof or a trapdoor at head height does not hide the rim.
     */
    static AxisAlignedBB merchantRimBox(AxisAlignedBB box) {
        return cappedRimBox(box, MERCHANT_RIM_HEIGHT_FRACTION);
    }

    private static AxisAlignedBB cappedRimBox(AxisAlignedBB box, double heightFraction) {
        double top = box.minY + (box.maxY - box.minY) * heightFraction;
        return new AxisAlignedBB(box.minX, box.minY, box.minZ, box.maxX, top, box.maxZ);
    }

    /** Whether {@code entity} is a container vehicle or a chested animal (the open-time entity-container set). */
    private static boolean isContainerEntity(Entity entity) {
        // Below 1.21 getInventoryColumns is nonzero without a chest, so it cannot substitute for hasChest.
        return entity instanceof EntityMinecartContainer
                || (entity instanceof AbstractChestHorse && ((AbstractChestHorse) entity).hasChest());
    }

    /**
     * Whether {@code entity} is a merchant worth rimming: an adult villager whose synced profession is a real trading
     * one. A nitwit or baby villager has no trades, so its rim could never clear. The profession and baby state ride
     * the synced data, so this is client-derivable. The wandering trader is a 1.14 addition absent at this band, so it
     * is not a case here; villagers are the only merchants.
     */
    private static boolean isTradeableMerchant(Entity entity) {
        if (entity instanceof EntityVillager && !((EntityVillager) entity).isChild()) {
            // Profession is an int on the synced data (getProfession) at this band; every profession trades except
            // nitwit (id 5), and there is no unemployed state below 1.14.
            return ((EntityVillager) entity).getProfession() != 5;
        }
        return false;
    }

    private static AxisAlignedBB boxOf(WorldClient level, BlockPos pos) {
        // Below the Flattening a block state has no VoxelShape; its collision bounding box stands in for the
        // outline shape, block-local, and is offset to world coordinates.
        return level.getBlockState(pos).getBoundingBox(level, pos).offset(pos);
    }

    /**
     * The exposed face of a block container's rim, from the collision-shape seal test against each cell's neighbors in
     * the preference order. Computed on the tick so the render reads a stamped face rather than probing the world each
     * frame; the neighbors it seals against change only on the tick.
     */
    private static RimFace faceOf(WorldClient level, long[] cells) {
        // The open-top chest is the overwhelming case, so settle it on one neighbor read; only a sealed top
        // pays for the rest of the order, which RimFace.selectExposed decides.
        if (!isSealed(level, cells, EnumFacing.UP)) {
            return RimFace.TOP;
        }
        return RimFace.selectExposed(true,
                isSealed(level, cells, EnumFacing.DOWN),
                isSealed(level, cells, EnumFacing.NORTH),
                isSealed(level, cells, EnumFacing.SOUTH),
                isSealed(level, cells, EnumFacing.WEST),
                isSealed(level, cells, EnumFacing.EAST));
    }

    // A merged-box face is exposed when any cell is open in that direction, so a double chest with one half
    // blocked still rims rather than vanishing (a completeness tool must not hide a reachable container); the rim
    // then spans both cells and its blocked half clips behind that neighbor. A neighbor that is itself one of
    // the cells is interior to the merged box, not an exterior face, and is skipped, without which a double
    // chest's two end faces always read open, since each end cell's inward neighbor is the other half. Only a
    // multi-cell box has such a neighbor, so a single container skips that test.
    private static boolean isSealed(WorldClient level, long[] cells, EnumFacing direction) {
        for (long cell : cells) {
            // This band has no static BlockPos long helpers (getX(long)/asLong(int,int,int)); unpack the cell key
            // through fromLong and repack the neighbor through a BlockPos. isFullBlock is the solid-cube test
            // standing in for isCollisionShapeFullBlock, which is a cosmetic rim-face seal, not saved data.
            BlockPos cellPos = BlockPos.fromLong(cell);
            int nx = cellPos.getX() + direction.getXOffset();
            int ny = cellPos.getY() + direction.getYOffset();
            int nz = cellPos.getZ() + direction.getZOffset();
            if (cells.length > 1 && containsCell(cells, new BlockPos(nx, ny, nz).toLong())) {
                continue;
            }
            neighbor.setPos(nx, ny, nz);
            if (!level.getBlockState(neighbor).isFullBlock()) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsCell(long[] cells, long pos) {
        for (long cell : cells) {
            if (cell == pos) {
                return true;
            }
        }
        return false;
    }

    /** Roll the per-tick build cost into a windowed log line. */
    private void recordTickTiming(long elapsedNanos) {
        if (tickTiming.record(elapsedNanos)) {
            int rims = 0;
            for (List<OutlineRim> sectionRims : drawSet.sections().values()) {
                rims += sectionRims.size();
            }
            LOGGER.info("outline tick: {} ticks, avg {} us, max {} us; {} rims in {} sections, {} chunks cached",
                    tickTiming.count(), tickTiming.averageMicros(), tickTiming.maxMicros(), rims,
                    drawSet.sections().size(), chunkCache.size());
            tickTiming.reset();
        }
    }
}
