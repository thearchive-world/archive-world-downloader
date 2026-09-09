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
 * The band's level-teardown edge, translated by the wiring into the controller's own calls. This loader reverts the
 * registries and nulls the player inside the same level teardown, and reports the disconnect on a client tick that
 * cannot come round until both are done, so the teardown is the one edge ahead of the revert and the last with a live
 * player, and the wiring decides there what the controller is owed.
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
     * The level-teardown edge fires on a dimension change too, and there the connection is still open: this loader
     * reverts nothing there, so the edge holds nothing, and ending a download the player never stopped is exactly what
     * it must not do.
     */
    @Test
    void aTeardownWithTheConnectionOpenHoldsNothingAndDoesNotEndTheDownload() {
        CaptureController controller = new CaptureController();
        CountingSession session = new CountingSession();
        controller.start(() -> session);

        Wdl.onLevelTeardown(controller, false);

        assertEquals(0, session.holds, "a dimension change reverts nothing, so nothing is held");
        assertEquals(0, session.finishes, "and flushes nothing, because this edge is not the disconnect");
        assertEquals(CaptureState.RECORDING, controller.state(), "the download is still running");
    }

    /**
     * The same signal with the connection already gone IS the disconnect: the writer is held ahead of the revert the
     * teardown runs, and the download flushes while the player is still live.
     */
    @Test
    void aTeardownWithTheConnectionClosedHoldsTheWriterAndFinishesTheDownload() {
        CaptureController controller = new CaptureController();
        CountingSession session = new CountingSession();
        controller.start(() -> session);

        Wdl.onLevelTeardown(controller, true);

        assertEquals(1, session.holds, "the writer is held ahead of the revert");
        assertEquals(1, session.finishes, "and the download flushes here, while the player is still live");
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
        controller.onDisconnect();

        assertEquals(1, session.finishes, "the finish the teardown edge ran is the only one");
    }
}
