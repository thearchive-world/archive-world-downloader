// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPos;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.IOWorker;
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

class RegionIntegrationTest {
    private final ChunkCodec codec = new ChunkCodecImpl();
    private final ContainerSink containerSink = new ContainerSinkImpl();
    private final LecternSink lecternSink = new LecternSinkImpl();

    private static IOWorker storage(WorldPaths paths, Path regionDirectory) {
        return paths.openRegionStorage(Level.OVERWORLD);
    }

    @Test
    void worldPathsPreCreatesDirectoriesAndOpensRegionStorage(@TempDir Path save) throws IOException {
        WorldPaths paths = new WorldPathsImpl(save);

        Path region = paths.regionDirectory(Level.OVERWORLD);
        Path entities = paths.entitiesDirectory(Level.OVERWORLD);

        assertTrue(Files.isDirectory(region), "region/ must be pre-created before any write");
        assertTrue(Files.isDirectory(entities), "entities/ must be pre-created");
        assertEquals(save.resolve("region"), region, "overworld region/ is at the save root");
        try (IOWorker opened = paths.openRegionStorage(Level.OVERWORLD)) {
            assertNotNull(opened, "the overworld region storage must open");
        }
    }

    @Test
    void multiChunkAndRegionBoundaryRoundTrip(@TempDir Path save) {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        WorldPaths paths = new WorldPathsImpl(save);
        Path region = paths.regionDirectory(Level.OVERWORLD);

        List<ChunkPos> positions = List.of(
                new ChunkPos(0, 0), new ChunkPos(31, 31), new ChunkPos(32, 0),
                new ChunkPos(0, 32), new ChunkPos(-1, -1));

        try (IOWorker out = storage(paths, region)) {
            for (int i = 0; i < positions.size(); i++) {
                CompoundTag tag = codec.encode(SyntheticChunks.full(registries, true), registries, false);
                tag.putInt("wdlTestId", i);
                out.store(positions.get(i), tag).join();
            }
            out.synchronize(true).join();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try (IOWorker in = storage(paths, region)) {
            for (int i = 0; i < positions.size(); i++) {
                CompoundTag back = in.loadAsync(positions.get(i)).join()
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
    void oversizeChunkSpillsToExternalMccFile(@TempDir Path save) throws IOException {
        WorldPaths paths = new WorldPathsImpl(save);
        Path region = paths.regionDirectory(Level.OVERWORLD);
        ChunkPos pos = new ChunkPos(5, 5);

        // RegionFile zlib-compresses chunks, so all-zero bytes would never spill.
        byte[] pad = new byte[2 * 1024 * 1024];
        new Random(0xC0FFEEL).nextBytes(pad);
        CompoundTag big = new CompoundTag();
        big.putByteArray("pad", pad);

        try (IOWorker out = storage(paths, region)) {
            out.store(pos, big).join();
            out.synchronize(true).join();
        }

        try (Stream<Path> files = Files.list(region)) {
            assertTrue(
                    files.anyMatch(path -> path.getFileName().toString().matches("c\\.-?\\d+\\.-?\\d+\\.mcc")),
                    "a >1 MiB chunk must spill to an external c.x.z.mcc file");
        }
        try (IOWorker in = storage(paths, region)) {
            CompoundTag back = in.loadAsync(pos).join().orElseThrow(() -> new AssertionError("oversize chunk lost"));
            assertEquals(2 * 1024 * 1024, back.getByteArray("pad").length);
        }
    }

    @Test
    void writeMergingReadsThePriorChunkAndReportsNewVersusRecaptured(@TempDir Path save) throws IOException {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        WorldPaths paths = new WorldPathsImpl(save);
        Path region = paths.regionDirectory(Level.OVERWORLD);
        int[] mergeCalls = { 0 };
        RegionChunkWriter.ChunkReadMerge merge = (onDisk, fresh) -> {
            mergeCalls[0]++;
            return 3;
        };

        try (IOWorker out = storage(paths, region)) {
            RegionChunkWriter.MergeWriteResult first = RegionChunkWriter.writeMerging(out, new ChunkPos(0, 0),
                    codec.encode(SyntheticChunks.full(registries, true), registries, false), merge);
            assertEquals(RegionChunkWriter.MergeOutcome.WRITTEN_NEW, first.outcome());
            assertEquals(0, mergeCalls[0], "no prior, so nothing to merge");
            out.synchronize(true).join();

            RegionChunkWriter.MergeWriteResult second = RegionChunkWriter.writeMerging(out, new ChunkPos(0, 0),
                    codec.encode(SyntheticChunks.full(registries, true), registries, false), merge);
            assertEquals(RegionChunkWriter.MergeOutcome.WRITTEN_RECAPTURED, second.outcome());
            assertEquals(1, mergeCalls[0], "the on-disk prior was read and merged");
            assertEquals(3, second.mergeBacks());

            assertEquals(RegionChunkWriter.MergeOutcome.NOTHING_TO_WRITE,
                    RegionChunkWriter.writeMerging(out, new ChunkPos(1, 0), null, merge).outcome());
        }
    }

    @Test
    void aSecondBookshelfInsertDoesNotEraseTheBooksAlreadyInTheRegionFile(@TempDir Path save) throws IOException {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        WorldPaths paths = new WorldPathsImpl(save);
        Path region = paths.regionDirectory(Level.OVERWORLD);
        ChunkPos pos = new ChunkPos(0, 0);

        try (IOWorker out = storage(paths, region)) {
            // The second capture names only the slot it saw, because a bookshelf's contents never reach the client.
            RegionChunkWriter.writeMerging(out, pos, bookshelfChunk(registries, 0, 1, 2), ChunkMerge::merge);
            out.synchronize(true).join();
            RegionChunkWriter.writeMerging(out, pos, bookshelfChunk(registries, 3), ChunkMerge::merge);
            out.synchronize(true).join();
        }

        try (IOWorker in = storage(paths, region)) {
            CompoundTag back = in.loadAsync(pos).join().orElseThrow(() -> new AssertionError("chunk not on disk"));
            assertEquals(Set.of(0, 1, 2, 3), occupiedSlots(findByPos(back, 4, 64, 9)),
                    "the books written by the earlier flush are still in the region file beside the newer one");
        }
    }

    /**
     * A chest's contents exist on the client only while its menu is open, so a re-walk re-captures the terrain and sees
     * an empty chest.
     */
    @Test
    void aReWalkedChestKeepsWhatAnEarlierFlushWroteIntoTheRegionFile(@TempDir Path save) throws IOException {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        WorldPaths paths = new WorldPathsImpl(save);
        Path region = paths.regionDirectory(Level.OVERWORLD);
        ChunkPos pos = new ChunkPos(0, 0);
        BlockPos chestPos = new BlockPos(4, 64, 9);

        try (IOWorker out = storage(paths, region)) {
            ChunkSnapshotSource captured = chestSnapshot(registries, chestPos, "minecraft:diamond");
            RegionChunkWriter.writeMerging(out, pos, codec.encode(captured, registries, false),
                    ChunkFlushPlan.readMerge(captured, List.of(), LongSet.of()));
            out.synchronize(true).join();

            ChunkSnapshotSource reWalked = chestSnapshot(registries, chestPos);
            RegionChunkWriter.writeMerging(out, pos, codec.encode(reWalked, registries, false),
                    ChunkFlushPlan.readMerge(reWalked, List.of(), LongSet.of()));
            out.synchronize(true).join();
        }

        try (IOWorker in = storage(paths, region)) {
            CompoundTag back = in.loadAsync(pos).join().orElseThrow(() -> new AssertionError("chunk not on disk"));
            assertEquals(1, findByPos(back, 4, 64, 9).getList("Items", Tag.TAG_COMPOUND).size(),
                    "a chunk the player walked past again must not lose the chest an earlier flush archived");
        }
    }

    /**
     * Both fresh sides carry the same present-but-empty {@code "Items"}, and only the open-time positions the flush
     * derives separate a container this write looked inside from one it merely re-captured.
     */
    @Test
    void aChestOpenedAndFoundEmptyIsWrittenEmptyIntoTheRegionFile(@TempDir Path save) throws IOException {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        WorldPaths paths = new WorldPathsImpl(save);
        Path region = paths.regionDirectory(Level.OVERWORLD);
        ChunkPos pos = new ChunkPos(0, 0);
        BlockPos chestPos = new BlockPos(4, 64, 9);

        try (IOWorker out = storage(paths, region)) {
            ChunkSnapshotSource captured = chestSnapshot(registries, chestPos, "minecraft:diamond");
            RegionChunkWriter.writeMerging(out, pos, codec.encode(captured, registries, false),
                    ChunkFlushPlan.readMerge(captured, List.of(), LongSet.of()));
            out.synchronize(true).join();

            ChunkSnapshotSource emptied = chestSnapshot(registries, chestPos);
            Map<BlockPos, CompoundTag> containers = new LinkedHashMap<>();
            containers.put(chestPos, BlockEntityFixtures.emptyContainerHolder(27, "minecraft:chest"));
            List<BlockPos> landing = ChunkFlushPlan.landingHolderPositions(emptied, containers);
            CompoundTag fresh = codec.encode(emptied, registries, false);
            MergeTally folded = ChunkFlushPlan.foldChunkStashes(fresh, pos, containerSink, lecternSink, containers,
                    Map.of(), Map.of());
            assertEquals(1, folded.merged(), "the open-time holder landed, so the fresh side is what the open saw");
            RegionChunkWriter.MergeWriteResult written = RegionChunkWriter.writeMerging(out, pos, fresh,
                    ChunkFlushPlan.readMerge(emptied, landing, LongSet.of()));
            out.synchronize(true).join();

            assertEquals(RegionChunkWriter.MergeOutcome.WRITTEN_RECAPTURED, written.outcome(),
                    "the prior chunk was found on disk, so the merge really ran over it");
            assertEquals(0, written.mergeBacks(), "and carried nothing back into the container it captured");
        }

        try (IOWorker in = storage(paths, region)) {
            CompoundTag back = in.loadAsync(pos).join().orElseThrow(() -> new AssertionError("chunk not on disk"));
            CompoundTag chest = findByPos(back, 4, 64, 9);
            assertTrue(chest.get("Items") instanceof ListTag items && items.isEmpty(),
                    "the chest is left with the present-but-empty list a vanilla chest writes, not with the items "
                            + "the player watched leave");
        }
    }

    private static ChunkSnapshotSource chestSnapshot(RegistryAccess.Frozen registries, BlockPos pos,
            String... itemIds) {
        CompoundTag chest = blockEntity("minecraft:chest", pos.getX(), pos.getY(), pos.getZ());
        chest.put("Items", ItemFixtures.items(itemIds));
        return SyntheticChunks.fullWithBlockEntities(registries, true, List.of(chest));
    }

    private CompoundTag bookshelfChunk(RegistryAccess.Frozen registries, int... slots) {
        CompoundTag tag = codec.encode(SyntheticChunks.full(registries, true), registries, false);
        CompoundTag shelf = blockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, 4, 64, 9);
        String[] books = new String[slots.length];
        Arrays.fill(books, "minecraft:written_book");
        shelf.put("Items", ItemFixtures.itemsAtSlots(slots, books));
        tag.put("block_entities", BlockEntityFixtures.chunkTagWith(shelf).getList("block_entities", Tag.TAG_COMPOUND));
        return tag;
    }

    private static Set<Integer> occupiedSlots(CompoundTag blockEntity) {
        Set<Integer> slots = new TreeSet<>();
        ListTag items = (ListTag) blockEntity.get("Items");
        for (int i = 0; i < items.size(); i++) {
            slots.add(
                    (int) (((CompoundTag) items.get(i)).contains("Slot") ? ((CompoundTag) items.get(i)).getByte("Slot")
                            : (byte) -1));
        }
        return slots;
    }
}
