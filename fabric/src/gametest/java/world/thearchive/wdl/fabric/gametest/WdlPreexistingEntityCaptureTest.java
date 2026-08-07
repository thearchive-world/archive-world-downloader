// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * The prime's back-fill axis: an entity already loaded when the download starts reaches the save only through the
 * loaded-chunk prime, and a vehicle carrying exactly one player is refused there, so it has to be re-offered at finish.
 *
 * <p>A boat rather than a saddled mount: it meets the same one-player-vehicle refusal and has no AI, so it cannot
 * wander out of the chunk the readback reads.
 *
 * <p>The armor stand covers the other miss: on the topmost placeable block its box sits exactly where a build-height
 * bound stops, which a strict intersection test excludes.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlPreexistingEntityCaptureTest implements FabricClientGameTest {
    private static final String FOLDER = "wdl-preexisting-entity";

    private static final int DISMOUNT_TIMEOUT_TICKS = 100;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();
            Path savesDirectory = context.computeOnClient(client -> client.getLevelSource().getBaseDir());
            SaveFixture.reset(savesDirectory, FOLDER);

            BlockPos playerPos = context.computeOnClient(client -> client.player.blockPosition());
            ChunkPos startChunk = context.computeOnClient(client -> client.player.chunkPosition());
            server.runCommand("summon minecraft:oak_boat " + (playerPos.getX() + 0.5) + " " + playerPos.getY()
                    + " " + (playerPos.getZ() + 0.5));
            context.waitFor(client -> boat(client) != null);
            server.runCommand("ride @a[limit=1] mount @e[type=minecraft:oak_boat,limit=1]");
            context.waitFor(client -> client.player.getVehicle() != null);

            int maxY = context.computeOnClient(client -> client.level.getMaxY());
            BlockPos capBlock = new BlockPos(playerPos.getX() + 16, maxY, playerPos.getZ()); // a chunk over
            server.runCommand("setblock " + capBlock.getX() + " " + capBlock.getY() + " " + capBlock.getZ()
                    + " minecraft:stone");
            context.waitFor(client -> client.level.getBlockState(capBlock).is(Blocks.STONE));
            server.runCommand("summon minecraft:armor_stand " + (capBlock.getX() + 0.5) + " " + (maxY + 1) + " "
                    + (capBlock.getZ() + 0.5));
            context.waitFor(client -> stand(client, maxY) != null);
            Check.that(
                    Math.abs(context.computeOnClient(client -> requireStand(client, maxY).getY()) - (maxY + 1)) < 0.001,
                    "the armor stand is not resting on the topmost placeable block, so it is not standing where "
                            + "a build-height bound would have missed it and the case is not being covered");

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget(FOLDER, FOLDER, DownloadMode.NEW), WdlConfig.DEFAULTS);
            run.tick(10); // the prime runs here
            server.runCommand("ride @a[limit=1] dismount");
            awaitDismount(context, run);
            ChunkPos boatChunk = context.computeOnClient(client -> requireBoat(client).chunkPosition());
            ChunkPos standChunk = context.computeOnClient(client -> requireStand(client, maxY).chunkPosition());
            Path saveRoot = run.tick(20).stopAndAwaitSave();

            ChunkPos endChunk = context.computeOnClient(client -> client.player.chunkPosition());
            Check.that(Math.abs(endChunk.x() - startChunk.x()) <= 1 && Math.abs(endChunk.z() - startChunk.z()) <= 1,
                    "the player traveled from " + startChunk + " to " + endChunk + "; this scenario needs a "
                            + "stationary player, or a revisit could re-prime the boat by itself");
            Optional<CompoundTag> entityChunk = CaptureReadback.readEntityChunk(saveRoot, boatChunk);
            Check.that(entityChunk.isPresent(), "entity chunk " + boatChunk
                    + " holds no entities at all, so the boat the player was sitting in was never written");
            List<CompoundTag> entities = CaptureReadback.entities(entityChunk.get());
            boolean foundBoat = entities.stream()
                    .anyMatch(entity -> entity.getString("id").orElse("").equals("minecraft:oak_boat"));
            Check.that(foundBoat, "the boat the player was sitting in when the download started is absent from "
                    + "the entities region: "
                    + entities.stream().map(entity -> entity.getString("id").orElse("?")).toList());

            Optional<CompoundTag> standEntityChunk = CaptureReadback.readEntityChunk(saveRoot, standChunk);
            Check.that(standEntityChunk.isPresent(), "entity chunk " + standChunk
                    + " holds no entities at all, so the armor stand on the build cap was never written");
            List<CompoundTag> standEntities = CaptureReadback.entities(standEntityChunk.get());
            boolean foundStand = standEntities.stream()
                    .anyMatch(entity -> entity.getString("id").orElse("").equals("minecraft:armor_stand"));
            Check.that(foundStand, "the armor stand resting on the topmost placeable block is absent from the "
                    + "entities region: "
                    + standEntities.stream().map(entity -> entity.getString("id").orElse("?")).toList());
        }
    }

    /**
     * Pump {@code run} until the player is out of its vehicle. A bare wait would advance client ticks without ticking
     * the capture, and the dismount has to land while the download is still recording.
     */
    private static void awaitDismount(ClientGameTestContext context, CaptureDriver run) {
        for (int tick = 0; tick < DISMOUNT_TIMEOUT_TICKS; tick++) {
            if (context.computeOnClient(client -> client.player.getVehicle() == null)) {
                return;
            }
            run.tick(1);
        }
        throw new AssertionError("the player never left the boat, so the ride command silently failed");
    }

    private static Entity requireBoat(Minecraft client) {
        Entity boat = boat(client);
        if (boat == null) {
            throw new AssertionError("the summoned boat is gone from the client level");
        }
        return boat;
    }

    private static Entity requireStand(Minecraft client, int maxY) {
        Entity stand = stand(client, maxY);
        if (stand == null) {
            throw new AssertionError("the summoned armor stand is gone from the client level");
        }
        return stand;
    }

    /**
     * The armor stand by its own column: the sibling's inflated player box never reaches the build cap, so a wait keyed
     * on one would time out whatever the capture did.
     */
    private static @Nullable Entity stand(Minecraft client, int maxY) {
        AABB column = new AABB(client.player.getX() - 32, maxY - 8, client.player.getZ() - 32,
                client.player.getX() + 32, maxY + 8, client.player.getZ() + 32);
        return client.level.getEntities((Entity) null, column).stream()
                .filter(entity -> entity.getType() == EntityType.ARMOR_STAND)
                .findFirst()
                .orElse(null);
    }

    private static @Nullable Entity boat(Minecraft client) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(16)).stream()
                .filter(entity -> entity.getType() == EntityType.OAK_BOAT)
                .findFirst()
                .orElse(null);
    }
}
