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
    void aTeardownWithTheConnectionOpenDoesNotEndTheDownload() {
        CaptureController controller = new CaptureController();
        CountingSession session = new CountingSession();
        controller.start(() -> session);

        Wdl.onLevelTeardown(controller, false);

        assertEquals(0, session.finishes, "a dimension change flushes nothing");
        assertEquals(0, session.holds, "and holds nothing, this loader reverting no registry there");
        assertEquals(CaptureState.RECORDING, controller.state(), "the download is still running");
    }

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
