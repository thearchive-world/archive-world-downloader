// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The one map data write task, shared by the on-sight streaming arm and the finish batch. Pinned here because a task
 * that stops counting its own failures lets a download that lost maps be reported clean: the count feeds the
 * partial-finish predicate that stamps the completion record.
 */
class MapWriteTaskTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen(); // sets the game version NbtUtils.addCurrentDataVersion reads
    }

    private static CompoundTag mapData() {
        CompoundTag data = new CompoundTag();
        data.putString("dimension", "minecraft:overworld");
        data.putByteArray("colors", new byte[16384]);
        return data;
    }

    @Test
    void writesTheCallersTagAndLeavesTheFailureCountAlone(@TempDir Path save) throws Exception {
        Path dataDirectory = save.resolve("data");
        AtomicInteger failures = new AtomicInteger();

        LiveCaptureSession.mapWriteTask(dataDirectory, "map_7", mapData(), failures).run();

        assertEquals(0, failures.get(), "a successful write counts no failure");
        CompoundTag envelope = NbtIo.readCompressed(dataDirectory.resolve("map_7.dat"),
                NbtAccounter.unlimitedHeap());
        assertEquals("minecraft:overworld", envelope.getCompoundOrEmpty("data").getStringOr("dimension", ""),
                "the map reached disk under its key carrying the caller's own tag");
    }

    @Test
    void countsEveryFailedWriteAndNeverThrows(@TempDir Path save) throws Exception {
        // createDirectories throws FileAlreadyExistsException when a regular file sits at the directory path:
        // the deterministic IO failure this task must absorb and count rather than propagate.
        Path dataDirectory = save.resolve("data");
        Files.writeString(dataDirectory, "not a directory");
        AtomicInteger failures = new AtomicInteger();

        LiveCaptureSession.mapWriteTask(dataDirectory, "map_7", mapData(), failures).run();
        LiveCaptureSession.mapWriteTask(dataDirectory, "map_8", mapData(), failures).run();

        assertEquals(2, failures.get(), "the tally accumulates, so it carries the loss magnitude the chat reports");
    }

    @Test
    void countsAnUnexpectedRuntimeFailure(@TempDir Path save) throws Exception {
        // A NUL byte is rejected by the Unix path parser the suite runs on, so resolving the file name throws
        // InvalidPathException after the directory has already been created. That is the RuntimeException half
        // of the catch, reached deterministically.
        Path dataDirectory = save.resolve("data");
        AtomicInteger failures = new AtomicInteger();

        LiveCaptureSession.mapWriteTask(dataDirectory, "map_\u0000", mapData(), failures).run();

        assertEquals(1, failures.get(), "a runtime failure is counted, not merely logged");
        try (Stream<Path> written = Files.list(dataDirectory)) {
            assertFalse(written.findAny().isPresent(), "nothing was written");
        }
    }
}
