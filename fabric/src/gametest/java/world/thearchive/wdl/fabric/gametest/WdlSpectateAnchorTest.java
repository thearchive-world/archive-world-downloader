// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.Arrays;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.ChunkPos;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * The anchor regression: a download taken while the viewer spectates a moving entity must capture the terrain the
 * camera travels over, not one square around the client player the server stopped moving.
 *
 * <p>Vanilla spectating is the entire mechanism, so this reproduces the Flashback playback defect with no replay mod on
 * the classpath: {@code ServerPlayer.tick} snaps the server-side player onto the camera every tick and re-centers chunk
 * tracking from there, while the owning client is sent no position, so its LocalPlayer parks where it stood.
 *
 * <p>The camera is set through a server console command on purpose. A client-side {@code Minecraft.setCameraEntity}
 * writes a field and sends nothing, so the server would never re-center, no chunks would stream, and this test would
 * fail even with the anchor fixed. {@code runCommand} reports nothing when a command is refused, so the setup asserts
 * its effect rather than assuming it.
 *
 * <p>The fixture world is superflat, which is load-bearing: a teleported pig on flat ground is never buried, so it
 * cannot suffocate mid-test. A dead camera entity makes vanilla reset the camera to the player, after which every later
 * teleport selector matches nothing and the far assertion fails for a reason that looks exactly like the defect.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlSpectateAnchorTest implements FabricClientGameTest {
    // Blocks the spectated pig travels. Capture scans a square of radius renderDistance chunks around its
    // center, so at the fixture's render distance this is comfortably past the square capture started in: the
    // far assertion cannot pass on overlap alone.
    private static final int TRAVEL_BLOCKS = 320;

    // Teleport step, short enough that the server streams the intervening chunks rather than one distant
    // square, which is what makes the far assertion about following a path rather than about a single jump.
    private static final int STEP_BLOCKS = 40;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().location().toString());
            BlockPos start = context.computeOnClient(client -> client.player.blockPosition());

            // A pig at the player's own position, so the camera begins where the capture window already is and
            // every later divergence is the travel, not the handoff.
            fixture.server().runCommand("summon minecraft:pig " + start.getX() + " " + start.getY() + " "
                    + start.getZ() + " {Tags:[\"wdl_anchor\"],NoAI:1b}");
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(16)).stream()
                    .anyMatch(Pig.class::isInstance));

            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-spectate-anchor", "wdl-spectate-anchor", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            driver.tick(10);

            // Spectator first: /spectate refuses a non-spectator, and the fixture connects in creative.
            // Never reorder these two, because leaving spectator resets the camera to the player.
            fixture.server().runCommand("gamemode spectator @a");
            fixture.server().runCommand("spectate @e[tag=wdl_anchor,limit=1] @p");
            driver.tick(10);

            // The setup's effect, not the command's return: runCommand reports nothing when a command is
            // refused, and a silently refused spectate would make every assertion below pass vacuously.
            Check.that(context.computeOnClient(client -> client.getCameraEntity() != client.player),
                    "spectate setup failed: the client camera is still the player, so the command did not take "
                            + "effect and the rest of this test would prove nothing");

            for (int traveled = STEP_BLOCKS; traveled <= TRAVEL_BLOCKS; traveled += STEP_BLOCKS) {
                fixture.server().runCommand("tp @e[tag=wdl_anchor,limit=1] " + (start.getX() + traveled) + " "
                        + start.getY() + " " + start.getZ());
                driver.tick(20); // let the server re-center and stream the step's chunks before the next one
            }

            ChunkPos startChunk = new ChunkPos(start);
            ChunkPos farChunk = new ChunkPos(new BlockPos(start.getX() + TRAVEL_BLOCKS, start.getY(),
                    start.getZ()));
            long[] saved = driver.rawSavedChunks(dimension);

            // The start must be present too: a change that relocated the window instead of following the camera
            // would satisfy the far assertion alone, and this is what separates the two.
            Check.that(containsChunk(saved, startChunk),
                    "the capture lost the start of the path, so the window jumped rather than followed it");
            Check.that(containsChunk(saved, farChunk),
                    "the capture never reached the spectated entity's destination: the window stayed on the "
                            + "parked player instead of following the camera. Captured " + saved.length
                            + " chunks, none at " + farChunk);

            Path saveRoot = driver.stopAndAwaitSave();
            Check.that(CaptureReadback.readChunk(saveRoot, farChunk).isPresent(),
                    "the far chunk entered the in-memory index but never serialized to region/");
        }
    }

    private static boolean containsChunk(long[] saved, ChunkPos pos) {
        return Arrays.stream(saved).anyMatch(key -> key == pos.toLong());
    }
}
