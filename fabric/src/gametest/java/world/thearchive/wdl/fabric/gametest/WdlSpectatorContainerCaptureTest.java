// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

@SuppressWarnings("UnstableApiUsage")
public class WdlSpectatorContainerCaptureTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
            BlockPos chest = new BlockPos(stand.getX(), stand.getY(), stand.getZ() + 2);
            ContainerDriver.placeFilledChest(fixture.server(), chest);
            context.waitFor(client -> client.level.getBlockEntity(chest) instanceof ChestBlockEntity);

            fixture.server().runCommand("gamemode spectator @a");
            context.waitFor(client -> client.player.isSpectator());

            ContainerDriver.aimEyesAt(context, ContainerDriver.center(chest));
            ContainerDriver.awaitCrosshair(context, client -> ContainerDriver.isLookingAt(client, chest),
                    "chest " + chest);

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-spectator-container", "wdl-spectator-container", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            run.tick(5);

            context.runOnClient(client -> {
                client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
                Check.that(ContainerDriver.isLookingAt(client, chest),
                        "crosshair drifted off the chest before opening: " + client.hitResult);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, (BlockHitResult) client.hitResult);
            });
            ContainerDriver.awaitMenuSlotItem(context, run, Items.DIAMOND);
            run.tick(5);

            Check.that(run.isCaptured(captured -> captured.containsBlock(chest.asLong())),
                    "the chest a spectator opened is absent from the captured-set, so the open was attributed "
                            + "to nothing: " + chest);

            Path saveRoot = run.stopAndAwaitSave();

            List<String> items = ContainerDriver.capturedChestItems(saveRoot, chest);
            Check.that(items.contains(ContainerDriver.PLANTED_ITEM),
                    "the spectator-opened chest's planted item is absent from its captured Items: " + items);

            UUID cartId = spectatorCrosshairEntityLegBindsNothing(context, fixture.server(), stand);
            spectatorCrosshairEnderChestBindsNothing(context, fixture.server(), stand, cartId);
        }
    }

    /**
     * The minecart is opened through the game mode directly, which reaches neither the use key nor the loader's
     * client-side use hook, so the menu arrives exactly as a server-opened GUI does.
     */
    private static UUID spectatorCrosshairEntityLegBindsNothing(ClientGameTestContext context,
            TestServerContext server, BlockPos stand) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.setScreen(null);
        });
        double cartX = stand.getX() + 2.5;
        double cartZ = stand.getZ() + 0.5;
        server.runCommand("summon minecraft:chest_minecart " + cartX + " " + stand.getY() + " " + cartZ);
        server.runCommand("item replace entity @e[type=minecraft:chest_minecart,limit=1] container.0 "
                + "with minecraft:diamond 7");
        context.waitFor(client -> client.level.getEntities((Entity) null,
                client.player.getBoundingBox().inflate(8)).stream().anyMatch(ContainerEntity.class::isInstance));

        ContainerDriver.aimEyesAt(context, new Vec3(cartX, stand.getY() + 0.35, cartZ));
        ContainerDriver.awaitCrosshair(context, WdlSpectatorContainerCaptureTest::isLookingAtVehicle,
                "chest minecart");
        ChunkPos cartChunk = context
                .computeOnClient(client -> ((EntityHitResult) client.hitResult).getEntity().chunkPosition());
        UUID cartId = context
                .computeOnClient(client -> ((EntityHitResult) client.hitResult).getEntity().getUUID());

        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-spectator-crosshair-entity", "wdl-spectator-crosshair-entity",
                        DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        run.tick(5);
        context.runOnClient(client -> client.gameMode.interact(client.player,
                ((EntityHitResult) client.hitResult).getEntity(), (EntityHitResult) client.hitResult,
                InteractionHand.MAIN_HAND));
        ContainerDriver.awaitMenuSlotItem(context, run, Items.DIAMOND);
        run.tick(5);

        Check.that(!run.isCaptured(captured -> captured.containsEntity(cartId)),
                "a spectator's unattributed open was bound to the vehicle under the crosshair: " + cartId);

        Path saveRoot = run.stopAndAwaitSave();
        List<String> cartItems = CaptureReadback.readEntityChunk(saveRoot, cartChunk)
                .map(CaptureReadback::entities)
                .orElse(List.of()).stream()
                .filter(entity -> CaptureReadback.entityUuid(entity).filter(cartId::equals).isPresent())
                .findFirst()
                .map(CaptureReadback::itemIds)
                .orElse(List.of());
        Check.that(!cartItems.contains("minecraft:diamond"),
                "a spectator's unattributed open reached disk on the crosshair's vehicle: " + cartItems);

        CaptureDriver observed = CaptureDriver.start(context,
                new DownloadTarget("wdl-spectator-entity-click", "wdl-spectator-entity-click", DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        observed.tick(5);
        context.runOnClient(client -> {
            client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
            Check.that(isLookingAtVehicle(client),
                    "crosshair drifted off the chest minecart before the observed click: " + client.hitResult);
            client.setScreen(null);
        });
        context.getInput().holdKey(options -> options.keyUse);
        ContainerDriver.awaitMenuSlotItem(context, observed, Items.DIAMOND);
        context.getInput().releaseKey(options -> options.keyUse);
        observed.tick(5);
        Check.that(observed.isCaptured(captured -> captured.containsEntity(cartId)),
                "a spectator's real click on the chest minecart was not observed, so this loader loses every "
                        + "spectator entity-container capture and the refusal above hides it: " + cartId);
        observed.stopAndAwaitSave();
        return cartId;
    }

    /**
     * An ender chest is vanilla's one menu-opening block with no menu provider, and the server's spectator branch opens
     * only from the provider, so no ender open a spectator caused can exist.
     *
     * <p>The open is the minecart's, driven through the game mode, and the crosshair is parked on the ender chest
     * instead. Both are twenty-seven-slot chest menus, so nothing downstream can separate them: the discriminator is
     * the block under the crosshair, which is exactly the wrong evidence.
     */
    private static void spectatorCrosshairEnderChestBindsNothing(ClientGameTestContext context,
            TestServerContext server, BlockPos stand, UUID cartId) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.setScreen(null);
        });
        BlockPos enderChest = new BlockPos(stand.getX(), stand.getY(), stand.getZ() - 2);
        server.runCommand("setblock " + enderChest.getX() + " " + enderChest.getY() + " " + enderChest.getZ()
                + " minecraft:ender_chest[facing=north]");
        context.waitFor(client -> client.level.getBlockEntity(enderChest) instanceof EnderChestBlockEntity);

        ContainerDriver.aimEyesAt(context, ContainerDriver.center(enderChest));
        ContainerDriver.awaitCrosshair(context, client -> ContainerDriver.isLookingAt(client, enderChest),
                "ender chest " + enderChest);

        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-spectator-crosshair-ender", "wdl-spectator-crosshair-ender",
                        DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        run.tick(5);
        context.runOnClient(client -> {
            Entity cart = client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(16)).stream()
                    .filter(entity -> entity.getUUID().equals(cartId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the chest minecart is gone, so nothing would open"));
            client.gameMode.interact(client.player, cart, new EntityHitResult(cart), InteractionHand.MAIN_HAND);
        });
        ContainerDriver.awaitMenuSlotItem(context, run, Items.DIAMOND);
        run.tick(5);

        Check.that(!run.isCaptured(captured -> captured.enderCaptured()),
                "an open a spectator's ender chest could not have caused was bound to the ender inventory");

        Path saveRoot = run.stopAndAwaitSave();
        List<String> enderItems = CaptureReadback.capturedPlayer(saveRoot)
                .getListOrEmpty("EnderItems").compoundStream()
                .map(item -> item.getString("id").orElse("?"))
                .toList();
        Check.that(!enderItems.contains("minecraft:diamond"),
                "the minecart's contents were written into the player's ender inventory: " + enderItems);
    }

    private static boolean isLookingAtVehicle(Minecraft client) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity() instanceof ContainerEntity;
    }
}
