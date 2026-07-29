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
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
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
    private static final Logger LOGGER = LogUtils.getLogger();
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

    private final OutlineDrawSet drawSet = new OutlineDrawSet();
    private final Long2ObjectMap<ChunkContainers> chunkCache = new Long2ObjectOpenHashMap<>();
    private @Nullable ClientLevel lastLevel;
    private long tickCounter;
    private final TimingWindow tickTiming = new TimingWindow(TIMING_WINDOW_TICKS);
    private boolean errorLogged;

    /** The cached geometry of one logical block container: its reused rim, the ender flag, and the cheap keys. */
    private static final class CachedContainer {
        final OutlineRim rim;
        final boolean ender;
        final boolean bookshelf;
        // The live block-entity type id at scan time, compared against the recorded capture type so a
        // same-position block replacement re-rims the stale capture (Gate 2).
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

    /**
     * Rebuild the draw-set for the current camera position. Clears it first, so an off toggle or a zero distance leaves
     * an empty query (no rim drawn). Runs on the client tick, the same thread the render reads on.
     */
    public void tick(ClientLevel level, Vec3 cameraPos, OutlineConfig config, boolean captureContainers,
            boolean captureEntities, boolean recaptureChunks, CapturedContainers captured,
            RecoveredCoverage recovered) {
        try {
            if (!config.renderUnsavedOutline()) {
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
            if (captureContainers) {
                buildBlockContainers(level, cameraPos, clamp, config, recaptureChunks, captured, recovered);
            } else {
                chunkCache.clear();
            }
            if (captureEntities) {
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
            boolean recaptureChunks, CapturedContainers captured, RecoveredCoverage recovered) {
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
                        entry.nextRescanTick = tickCounter; // over budget this tick; due again next tick
                    }
                }
                entry.lastTouchedTick = tickCounter;
                emit(level, entry, cameraPos, clamp, config, recaptureChunks, captured, recovered);
            }
        }
        evictUntouched();
    }

    /** Drop cache entries the camera no longer covers (not visited this tick), bounding the cache to the clamp. */
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
            entry.nextRescanTick = tickCounter + 1L; // not loaded yet; retry next tick so its containers appear
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
        // Invariant, kept in sync by hand: every type outlined here (and in isContainerEntity) must have an
        // enabled capture path, or its rim is a to-do the player can never clear. Contents arrive either on
        // open, bound in LiveCaptureSession.captureOpenContainer, or on interaction, recorded in
        // InteractionCapture. The invariant is one-way; a capturable type need not be outlined. Two deliberate
        // asymmetries: a chiseled bookshelf is outlined here but captured on interaction (it opens no menu and
        // is not a BaseContainerBlockEntity), and its rim is suppressed in emit when interaction capture is off
        // so it never becomes a stuck to-do; a jukebox is captured on interaction but deliberately not outlined
        // (not a BaseContainerBlockEntity, it falls through to the else return below).
        if (blockEntity instanceof ChestBlockEntity) {
            BlockState state = level.getBlockState(pos);
            BlockPos partner = doubleChestPartner(level, pos, state);
            if (partner != null) {
                if (pos.asLong() > partner.asLong()) {
                    return; // the lower-keyed half caches the merged rim, so the double chest is one entry
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
                return; // an empty bookshelf holds nothing capturable, so it is not a candidate (mirrors the lectern)
            }
            cells = new long[] { pos.asLong() };
            box = boxOf(level, pos);
            bookshelf = true;
        } else if (blockEntity instanceof BaseContainerBlockEntity) {
            cells = new long[] { pos.asLong() };
            box = boxOf(level, pos);
        } else {
            return; // not a container whose contents arrive only on open
        }
        // The hue is a placeholder restamped from the live classification each tick; the geometry is what is cached.
        OutlineRim rim = new OutlineRim(config.unscannedColor(), box, cells);
        long sectionKey = SectionPos.blockToSection(cells[0]);
        long secondSectionKey = cells.length > 1 ? SectionPos.blockToSection(cells[1]) : sectionKey;
        entry.containers.add(new CachedContainer(rim, ender, bookshelf, blockEntityTypeId(blockEntity), sectionKey,
                secondSectionKey, (box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5,
                (box.minZ + box.maxZ) * 0.5));
    }

    // The block-entity registry id string, the key the chunk tag writes as "id" (via byNameCodec) and the same
    // one the recorded capture type holds. Kept as a String, never the band-renamed id type (ResourceLocation
    // vs Identifier), so this shared file stays band-portable.
    @SuppressWarnings("NullAway") // getKey is non-null for a live block entity's registered type
    private static String blockEntityTypeId(BlockEntity blockEntity) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString();
    }

    private void emit(ClientLevel level, ChunkContainers entry, Vec3 cameraPos, double clamp, OutlineConfig config,
            boolean recaptureChunks, CapturedContainers captured, RecoveredCoverage recovered) {
        for (int i = 0; i < entry.containers.size(); i++) {
            CachedContainer container = entry.containers.get(i);
            if (!OutlineClamp.isWithin(cameraPos.x, cameraPos.y, cameraPos.z, container.centerX, container.centerY,
                    container.centerZ, clamp)) {
                continue;
            }
            OutlineClass classification;
            if (container.bookshelf) {
                long posKey = container.rim.cells()[0];
                int occupiedMask = BookshelfSlots.occupiedSlotMask(level.getBlockState(BlockPos.of(posKey)));
                if (occupiedMask == 0) {
                    continue; // emptied or broken since the last rescan; nothing to flag, the rescan will evict it
                }
                classification = OutlineClassifier.classifyBookshelf(occupiedMask,
                        captured.bookshelfCapturedSlots(posKey), recovered.bookshelfSavedSlots(posKey));
                if (classification == OutlineClass.UNSAVED && !recaptureChunks) {
                    continue; // with the interaction-capture axis off the red rim is an unclearable to-do
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
        for (Entity entity : level.getEntitiesOfClass(Entity.class, clampBox, OutlineTracker::isContainerEntity)) {
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
            rim.face(RimFace.TOP); // a vehicle in the open has no block neighbors to seal, so it rims its box top
            drawSet.add(sectionKey, rim);
        }
    }

    /** The rim box for a container entity: a chested animal's box is lowered to its chest, a vehicle keeps its own. */
    private static AABB rimBox(Entity entity) {
        AABB box = entity.getBoundingBox();
        return entity instanceof AbstractChestedHorse ? chestedRimBox(box) : box;
    }

    /**
     * {@code box} with its top capped to the chest height (see {@link #CHESTED_RIM_HEIGHT_FRACTION}), footprint kept.
     */
    static AABB chestedRimBox(AABB box) {
        double top = box.minY + (box.maxY - box.minY) * CHESTED_RIM_HEIGHT_FRACTION;
        return new AABB(box.minX, box.minY, box.minZ, box.maxX, top, box.maxZ);
    }

    /** Whether {@code entity} is a container vehicle or a chested animal (the open-time entity-container set). */
    private static boolean isContainerEntity(Entity entity) {
        return entity instanceof ContainerEntity
                || (entity instanceof AbstractChestedHorse animal && animal.getInventoryColumns() > 0);
    }

    /** The connected half of a double chest at {@code pos}, or {@code null} if it is single or not loaded. */
    private static @Nullable BlockPos doubleChestPartner(ClientLevel level, BlockPos pos, BlockState state) {
        if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        BlockPos partner = ChestBlock.getConnectedBlockPos(pos, state);
        return level.getBlockEntity(partner) instanceof ChestBlockEntity ? partner : null;
    }

    /** The container's shape bounds in world coordinates, or its full cell when the shape is empty. */
    private static AABB boxOf(ClientLevel level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        AABB local = shape.isEmpty() ? new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) : shape.bounds();
        return local.move(pos);
    }

    /**
     * The exposed face of a block container's rim, from the collision-shape seal test against each cell's neighbors in
     * the preference order. Computed on the tick so the render reads a stamped face rather than probing the world each
     * frame; the neighbors it seals against change only on the tick.
     */
    private static RimFace faceOf(ClientLevel level, long[] cells) {
        // The open-top chest is the overwhelming case, so settle it on one neighbor read; only a sealed top
        // pays for the rest of the order, which RimFace.selectExposed decides.
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

    // A merged-box face is exposed when any cell is open in that direction, so a double chest with one half
    // blocked still rims rather than vanishing (a completeness tool must not hide a reachable container); the rim
    // then spans both cells and its blocked half clips behind that neighbor. A neighbor that is itself one of
    // the cells is interior to the merged box, not an exterior face, and is skipped, without which a double
    // chest's two end faces always read open, since each end cell's inward neighbor is the other half. Only a
    // multi-cell box has such a neighbor, so a single container skips that test.
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
