// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import org.apache.logging.log4j.core.LogEvent;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.report.DownloadReportLog;
import world.thearchive.wdl.core.report.DownloadSession;

@SuppressWarnings("UnstableApiUsage")
public class WdlHalfFailedCaptureTest implements FabricClientGameTest {
    private static final String SESSION_LOGGER = "world.thearchive.wdl.adapter.LiveCaptureSession";
    private static final String DROP_LINE = "failed to encode entity";
    /** An entity id the fixture's client never loads, so the leash holder stays unresolved through the encode. */
    private static final int UNLOADED_HOLDER_ID = Integer.MAX_VALUE;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context);
                LogCapture logs = new LogCapture(SESSION_LOGGER)) {
            TestServerContext server = fixture.server();
            GameRuleFixture.set(server, GameRules.RULE_DOMOBSPAWNING, false);
            GameRuleFixture.set(server, GameRules.RULE_DOMOBLOOT, false);

            Path savesDirectory = context.computeOnClient(client -> client.getLevelSource().getBaseDir());
            SaveFixture.reset(savesDirectory, "wdl-half-failed-baseline");
            SaveFixture.reset(savesDirectory, "wdl-half-failed");

            BlockPos spawn = context.computeOnClient(client -> client.player.blockPosition());
            ChunkPos chunk = context.computeOnClient(client -> client.player.chunkPosition());

            summonCow(server, spawn);
            context.waitFor(client -> findCow(client).isPresent());
            Path baseline = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-half-failed-baseline", "wdl-half-failed-baseline", DownloadMode.NEW),
                    WdlConfig.DEFAULTS, 40);
            Check.that(cowCount(baseline, chunk) == 1,
                    "baseline: the intact cow is missing from the save (expected one captured cow, got entities "
                            + capturedEntityIds(baseline, chunk) + ")");
            Check.that(!savedCowHasLeash(baseline, chunk),
                    "baseline: a cow with no leash break was written with a leash tag");
            Check.that(encodeFailures(logs).isEmpty(),
                    "baseline: a clean capture surfaced an entity drop it should not have: " + describe(logs));
            checkRecordedClean(baseline, "baseline");

            server.runCommand("kill @e[type=minecraft:cow]");
            context.waitFor(client -> findCow(client).isEmpty());
            logs.clear();

            summonCow(server, spawn);
            context.waitFor(client -> findCow(client).isPresent());
            breakLeash(context);
            Path halfFailed = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-half-failed", "wdl-half-failed", DownloadMode.NEW), WdlConfig.DEFAULTS, 40);

            Check.that(cowCount(halfFailed, chunk) == 1,
                    "half-failed: the unsavable-leash cow was lost from disk; the sink must strip the leash and "
                            + "save the mob unleashed, not drop it whole. got entities "
                            + capturedEntityIds(halfFailed, chunk));
            Check.that(!savedCowHasLeash(halfFailed, chunk),
                    "half-failed: the saved cow kept a leash tag; the unsavable leash must be stripped, not written");
            Check.that(encodeFailures(logs).isEmpty(),
                    "half-failed: the graceful strip surfaced a drop it should not have (the strip is silent): "
                            + describe(logs));
            checkRecordedClean(halfFailed, "half-failed");
        }
    }

    private static void checkRecordedClean(Path saveRoot, String run) {
        List<DownloadSession> downloads;
        try {
            downloads = DownloadReportLog.readDownloads(saveRoot);
        } catch (IOException e) {
            throw new AssertionError(run + ": the download report under " + saveRoot + " could not be read", e);
        }
        Check.that(downloads.size() == 1,
                run + ": expected exactly one download record under " + saveRoot + ", got " + downloads.size());
        DownloadSession recorded = downloads.get(0);
        Check.that(recorded.isComplete(),
                run + ": the capture finished but left the crash sentinel standing rather than a completion record");
        Check.that(recorded.isClean(),
                run + ": the completion record reads partial, so some capture-loss tally moved on a run that "
                        + "should lose nothing. This assertion covers all of them at once, so read the "
                        + "\"counted capture losses\" line in the log to see which one, rather than assuming "
                        + "it is the entity drop this test is otherwise about");
    }

    private static void summonCow(TestServerContext server, BlockPos at) {
        server.runCommand("summon minecraft:cow " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5)
                + " {NoAI:1b,PersistenceRequired:1b}");
    }

    private static Optional<Entity> findCow(Minecraft client) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(16.0)).stream()
                .filter(entity -> entity.getType() == EntityType.COW)
                .findFirst();
    }

    /**
     * Put the cow into the client state a leash to an unloaded holder produces, the exact call
     * {@code ClientPacketListener.handleEntityLinkPacket} makes.
     */
    private static void breakLeash(ClientGameTestContext context) {
        context.runOnClient(client -> {
            Entity cow = findCow(client)
                    .orElseThrow(() -> new AssertionError("the planted cow is not loaded on the client"));
            ((Leashable) cow).setDelayedLeashHolderId(UNLOADED_HOLDER_ID);
        });
    }

    private static List<String> capturedEntityIds(Path saveRoot, ChunkPos chunk) {
        return CaptureReadback.readEntityChunk(saveRoot, chunk)
                .map(tag -> CaptureReadback.entities(tag).stream()
                        .map(entity -> (entity.contains("id") ? entity.getString("id") : "?"))
                        .toList())
                .orElse(List.of());
    }

    private static long cowCount(Path saveRoot, ChunkPos chunk) {
        return capturedEntityIds(saveRoot, chunk).stream().filter("minecraft:cow"::equals).count();
    }

    private static boolean savedCowHasLeash(Path saveRoot, ChunkPos chunk) {
        return CaptureReadback.readEntityChunk(saveRoot, chunk)
                .map(tag -> CaptureReadback.entities(tag).stream()
                        .filter(entity -> "minecraft:cow".equals(entity.getString("id")))
                        .anyMatch(entity -> entity.contains("leash")))
                .orElse(false);
    }

    private static List<LogEvent> encodeFailures(LogCapture logs) {
        return logs.events().stream()
                .filter(event -> event.getMessage().getFormattedMessage().contains(DROP_LINE))
                .toList();
    }

    private static String describe(LogCapture logs) {
        return logs.events().stream().map(event -> event.getMessage().getFormattedMessage()).toList().toString();
    }
}
