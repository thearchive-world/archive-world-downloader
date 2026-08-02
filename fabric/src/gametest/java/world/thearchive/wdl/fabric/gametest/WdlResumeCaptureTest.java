// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Resume axis: a resumed download adds new content without clobbering the prior run. A first NEW capture records area
 * A, the player then moves far enough that area A unloads, and a second RESUME capture records a new area B. The
 * resumed save must still hold area A (preserved, never re-captured this run) and now hold area B (added), which is the
 * add-to-without-clobber contract resume exists for.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlResumeCaptureTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            ChunkPos chunkA = context.computeOnClient(client -> client.player.chunkPosition());
            BlockPos markerA = new BlockPos(chunkA.getMinBlockX() + 8, 80, chunkA.getMinBlockZ() + 8);
            server.runOnServer(minecraftServer -> minecraftServer.overworld().setBlock(markerA,
                    Blocks.GOLD_BLOCK.defaultBlockState(), 3));
            context.waitFor(client -> client.level.getBlockState(markerA).is(Blocks.GOLD_BLOCK));

            DownloadTarget folder = new DownloadTarget("wdl-resume", "wdl-resume", DownloadMode.NEW);
            Path saveRoot = CaptureDriver.capture(context, folder, WdlConfig.DEFAULTS, 30);
            Check.that(goldAt(saveRoot, chunkA), "NEW capture did not write the gold block in area A");

            server.runCommand("tp @a 2000 100 2000");
            context.waitFor(client -> client.player != null && client.player.getX() > 1900);
            fixture.clientWorld().waitForChunksDownload();
            context.waitFor(client -> client.player != null && client.level != null);
            ChunkPos chunkB = context.computeOnClient(client -> client.player.chunkPosition());
            BlockPos markerB = new BlockPos(chunkB.getMinBlockX() + 8, 80, chunkB.getMinBlockZ() + 8);
            server.runOnServer(minecraftServer -> minecraftServer.overworld().setBlock(markerB,
                    Blocks.DIAMOND_BLOCK.defaultBlockState(), 3));
            context.waitFor(client -> client.level.getBlockState(markerB).is(Blocks.DIAMOND_BLOCK));

            Path resumed = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-resume", "wdl-resume", DownloadMode.RESUME), WdlConfig.DEFAULTS, 30);
            Check.that(goldAt(resumed, chunkA),
                    "RESUME did not preserve the prior gold block in area A " + chunkA);
            Check.that(blockAt(resumed, chunkB, "minecraft:diamond_block"),
                    "RESUME did not add the new diamond block in area B " + chunkB);
        }
    }

    private static boolean goldAt(Path saveRoot, ChunkPos chunk) {
        return blockAt(saveRoot, chunk, "minecraft:gold_block");
    }

    private static boolean blockAt(Path saveRoot, ChunkPos chunk, String blockId) {
        return CaptureReadback.readChunk(saveRoot, chunk)
                .map(tag -> CaptureReadback.paletteBlockNames(tag).contains(blockId))
                .orElse(false);
    }
}
