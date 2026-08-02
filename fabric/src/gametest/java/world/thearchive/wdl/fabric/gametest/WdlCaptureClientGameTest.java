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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * The one-block round-trip smoke: place a distinctive block on the server, drive a real capture to a save on disk, and
 * assert the block is in the captured chunk's palette. It is the driver spine the rest of the coverage matrix grows on,
 * proving the live front (a real {@code ClientLevel} snapshot through the capture loop and writer) reaches an on-disk
 * save.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlCaptureClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            ChunkPos playerChunk = context.computeOnClient(client -> client.player.chunkPosition());
            BlockPos marker = new BlockPos(playerChunk.getMinBlockX() + 8, 80, playerChunk.getMinBlockZ() + 8);
            server.runOnServer(minecraftServer -> minecraftServer.overworld().setBlock(marker,
                    Blocks.GOLD_BLOCK.defaultBlockState(), 3));
            context.waitFor(client -> client.level.getBlockState(marker).is(Blocks.GOLD_BLOCK));

            Path saveRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-smoke", "wdl-smoke", DownloadMode.NEW), WdlConfig.DEFAULTS, 40);

            Optional<CompoundTag> chunk = CaptureReadback.readChunk(saveRoot, playerChunk);
            Check.that(chunk.isPresent(), "captured chunk " + playerChunk + " is missing from the save");
            List<String> palette = CaptureReadback.paletteBlockNames(chunk.get());
            Check.that(palette.contains("minecraft:gold_block"),
                    "the placed gold block is absent from the captured chunk palette: " + palette);
        }
    }
}
