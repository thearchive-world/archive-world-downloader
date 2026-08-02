// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.stats.Stats;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Stats and advancements axis: grant an advancement, drive a real capture, and assert both JSON surfaces reach disk
 * with real values. Proves the one thing headless tests cannot: the async stats request is genuinely answered and the
 * server's values land on disk.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlStatsAdvancementsCaptureTest implements FabricClientGameTest {
    private static final String GRANTED = "minecraft:story/root";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();
            server.runCommand("advancement grant @a only " + GRANTED);
            UUID uuid = context.computeOnClient(client -> client.player.getUUID());

            // Handle form: the first driver.tick() pumps the controller, which sends REQUEST_STATS (start() does
            // not pump). Then wait for the async reply via the public StatsCounter getter (no isEmpty() exists).
            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-progress", "wdl-progress", DownloadMode.NEW), WdlConfig.DEFAULTS);
            driver.tick(1);
            context.waitFor(client -> client.player.getStats()
                    .getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) > 0);
            Path saveRoot = driver.tick(5).stopAndAwaitSave();

            JsonObject advancements = CaptureReadback.advancementsJson(saveRoot, uuid);
            Check.that(advancements.has(GRANTED), "captured advancements lack " + GRANTED + ": " + advancements);
            JsonObject entry = advancements.getAsJsonObject(GRANTED);
            Check.that(entry.get("done").getAsBoolean(), GRANTED + " was not captured as done");
            JsonObject criteria = entry.getAsJsonObject("criteria");
            Check.that(!criteria.keySet().isEmpty(), GRANTED + " captured no criteria: " + entry);
            String obtained = criteria.get(criteria.keySet().iterator().next()).getAsString();
            Check.that(obtained.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4}"),
                    "criterion timestamp is not 'yyyy-MM-dd HH:mm:ss Z': " + obtained);

            JsonObject stats = CaptureReadback.statsJson(saveRoot, uuid).getAsJsonObject("stats");
            Check.that(stats.has("minecraft:custom")
                    && stats.getAsJsonObject("minecraft:custom").has("minecraft:play_time")
                    && stats.getAsJsonObject("minecraft:custom").get("minecraft:play_time").getAsInt() > 0,
                    "captured stats lack a positive play_time: " + stats);

            Properties offProperties = new Properties();
            offProperties.setProperty("captureAdvancements", "false");
            offProperties.setProperty("captureStatistics", "false");
            WdlConfig off = WdlConfig.parse(offProperties);
            Path offRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-progress-off", "wdl-progress-off", DownloadMode.NEW), off, 6);
            UUID offUuid = context.computeOnClient(client -> client.player.getUUID());
            Check.that(!CaptureReadback.progressFileExists(offRoot, "advancements", offUuid),
                    "captureAdvancements=false still wrote an advancements file");
            Check.that(!CaptureReadback.progressFileExists(offRoot, "stats", offUuid),
                    "captureStatistics=false still wrote a stats file");
        }
    }
}
