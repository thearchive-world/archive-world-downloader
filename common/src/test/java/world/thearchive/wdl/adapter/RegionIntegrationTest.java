// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPos;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.adapter.impl.WorldPathsImpl;
import world.thearchive.wdl.testsupport.BlockEntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * Region-pipeline integration: the real {@link SimpleRegionStorage} path under {@link WorldPaths}, covering multi-chunk
 * + region-boundary placement, the &gt;1 MiB external-{@code .mcc} spill, {@code synchronize(true)}+{@code close()}
 * drain, reopen-and-read-back from a fresh storage, and the read-merge recapture path over an on-disk prior.
 *
 * <p>The chiseled bookshelf rides here rather than only in {@link ChunkMerge}'s own headless test because the loss it
 * guards is invisible anywhere else: both writes succeed, no counter moves, and the only evidence that the earlier
 * books are gone is the bytes left in the region file.
 */
class RegionIntegrationTest {
    private final ChunkCodec codec = new ChunkCodecImpl();

    private static SimpleRegionStorage storage(WorldPaths paths, Path regionDirectory) {
        return new SimpleRegionStorage(
                paths.regionStorageInfo(Level.OVERWORLD), regionDirectory,
                DataFixers.getDataFixer(), false, DataFixTypes.CHUNK);
    }

    @Test
    void worldPathsPreCreatesDirectoriesAndSuppliesStorageInfo(@TempDir Path save) {
        WorldPaths paths = new WorldPathsImpl(save);

        Path region = paths.regionDirectory(Level.OVERWORLD);
        Path entities = paths.entitiesDirectory(Level.OVERWORLD);

        assertTrue(Files.isDirectory(region), "region/ must be pre-created before any write");
        assertTrue(Files.isDirectory(entities), "entities/ must be pre-created");
        assertEquals(save.resolve("region"), region, "overworld region/ is at the save root");
        assertEquals(Level.OVERWORLD, paths.regionStorageInfo(Level.OVERWORLD).dimension());
    }

    @Test
    void multiChunkAndRegionBoundaryRoundTrip(@TempDir Path save) {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        WorldPaths paths = new WorldPathsImpl(save);
        Path region = paths.regionDirectory(Level.OVERWORLD);

        // (0,0)+(31,31) share region 0,0; the others cross boundaries into r.1.0 / r.0.1 / r.-1.-1.
        List<ChunkPos> positions = List.of(
                new ChunkPos(0, 0), new ChunkPos(31, 31), new ChunkPos(32, 0),
                new ChunkPos(0, 32), new ChunkPos(-1, -1));

        try (SimpleRegionStorage out = storage(paths, region)) {
            for (int i = 0; i < positions.size(); i++) {
                CompoundTag tag = codec.encode(SyntheticChunks.full(registries, true), registries, false);
                tag.putInt("wdlTestId", i); // distinct marker -> proves each chunk lands at its own pos
                out.write(positions.get(i), tag).join();
            }
            out.synchronize(true).join();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Reopen with a FRESH storage and read every chunk back at its position.
        try (SimpleRegionStorage in = storage(paths, region)) {
            for (int i = 0; i < positions.size(); i++) {
                CompoundTag back = in.read(positions.get(i)).join()
                        .orElseThrow(() -> new AssertionError("missing chunk"));
                assertEquals(i, back.getIntOr("wdlTestId", -1), "wrong chunk read back at " + positions.get(i));
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

        // Incompressible payload: RegionFile zlib-compresses chunks, so all-zero bytes would shrink to
        // nothing and never spill. Fixed-seed random bytes stay ~2 MiB compressed -> external-file branch.
        byte[] pad = new byte[2 * 1024 * 1024];
        new Random(0xC0FFEEL).nextBytes(pad);
        CompoundTag big = new CompoundTag();
        big.putByteArray("pad", pad);

        try (SimpleRegionStorage out = storage(paths, region)) {
            out.write(pos, big).join();
            out.synchronize(true).join();
        }

        try (Stream<Path> files = Files.list(region)) {
            assertTrue(
                    files.anyMatch(path -> path.getFileName().toString().matches("c\\.-?\\d+\\.-?\\d+\\.mcc")),
                    "a >1 MiB chunk must spill to an external c.x.z.mcc file");
        }
        try (SimpleRegionStorage in = storage(paths, region)) {
            CompoundTag back = in.read(pos).join().orElseThrow(() -> new AssertionError("oversize chunk lost"));
            assertEquals(2 * 1024 * 1024, back.getByteArray("pad").orElseThrow().length);
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
            return 3; // a stand-in carry-forward count; ChunkMerge's own logic is tested headlessly
        };

        try (SimpleRegionStorage out = storage(paths, region)) {
            // No prior on disk: a plain new write, the merge is never invoked.
            RegionChunkWriter.MergeWriteResult first = RegionChunkWriter.writeMerging(out, new ChunkPos(0, 0),
                    codec.encode(SyntheticChunks.full(registries, true), registries, false), merge);
            assertEquals(RegionChunkWriter.MergeOutcome.WRITTEN_NEW, first.outcome());
            assertEquals(0, mergeCalls[0], "no prior, so nothing to merge");
            out.synchronize(true).join();

            // A prior now exists: the read finds it, the merge runs, the merge-back count is surfaced.
            RegionChunkWriter.MergeWriteResult second = RegionChunkWriter.writeMerging(out, new ChunkPos(0, 0),
                    codec.encode(SyntheticChunks.full(registries, true), registries, false), merge);
            assertEquals(RegionChunkWriter.MergeOutcome.WRITTEN_RECAPTURED, second.outcome());
            assertEquals(1, mergeCalls[0], "the on-disk prior was read and merged");
            assertEquals(3, second.mergeBacks());

            // A null tag has nothing to write, which is not a loss and is counted as none.
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

        try (SimpleRegionStorage out = storage(paths, region)) {
            // Three books go in, the chunk flushes; later one more book goes in and the chunk flushes again. The
            // second capture names only the slot it saw, because a bookshelf's contents never reach the client.
            RegionChunkWriter.writeMerging(out, pos, bookshelfChunk(registries, 0, 1, 2), ChunkMerge::merge);
            out.synchronize(true).join();
            RegionChunkWriter.writeMerging(out, pos, bookshelfChunk(registries, 3), ChunkMerge::merge);
            out.synchronize(true).join();
        }

        try (SimpleRegionStorage in = storage(paths, region)) {
            CompoundTag back = in.read(pos).join().orElseThrow(() -> new AssertionError("chunk not on disk"));
            assertEquals(Set.of(0, 1, 2, 3), occupiedSlots(findByPos(back, 4, 64, 9)),
                    "the books written by the earlier flush are still in the region file beside the newer one");
        }
    }

    /**
     * The un-opened half of the container carry-forward, driven through the composed read-merge the flush actually
     * builds. A chest's contents exist on the client only while its menu is open, so a re-walk re-captures the terrain
     * and sees an empty chest; nothing but the region file says whether the earlier flush's items survived that write.
     */
    @Test
    void aReWalkedChestKeepsWhatAnEarlierFlushWroteIntoTheRegionFile(@TempDir Path save) throws IOException {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        WorldPaths paths = new WorldPathsImpl(save);
        Path region = paths.regionDirectory(Level.OVERWORLD);
        ChunkPos pos = new ChunkPos(0, 0);
        BlockPos chestPos = new BlockPos(4, 64, 9);

        try (SimpleRegionStorage out = storage(paths, region)) {
            ChunkSnapshotSource captured = chestSnapshot(registries, chestPos, "minecraft:diamond");
            RegionChunkWriter.writeMerging(out, pos, codec.encode(captured, registries, false),
                    ChunkFlushPlan.readMerge(captured, List.of(), LongSet.of()));
            out.synchronize(true).join();

            ChunkSnapshotSource reWalked = chestSnapshot(registries, chestPos);
            RegionChunkWriter.writeMerging(out, pos, codec.encode(reWalked, registries, false),
                    ChunkFlushPlan.readMerge(reWalked, List.of(), LongSet.of()));
            out.synchronize(true).join();
        }

        try (SimpleRegionStorage in = storage(paths, region)) {
            CompoundTag back = in.read(pos).join().orElseThrow(() -> new AssertionError("chunk not on disk"));
            assertEquals(1, findByPos(back, 4, 64, 9).getListOrEmpty("Items").size(),
                    "a chunk the player walked past again must not lose the chest an earlier flush archived");
        }
    }

    /** A captured chunk snapshot whose one chest at {@code pos} holds each named item. */
    private static ChunkSnapshotSource chestSnapshot(RegistryAccess.Frozen registries, BlockPos pos,
            String... itemIds) {
        CompoundTag chest = blockEntity("minecraft:chest", pos.getX(), pos.getY(), pos.getZ());
        chest.put("Items", ItemFixtures.items(itemIds));
        return SyntheticChunks.fullWithBlockEntities(registries, true, List.of(chest));
    }

    /** A captured chunk whose one chiseled bookshelf holds a book at each named slot. */
    private CompoundTag bookshelfChunk(RegistryAccess.Frozen registries, int... slots) {
        CompoundTag tag = codec.encode(SyntheticChunks.full(registries, true), registries, false);
        CompoundTag shelf = blockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, 4, 64, 9);
        String[] books = new String[slots.length];
        Arrays.fill(books, "minecraft:written_book");
        shelf.put("Items", ItemFixtures.itemsAtSlots(slots, books));
        tag.put("block_entities", BlockEntityFixtures.chunkTagWith(shelf).getListOrEmpty("block_entities"));
        return tag;
    }

    private static Set<Integer> occupiedSlots(CompoundTag blockEntity) {
        Set<Integer> slots = new TreeSet<>();
        ListTag items = (ListTag) blockEntity.get("Items");
        for (int i = 0; i < items.size(); i++) {
            slots.add((int) ((CompoundTag) items.get(i)).getByteOr("Slot", (byte) -1));
        }
        return slots;
    }
}
