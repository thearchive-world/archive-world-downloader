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

@SuppressWarnings("UnstableApiUsage")
public class WdlDimensionRebindEntityCaptureTest implements FabricClientGameTest {
    private static final String NETHER_KEY = "minecraft:the_nether";

    // The nether y sits under the roof, which can be inside rock, and the overworld y is the flat surface; the nether
    // one is safe only because the fixture puts the player in creative.
    private static final BlockPos NETHER_STAND = new BlockPos(200, 100, 200);
    private static final BlockPos OVERWORLD_RETURN = new BlockPos(200, -60, 200);
    private static final ChunkPos SHARED_CHUNK = new ChunkPos(NETHER_STAND);

    @Override
    public void runTest(ClientGameTestContext context) {
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
            run.tick(25);
            // The dimension is named rather than left to the command's own context, which is the server console's
            // overworld: a bare summon puts the stand in the world the player is NOT in, where it is broadcast only
            // once they return.
            server.runCommand("execute in " + NETHER_KEY + " run summon minecraft:armor_stand "
                    + (NETHER_STAND.getX() + 0.5) + " " + NETHER_STAND.getY() + " "
                    + (NETHER_STAND.getZ() + 0.5));
            run.tick(25);

            warpTo(context, server, "minecraft:overworld", OVERWORLD_RETURN);
            run.tick(45);
            Path saveRoot = run.stopAndAwaitSave();

            Optional<CompoundTag> nether = CaptureReadback.readEntityChunkIn(saveRoot, "DIM-1", SHARED_CHUNK);
            Check.that(nether.isPresent(), "the nether's entity chunk " + SHARED_CHUNK + " is missing from "
                    + saveRoot.resolve("DIM-1").resolve("entities")
                    + ": nothing the player left behind in the nether was written under it");
            Check.that(hasArmorStand(nether.get()), "the armor stand standing beside the player when they left "
                    + "the nether is absent from the nether's entities region: " + ids(nether.get()));

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
