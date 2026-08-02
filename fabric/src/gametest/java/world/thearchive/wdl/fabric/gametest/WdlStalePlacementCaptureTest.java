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

/**
 * The two ways a right-click carrying a block item can write one container's contents onto another one.
 *
 * <p>The first is the placement that cannot happen: the place prediction records a candidate at the cell the clicked
 * face points into, and the reconcile gate then asks only whether a shulker box is standing there, which is precisely
 * the state that makes writing wrong. Clicking a slab whose upper cell already holds somebody else's shulker box
 * records the HELD shulker's contents against THEIRS.
 *
 * <p>The second is the container replaced at a captured position: an open-time stash is keyed by block position with no
 * per-instance identity, and the merge gate compares block-entity types, so a chest placed where a captured chest stood
 * inherits the old one's contents. The placement is the client's own evidence that the captured block is gone.
 *
 * <p>Both run inside one download so the suite pays one server boot, and both ASSERT on the region file rather than on
 * a capture-side count, because what makes them defects is what a player opening the save would find. The positive
 * control is the one capture-side read here, and deliberately: the cell it guards sits beside the player for the whole
 * run, so its chunk never leaves the keep-hot window and there is no on-disk answer to read until the save. It proves
 * the open bound and stashed, which is the precondition both negatives need, and not that the contents reached disk.
 */
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

            // The place path is reconciled against a re-captured block-state, so let the edit-zone re-encode
            // run before the drain; the assertions below are about what that drain writes.
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

    /** Open the filled chest so its contents are stashed against its position, then close the menu. */
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

    /** Clear the captured chest and let the player place a fresh one of the same type in its cell. */
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

    /**
     * Right-click the slab's exposed top face while holding a filled shulker box. The cell that face points into
     * already holds a shulker box, so vanilla places nothing and the client's own placement context says so; the
     * prediction must not record the held contents against the standing box.
     */
    private static void clickIntoTheOccupiedCell(ClientGameTestContext context, TestServerContext server,
            CaptureDriver run, BlockPos slab) {
        // Pin the CONTENTS, not the component: a fresh chest item already carries an empty CONTAINER
        // component, so waiting on the component alone is satisfied by whatever was in the hand already and
        // the click that follows exercises the wrong item.
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

    /** Aim at the top surface of {@code block}, which for a bottom slab is its own upper face. */
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

    /**
     * Put {@code itemSpec} in the player's main hand and wait for the client to hold it. The wait is the point: a
     * command the band renamed or reshaped fails silently, and the click that follows would then exercise an empty hand
     * and pass for a reason that has nothing to do with the guard.
     */
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
