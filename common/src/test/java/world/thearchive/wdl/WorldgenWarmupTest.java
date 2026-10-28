// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import world.thearchive.wdl.core.WorldType;
import world.thearchive.wdl.testsupport.JulCapture;

class WorldgenWarmupTest {
    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }
    }

    @RegisterExtension
    final JulCapture warnings = JulCapture.of(WorldgenWarmup.class);

    @Test
    void screenOpenSubmitsWarmForGeneratedTerrain() {
        assertEquals(1, submittedAtScreenOpen(WorldType.DEFAULT));
        assertEquals(1, submittedAtScreenOpen(WorldType.FLAT));
    }

    @Test
    void screenOpenSubmitsWarmForVoidToo() {
        assertEquals(1, submittedAtScreenOpen(WorldType.VOID));
    }

    @Test
    void aFailedWarmIsSwallowedAndLoggedOnce() {
        Runnable throwingWarm = () -> {
            throw new IllegalStateException("warmup failed");
        };

        assertDoesNotThrow(() -> WorldgenWarmup.dispatchForScreenOpen(WorldType.DEFAULT, throwingWarm, Runnable::run),
                "a broken warmup never escapes onto the worker");

        LogRecord logged = warnings.drain("worldgen registry warmup failed");
        assertEquals(Level.WARNING, logged.getLevel());
        assertTrue(logged.getThrown() instanceof IllegalStateException, "the cause is carried into the log");
    }

    private int submittedAtScreenOpen(WorldType worldType) {
        RecordingExecutor executor = new RecordingExecutor();
        WorldgenWarmup.dispatchForScreenOpen(worldType, () -> {}, executor);
        return executor.tasks.size();
    }
}
