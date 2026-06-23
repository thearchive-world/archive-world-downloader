// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.adapter.impl.WorldPathsImpl;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * Region-pipeline integration: the real {@link SimpleRegionStorage} path under {@link WorldPaths}, covering multi-chunk
 * + region-boundary placement, the &gt;1 MiB external-{@code .mcc} spill, {@code synchronize(true)}+{@code close()}
 * drain, reopen-and-read-back from a fresh storage, and the read-merge recapture path over an on-disk prior.
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
            return 3;
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
}
