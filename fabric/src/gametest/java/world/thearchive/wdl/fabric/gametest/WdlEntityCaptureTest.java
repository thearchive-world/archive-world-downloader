// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Entity axis: an entity spawned while recording must be captured through the inbound packet tee and written to the
 * entities/ region. Spawning mid-capture is what routes it through the {@code ClientboundAddEntityPacket} the tee
 * observes, the packet-derived path entity capture rests on, rather than the loaded-chunk prime that back-fills
 * entities present before the download began.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlEntityCaptureTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            ChunkPos playerChunk = context.computeOnClient(client -> client.player.chunkPosition());
            BlockPos playerPos = context.computeOnClient(client -> client.player.blockPosition());

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-entity", "wdl-entity", DownloadMode.NEW), WdlConfig.DEFAULTS);
            run.tick(5);
            server.runCommand("summon minecraft:armor_stand "
                    + (playerPos.getX() + 0.5) + " " + playerPos.getY() + " " + (playerPos.getZ() + 0.5));
            Path saveRoot = run.tick(40).stopAndAwaitSave();

            Optional<CompoundTag> entityChunk = CaptureReadback.readEntityChunk(saveRoot, playerChunk);
            Check.that(entityChunk.isPresent(),
                    "entity chunk " + playerChunk + " is missing from the save (no entities were written)");
            List<CompoundTag> entities = CaptureReadback.entities(entityChunk.get());
            boolean foundArmorStand = entities.stream()
                    .anyMatch(entity -> entity.getString("id").equals("minecraft:armor_stand"));
            Check.that(foundArmorStand, "the spawned armor stand is absent from the entities/ region: "
                    + entities.stream().map(entity -> (entity.contains("id") ? entity.getString("id") : "?"))
                            .collect(Collectors.toList()));
        }
    }
}
