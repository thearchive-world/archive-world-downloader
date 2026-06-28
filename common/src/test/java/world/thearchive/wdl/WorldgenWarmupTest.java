// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.WorldType;

/**
 * The pre-download warmup dispatch: the gate submits a warmup only when the chosen generator needs worldgen (and, on
 * the auto path, only when auto-download is on), and the submitted worker body swallows a failed warmup after logging
 * it once, so a broken reconstruction never escapes onto the daemon and never poisons the download.
 */
class WorldgenWarmupTest {
    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }
    }

    private final List<LogRecord> logged = new ArrayList<>();
    private final Handler capturing = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };

    @Nullable
    private Logger attachedTo;

    @AfterEach
    void detachHandler() {
        if (attachedTo != null) {
            attachedTo.removeHandler(capturing);
        }
    }

    @Test
    void screenOpenSubmitsWarmForGeneratedTerrain() {
        assertEquals(1, submittedAtScreenOpen(WorldType.DEFAULT), "DEFAULT reconstructs worldgen, so it warms");
        assertEquals(1, submittedAtScreenOpen(WorldType.FLAT), "FLAT reconstructs worldgen, so it warms");
    }

    @Test
    void screenOpenSubmitsNothingForVoid() {
        assertEquals(0, submittedAtScreenOpen(WorldType.VOID), "VOID uses the synced registries, so no warmup");
    }

    @Test
    void aFailedWarmIsSwallowedAndLoggedOnce() {
        attachCapture();
        Runnable throwingWarm = () -> {
            throw new IllegalStateException("reconstruction failed");
        };

        assertDoesNotThrow(() -> WorldgenWarmup.dispatchForScreenOpen(WorldType.DEFAULT, throwingWarm, Runnable::run),
                "a broken warmup never escapes onto the worker");

        assertEquals(1, logged.size(), "the failure is logged exactly once");
        assertEquals(Level.WARNING, logged.get(0).getLevel());
        assertTrue(logged.get(0).getThrown() instanceof IllegalStateException, "the cause is carried into the log");
    }

    private int submittedAtScreenOpen(WorldType worldType) {
        RecordingExecutor executor = new RecordingExecutor();
        WorldgenWarmup.dispatchForScreenOpen(worldType, () -> {}, executor);
        return executor.tasks.size();
    }

    private void attachCapture() {
        capturing.setLevel(Level.ALL);
        Logger logger = Logger.getLogger("world.thearchive.wdl.WorldgenWarmup");
        logger.setLevel(Level.ALL);
        logger.addHandler(capturing);
        attachedTo = logger;
    }
}
