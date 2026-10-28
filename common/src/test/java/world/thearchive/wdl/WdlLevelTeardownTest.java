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

class WdlLevelTeardownTest {
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
