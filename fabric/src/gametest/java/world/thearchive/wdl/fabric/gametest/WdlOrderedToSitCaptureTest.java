// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Server-authoritative {@code Sitting} restoration on capture, exercised end to end. A pet's
 * {@code TamableAnimal.orderedToSit} never leaves the server; only its sit pose is synced, so the capture orders a pet
 * to sit from the pose. A cat ordered to sit must read back {@code Sitting:1b}, a standing cat is the control and must
 * not, and a sitting cat riding a boat proves the restoration reaches passengers, which save nested under their
 * vehicle. The cats are {@code NoAI}, so the summoned pose is the pose the client sees and nothing stands them up or
 * walks them out of the chunk.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlOrderedToSitCaptureTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            ChunkPos playerChunk = context.computeOnClient(client -> client.player.chunkPosition());
            BlockPos base = context.computeOnClient(client -> client.player.blockPosition());
            UUID owner = context.computeOnClient(client -> client.player.getUUID());
            // A boat boards any non-player living entity it touches, so the cats stand four blocks from it, all
            // inside the player's chunk.
            BlockPos cats = playerChunk.getMiddleBlockPosition(base.getY());
            String at = (cats.getX() + 0.5) + " " + cats.getY() + " " + (cats.getZ() + 0.5);
            String boatAt = (cats.getX() + 4.5) + " " + cats.getY() + " " + (cats.getZ() + 0.5);
            String pet = "NoAI:1b,Owner:[I;" + Arrays.stream(UUIDUtil.uuidToIntArray(owner))
                    .mapToObj(Integer::toString).collect(Collectors.joining(",")) + "]";

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-ordered-to-sit", "wdl-ordered-to-sit", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            run.tick(5);
            server.runCommand("summon minecraft:cat " + at + " {" + pet + ",Sitting:1b,CustomName:\"Sitter\"}");
            server.runCommand("summon minecraft:cat " + at + " {" + pet + ",Sitting:0b,CustomName:\"Stander\"}");
            server.runCommand("summon minecraft:oak_boat " + boatAt + " {Passengers:[{id:\"minecraft:cat\"," + pet
                    + ",Sitting:1b,CustomName:\"Rider\"}]}");
            Path saveRoot = run.tick(60).stopAndAwaitSave();

            Optional<CompoundTag> entityChunk = CaptureReadback.readEntityChunk(saveRoot, playerChunk);
            Check.that(entityChunk.isPresent(), "entity chunk " + playerChunk + " is missing from the save");
            List<CompoundTag> entities = CaptureReadback.entities(entityChunk.get());

            Check.that(sitting(findCat(entities, "Sitter")),
                    "the cat ordered to sit must read back Sitting:1b, or it stands up and teleports to its owner");
            Check.that(!sitting(findCat(entities, "Stander")),
                    "the standing cat is the control and must not be ordered to sit");
            Check.that(sitting(findCat(passengers(findBoat(entities)), "Rider")),
                    "the sitting cat riding the boat must read back Sitting:1b under the boat's Passengers");
        }
    }

    private static boolean sitting(CompoundTag entity) {
        return entity.getBoolean("Sitting").orElse(false);
    }

    private static CompoundTag findCat(List<CompoundTag> entities, String customName) {
        return entities.stream()
                .filter(entity -> entity.getString("id").orElse("").equals("minecraft:cat"))
                .filter(entity -> customName.equals(entity.getString("CustomName").orElse("")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no captured cat named " + customName + " in: "
                        + entities.stream().map(entity -> entity.getString("id").orElse("?")).toList()));
    }

    private static CompoundTag findBoat(List<CompoundTag> entities) {
        return entities.stream()
                .filter(entity -> entity.getString("id").orElse("").equals("minecraft:oak_boat"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no captured boat in: "
                        + entities.stream().map(entity -> entity.getString("id").orElse("?")).toList()));
    }

    private static List<CompoundTag> passengers(CompoundTag vehicle) {
        return vehicle.getListOrEmpty("Passengers").compoundStream().toList();
    }
}
