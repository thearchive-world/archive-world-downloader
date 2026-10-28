// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.MultiPlayerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
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
    private @Nullable MultiPlayerLevel lastLevel;
    private long tickCounter;
    private final TimingWindow tickTiming = new TimingWindow(TIMING_WINDOW_TICKS);
    private boolean errorLogged;
    private BobbyChunkFilter bobbyFilter = BobbyChunkFilter.INACTIVE;

    private static final class CachedContainer {
        final OutlineRim rim;
        final boolean ender;
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
    public void tick(MultiPlayerLevel level, Vec3 cameraPos, OutlineConfig config, CaptureToggles toggles,
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

    private void buildBlockContainers(MultiPlayerLevel level, Vec3 cameraPos, double clamp, OutlineConfig config,
            CaptureToggles toggles, CapturedContainers captured, RecoveredCoverage recovered) {
        int chunkRadius = Mth.ceil(clamp / 16.0);
        int cameraChunkX = Mth.floor(cameraPos.x) >> 4;
        int cameraChunkZ = Mth.floor(cameraPos.z) >> 4;
        ClientChunkCache chunkSource = level.getChunkSource();
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
        ObjectIterator<Long2ObjectMap.Entry<ChunkContainers>> entries = Long2ObjectMaps.fastIterator(chunkCache);
        while (entries.hasNext()) {
            if (entries.next().getValue().lastTouchedTick != tickCounter) {
                entries.remove();
            }
        }
    }

    private void rescan(MultiPlayerLevel level, ClientChunkCache chunkSource, int cx, int cz, long key,
            OutlineConfig config, ChunkContainers entry) {
        entry.containers.clear();
        LevelChunk chunk = chunkSource.method_17044(cx, cz, true, false);
        if (chunk == null) {
            entry.nextRescanTick = tickCounter + 1L;
            return;
        }
        if (bobbyFilter.isBobbyChunk(chunk)) {
            entry.nextRescanTick = tickCounter + RESCAN_PERIOD_TICKS + Math.floorMod(key, RESCAN_PERIOD_TICKS);
            return;
        }
        entry.nextRescanTick = tickCounter + RESCAN_PERIOD_TICKS + Math.floorMod(key, RESCAN_PERIOD_TICKS);
        for (Map.Entry<BlockPos, BlockEntity> blockEntity : chunk.getBlockEntities().entrySet()) {
            cacheContainer(level, blockEntity.getKey(), blockEntity.getValue(), config, entry);
        }
    }

    private void cacheContainer(MultiPlayerLevel level, BlockPos pos, BlockEntity blockEntity, OutlineConfig config,
            ChunkContainers entry) {
        long[] cells;
        AABB box;
        boolean ender = false;
        // Invariant, kept in sync by hand: every type outlined here (and in isContainerEntity and isTradeableMerchant)
        // must have an enabled capture path, or its rim is a to-do the player can never clear.
        if (blockEntity instanceof ChestBlockEntity) {
            BlockState state = level.getBlockState(pos);
            BlockPos partner = doubleChestPartner(level, pos, state);
            if (partner != null) {
                if (pos.asLong() > partner.asLong()) {
                    return;
                }
                cells = new long[] { pos.asLong(), partner.asLong() };
                box = boxOf(level, pos).minmax(boxOf(level, partner));
            } else {
                cells = new long[] { pos.asLong() };
                box = boxOf(level, pos);
            }
        } else if (blockEntity instanceof EnderChestBlockEntity) {
            cells = new long[] { pos.asLong() };
            box = boxOf(level, pos);
            ender = true;
        } else if (blockEntity instanceof BaseContainerBlockEntity) {
            cells = new long[] { pos.asLong() };
            box = boxOf(level, pos);
        } else {
            return;
        }
        OutlineRim rim = new OutlineRim(config.unscannedColor(), box, cells);
        long sectionKey = SectionKey.blockToSection(cells[0]);
        long secondSectionKey = cells.length > 1 ? SectionKey.blockToSection(cells[1]) : sectionKey;
        entry.containers.add(new CachedContainer(rim, ender, blockEntityTypeId(blockEntity), sectionKey,
                secondSectionKey, (box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5,
                (box.minZ + box.maxZ) * 0.5));
    }

    @SuppressWarnings("NullAway") // getKey is non-null for a live block entity's registered type
    private static String blockEntityTypeId(BlockEntity blockEntity) {
        return Registry.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString();
    }

    private void emit(MultiPlayerLevel level, ChunkContainers entry, Vec3 cameraPos, double clamp, OutlineConfig config,
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
            if (container.secondSectionKey != container.sectionKey) {
                drawSet.add(container.secondSectionKey, container.rim);
            }
        }
    }

    private void enumerateEntityContainers(MultiPlayerLevel level, Vec3 cameraPos, double clamp, OutlineConfig config,
            CapturedContainers captured, RecoveredCoverage recovered) {
        AABB clampBox = new AABB(cameraPos.x - clamp, cameraPos.y - clamp, cameraPos.z - clamp, cameraPos.x + clamp,
                cameraPos.y + clamp, cameraPos.z + clamp);
        for (Entity entity : level.getEntitiesOfClass(Entity.class, clampBox,
                candidate -> isContainerEntity(candidate) || isTradeableMerchant(candidate))) {
            if (entity.isInvisible()) {
                continue; // an invisible mob's body does not render, so its rim would reveal it: a fairness leak
            }
            Vec3 center = new Vec3(entity.x, entity.y, entity.z);
            if (!OutlineClamp.isWithin(cameraPos.x, cameraPos.y, cameraPos.z, center.x, center.y, center.z, clamp)) {
                continue;
            }
            OutlineClass classification = OutlineClassifier.classify(NO_CELLS, null, entity.getUUID(), false,
                    captured, recovered);
            if (classification == OutlineClass.CAPTURED) {
                continue;
            }
            long sectionKey = SectionKey.asLong(Mth.floor(center.x) >> 4, Mth.floor(center.y) >> 4,
                    Mth.floor(center.z) >> 4);
            OutlineRim rim = new OutlineRim(OutlineClassifier.hueFor(classification, config), rimBox(entity),
                    NO_CELLS);
            rim.face(RimFace.TOP);
            drawSet.add(sectionKey, rim);
        }
    }

    private static AABB rimBox(Entity entity) {
        AABB box = entity.getBoundingBox();
        if (entity instanceof AbstractChestedHorse) {
            return chestedRimBox(box);
        }
        if (entity instanceof Villager) {
            return merchantRimBox(box);
        }
        return box;
    }

    static AABB chestedRimBox(AABB box) {
        return cappedRimBox(box, CHESTED_RIM_HEIGHT_FRACTION);
    }

    /**
     * {@code box} with its top dropped below the villager's head (see {@link #MERCHANT_RIM_HEIGHT_FRACTION}), so a
     * trading-hall roof or a trapdoor at head height does not hide the rim.
     */
    static AABB merchantRimBox(AABB box) {
        return cappedRimBox(box, MERCHANT_RIM_HEIGHT_FRACTION);
    }

    private static AABB cappedRimBox(AABB box, double heightFraction) {
        double top = box.minY + (box.maxY - box.minY) * heightFraction;
        return new AABB(box.minX, box.minY, box.minZ, box.maxX, top, box.maxZ);
    }

    private static boolean isContainerEntity(Entity entity) {
        // Below 1.21 getInventoryColumns is nonzero without a chest, so it cannot substitute for hasChest.
        return entity instanceof AbstractMinecartContainer
                || (entity instanceof AbstractChestedHorse && ((AbstractChestedHorse) entity).hasChest());
    }

    /**
     * A nitwit or baby villager has no trades in ordinary play, so its rim could never clear.
     */
    private static boolean isTradeableMerchant(Entity entity) {
        if (entity instanceof Villager && !((Villager) entity).isBaby()) {
            // Profession is an int on the synced data (method_3115) at this band; every profession trades except nitwit
            // (id 5).
            return ((Villager) entity).method_3115() != 5;
        }
        return false;
    }

    private static @Nullable BlockPos doubleChestPartner(MultiPlayerLevel level, BlockPos pos, BlockState state) {
        if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
        return level.getBlockEntity(partner) instanceof ChestBlockEntity ? partner : null;
    }

    private static AABB boxOf(MultiPlayerLevel level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        AABB local = shape.isEmpty() ? new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) : shape.bounds();
        return local.move(pos);
    }

    private static RimFace faceOf(MultiPlayerLevel level, long[] cells) {
        if (!isSealed(level, cells, Direction.UP)) {
            return RimFace.TOP;
        }
        return RimFace.selectExposed(true,
                isSealed(level, cells, Direction.DOWN),
                isSealed(level, cells, Direction.NORTH),
                isSealed(level, cells, Direction.SOUTH),
                isSealed(level, cells, Direction.WEST),
                isSealed(level, cells, Direction.EAST));
    }

    // A merged-box face is exposed when any cell is open in that direction, so a double chest with one half blocked
    // still rims rather than vanishing (a completeness tool must not hide a reachable container). A neighbor that is
    // itself one of the cells is interior to the merged box, not an exterior face, and is skipped, without which a
    // double chest's two end faces always read open, since each end cell's inward neighbor is the other half.
    private static boolean isSealed(MultiPlayerLevel level, long[] cells, Direction direction) {
        for (long cell : cells) {
            BlockPos cellPos = BlockPos.method_10488(cell);
            int nx = cellPos.getX() + direction.getStepX();
            int ny = cellPos.getY() + direction.getStepY();
            int nz = cellPos.getZ() + direction.getStepZ();
            if (cells.length > 1 && containsCell(cells, new BlockPos(nx, ny, nz).asLong())) {
                continue;
            }
            neighbor.set(nx, ny, nz);
            if (!level.getBlockState(neighbor).method_16907()) {
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
