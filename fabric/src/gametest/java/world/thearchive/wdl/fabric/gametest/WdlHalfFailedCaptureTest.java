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

/**
 * The leash-degradation axis at the capture-integration level: a mob whose leash the client could not resolve must
 * still reach disk, unleashed, rather than being lost whole. The capture half-fails on purpose. The leash sub-datum is
 * the half the vanilla save codec rejects; the mod strips just that leash and saves the mob around it. The primed cow
 * is put into the exact client state {@code ClientPacketListener.handleEntityLinkPacket} builds for a
 * {@code ClientboundSetEntityLinkPacket} naming an unloaded holder, {@code
 * Leashable.setDelayedLeashHolderId}: the resulting {@code Leashable.LeashData} has neither a resolved holder nor a
 * delayed attachment, the one state {@code Leashable.LeashData.CODEC} throws {@code "Invalid LeashData had no
 * attachment"} on when the prime path encodes the live entity. The production sink detaches that unsavable leash before
 * the encode and restores it after, so {@code Entity.save} succeeds and the cow is written with no leash tag. That same
 * unresolved shape is what ViaBackwards leaves after down-translating the reworked 26.x leash into the 1.21.11 shape,
 * so the induced state is a real client scenario, not a synthetic one.
 *
 * <p>This is the integration complement to the {@code EntitySinkImpl} leash-degradation unit, which drives the sink
 * directly with a headless entity double. Here the strip is exercised through the whole live path: a real client
 * receiving the leash link, a real capture session priming the loaded cow, and the vanilla Anvil writer, then the
 * produced save is read back off disk. So a green run proves the strip holds end to end, not only in the sink in
 * isolation.
 *
 * <p>The contrast is load-bearing: a clean baseline captures a cow with no leash break, which saves normally with no
 * leash tag. The half-failed run changes the single variable (a leash to an unloaded holder) and the cow still reaches
 * disk with its unsavable leash stripped and no drop surfaced. Without the strip the vanilla codec would reject the
 * encode and the cow would be lost, so a count of one is the whole assertion. The cow is matched client-side by its
 * synced {@link EntityType} (a scoreboard tag would not do, it is server-only and never reaches the client), and
 * natural mob spawning is off so the planted cow is the only one.
 *
 * <p>The mechanism is a 1.21.6+ band floor: the {@link Leashable} interface and the {@code LeashData} codec rejection
 * it rests on do not exist below 1.21.6 (1.20.6 and earlier have no {@code Leashable}). On an earlier band a mob with
 * an unresolved holder already saves unleashed through vanilla itself, so the strip is a no-op there and the axis would
 * need a different fixture, a documented band gap. The behavior it asserts lives in shared {@code common/} and is
 * band-stable; only the vanilla codec rejection the strip guards against is per-band.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlHalfFailedCaptureTest implements FabricClientGameTest {
    /** The per-class logger name {@code LiveCaptureSession} reports a prime drop under. */
    private static final String SESSION_LOGGER = "world.thearchive.wdl.adapter.LiveCaptureSession";
    /**
     * The per-entity drop line's opening, which matches no sibling on this logger: the malformed-envelope WARN reads
     * {@code "skipping entity {}: the entity sink returned an envelope without it"}, and the container-merge WARNs sit
     * on another logger the name filter already drops. Matched on text alone rather than with a level, since a level
     * filter that fell out of step with the loss voices would empty this list without failing anything.
     */
    private static final String DROP_LINE = "failed to encode entity";
    /** An entity id the fixture's client never loads, so the leash holder stays unresolved through the encode. */
    private static final int UNLOADED_HOLDER_ID = Integer.MAX_VALUE;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context);
                LogCapture logs = new LogCapture(SESSION_LOGGER)) {
            TestServerContext server = fixture.server();
            // No natural spawns so the planted cow is the only entity, and no mob loot so the between-run
            // cow kill does not drop beef the creative player pockets into the half-failed save. Set-and-verify:
            // the gamerule names are a band seam (1.21.11 snake_case; older bands use camelCase doMobSpawning /
            // doMobLoot), and a wrong name would otherwise no-op silently through the void runCommand.
            GameRuleFixture.set(server, GameRules.RULE_DOMOBSPAWNING, false);
            GameRuleFixture.set(server, GameRules.RULE_DOMOBLOOT, false);

            // The saves directory persists across suite runs in the same run directory, and the download
            // report is append-only, so a second run would read two completion records where the assertions
            // below expect this run's one.
            Path savesDirectory = context.computeOnClient(client -> client.getLevelSource().getBaseDir());
            SaveFixture.reset(savesDirectory, "wdl-half-failed-baseline");
            SaveFixture.reset(savesDirectory, "wdl-half-failed");

            BlockPos spawn = context.computeOnClient(client -> client.player.blockPosition());
            ChunkPos chunk = context.computeOnClient(client -> client.player.chunkPosition());

            // Clean baseline: a cow with no leash break saves normally, with no leash tag and no drop.
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

            // Half-failed: a fresh cow leashed to a holder the client never loaded carries the leash state the
            // vanilla save codec rejects. The sink strips just that unsavable leash and saves the mob unleashed
            // rather than dropping it. The leash is the single changed variable.
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

    /**
     * The durable half of the same claim: a capture that dropped nothing must stamp its completion record complete, not
     * partial. The log scrape above says which items were lost, this says whether the download was, and it is the field
     * the downloads screen renders. It reads the record through the production reader, so a status written under the
     * wrong key or in the wrong sense fails here.
     *
     * <p>It is the only tier that can assert this end to end: the write is driven from finish(), which needs a client.
     * The headless suite pins the derivation the flag comes from and both of its arms; what this adds is that the whole
     * live path from a real capture to the file on disk agrees with it. Only the clean arm is asserted, because this
     * fixture's half-failure is one the mod recovers from silently by design, and inducing a real loss to see the
     * partial arm would be a different test.
     *
     * <p>The flag sums every capture-loss tally, so this reads wider than the rest of the class: a red here means some
     * tally moved, not necessarily the entity drop the log scrape beside it is about. The message says so, and says
     * where to look.
     */
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

    /** Summon a persistent, stationary cow (a {@link Leashable}) at {@code at}. */
    private static void summonCow(TestServerContext server, BlockPos at) {
        server.runCommand("summon minecraft:cow " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5)
                + " {NoAI:1b,PersistenceRequired:1b}");
    }

    /** The cow near the player, matched by its synced {@link EntityType} (scoreboard tags never reach the client). */
    private static Optional<Entity> findCow(Minecraft client) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(16.0)).stream()
                .filter(entity -> entity.getType() == EntityType.COW)
                .findFirst();
    }

    /**
     * Put the cow into the client state a leash to an unloaded holder produces, the exact call {@code
     * ClientPacketListener.handleEntityLinkPacket} makes. The holder id never resolves, so the prime path's encode hits
     * the codec rejection the sink strips.
     */
    private static void breakLeash(ClientGameTestContext context) {
        context.runOnClient(client -> {
            Entity cow = findCow(client)
                    .orElseThrow(() -> new AssertionError("the planted cow is not loaded on the client"));
            ((Leashable) cow).setDelayedLeashHolderId(UNLOADED_HOLDER_ID);
        });
    }

    /** The entity type ids written to the entities region for {@code chunk}, empty if no entity-chunk was written. */
    private static List<String> capturedEntityIds(Path saveRoot, ChunkPos chunk) {
        return CaptureReadback.readEntityChunk(saveRoot, chunk)
                .map(tag -> CaptureReadback.entities(tag).stream()
                        .map(entity -> (entity.contains("id") ? entity.getString("id") : "?"))
                        .toList())
                .orElse(List.of());
    }

    /** How many cows reached the entities region for {@code chunk}. */
    private static long cowCount(Path saveRoot, ChunkPos chunk) {
        return capturedEntityIds(saveRoot, chunk).stream().filter("minecraft:cow"::equals).count();
    }

    /** Whether a saved cow in {@code chunk} carries a leash tag; the stripped unsavable leash must leave none. */
    private static boolean savedCowHasLeash(Path saveRoot, ChunkPos chunk) {
        return CaptureReadback.readEntityChunk(saveRoot, chunk)
                .map(tag -> CaptureReadback.entities(tag).stream()
                        .filter(entity -> "minecraft:cow".equals(entity.getString("id")))
                        .anyMatch(entity -> entity.contains("leash")))
                .orElse(false);
    }

    /** The captured per-entity drop lines ({@code "failed to encode entity ..."}); the strip surfaces none. */
    private static List<LogEvent> encodeFailures(LogCapture logs) {
        return logs.events().stream()
                .filter(event -> event.getMessage().getFormattedMessage().contains(DROP_LINE))
                .toList();
    }

    /** The captured logger messages, for an assertion-failure diagnostic. */
    private static String describe(LogCapture logs) {
        return logs.events().stream().map(event -> event.getMessage().getFormattedMessage()).toList().toString();
    }
}
