// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Ender-chest container axis: an ender chest binds to the PLAYER's global ender inventory, not the block, so the
 * contents merge into level.dat's {@code Player.EnderItems} rather than a chunk block entity. The client does not know
 * its ender contents until it opens an ender chest (the menu syncs them), so the open is load-bearing: the player's
 * ender inventory is filled server-side, the drive opens the chest to sync and bind it, and the planted item must
 * appear in the captured {@code Player.EnderItems}.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlEnderChestCaptureTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
            BlockPos enderChest = new BlockPos(stand.getX(), stand.getY(), stand.getZ() + 2);
            String at = enderChest.getX() + " " + enderChest.getY() + " " + enderChest.getZ();
            server.runCommand("setblock " + at + " minecraft:ender_chest[facing=north]");
            server.runCommand("item replace entity @a enderchest.0 with minecraft:diamond 7");
            context.waitFor(client -> client.level.getBlockEntity(enderChest) instanceof EnderChestBlockEntity);

            ContainerDriver.aimEyesAt(context, ContainerDriver.center(enderChest));
            ContainerDriver.awaitCrosshair(context, client -> ContainerDriver.isLookingAt(client, enderChest),
                    "ender chest " + enderChest);

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-ender-chest", "wdl-ender-chest", DownloadMode.NEW), WdlConfig.DEFAULTS);
            run.tick(5);
            context.runOnClient(client -> {
                client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
                Check.that(ContainerDriver.isLookingAt(client, enderChest),
                        "crosshair drifted off the ender chest before opening: " + client.hitResult);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, (BlockHitResult) client.hitResult);
            });
            ContainerDriver.awaitMenuSlotItem(context, run, Items.DIAMOND);
            run.tick(5);

            Check.that(run.isCaptured(captured -> captured.enderCaptured()),
                    "opening the ender chest did not set the captured-set ender flag");
            Check.that(!run.isCaptured(captured -> captured.containsBlock(enderChest.asLong())),
                    "the ender chest was folded into the block captured-set (it must be the shared flag)");

            Path saveRoot = run.stopAndAwaitSave();

            CompoundTag player = CaptureReadback.levelData(saveRoot).getCompoundOrEmpty("Player");
            List<String> enderItems = player.getListOrEmpty("EnderItems").compoundStream()
                    .map(item -> item.getString("id").orElse("?"))
                    .toList();
            Check.that(enderItems.contains("minecraft:diamond"),
                    "the planted item is absent from the captured Player.EnderItems: " + enderItems);

            obstructedEnderChestSeedsNothing(context, server, stand);
            enderChestOffBindsNothing(context, server, stand);
        }
    }

    /**
     * Third scenario: with savePlayerEnderChest off the finish strips EnderItems, so the open must bind nothing. A bind
     * would clear the captured-set ender flag and tally a container the save does not hold, which is the report
     * claiming content the archive deliberately drops. The menu still syncs the contents, so the empty EnderItems below
     * is the strip and not an unsynced client.
     */
    private static void enderChestOffBindsNothing(ClientGameTestContext context, TestServerContext server,
            BlockPos stand) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.setScreen(null);
        });
        BlockPos enderChest = new BlockPos(stand.getX() - 2, stand.getY(), stand.getZ() + 2);
        String at = enderChest.getX() + " " + enderChest.getY() + " " + enderChest.getZ();
        server.runCommand("setblock " + at + " minecraft:ender_chest[facing=north]");
        server.runCommand("item replace entity @a enderchest.0 with minecraft:diamond 7");
        context.waitFor(client -> client.level.getBlockEntity(enderChest) instanceof EnderChestBlockEntity);

        ContainerDriver.aimEyesAt(context, ContainerDriver.center(enderChest));
        ContainerDriver.awaitCrosshair(context, client -> ContainerDriver.isLookingAt(client, enderChest),
                "ender chest " + enderChest);

        Properties enderOff = new Properties();
        enderOff.setProperty("savePlayerEnderChest", "false");
        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-ender-off", "wdl-ender-off", DownloadMode.NEW), WdlConfig.parse(enderOff));
        run.tick(5);
        context.runOnClient(client -> {
            client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
            Check.that(ContainerDriver.isLookingAt(client, enderChest),
                    "crosshair drifted off the ender chest before opening: " + client.hitResult);
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, (BlockHitResult) client.hitResult);
        });
        ContainerDriver.awaitMenuSlotItem(context, run, Items.DIAMOND);
        run.tick(5);

        Check.that(!run.isCaptured(captured -> captured.enderCaptured()),
                "the open bound the ender inventory although the toggle strips it at finish, so the outline "
                        + "cleared its rim and the report counted a container the save does not hold");

        Path saveRoot = run.stopAndAwaitSave();
        List<String> enderItems = CaptureReadback.levelData(saveRoot).getCompoundOrEmpty("Player")
                .getListOrEmpty("EnderItems").compoundStream()
                .map(item -> item.getString("id").orElse("?"))
                .toList();
        Check.that(enderItems.isEmpty(),
                "savePlayerEnderChest off must leave no EnderItems in the save: " + enderItems);
    }

    /**
     * Second scenario: a click that opens nothing must seed nothing. An ender chest under a redstone conductor is
     * vanilla's own case of that, its use handler returning before it builds any menu, and the click filter would
     * otherwise latch it because an unobstructed ender chest genuinely does open one. A latched intent no open can
     * consume is taken by the next open that no click accounts for, and because the ender discriminator is checked
     * ahead of every other block leg, that open binds ENDER and merges into the player tag in level.dat rather than
     * into a chunk.
     *
     * <p>The unattributed open here is a chest minecart driven through the game mode, which reaches no use hook. Both
     * menus are twenty-seven-slot chest menus, so nothing downstream can separate them; the only thing standing between
     * the minecart's open and the ender inventory is whether that click was latched.
     */
    private static void obstructedEnderChestSeedsNothing(ClientGameTestContext context, TestServerContext server,
            BlockPos stand) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.setScreen(null);
        });
        BlockPos blocked = new BlockPos(stand.getX() + 2, stand.getY(), stand.getZ() + 2);
        String at = blocked.getX() + " " + blocked.getY() + " " + blocked.getZ();
        String above = blocked.getX() + " " + (blocked.getY() + 1) + " " + blocked.getZ();
        server.runCommand("setblock " + at + " minecraft:ender_chest[facing=north]");
        server.runCommand("setblock " + above + " minecraft:stone");
        context.waitFor(client -> client.level.getBlockEntity(blocked) instanceof EnderChestBlockEntity
                && client.level.getBlockState(blocked.above()).isRedstoneConductor(client.level, blocked.above()));

        double cartX = stand.getX() + 0.5;
        double cartZ = stand.getZ() - 2 + 0.5;
        server.runCommand("summon minecraft:chest_minecart " + cartX + " " + stand.getY() + " " + cartZ);
        server.runCommand("item replace entity @e[type=minecraft:chest_minecart,limit=1] container.0 "
                + "with minecraft:emerald 5");
        context.waitFor(client -> client.level.getEntities((Entity) null,
                client.player.getBoundingBox().inflate(8)).stream().anyMatch(ContainerEntity.class::isInstance));

        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-obstructed-ender", "wdl-obstructed-ender", DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        run.tick(5);

        // The use key, not a direct game-mode call: this click must reach the loader's use hook, because
        // whether the hook latches it is the whole question.
        ContainerDriver.aimEyesAt(context, ContainerDriver.center(blocked));
        ContainerDriver.awaitCrosshair(context, client -> ContainerDriver.isLookingAt(client, blocked),
                "obstructed ender chest " + blocked);
        context.runOnClient(client -> client.setScreen(null));
        context.getInput().holdKey(options -> options.keyUse);
        run.tick(5);
        context.getInput().releaseKey(options -> options.keyUse);
        context.runOnClient(client -> Check.that(client.player.containerMenu == client.player.inventoryMenu,
                "the obstructed ender chest opened a menu, so vanilla did not refuse it and this scenario "
                        + "would prove nothing"));

        Entity cart = context.computeOnClient(client -> client.level
                .getEntities((Entity) null, client.player.getBoundingBox().inflate(8)).stream()
                .filter(ContainerEntity.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the chest minecart is not loaded on the client")));
        context.runOnClient(client -> client.gameMode.interact(client.player, cart, new EntityHitResult(cart),
                InteractionHand.MAIN_HAND));
        ContainerDriver.awaitMenuSlotItem(context, run, Items.EMERALD);
        run.tick(5);

        Check.that(!run.isCaptured(captured -> captured.enderCaptured()),
                "a click on an ender chest that opens nothing seeded a later open onto the ender inventory");

        Path saveRoot = run.stopAndAwaitSave();
        List<String> enderItems = CaptureReadback.levelData(saveRoot).getCompoundOrEmpty("Player")
                .getListOrEmpty("EnderItems").compoundStream()
                .map(item -> item.getString("id").orElse("?"))
                .toList();
        // Not an emptiness check: the raw player tag carries the live client's own ender inventory whatever
        // the bind does, so the diamond the first scenario planted is legitimately still here. What must be
        // absent is the MINECART's item, which can only reach EnderItems through an ender bind that the
        // obstructed click seeded.
        Check.that(!enderItems.contains("minecraft:emerald"),
                "the minecart's contents were written into the player's ender inventory: " + enderItems);
    }
}
