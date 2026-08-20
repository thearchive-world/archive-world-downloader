// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPos;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.adapter.impl.LecternSinkImpl;
import world.thearchive.wdl.adapter.impl.WorldPathsImpl;
import world.thearchive.wdl.testsupport.BlockEntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * Region-pipeline integration: the real {@link WdlRegionStorage} path under {@link WorldPaths}, covering multi-chunk +
 * region-boundary placement, {@code close()} drain, reopen-and-read-back from a fresh storage, and the read-merge
 * recapture path over an on-disk prior.
 */
class RegionIntegrationTest {
    private final ChunkCodec codec = new ChunkCodecImpl();
    private final ContainerSink containerSink = new ContainerSinkImpl();
    private final LecternSink lecternSink = new LecternSinkImpl();

    private static WdlRegionStorage storage(WorldPaths paths, Path regionDirectory) {
        return paths.openRegionStorage(DimensionType.field_18954);
    }

    @Test
    void worldPathsPreCreatesDirectoriesAndOpensRegionStorage(@TempDir Path save) throws IOException {
        WorldPaths paths = new WorldPathsImpl(save);

        Path region = paths.regionDirectory(DimensionType.field_18954);

        assertTrue(Files.isDirectory(region), "region/ must be pre-created before any write");
        assertEquals(save.resolve("region"), region, "overworld region/ is at the save root");
        try (WdlRegionStorage opened = paths.openRegionStorage(DimensionType.field_18954)) {
            assertNotNull(opened, "the overworld region storage must open");
        }
    }

    @Test
    void multiChunkAndRegionBoundaryRoundTrip(@TempDir Path save) {
        TestRegistries.bootstrap();
        WorldPaths paths = new WorldPathsImpl(save);
        Path region = paths.regionDirectory(DimensionType.field_18954);

        // (0,0)+(31,31) share region 0,0; the others cross boundaries into r.1.0 / r.0.1 / r.-1.-1.
        List<ChunkPos> positions = ImmutableList.of(
                new ChunkPos(0, 0), new ChunkPos(31, 31), new ChunkPos(32, 0),
                new ChunkPos(0, 32), new ChunkPos(-1, -1));

        try (WdlRegionStorage out = storage(paths, region)) {
            for (int i = 0; i < positions.size(); i++) {
                CompoundTag tag = codec.encode(SyntheticChunks.full(true), false);
                tag.putInt("wdlTestId", i); // distinct marker -> proves each chunk lands at its own pos
                out.write(positions.get(i), tag);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Reopen with a FRESH storage and read every chunk back at its position.
        try (WdlRegionStorage in = storage(paths, region)) {
            for (int i = 0; i < positions.size(); i++) {
                CompoundTag back = Optional.ofNullable(in.read(positions.get(i)))
                        .orElseThrow(() -> new AssertionError("missing chunk"));
                assertEquals(i, (back.contains("wdlTestId") ? back.getInt("wdlTestId") : -1),
                        "wrong chunk read back at " + positions.get(i));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        assertTrue(Files.exists(region.resolve("r.0.0.mca")), "r.0.0 holds (0,0) and (31,31)");
        assertTrue(Files.exists(region.resolve("r.1.0.mca")), "(32,0) -> region 1,0");
        assertTrue(Files.exists(region.resolve("r.0.1.mca")), "(0,32) -> region 0,1");
        assertTrue(Files.exists(region.resolve("r.-1.-1.mca")), "(-1,-1) -> region -1,-1");
    }

    @Test
    void writeMergingReadsThePriorChunkAndReportsNewVersusRecaptured(@TempDir Path save) throws IOException {
        TestRegistries.bootstrap();
        WorldPaths paths = new WorldPathsImpl(save);
        Path region = paths.regionDirectory(DimensionType.field_18954);
        int[] mergeCalls = { 0 };
        RegionChunkWriter.ChunkReadMerge merge = (onDisk, fresh) -> {
            mergeCalls[0]++;
            return 3; // a stand-in carry-forward count; ChunkMerge's own logic is tested headlessly
        };

        try (WdlRegionStorage out = storage(paths, region)) {
            // No prior on disk: a plain new write, the merge is never invoked.
            RegionChunkWriter.MergeWriteResult first = RegionChunkWriter.writeMerging(out, new ChunkPos(0, 0),
                    codec.encode(SyntheticChunks.full(true), false), merge);
            assertEquals(RegionChunkWriter.MergeOutcome.WRITTEN_NEW, first.outcome());
            assertEquals(0, mergeCalls[0], "no prior, so nothing to merge");

            // A prior now exists: the read finds it, the merge runs, the merge-back count is surfaced.
            RegionChunkWriter.MergeWriteResult second = RegionChunkWriter.writeMerging(out, new ChunkPos(0, 0),
                    codec.encode(SyntheticChunks.full(true), false), merge);
            assertEquals(RegionChunkWriter.MergeOutcome.WRITTEN_RECAPTURED, second.outcome());
            assertEquals(1, mergeCalls[0], "the on-disk prior was read and merged");
            assertEquals(3, second.mergeBacks());

            // A null tag has nothing to write, which is not a loss and is counted as none.
            assertEquals(RegionChunkWriter.MergeOutcome.NOTHING_TO_WRITE,
                    RegionChunkWriter.writeMerging(out, new ChunkPos(1, 0), null, merge).outcome());
        }
    }

    /**
     * The un-opened half of the container carry-forward, driven through the composed read-merge the flush actually
     * builds. A chest's contents exist on the client only while its menu is open, so a re-walk re-captures the terrain
     * and sees an empty chest; nothing but the region file says whether the earlier flush's items survived that write.
     */
    @Test
    void aReWalkedChestKeepsWhatAnEarlierFlushWroteIntoTheRegionFile(@TempDir Path save) throws IOException {
        TestRegistries.bootstrap();
        WorldPaths paths = new WorldPathsImpl(save);
        Path region = paths.regionDirectory(DimensionType.field_18954);
        ChunkPos pos = new ChunkPos(0, 0);
        BlockPos chestPos = new BlockPos(4, 64, 9);

        try (WdlRegionStorage out = storage(paths, region)) {
            ChunkSnapshotSource captured = chestSnapshot(chestPos, "minecraft:diamond");
            RegionChunkWriter.writeMerging(out, pos, codec.encode(captured, false),
                    ChunkFlushPlan.readMerge(captured, ImmutableList.of(), LongSets.EMPTY_SET));

            ChunkSnapshotSource reWalked = chestSnapshot(chestPos);
            RegionChunkWriter.writeMerging(out, pos, codec.encode(reWalked, false),
                    ChunkFlushPlan.readMerge(reWalked, ImmutableList.of(), LongSets.EMPTY_SET));
        }

        try (WdlRegionStorage in = storage(paths, region)) {
            CompoundTag back = Optional.ofNullable(in.read(pos))
                    .orElseThrow(() -> new AssertionError("chunk not on disk"));
            assertEquals(1, findByPos(back, 4, 64, 9).getList("Items", 10).size(),
                    "a chunk the player walked past again must not lose the chest an earlier flush archived");
        }
    }

    /**
     * The opened half, and the reason it cannot be told from a plain re-walk by the tags alone: both fresh sides carry
     * the same present-but-empty {@code "Items"}, and only the open-time positions the flush derives separate a
     * container this write looked inside from one it merely re-captured.
     */
    @Test
    void aChestOpenedAndFoundEmptyIsWrittenEmptyIntoTheRegionFile(@TempDir Path save) throws IOException {
        TestRegistries.bootstrap();
        WorldPaths paths = new WorldPathsImpl(save);
        Path region = paths.regionDirectory(DimensionType.field_18954);
        ChunkPos pos = new ChunkPos(0, 0);
        BlockPos chestPos = new BlockPos(4, 64, 9);

        try (WdlRegionStorage out = storage(paths, region)) {
            ChunkSnapshotSource captured = chestSnapshot(chestPos, "minecraft:diamond");
            RegionChunkWriter.writeMerging(out, pos, codec.encode(captured, false),
                    ChunkFlushPlan.readMerge(captured, ImmutableList.of(), LongSets.EMPTY_SET));

            ChunkSnapshotSource emptied = chestSnapshot(chestPos);
            Map<BlockPos, CompoundTag> containers = new LinkedHashMap<>();
            containers.put(chestPos, BlockEntityFixtures.emptyContainerHolder(27, "minecraft:chest"));
            List<BlockPos> landing = ChunkFlushPlan.landingHolderPositions(emptied, containers);
            CompoundTag fresh = codec.encode(emptied, false);
            MergeTally folded = ChunkFlushPlan.foldChunkStashes(fresh, pos, containerSink, lecternSink, containers,
                    ImmutableMap.of(), ImmutableMap.of());
            assertEquals(1, folded.merged(), "the open-time holder landed, so the fresh side is what the open saw");
            RegionChunkWriter.MergeWriteResult written = RegionChunkWriter.writeMerging(out, pos, fresh,
                    ChunkFlushPlan.readMerge(emptied, landing, LongSets.EMPTY_SET));

            // Without these the case cannot tell the skip firing from the merge never running at all, which is
            // the same green either way.
            assertEquals(RegionChunkWriter.MergeOutcome.WRITTEN_RECAPTURED, written.outcome(),
                    "the prior chunk was found on disk, so the merge really ran over it");
            assertEquals(0, written.mergeBacks(), "and carried nothing back into the container it captured");
        }

        try (WdlRegionStorage in = storage(paths, region)) {
            CompoundTag back = Optional.ofNullable(in.read(pos))
                    .orElseThrow(() -> new AssertionError("chunk not on disk"));
            CompoundTag chest = findByPos(back, 4, 64, 9);
            assertTrue(chest.get("Items") instanceof ListTag && ((ListTag) chest.get("Items")).isEmpty(),
                    "the chest is left with the present-but-empty list a vanilla chest writes, not with the items "
                            + "the player watched leave");
        }
    }

    /** A captured chunk snapshot whose one chest at {@code pos} holds each named item. */
    private static ChunkSnapshotSource chestSnapshot(BlockPos pos,
            String... itemIds) {
        CompoundTag chest = blockEntity("minecraft:chest", pos.getX(), pos.getY(), pos.getZ());
        chest.put("Items", ItemFixtures.items(itemIds));
        return SyntheticChunks.fullWithBlockEntities(true, ImmutableList.of(chest));
    }
}
