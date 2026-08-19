// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.testsupport.LogCapture;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The one map data write task, shared by the on-sight streaming arm and the finish batch. Pinned here because a task
 * that stops counting its own failures lets a download that lost maps be reported clean: the count feeds the
 * partial-finish predicate that stamps the completion record. Its other half is the voice: a task that logs a loss
 * itself instead of through the voice it is handed takes the stack bound with it, and the caller holding that voice for
 * the whole download is the only thing standing between a bad save folder and a stack per lost map.
 */
class MapWriteTaskTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen(); // sets the game version NbtUtils.addCurrentDataVersion reads
    }

    private static CaptureLossLog lossLog() {
        return new CaptureLossLog(LogManager.getLogger(MapWriteTaskTest.class),
                "failed to write map data {}", "it renders blank in the reopened world");
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

        LiveCaptureSession.mapWriteTask(dataDirectory, "map_7", mapData(), failures, lossLog()).run();

        assertEquals(0, failures.get(), "a successful write counts no failure");
        CompoundTag envelope = NbtIo.readCompressed(dataDirectory.resolve("map_7.dat").toFile());
        assertEquals("minecraft:overworld", envelope.getCompound("data").getString("dimension"),
                "the map reached disk under its key carrying the caller's own tag");
    }

    @Test
    void countsEveryFailedWriteAndNeverThrows(@TempDir Path save) throws Exception {
        // createDirectories throws FileAlreadyExistsException when a regular file sits at the directory path:
        // the deterministic IO failure this task must absorb and count rather than propagate.
        Path dataDirectory = save.resolve("data");
        Files.write(dataDirectory, "not a directory".getBytes(StandardCharsets.UTF_8));
        AtomicInteger failures = new AtomicInteger();
        CaptureLossLog loss = lossLog();

        LiveCaptureSession.mapWriteTask(dataDirectory, "map_7", mapData(), failures, loss).run();
        LiveCaptureSession.mapWriteTask(dataDirectory, "map_8", mapData(), failures, loss).run();

        assertEquals(2, failures.get(), "the tally accumulates, so it carries the loss magnitude the chat reports");
    }

    @Test
    void namesEveryLostMapThroughTheVoiceItWasHanded(@TempDir Path save) throws Exception {
        Path dataDirectory = save.resolve("data");
        Files.write(dataDirectory, "not a directory".getBytes(StandardCharsets.UTF_8));
        AtomicInteger failures = new AtomicInteger();
        try (LogCapture captured = LogCapture.attach(MapWriteTaskTest.class.getName())) {
            CaptureLossLog loss = lossLog();

            LiveCaptureSession.mapWriteTask(dataDirectory, "map_7", mapData(), failures, loss).run();
            LiveCaptureSession.mapWriteTask(dataDirectory, "map_8", mapData(), failures, loss).run();

            assertEquals(2, captured.count(),
                    "the task speaks only through the voice it is handed, so a caller can bound the stacks");
            assertEquals("INFO", captured.level(1), "a lost map is a soft failure and logs at INFO");
            assertTrue(captured.rendered(1).contains("map_8"), "each line names the map that particular write lost");
            assertNotNull(captured.thrown(0), "the first loss of a cause type carries its stack");
            assertNull(captured.thrown(1),
                    "the handed voice is what bounds the stacks; a task logging on its own would repeat them");
        }
    }

    @Test
    void countsAnUnexpectedRuntimeFailure(@TempDir Path save) throws Exception {
        // A NUL byte is rejected by the Unix path parser the suite runs on, so resolving the target file name
        // throws InvalidPathException before any directory is created. That is the RuntimeException half of the
        // catch, reached deterministically.
        Path dataDirectory = save.resolve("data");
        AtomicInteger failures = new AtomicInteger();

        LiveCaptureSession.mapWriteTask(dataDirectory, "map_\u0000", mapData(), failures, lossLog()).run();

        assertEquals(1, failures.get(), "a runtime failure is counted, not merely logged");
        if (Files.isDirectory(dataDirectory)) {
            try (Stream<Path> written = Files.list(dataDirectory)) {
                assertFalse(written.findAny().isPresent(), "nothing was written");
            }
        }
    }
}
