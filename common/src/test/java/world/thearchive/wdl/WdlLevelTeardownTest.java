// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.CaptureController;
import world.thearchive.wdl.core.CaptureCounts;
import world.thearchive.wdl.core.CaptureState;
import world.thearchive.wdl.core.CaptureToggles;
import world.thearchive.wdl.core.CapturedContainers;
import world.thearchive.wdl.core.RecoveredCoverage;
import world.thearchive.wdl.core.SaveStage;
import world.thearchive.wdl.core.WdlConfig;

/**
 * The band's level-teardown edge, translated by the wiring into the controller's own calls. This loader reports a
 * disconnect on a client tick, which cannot come round until the client has nulled the player the finish reads its
 * inventory, ender chest, advancements, statistics and game mode from, so the teardown is the last edge with a live
 * player and the wiring decides there what the controller is owed.
 */
class WdlLevelTeardownTest {
    /** A session that only counts what the controller asks of it; the save never completes on its own. */
    private static final class CountingSession implements CaptureController.Session {
        int finishes;
        int holds;

        @Override
        public void captureTick() {}

        @Override
        public void holdWriterEncoding() {
            holds++;
        }

        @Override
        public void finish() {
            finishes++;
        }

        @Override
        public boolean isSaveComplete() {
            return false;
        }

        @Override
        public CaptureCounts counts() {
            return CaptureCounts.EMPTY;
        }

        @Override
        public CapturedContainers capturedContainers() {
            return CapturedContainers.EMPTY;
        }

        @Override
        public RecoveredCoverage recoveredCoverage() {
            return RecoveredCoverage.EMPTY;
        }

        @Override
        public CaptureToggles latchedToggles() {
            return CaptureToggles.from(WdlConfig.DEFAULTS);
        }

        @Override
        public SaveStage saveStage() {
            return SaveStage.NONE;
        }

        @Override
        public float saveProgress() {
            return 0;
        }
    }

    /**
     * The level-teardown edge fires on a dimension change too, and there the connection is still open: ending a
     * download the player never stopped is exactly what the edge must not do.
     */
    @Test
    void aTeardownWithTheConnectionOpenDoesNotEndTheDownload() {
        CaptureController controller = new CaptureController();
        CountingSession session = new CountingSession();
        controller.start(() -> session);

        Wdl.onLevelTeardown(controller, false);

        assertEquals(0, session.finishes, "a dimension change flushes nothing");
        assertEquals(0, session.holds, "and holds nothing, this loader reverting no registry there");
        assertEquals(CaptureState.RECORDING, controller.state(), "the download is still running");
    }

    /**
     * The same signal with the connection already gone IS the disconnect, and the flush runs while the player is live.
     */
    @Test
    void aTeardownWithTheConnectionClosedFinishesTheDownload() {
        CaptureController controller = new CaptureController();
        CountingSession session = new CountingSession();
        controller.start(() -> session);

        Wdl.onLevelTeardown(controller, true);

        assertEquals(1, session.finishes, "the download flushes here, while the player is still live");
        assertEquals(0, session.holds, "without a writer hold, this loader reverting no registry on a disconnect");
        assertEquals(CaptureState.SAVING, controller.state(), "the download is draining");
    }

    /**
     * The loader's own disconnect callback still arrives, past the player. It must find the download already finished
     * rather than starting a second one.
     */
    @Test
    void theTickEdgeDisconnectAfterTheClosedTeardownFinishesNothingFurther() {
        CaptureController controller = new CaptureController();
        CountingSession session = new CountingSession();
        controller.start(() -> session);

        Wdl.onLevelTeardown(controller, true);
        controller.tick();
        controller.stop();

        assertEquals(1, session.finishes, "the finish the teardown edge ran is the only one");
    }
}
