// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * An entity captured in one dimension is written under that dimension when the player travels on. It is held in the
 * packet accumulator until its chunk leaves the keep-hot window, so everything standing around the player at the moment
 * they change dimension is still held: exactly the base they went there for.
 *
 * <p>Both halves of the wrong answer are driven at once, by returning to the SAME chunk coordinates in the overworld
 * that the nether visit used. The destination the entity belongs to and the destination a dimension-blind chunk key
 * sends it to are then the same position in two folders, so one run tells apart written-here from written-there, and
 * neither assertion can pass on the other's outcome. That coincidence is the ordinary case rather than a contrived one:
 * nether coordinates are one eighth of the overworld ones they map to, so a key leaked out of the nether lands inside
 * the region a player who explored spawn has captured.
 *
 * <p>No headless fixture can reach this. Reconstructing an entity needs a live client level, and the drain this pins
 * runs against the level being left while the client has already been handed the one being entered, which only a real
 * dimension change produces.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlDimensionRebindEntityCaptureTest implements FabricClientGameTest {
    private static final String NETHER_KEY = "minecraft:the_nether";

    // The nether stand, and the overworld return, share chunk (12, 12): see the class note on why that is the
    // point rather than a coincidence. The nether y sits under the roof, which is inside rock, and the overworld
    // y is the flat surface; both are safe only because the fixture puts the player in creative.
    private static final BlockPos NETHER_STAND = new BlockPos(200, 100, 200);
    private static final BlockPos OVERWORLD_RETURN = new BlockPos(200, -60, 200);
    private static final ChunkPos SHARED_CHUNK = new ChunkPos(NETHER_STAND);

    @Override
    public void runTest(ClientGameTestContext context) {
        // The whole discrimination rests on the two positions sharing one chunk key, and they share it only
        // because two constants agree by hand; a later edit to either would leave both assertions below green
        // with the wrong-dimension arm dead.
        Check.that(new ChunkPos(OVERWORLD_RETURN).equals(SHARED_CHUNK),
                "the overworld return must sit in the same chunk as the nether stand, or this test proves nothing: "
                        + new ChunkPos(OVERWORLD_RETURN) + " versus " + SHARED_CHUNK);
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-dimension-rebind", "wdl-dimension-rebind", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            run.tick(5);

            warpTo(context, server, NETHER_KEY, NETHER_STAND);
            run.tick(25); // capture the nether terrain around the player, the entity privacy gate
            // Summoned mid-capture so it arrives as a spawn packet the inbound tee holds, and close enough that
            // it never leaves the keep-hot window before the player leaves the dimension. The dimension is named
            // rather than left to the command's own context, which is the server console's overworld: a bare
            // summon puts the stand in the world the player is NOT in, where it is broadcast only once they
            // return and the test then proves nothing.
            server.runCommand("execute in " + NETHER_KEY + " run summon minecraft:armor_stand "
                    + (NETHER_STAND.getX() + 0.5) + " " + NETHER_STAND.getY() + " "
                    + (NETHER_STAND.getZ() + 0.5));
            run.tick(25);

            warpTo(context, server, "minecraft:overworld", OVERWORLD_RETURN);
            run.tick(45); // long enough for the overworld to capture the shared chunk's terrain as well
            Path saveRoot = run.stopAndAwaitSave();

            Optional<CompoundTag> nether = CaptureReadback.readEntityChunkIn(saveRoot, "DIM-1", SHARED_CHUNK);
            Check.that(nether.isPresent(), "the nether's entity chunk " + SHARED_CHUNK + " is missing from "
                    + saveRoot.resolve("DIM-1").resolve("entities")
                    + ": nothing the player left behind in the nether was written under it");
            Check.that(hasArmorStand(nether.get()), "the armor stand standing beside the player when they left "
                    + "the nether is absent from the nether's entities region: " + ids(nether.get()));

            // The positive control for the assertion below: it is a test of the routing only while the overworld
            // has actually captured this chunk's terrain, since a dimension-blind key landing in an UNcaptured
            // chunk is refused by the privacy gate and would pass the absence check for the wrong reason.
            Check.that(CaptureReadback.readChunk(saveRoot, SHARED_CHUNK).isPresent(),
                    "the overworld never captured chunk " + SHARED_CHUNK + ", so its entity region proves nothing "
                            + "about where a leaked nether key would have sent the stand");
            Optional<CompoundTag> overworld = CaptureReadback.readEntityChunk(saveRoot, SHARED_CHUNK);
            Check.that(overworld.isEmpty() || !hasArmorStand(overworld.get()),
                    "the nether's armor stand was written into the OVERWORLD's entities region at the same chunk "
                            + "key, where the reopened save shows it standing in a world it was never in: "
                            + overworld.map(WdlDimensionRebindEntityCaptureTest::ids).orElse(List.of()));
        }
    }

    private static boolean hasArmorStand(CompoundTag entityChunkTag) {
        return CaptureReadback.entities(entityChunkTag).stream()
                .anyMatch(entity -> entity.getString("id").equals("minecraft:armor_stand"));
    }

    private static List<String> ids(CompoundTag entityChunkTag) {
        return CaptureReadback.entities(entityChunkTag).stream()
                .map(entity -> (entity.contains("id") ? entity.getString("id") : "?"))
                .toList();
    }

    /**
     * Teleport into {@code dimension} and wait for the client to be told about it. The wait is on the client's own
     * level key, because the capture follows the player on exactly that signal, and anything weaker would let the run
     * continue while the session is still bound to the world it is being asked to leave.
     */
    private static void warpTo(ClientGameTestContext context, TestServerContext server, String dimension,
            BlockPos to) {
        server.runCommand("execute in " + dimension + " run tp @a " + to.getX() + " " + to.getY() + " "
                + to.getZ());
        for (int tick = 0; tick <= ClientGameTestContext.DEFAULT_TIMEOUT; tick++) {
            if (context.computeOnClient(client -> dimension.equals(levelKeyOf(client.level.dimension())))) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("the client never entered " + dimension + "; it is still in "
                + context.computeOnClient(client -> levelKeyOf(client.level.dimension())));
    }

    private static String levelKeyOf(ResourceKey<Level> dimension) {
        return dimension.location().toString();
    }
}
