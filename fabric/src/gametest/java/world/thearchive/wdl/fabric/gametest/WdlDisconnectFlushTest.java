// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.report.DownloadReportLog;
import world.thearchive.wdl.core.report.DownloadSession;

/**
 * Disconnect axis: a download still recording when the connection ends must not report itself finished while missing
 * the player. The finish reads the player from the live client, and a connection the player did not close tears that
 * client down alongside the finish, so the read can lose the race; what must never happen is losing it silently.
 *
 * <p>Do not strengthen this to require the record itself. Whether the read wins depends on how far the client's
 * teardown has already run when the loader delivers the event, so asserting it goes red on a slow machine while the mod
 * behaved exactly as designed.
 *
 * <p>Do not replace the fixture's own close with a hand-called auto-stop: this arm is the one where the event can
 * arrive off the client thread, and hand-calling it exercises the safe arm while looking like it covered this one.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlDisconnectFlushTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        CaptureDriver run;
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            fixture.clientWorld().waitForChunksDownload();
            run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-disconnect-flush", "wdl-disconnect-flush", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            run.tick(20);
            // Armed after the capture ticks so the download has chunks: one that captured nothing exits the
            // finish before the player is ever read, and would assert nothing.
            run.armDisconnectAutoStop();
        }
        Path saveRoot = run.awaitSaveAfterDisconnect();

        boolean playerRecorded = hasPlayerRecord(saveRoot);
        DownloadSession recorded = completionRecord(saveRoot);
        Check.that(playerRecorded || !recorded.isClean(),
                "the download flushed at disconnect kept no player record and still recorded a clean finish, so "
                        + "the save opens at its default spawn with none of the player state and nothing tells "
                        + "the user to resume it");
        Check.that(playerRecorded || recorded.losses().containsKey("player_records"),
                "the record reports partial without naming the player as what it lost, so the report cannot tell "
                        + "the user what to come back for: " + recorded.losses());
    }

    /** Whether the save carries the local player, in this band's own home for it. */
    private static boolean hasPlayerRecord(Path saveRoot) {
        return !CaptureReadback.levelData(saveRoot).getCompoundOrEmpty("Player").isEmpty();
    }

    /** The completion record this finish wrote, read through the production reader. */
    private static DownloadSession completionRecord(Path saveRoot) {
        List<DownloadSession> downloads;
        try {
            downloads = DownloadReportLog.readDownloads(saveRoot);
        } catch (IOException e) {
            throw new RuntimeException("failed reading the download record under " + saveRoot, e);
        }
        Check.that(!downloads.isEmpty(), "the disconnect flush wrote no download record at all under " + saveRoot);
        DownloadSession recorded = downloads.get(downloads.size() - 1);
        Check.that(recorded.isComplete(),
                "the disconnect flush left its record interrupted rather than completed, so the save reached no "
                        + "terminal state and neither verdict below can be read from it");
        return recorded;
    }
}
