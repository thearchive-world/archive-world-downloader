// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * The placement self-check reads the ACTUAL face the click resolved to off that same click's own hit result, so it
 * cannot drift out of sync with whatever face vanilla's picker actually chooses.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlSneakPlaceClickCascadeTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
            BlockPos sneakChest = new BlockPos(stand.getX(), stand.getY(), stand.getZ() + 2);
            BlockPos realChest = new BlockPos(stand.getX() + 2, stand.getY(), stand.getZ() + 2);
            ContainerDriver.placeFilledChest(server, sneakChest);
            ContainerDriver.placeFilledChest(server, realChest);
            context.waitFor(client -> client.level.getBlockEntity(sneakChest) instanceof ChestBlockEntity
                    && client.level.getBlockEntity(realChest) instanceof ChestBlockEntity);

            giveMainHandDirt(context, server);

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-sneak-place-cascade", "wdl-sneak-place-cascade", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            run.tick(5);

            sneakPlaceOnChest(context, run, sneakChest);
            openChestAndAssertCaptured(context, run, realChest);
        }
    }

    private static void sneakPlaceOnChest(ClientGameTestContext context, CaptureDriver run, BlockPos chest) {
        context.runOnClient(client -> client.setScreen(null));
        context.getInput().holdKey(options -> options.keyShift);
        context.waitTicks(3); // let the sneak state reach the server before the click (secondary-use gate)

        ContainerDriver.aimEyesAt(context, ContainerDriver.center(chest));
        ContainerDriver.awaitCrosshair(context, client -> ContainerDriver.isLookingAt(client, chest),
                "sneak-place chest " + chest);
        Direction clickedFace = context.computeOnClient(client -> {
            client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
            Check.that(ContainerDriver.isLookingAt(client, chest),
                    "crosshair drifted off the sneak-place chest before clicking: " + client.hitResult);
            BlockHitResult hit = (BlockHitResult) client.hitResult;
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
            return hit.getDirection();
        });
        run.tick(3);
        context.getInput().releaseKey(options -> options.keyShift);
        context.waitTicks(3); // let the released sneak state reach the server before the next click

        BlockPos placed = chest.relative(clickedFace);
        context.runOnClient(client -> {
            Check.that(client.player.containerMenu == client.player.inventoryMenu,
                    "the sneak-place click opened a menu, so this scenario is not the no-menu case it claims to be");
            Check.that(client.level.getBlockState(placed).is(Blocks.DIRT),
                    "the held item was not placed at " + placed + " (the sneak-clicked chest's " + clickedFace
                            + " face), so vanilla did not take the suppress-and-place path this scenario's "
                            + "precondition requires");
        });
    }

    private static void openChestAndAssertCaptured(ClientGameTestContext context, CaptureDriver run, BlockPos chest) {
        ContainerDriver.aimEyesAt(context, ContainerDriver.center(chest));
        ContainerDriver.awaitCrosshair(context, client -> ContainerDriver.isLookingAt(client, chest),
                "chest " + chest);
        context.runOnClient(client -> {
            client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
            Check.that(ContainerDriver.isLookingAt(client, chest),
                    "crosshair drifted off the chest before opening: " + client.hitResult);
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, (BlockHitResult) client.hitResult);
        });
        ContainerDriver.awaitMenuSlotItem(context, run, Items.DIAMOND);
        run.tick(5);

        Check.that(run.isCaptured(captured -> captured.containsBlock(chest.asLong())),
                "the chest opened right after a sneak-place click did not enter the captured-set (the "
                        + "sneak-place phantom poisoned the chest's own open): " + chest);

        Path saveRoot = run.stopAndAwaitSave();
        List<String> chestItems = ContainerDriver.capturedChestItems(saveRoot, chest);
        Check.that(chestItems.contains(ContainerDriver.PLANTED_ITEM),
                "the chest's planted item is absent from its captured Items despite the preceding sneak-place "
                        + "click: " + chestItems);
    }

    private static void giveMainHandDirt(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("item replace entity @a weapon.mainhand with minecraft:dirt 4");
        for (int tick = 0; tick <= ClientGameTestContext.DEFAULT_TIMEOUT; tick++) {
            if (context.computeOnClient(client -> client.player.getMainHandItem().is(Items.DIRT))) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("the main hand never held dirt; the setup command did not take");
    }
}
