// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

@SuppressWarnings("UnstableApiUsage")
public class WdlStalePlacementCaptureTest implements FabricClientGameTest {
    private static final String FILLED_SHULKER = "minecraft:shulker_box[minecraft:container=["
            + "{slot:0,item:{id:\"minecraft:diamond\",count:1}}]]";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
            BlockPos replacedChest = new BlockPos(stand.getX(), stand.getY(), stand.getZ() + 2);
            BlockPos slab = new BlockPos(stand.getX() + 2, stand.getY(), stand.getZ() + 2);
            BlockPos standingShulker = slab.above();

            ContainerDriver.placeFilledChest(server, replacedChest);
            setBlock(server, slab, "minecraft:smooth_stone_slab[type=bottom]");
            setBlock(server, standingShulker, "minecraft:shulker_box");
            context.waitFor(client -> client.level.getBlockEntity(replacedChest) instanceof ChestBlockEntity
                    && client.level.getBlockEntity(standingShulker) instanceof ShulkerBoxBlockEntity);

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-stale-place", "wdl-stale-place", DownloadMode.NEW), WdlConfig.DEFAULTS);
            run.tick(5);

            openAndClose(context, run, replacedChest);
            // The positive control both assertions need: they are negatives, so a run in which the chest was
            // never captured at all passes them for a reason that has nothing to do with either guard. It has
            // to be read HERE, before the replacement, because the drop under test un-marks this very cell.
            Check.that(run.isCaptured(captured -> captured.containsBlock(replacedChest.asLong())),
                    "the chest was never captured, so the assertions below prove nothing: " + replacedChest);

            replaceCapturedChest(context, server, run, replacedChest);
            clickIntoTheOccupiedCell(context, server, run, slab);

            run.tick(45);
            Path saveRoot = run.stopAndAwaitSave();

            List<String> replacedItems = ContainerDriver.capturedChestItems(saveRoot, replacedChest);
            Check.that(!replacedItems.contains(ContainerDriver.PLANTED_ITEM),
                    "the chest placed where a captured chest stood inherited the old chest's contents: "
                            + replacedItems);

            List<String> standingItems = ContainerDriver.capturedChestItems(saveRoot, standingShulker);
            Check.that(!standingItems.contains(ContainerDriver.PLANTED_ITEM),
                    "a click that placed nothing wrote the held shulker's contents onto the standing one: "
                            + standingItems);
        }
    }

    private static void openAndClose(ClientGameTestContext context, CaptureDriver run, BlockPos chest) {
        ContainerDriver.aimEyesAt(context, ContainerDriver.center(chest));
        ContainerDriver.awaitCrosshair(context, client -> ContainerDriver.isLookingAt(client, chest),
                "chest " + chest);
        context.runOnClient(client -> {
            client.gameRenderer.pick(1.0f);
            Check.that(ContainerDriver.isLookingAt(client, chest),
                    "crosshair drifted off the chest before opening: " + client.hitResult);
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, (BlockHitResult) client.hitResult);
        });
        ContainerDriver.awaitMenuSlotItem(context, run, Items.DIAMOND);
        run.tick(3);
        context.runOnClient(client -> client.player.closeContainer());
        run.tick(3);
    }

    private static void replaceCapturedChest(ClientGameTestContext context, TestServerContext server,
            CaptureDriver run, BlockPos chest) {
        setBlock(server, chest, "minecraft:air");
        context.waitFor(client -> client.level.getBlockEntity(chest) == null);
        giveMainHand(context, server, "minecraft:chest", client -> client.player.getMainHandItem().is(Items.CHEST));

        BlockPos floor = chest.below();
        aimAtTopFace(context, floor);
        context.runOnClient(client -> {
            client.gameRenderer.pick(1.0f);
            Check.that(isLookingAtTopFaceOf(client, floor), "crosshair is not on the floor's top face: "
                    + client.hitResult);
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, (BlockHitResult) client.hitResult);
        });
        context.waitFor(client -> client.level.getBlockEntity(chest) instanceof ChestBlockEntity);
        run.tick(3);
    }

    private static void clickIntoTheOccupiedCell(ClientGameTestContext context, TestServerContext server,
            CaptureDriver run, BlockPos slab) {
        giveMainHand(context, server, FILLED_SHULKER, client -> {
            ItemStack held = client.player.getMainHandItem();
            return held.is(Items.SHULKER_BOX) && held.getOrDefault(DataComponents.CONTAINER,
                    ItemContainerContents.EMPTY).nonEmptyStream().anyMatch(item -> item.is(Items.DIAMOND));
        });
        aimAtTopFace(context, slab);
        context.runOnClient(client -> {
            client.gameRenderer.pick(1.0f);
            Check.that(isLookingAtTopFaceOf(client, slab), "crosshair is not on the slab's top face: "
                    + client.hitResult);
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, (BlockHitResult) client.hitResult);
        });
        run.tick(3);
        context.runOnClient(client -> Check.that(
                client.level.getBlockEntity(slab.above()) instanceof ShulkerBoxBlockEntity,
                "the occupied cell must still hold the standing shulker box, or the click placed after all"));
    }

    private static void aimAtTopFace(ClientGameTestContext context, BlockPos block) {
        Vec3 face = new Vec3(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
        ContainerDriver.aimEyesAt(context, face);
        ContainerDriver.awaitCrosshair(context, client -> isLookingAtTopFaceOf(client, block),
                "top face of " + block);
    }

    private static boolean isLookingAtTopFaceOf(Minecraft client, BlockPos block) {
        return ContainerDriver.isLookingAt(client, block)
                && ((BlockHitResult) client.hitResult).getDirection() == Direction.UP;
    }

    private static void giveMainHand(ClientGameTestContext context, TestServerContext server, String itemSpecification,
            Predicate<Minecraft> holding) {
        server.runCommand("item replace entity @a weapon.mainhand with " + itemSpecification);
        for (int tick = 0; tick <= ClientGameTestContext.DEFAULT_TIMEOUT; tick++) {
            if (context.computeOnClient(holding::test)) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("the main hand never held " + itemSpecification + "; the setup command did not take");
    }

    private static void setBlock(TestServerContext server, BlockPos pos, String block) {
        server.runCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }
}
