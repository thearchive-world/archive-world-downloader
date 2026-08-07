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
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Block-borne container axis: a chest's contents reach the captured chunk's {@code block_entities} only when the player
 * opens it. Over a connection the server never puts a chest's {@code Items} in the chunk packet; the contents arrive
 * client-side through the open menu's synced slots, so the capture must drive the open to bind and record them. Two
 * filled chests are captured in one run: one opened (its planted item must be on disk) and one left shut (the negative
 * pin: its captured {@code Items} is empty, the item absent), so a present item proves the open and the content-sync
 * reached disk, not mere chunk capture.
 *
 * <p>The bind in {@code LiveCaptureSession.captureOpenContainer} resolves the block the CLICK carried, so the drive
 * aims first, confirms the crosshair has landed on the chest, and only then opens through {@code gameMode.useItemOn},
 * the loader use hook's own injection site for blocks. The default flat test world leaves clear air above the surface,
 * so a chest placed at the player's feet level is in unobstructed reach and line of sight.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlContainerCaptureTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
            BlockPos openedChest = new BlockPos(stand.getX(), stand.getY(), stand.getZ() + 2);
            BlockPos shutChest = new BlockPos(stand.getX() + 2, stand.getY(), stand.getZ() + 2);
            BlockPos emptyChest = new BlockPos(stand.getX() - 2, stand.getY(), stand.getZ() + 2);
            ContainerDriver.placeFilledChest(server, openedChest);
            ContainerDriver.placeFilledChest(server, shutChest);
            placeEmptyChest(server, emptyChest);
            context.waitFor(client -> client.level.getBlockEntity(openedChest) instanceof ChestBlockEntity
                    && client.level.getBlockEntity(shutChest) instanceof ChestBlockEntity
                    && client.level.getBlockEntity(emptyChest) instanceof ChestBlockEntity);

            ContainerDriver.aimEyesAt(context, ContainerDriver.center(openedChest));
            ContainerDriver.awaitCrosshair(context, client -> ContainerDriver.isLookingAt(client, openedChest),
                    "chest " + openedChest);

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-container", "wdl-container", DownloadMode.NEW), WdlConfig.DEFAULTS);
            run.tick(5);

            context.runOnClient(client -> {
                client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
                Check.that(ContainerDriver.isLookingAt(client, openedChest),
                        "crosshair drifted off the chest before opening: " + client.hitResult);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, (BlockHitResult) client.hitResult);
            });
            ContainerDriver.awaitMenuSlotItem(context, run, Items.DIAMOND);
            run.tick(5);

            Check.that(run.isCaptured(captured -> captured.containsBlock(openedChest.asLong())),
                    "the opened chest is absent from the captured-set (content-gate): " + openedChest);
            Check.that(!run.isCaptured(captured -> captured.containsBlock(shutChest.asLong())),
                    "the never-opened chest entered the captured-set (negative pin): " + shutChest);

            // Opening a zero-item chest still clears its rim: the content-gate keys on the block slots
            // being synced this tick, not on the items being non-empty, so an opened-empty container is captured.
            context.runOnClient(client -> client.player.closeContainer());
            ContainerDriver.aimEyesAt(context, ContainerDriver.center(emptyChest));
            ContainerDriver.awaitCrosshair(context, client -> ContainerDriver.isLookingAt(client, emptyChest),
                    "empty chest " + emptyChest);
            context.runOnClient(client -> {
                client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
                Check.that(ContainerDriver.isLookingAt(client, emptyChest),
                        "crosshair drifted off the empty chest before opening: " + client.hitResult);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, (BlockHitResult) client.hitResult);
            });
            ContainerDriver.awaitMenuReady(context, run,
                    client -> client.player.containerMenu != client.player.inventoryMenu, "empty chest menu");
            run.tick(3);
            Check.that(run.isCaptured(captured -> captured.containsBlock(emptyChest.asLong())),
                    "opening an empty chest did not populate the captured-set (synced-but-empty): " + emptyChest);

            Path saveRoot = run.stopAndAwaitSave();

            List<String> openedItems = ContainerDriver.capturedChestItems(saveRoot, openedChest);
            Check.that(openedItems.contains(ContainerDriver.PLANTED_ITEM),
                    "the opened chest's planted item is absent from its captured Items: " + openedItems);

            List<String> shutItems = ContainerDriver.capturedChestItems(saveRoot, shutChest);
            Check.that(!shutItems.contains(ContainerDriver.PLANTED_ITEM),
                    "the shut chest captured contents without an open (negative pin): " + shutItems);
        }
    }

    /** Set an empty chest at {@code pos} on the server (no contents), for the synced-but-empty content-gate. */
    private static void placeEmptyChest(TestServerContext server, BlockPos pos) {
        server.runCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " minecraft:chest");
    }
}
