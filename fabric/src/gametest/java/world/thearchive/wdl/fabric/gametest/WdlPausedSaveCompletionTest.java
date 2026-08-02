// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.PauseScreen;

import world.thearchive.wdl.client.WdlDownloadsScreen;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Regression guards for the paused-replay strand, where the game tick is suspended but the client still drains its task
 * queue: a completed background save must reach IDLE on the client thread via the marshaled poke even when the
 * controller is never ticked, a nothing-captured finish must complete through its inline poke on the same route, and
 * the pause-menu button must open the download screen on the click rather than through the next-tick deferral. The
 * driver owns its own controller, separate from the production singleton, so withholding the driver's tick isolates the
 * poke as the only route to IDLE.
 */
public class WdlPausedSaveCompletionTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            asyncSaveCompletesWithoutTick(context);
            nothingCapturedCompletesWithoutTick(context);
            pauseButtonOpensOnTheClick(context);
        }
    }

    /** A real recorded capture: the writer completes off-thread and the marshal hands the poke back. */
    private void asyncSaveCompletesWithoutTick(ClientGameTestContext context) {
        DownloadTarget target = new DownloadTarget("wdl-paused-save", "wdl-paused-save", DownloadMode.NEW);
        CaptureDriver driver = CaptureDriver.start(context, target, WdlConfig.DEFAULTS);
        driver.tick(40); // record real chunks so finish() takes the async writer path, not an inline outcome
        Path saveRoot = driver.stopAndAwaitSaveWithoutTick();
        Check.that(Files.isDirectory(saveRoot.resolve("region")),
                "the paused-save capture did not write a region folder at " + saveRoot);
        assertPokeRanOnTheClientThread(context, driver, "the async save completion poke");
    }

    /**
     * A capture stopped with zero recorded ticks: no chunk is captured, so finish() short-circuits before it builds a
     * writer and runs the poke inline. Without that inline poke the controller has no route out of SAVING at all, since
     * this driver is never ticked.
     */
    private void nothingCapturedCompletesWithoutTick(ClientGameTestContext context) {
        DownloadTarget target = new DownloadTarget("wdl-paused-nothing", "wdl-paused-nothing", DownloadMode.NEW);
        CaptureDriver driver = CaptureDriver.start(context, target, WdlConfig.DEFAULTS);
        Path saveRoot = driver.stopAndAwaitSaveWithoutTick();
        Check.that(!Files.isDirectory(saveRoot.resolve("region")),
                "the zero-tick capture wrote a region folder, so it took the writer path instead of the "
                        + "nothing-captured finish this case covers: " + saveRoot);
        assertPokeRanOnTheClientThread(context, driver, "the nothing-captured completion poke");
    }

    /**
     * The pause-menu button must open the download screen during the click, not arm the next-tick deferral, since a
     * suspended tick would never consume it. The harness runs no client tick between two consecutive client hand-offs,
     * so the screen being live on the call right after the click is exactly the inline-open claim: a deferred open
     * would still be sitting unconsumed.
     */
    private void pauseButtonOpensOnTheClick(ClientGameTestContext context) {
        context.setScreen(() -> new PauseScreen(true));
        context.waitForScreen(PauseScreen.class);
        context.clickScreenButton("wdl.screen.downloads.open");
        Check.that(context.computeOnClient(client -> client.screen instanceof WdlDownloadsScreen),
                "the pause-menu button must open the download screen on the click, with no client tick in "
                        + "between, so a suspended game tick cannot strand it");
        context.setScreen(() -> null);
    }

    /**
     * The completion must land on the client thread, not merely produce the idle state: the state alone is
     * thread-blind, so on the async case an executor that ran the poke on the writer's background thread would still
     * pass every state assertion. On the nothing-captured case the poke runs inline from the driver's own client
     * hand-off, so the thread identity holds by construction and this asserts nothing beyond it; it stays there only so
     * both cases record which thread the poke actually reached.
     */
    private void assertPokeRanOnTheClientThread(ClientGameTestContext context, CaptureDriver driver, String what) {
        Check.that(context.computeOnClient(client -> driver.completionPokeThread() == Thread.currentThread()),
                what + " must be marshaled onto the client thread, saw " + driver.completionPokeThread());
    }
}
