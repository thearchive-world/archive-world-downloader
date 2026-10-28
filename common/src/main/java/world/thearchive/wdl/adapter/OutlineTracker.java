// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecartContainer;
import net.minecraft.entity.passive.EntityHorse;
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

public final class OutlineTracker {
    private static final long[] NO_CELLS = new long[0];
    // Client thread only.
    private static final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
    private static final Logger LOGGER = LogManager.getLogger(OutlineTracker.class);
    private static final int TIMING_WINDOW_TICKS = 100;

    private static final int RESCAN_PERIOD_TICKS = 30;

    private static final int MAX_RESCANS_PER_TICK = 32;

    // A chested animal's bounding box is its full standing height, so rimming the box top would float the rim far above
    // the chest, which hangs on the flanks at the animal's back. Across the chested equines the chest top sits near
    // this fraction of the box height, so the rim box is capped there.
    private static final double CHESTED_RIM_HEIGHT_FRACTION = 0.74;
    private static final double MERCHANT_RIM_HEIGHT_FRACTION = 0.45;

    private final OutlineDrawSet drawSet = new OutlineDrawSet();
    private final Long2ObjectMap<ChunkContainers> chunkCache = new Long2ObjectOpenHashMap<>();
    private @Nullable WorldClient lastLevel;
    private long tickCounter;
    private final TimingWindow tickTiming = new TimingWindow(TIMING_WINDOW_TICKS);
    private boolean errorLogged;
    private BobbyChunkFilter bobbyFilter = BobbyChunkFilter.INACTIVE;

    private static final class CachedContainer {
        final OutlineRim rim;
        final boolean ender;
        final @Nullable String liveTypeId;
        final long sectionKey;
        final long secondSectionKey;
        final double centerX;
        final double centerY;
        final double centerZ;

        CachedContainer(OutlineRim rim, boolean ender, @Nullable String liveTypeId,
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

    private static final class ChunkContainers {
        final List<CachedContainer> containers = new ArrayList<>();
        long nextRescanTick;
        long lastTouchedTick = -1L;
    }

    /** The draw-set to render this frame, rebuilt by the most recent {@link #tick}. */
    public OutlineDrawSet drawSet() {
        return drawSet;
    }

    /** Empty the draw-set, the chunk cache, and the level anchor. */
    public void clear() {
        drawSet.clear();
        chunkCache.clear();
        lastLevel = null;
    }

    public void useBobbyFilter(BobbyChunkFilter filter) {
        this.bobbyFilter = filter;
    }

    /**
     * Rebuild the draw-set for the current camera position. Clears it first, so an off toggle leaves an empty query (no
     * rim drawn). Runs on the client tick, the same thread the render reads on.
     *
     * <p>Every gate on whether a rim may be drawn at all comes from {@code toggles}, which the caller has already
     * reconciled against what the running download latched.
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
        int cameraChunkX = MathHelper.floor(cameraPos.xCoord) >> 4;
        int cameraChunkZ = MathHelper.floor(cameraPos.zCoord) >> 4;
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
                        entry.nextRescanTick = tickCounter;
                    }
                }
                entry.lastTouchedTick = tickCounter;
                emit(level, entry, cameraPos, clamp, config, toggles, captured, recovered);
            }
        }
        evictUntouched();
    }

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
        Chunk chunk = chunkSource.getLoadedChunk(cx, cz);
        if (chunk == null) {
            entry.nextRescanTick = tickCounter + 1L;
            return;
        }
        if (bobbyFilter.isBobbyChunk(chunk)) {
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
        // Invariant, kept in sync by hand: every type outlined here (and in isContainerEntity and isTradeableMerchant)
        // must have an enabled capture path, or its rim is a to-do the player can never clear.
        if (blockEntity instanceof TileEntityChest) {
            BlockPos partner = ContainerCapture.doubleChestPartner(level, pos);
            if (partner != null) {
                if (pos.toLong() > partner.toLong()) {
                    return;
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
        } else if (blockEntity instanceof TileEntityLockable) {
            cells = new long[] { pos.toLong() };
            box = boxOf(level, pos);
        } else {
            return;
        }
        OutlineRim rim = new OutlineRim(config.unscannedColor(), box, cells);
        long sectionKey = SectionKey.blockToSection(cells[0]);
        long secondSectionKey = cells.length > 1 ? SectionKey.blockToSection(cells[1]) : sectionKey;
        entry.containers.add(new CachedContainer(rim, ender, LiveCaptureSession.blockEntityTypeId(blockEntity),
                sectionKey, secondSectionKey, (box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5,
                (box.minZ + box.maxZ) * 0.5));
    }

    private void emit(WorldClient level, ChunkContainers entry, Vec3d cameraPos, double clamp, OutlineConfig config,
            CaptureToggles toggles, CapturedContainers captured, RecoveredCoverage recovered) {
        for (int i = 0; i < entry.containers.size(); i++) {
            CachedContainer container = entry.containers.get(i);
            if (!OutlineClamp.isWithin(cameraPos.xCoord, cameraPos.yCoord, cameraPos.zCoord, container.centerX,
                    container.centerY,
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
            if (container.secondSectionKey != container.sectionKey) {
                drawSet.add(container.secondSectionKey, container.rim);
            }
        }
    }

    private void enumerateEntityContainers(WorldClient level, Vec3d cameraPos, double clamp, OutlineConfig config,
            CapturedContainers captured, RecoveredCoverage recovered) {
        AxisAlignedBB clampBox = new AxisAlignedBB(cameraPos.xCoord - clamp, cameraPos.yCoord - clamp,
                cameraPos.zCoord - clamp,
                cameraPos.xCoord + clamp,
                cameraPos.yCoord + clamp, cameraPos.zCoord + clamp);
        for (Entity entity : level.getEntitiesWithinAABB(Entity.class, clampBox,
                candidate -> isContainerEntity(candidate) || isTradeableMerchant(candidate))) {
            if (entity.isInvisible()) {
                continue; // an invisible mob's body does not render, so its rim would reveal it: a fairness leak
            }
            Vec3d center = new Vec3d(entity.posX, entity.posY, entity.posZ);
            if (!OutlineClamp.isWithin(cameraPos.xCoord, cameraPos.yCoord, cameraPos.zCoord, center.xCoord,
                    center.yCoord, center.zCoord, clamp)) {
                continue;
            }
            OutlineClass classification = OutlineClassifier.classify(NO_CELLS, null, entity.getUniqueID(), false,
                    captured, recovered);
            if (classification == OutlineClass.CAPTURED) {
                continue;
            }
            long sectionKey = SectionKey.asLong(MathHelper.floor(center.xCoord) >> 4,
                    MathHelper.floor(center.yCoord) >> 4,
                    MathHelper.floor(center.zCoord) >> 4);
            OutlineRim rim = new OutlineRim(OutlineClassifier.hueFor(classification, config), rimBox(entity),
                    NO_CELLS);
            rim.face(RimFace.TOP);
            drawSet.add(sectionKey, rim);
        }
    }

    private static AxisAlignedBB rimBox(Entity entity) {
        AxisAlignedBB box = entity.getEntityBoundingBox();
        if (entity instanceof EntityHorse) {
            return chestedRimBox(box);
        }
        if (entity instanceof EntityVillager) {
            return merchantRimBox(box);
        }
        return box;
    }

    static AxisAlignedBB chestedRimBox(AxisAlignedBB box) {
        return cappedRimBox(box, CHESTED_RIM_HEIGHT_FRACTION);
    }

    /**
     * {@code box} with its top dropped below the villager's head (see {@link #MERCHANT_RIM_HEIGHT_FRACTION}), so a
     * trading-hall roof or a trapdoor at head height does not hide the rim.
     */
    static AxisAlignedBB merchantRimBox(AxisAlignedBB box) {
        return cappedRimBox(box, MERCHANT_RIM_HEIGHT_FRACTION);
    }

    private static AxisAlignedBB cappedRimBox(AxisAlignedBB box, double heightFraction) {
        double top = box.minY + (box.maxY - box.minY) * heightFraction;
        return new AxisAlignedBB(box.minX, box.minY, box.minZ, box.maxX, top, box.maxZ);
    }

    private static boolean isContainerEntity(Entity entity) {
        return entity instanceof EntityMinecartContainer
                || (entity instanceof EntityHorse && ((EntityHorse) entity).isChested());
    }

    /**
     * A baby villager has no trades, so its rim could never clear.
     */
    private static boolean isTradeableMerchant(Entity entity) {
        if (entity instanceof EntityVillager && !((EntityVillager) entity).isChild()) {
            return ((EntityVillager) entity).getProfession() != 5;
        }
        return false;
    }

    private static AxisAlignedBB boxOf(WorldClient level, BlockPos pos) {
        // Below the Flattening a block state has no VoxelShape, and getBoundingBox returns a single box that
        // need not span the model: a brewing stand's is its base plate, two sixteenths of a block against the
        // fourteen its post reaches, so a top-face rim would sit at the foot. The parts that box omits arrive
        // only through the collision list, which yields them already offset to world coordinates.
        IBlockState state = level.getBlockState(pos);
        AxisAlignedBB box = state.getBoundingBox(level, pos).offset(pos);
        List<AxisAlignedBB> parts = new ArrayList<>(4);
        state.addCollisionBoxToList(level, pos, new AxisAlignedBB(pos), parts, null);
        for (int i = 0; i < parts.size(); i++) {
            box = box.union(parts.get(i));
        }
        return box;
    }

    private static RimFace faceOf(WorldClient level, long[] cells) {
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

    // A merged-box face is exposed when any cell is open in that direction, so a double chest with one half blocked
    // still rims rather than vanishing (a completeness tool must not hide a reachable container). A neighbor that is
    // itself one of the cells is interior to the merged box, not an exterior face, and is skipped, without which a
    // double chest's two end faces always read open, since each end cell's inward neighbor is the other half.
    private static boolean isSealed(WorldClient level, long[] cells, EnumFacing direction) {
        for (long cell : cells) {
            BlockPos cellPos = BlockPos.fromLong(cell);
            int nx = cellPos.getX() + direction.getFrontOffsetX();
            int ny = cellPos.getY() + direction.getFrontOffsetY();
            int nz = cellPos.getZ() + direction.getFrontOffsetZ();
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
