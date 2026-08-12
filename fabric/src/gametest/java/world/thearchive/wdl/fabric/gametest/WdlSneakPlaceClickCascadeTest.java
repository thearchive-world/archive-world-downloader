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
 * The no-menu-click cascade fix, block axis: sneaking with a non-empty hand makes vanilla place the held item instead
 * of opening the clicked block's menu ({@code MultiPlayerGameMode.performUseItemOn} and
 * {@code ServerPlayerGameMode.useItemOn} compute the identical {@code suppressUsingBlock} and skip the block's own use
 * on both sides), so such a click owes no open. Before the fix, {@code OpenClickTracker.dispatchUseBlock} latched it
 * anyway, because {@code opensMenuFor} only asks whether the clicked block CAN open a menu, not whether this click's
 * own suppression skipped its use. That phantom BLOCK intent then sat pending until the next real chest click
 * superseded it, minting a marker that consumed the following chest's own open as SUPERSEDED, dropping its contents
 * even though the player watched it open. This is the block-axis sibling of {@link WdlIncapableEntityClickCascadeTest};
 * the mechanism there is identical, only the source of the unowed click differs.
 *
 * <p>The click goes through {@code gameMode.useItemOn} directly, matching {@link WdlContainerCaptureTest} and
 * {@link WdlIncapableEntityClickCascadeTest}: that is the loader use hook's own injection site for blocks (a
 * {@code MultiPlayerGameModeMixin} inject at the {@code startPrediction} call inside {@code useItemOn}, upstream of
 * {@code performUseItemOn} and its own suppression check), so the fixture click actually exercises the hook the fix
 * touches. The sneak-place target is aimed the same way every other chest click in this suite is
 * ({@link ContainerDriver#center} plus {@link ContainerDriver#isLookingAt}, which only pins the clicked BLOCK, not a
 * particular face), because that aim is the one already proven to resolve reliably against a chest at this stand
 * distance elsewhere in the suite; a face picked in advance and asserted against blind (this file once aimed at the
 * chest's own bounding-box center hoping for the top face, which a shallow eye-to-target angle never delivered) is
 * exactly the kind of static reasoning a live run can contradict. The placement self-check instead reads the ACTUAL
 * face the click resolved to off that same click's own hit result, so it cannot drift out of sync with whatever face
 * vanilla's picker actually chooses.
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

    /**
     * Sneak-right-click the chest while holding a placeable item, and self-check both halves of the precondition this
     * scenario claims: no menu opened, and the item was actually placed adjacent to the face the click resolved to (so
     * the click genuinely hit vanilla's suppression path rather than whiffing for an unrelated reason, which would make
     * the "no menu opened" half true for the wrong cause and prove nothing about the guard under test).
     */
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

    /**
     * Open {@code chest} through the block-hook injection site and assert its planted contents reached both the live
     * captured-set and disk, matching {@link WdlIncapableEntityClickCascadeTest#openChestAndAssertCaptured}.
     */
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

    /**
     * Put a stack of dirt in the player's main hand and wait for the client to hold it. The wait is the point: a
     * command the band renamed or reshaped fails silently, and the click that follows would then exercise an empty hand
     * and pass for a reason that has nothing to do with the guard under test.
     */
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
