// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

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
    private static final Logger LOGGER = LogUtils.getLogger();
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
    private @Nullable ClientLevel lastLevel;
    private long tickCounter;
    private final TimingWindow tickTiming = new TimingWindow(TIMING_WINDOW_TICKS);
    private boolean errorLogged;
    private BobbyChunkFilter bobbyFilter = BobbyChunkFilter.INACTIVE;

    private static final class CachedContainer {
        final OutlineRim rim;
        final boolean ender;
        final boolean bookshelf;
        final String liveTypeId;
        final long sectionKey;
        final long secondSectionKey;
        final double centerX;
        final double centerY;
        final double centerZ;

        CachedContainer(OutlineRim rim, boolean ender, boolean bookshelf, String liveTypeId,
                long sectionKey, long secondSectionKey, double centerX, double centerY, double centerZ) {
            this.rim = rim;
            this.ender = ender;
            this.bookshelf = bookshelf;
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
    public void tick(ClientLevel level, Vec3 cameraPos, OutlineConfig config, CaptureToggles toggles,
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

    private void buildBlockContainers(ClientLevel level, Vec3 cameraPos, double clamp, OutlineConfig config,
            CaptureToggles toggles, CapturedContainers captured, RecoveredCoverage recovered) {
        int chunkRadius = Mth.ceil(clamp / 16.0);
        int cameraChunkX = SectionPos.blockToSectionCoord(Mth.floor(cameraPos.x));
        int cameraChunkZ = SectionPos.blockToSectionCoord(Mth.floor(cameraPos.z));
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

    private void rescan(ClientLevel level, ClientChunkCache chunkSource, int cx, int cz, long key,
            OutlineConfig config, ChunkContainers entry) {
        entry.containers.clear();
        LevelChunk chunk = chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false);
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

    private void cacheContainer(ClientLevel level, BlockPos pos, BlockEntity blockEntity, OutlineConfig config,
            ChunkContainers entry) {
        long[] cells;
        AABB box;
        boolean ender = false;
        boolean bookshelf = false;
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
        } else if (blockEntity instanceof LecternBlockEntity) {
            BlockState state = level.getBlockState(pos);
            if (!state.hasProperty(LecternBlock.HAS_BOOK) || !state.getValue(LecternBlock.HAS_BOOK)) {
                return; // an empty lectern holds nothing capturable, so it is not a candidate
            }
            cells = new long[] { pos.asLong() };
            box = boxOf(level, pos);
        } else if (blockEntity instanceof ChiseledBookShelfBlockEntity) {
            if (BookshelfSlots.occupiedSlotMask(level.getBlockState(pos)) == 0) {
                return;
            }
            cells = new long[] { pos.asLong() };
            box = boxOf(level, pos);
            bookshelf = true;
        } else if (blockEntity instanceof BaseContainerBlockEntity) {
            cells = new long[] { pos.asLong() };
            box = boxOf(level, pos);
        } else {
            return;
        }
        OutlineRim rim = new OutlineRim(config.unscannedColor(), box, cells);
        long sectionKey = SectionPos.blockToSection(cells[0]);
        long secondSectionKey = cells.length > 1 ? SectionPos.blockToSection(cells[1]) : sectionKey;
        entry.containers.add(new CachedContainer(rim, ender, bookshelf, blockEntityTypeId(blockEntity), sectionKey,
                secondSectionKey, (box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5,
                (box.minZ + box.maxZ) * 0.5));
    }

    @SuppressWarnings("NullAway") // getKey is non-null for a live block entity's registered type
    private static String blockEntityTypeId(BlockEntity blockEntity) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString();
    }

    private void emit(ClientLevel level, ChunkContainers entry, Vec3 cameraPos, double clamp, OutlineConfig config,
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
            OutlineClass classification;
            if (container.bookshelf) {
                long posKey = container.rim.cells()[0];
                int occupiedMask = BookshelfSlots.occupiedSlotMask(level.getBlockState(BlockPos.of(posKey)));
                if (occupiedMask == 0) {
                    continue;
                }
                classification = OutlineClassifier.classifyBookshelf(occupiedMask,
                        captured.bookshelfCapturedSlots(posKey), recovered.bookshelfSavedSlots(posKey));
                if (classification == OutlineClass.UNSAVED && !toggles.refreshesHotChunks()) {
                    continue; // with the interaction-capture axis off the rim is an unclearable to-do
                }
            } else {
                classification = OutlineClassifier.classify(container.rim.cells(), container.liveTypeId, null,
                        container.ender, captured, recovered);
            }
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

    private void enumerateEntityContainers(ClientLevel level, Vec3 cameraPos, double clamp, OutlineConfig config,
            CapturedContainers captured, RecoveredCoverage recovered) {
        AABB clampBox = new AABB(cameraPos.x - clamp, cameraPos.y - clamp, cameraPos.z - clamp, cameraPos.x + clamp,
                cameraPos.y + clamp, cameraPos.z + clamp);
        for (Entity entity : level.getEntitiesOfClass(Entity.class, clampBox,
                candidate -> isContainerEntity(candidate) || isTradeableMerchant(candidate))) {
            if (entity.isInvisible()) {
                continue; // an invisible mob's body does not render, so its rim would reveal it: a fairness leak
            }
            Vec3 center = entity.position();
            if (!OutlineClamp.isWithin(cameraPos.x, cameraPos.y, cameraPos.z, center.x, center.y, center.z, clamp)) {
                continue;
            }
            OutlineClass classification = OutlineClassifier.classify(NO_CELLS, null, entity.getUUID(), false,
                    captured, recovered);
            if (classification == OutlineClass.CAPTURED) {
                continue;
            }
            long sectionKey = SectionPos.blockToSection(
                    BlockPos.asLong(Mth.floor(center.x), Mth.floor(center.y), Mth.floor(center.z)));
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
        if (entity instanceof Villager || entity instanceof WanderingTrader) {
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
        return entity instanceof ContainerEntity
                || (entity instanceof AbstractChestedHorse animal && animal.hasChest());
    }

    /**
     * A nitwit, unemployed, or baby villager has no trades in ordinary play, so its rim could never clear.
     */
    private static boolean isTradeableMerchant(Entity entity) {
        if (entity instanceof WanderingTrader) {
            return true;
        }
        if (entity instanceof Villager villager && !villager.isBaby()) {
            VillagerProfession profession = villager.getVillagerData().getProfession();
            return profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT;
        }
        return false;
    }

    private static @Nullable BlockPos doubleChestPartner(ClientLevel level, BlockPos pos, BlockState state) {
        if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
        return level.getBlockEntity(partner) instanceof ChestBlockEntity ? partner : null;
    }

    private static AABB boxOf(ClientLevel level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        AABB local = shape.isEmpty() ? new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) : shape.bounds();
        return local.move(pos);
    }

    private static RimFace faceOf(ClientLevel level, long[] cells) {
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
    private static boolean isSealed(ClientLevel level, long[] cells, Direction direction) {
        for (long cell : cells) {
            int nx = BlockPos.getX(cell) + direction.getStepX();
            int ny = BlockPos.getY(cell) + direction.getStepY();
            int nz = BlockPos.getZ(cell) + direction.getStepZ();
            if (cells.length > 1 && containsCell(cells, BlockPos.asLong(nx, ny, nz))) {
                continue;
            }
            neighbor.set(nx, ny, nz);
            if (!level.getBlockState(neighbor).isCollisionShapeFullBlock(level, neighbor)) {
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
