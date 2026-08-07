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
 * Double-chest container axis: a large chest binds BOTH halves' block positions, and the 54-slot menu's two 27-slot
 * halves merge into their own chest block entities. Distinct items are planted in each half (a diamond in the LEFT
 * half, an emerald in the RIGHT) so a passing run proves each half landed on its own block, not one half copied to
 * both. The contents arrive client-side only through the open menu, so the drive opens one half; opening either binds
 * the double.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlDoubleChestCaptureTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
            // facing=north connects the double along +X: ChestBlock.getConnectedDirection maps a LEFT half to
            // facing.getClockWise() (EAST), so the LEFT half's partner is one block east, the RIGHT half.
            BlockPos leftHalf = new BlockPos(stand.getX(), stand.getY(), stand.getZ() + 2);
            BlockPos rightHalf = new BlockPos(stand.getX() + 1, stand.getY(), stand.getZ() + 2);
            placeHalf(server, leftHalf, "left", "minecraft:diamond");
            placeHalf(server, rightHalf, "right", "minecraft:emerald");
            context.waitFor(client -> client.level.getBlockEntity(leftHalf) instanceof ChestBlockEntity
                    && client.level.getBlockEntity(rightHalf) instanceof ChestBlockEntity);

            ContainerDriver.aimEyesAt(context, ContainerDriver.center(leftHalf));
            ContainerDriver.awaitCrosshair(context, client -> ContainerDriver.isLookingAt(client, leftHalf),
                    "double chest " + leftHalf);

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-double-chest", "wdl-double-chest", DownloadMode.NEW), WdlConfig.DEFAULTS);
            run.tick(5);
            context.runOnClient(client -> {
                client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
                Check.that(ContainerDriver.isLookingAt(client, leftHalf),
                        "crosshair drifted off the double chest before opening: " + client.hitResult);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, (BlockHitResult) client.hitResult);
            });
            // The 54-slot menu is the CompoundContainer in (RIGHT, LEFT) order, so slots 0..26 are the RIGHT
            // half: slot 0 holds the emerald once the contents sync.
            ContainerDriver.awaitMenuSlotItem(context, run, Items.EMERALD);
            run.tick(5);

            Check.that(run.isCaptured(captured -> captured.containsBlock(leftHalf.asLong())
                    && captured.containsBlock(rightHalf.asLong())),
                    "the double chest did not enter both halves into the captured-set (one rim spans both)");

            Path saveRoot = run.stopAndAwaitSave();

            List<String> leftItems = ContainerDriver.capturedChestItems(saveRoot, leftHalf);
            Check.that(leftItems.contains("minecraft:diamond"),
                    "the LEFT half's planted diamond is absent from its captured Items: " + leftItems);
            List<String> rightItems = ContainerDriver.capturedChestItems(saveRoot, rightHalf);
            Check.that(rightItems.contains("minecraft:emerald"),
                    "the RIGHT half's planted emerald is absent from its captured Items: " + rightItems);
        }
    }

    /** Set one half of the double at {@code pos} with the given chest type and plant a distinctive item in it. */
    private static void placeHalf(TestServerContext server, BlockPos pos, String type, String itemId) {
        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        server.runCommand("setblock " + at + " minecraft:chest[facing=north,type=" + type + "]");
        server.runCommand("item replace block " + at + " container.0 with " + itemId + " 7");
    }
}
