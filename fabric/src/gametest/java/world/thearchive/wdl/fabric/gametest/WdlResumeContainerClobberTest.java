// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

@SuppressWarnings("UnstableApiUsage")
public class WdlResumeContainerClobberTest implements FabricClientGameTest {
    private static final String RESUME_MARKER = "minecraft:gold_block";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
            BlockPos chest = new BlockPos(stand.getX(), stand.getY(), stand.getZ() + 2);
            ChunkPos chestChunk = ChunkPos.containing(chest);
            ContainerDriver.placeFilledChest(server, chest);
            context.waitFor(client -> client.level.getBlockEntity(chest) instanceof ChestBlockEntity);

            ContainerDriver.aimEyesAt(context, ContainerDriver.center(chest));
            ContainerDriver.awaitCrosshair(context, client -> ContainerDriver.isLookingAt(client, chest),
                    "chest " + chest);
            CaptureDriver firstRun = CaptureDriver.start(context,
                    new DownloadTarget("wdl-resume-clobber", "wdl-resume-clobber", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            firstRun.tick(5);
            context.runOnClient(client -> {
                client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
                Check.that(ContainerDriver.isLookingAt(client, chest),
                        "crosshair drifted off the chest before opening: " + client.hitResult);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, (BlockHitResult) client.hitResult);
            });
            ContainerDriver.awaitMenuSlotItem(context, firstRun, Items.DIAMOND);
            firstRun.tick(5);
            Path firstSave = firstRun.stopAndAwaitSave();
            Check.that(ContainerDriver.capturedChestItems(firstSave, chest).contains(ContainerDriver.PLANTED_ITEM),
                    "NEW capture did not record the opened chest's planted item");

            context.runOnClient(client -> client.player.closeContainer());
            context.waitFor(client -> client.player.containerMenu == client.player.inventoryMenu);

            BlockPos marker = new BlockPos(chestChunk.getMinBlockX() + 8, 80, chestChunk.getMinBlockZ() + 8);
            server.runOnServer(minecraftServer -> minecraftServer.overworld().setBlock(marker,
                    Blocks.GOLD_BLOCK.defaultBlockState(), 3));
            context.waitFor(client -> client.level.getBlockState(marker).is(Blocks.GOLD_BLOCK));
            Path resumed = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-resume-clobber", "wdl-resume-clobber", DownloadMode.RESUME),
                    WdlConfig.DEFAULTS, 30);

            boolean recaptured = CaptureReadback.readChunk(resumed, chestChunk)
                    .map(tag -> CaptureReadback.paletteBlockNames(tag).contains(RESUME_MARKER))
                    .orElse(false);
            Check.that(recaptured, "RESUME did not re-capture the chest's chunk (the resume-only marker is absent), "
                    + "so the fold path was not exercised");
            List<String> resumedItems = ContainerDriver.capturedChestItems(resumed, chest);
            Check.that(resumedItems.contains(ContainerDriver.PLANTED_ITEM),
                    "RESUME clobbered the chest's prior contents with the fresh empty re-capture: " + resumedItems);
        }
    }
}
