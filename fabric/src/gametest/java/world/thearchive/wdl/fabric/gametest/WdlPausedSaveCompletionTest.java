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
 * The driver owns its own controller, separate from the production singleton, so withholding the driver's tick isolates
 * the poke as the only route to IDLE.
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

    private void asyncSaveCompletesWithoutTick(ClientGameTestContext context) {
        DownloadTarget target = new DownloadTarget("wdl-paused-save", "wdl-paused-save", DownloadMode.NEW);
        CaptureDriver driver = CaptureDriver.start(context, target, WdlConfig.DEFAULTS);
        driver.tick(40); // record real chunks so finish() takes the async writer path, not an inline outcome
        Path saveRoot = driver.stopAndAwaitSaveWithoutTick();
        Check.that(Files.isDirectory(CaptureReadback.overworldRegionDir(saveRoot)),
                "the paused-save capture did not write a region folder at "
                        + CaptureReadback.overworldRegionDir(saveRoot));
        assertPokeRanOnTheClientThread(context, driver, "the async save completion poke");
    }

    private void nothingCapturedCompletesWithoutTick(ClientGameTestContext context) {
        DownloadTarget target = new DownloadTarget("wdl-paused-nothing", "wdl-paused-nothing", DownloadMode.NEW);
        CaptureDriver driver = CaptureDriver.start(context, target, WdlConfig.DEFAULTS);
        Path saveRoot = driver.stopAndAwaitSaveWithoutTick();
        Check.that(!Files.isDirectory(CaptureReadback.overworldRegionDir(saveRoot)),
                "the zero-tick capture wrote a region folder, so it took the writer path instead of the "
                        + "nothing-captured finish this case covers: " + CaptureReadback.overworldRegionDir(saveRoot));
        assertPokeRanOnTheClientThread(context, driver, "the nothing-captured completion poke");
    }

    /**
     * The harness runs no client tick between two consecutive client hand-offs, so the screen being live on the call
     * right after the click is exactly the inline-open claim: a deferred open would still be sitting unconsumed.
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
     * pass every state assertion.
     */
    private void assertPokeRanOnTheClientThread(ClientGameTestContext context, CaptureDriver driver, String what) {
        Check.that(context.computeOnClient(client -> driver.completionPokeThread() == Thread.currentThread()),
                what + " must be marshaled onto the client thread, saw " + driver.completionPokeThread());
    }
}
