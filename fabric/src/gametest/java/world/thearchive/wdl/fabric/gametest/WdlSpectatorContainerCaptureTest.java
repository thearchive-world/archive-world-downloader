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

/**
 * The spectator crosshair fallback's guard: a container a SPECTATOR opens reaches disk only because
 * {@code ContainerCapture.resolveOpenTarget} still binds one to the crosshair. A spectator's click is not observable on
 * this loader, so nothing else can attribute the open. Fabric's {@code UseBlockCallback} returns for a spectator before
 * invoking its listeners, deliberately, so the open-click latch never sees the click even though vanilla sends the use
 * packet and the server opens the menu. Delete the fallback and this scenario goes red on the captured-set assertion,
 * which is what pins it as load-bearing rather than vestigial.
 *
 * <p>Distinct from {@link WdlContainerCaptureTest}, which drives the same open in creative and binds through the
 * clicked-block latch: nothing else in the suite enters the bind as a spectator, so the two branches of the resolve are
 * covered separately rather than by one scenario that cannot tell them apart.
 *
 * <p>The open is driven through {@code gameMode.useItemOn}, the method {@code Minecraft.startUseItem} calls for a
 * spectator too and the loader use hook's own injection site for blocks, so a drive that fails to latch here fails for
 * the loader's reason rather than for entering below the hook. The crosshair must land on the chest first for both
 * reasons at once: it is what the use call is handed, and it is what the bind reads.
 *
 * <p>The second scenario is the other half of the same rule, and the reason the fallback is per-axis rather than whole:
 * this loader DOES observe a spectator's entity click, so its crosshair entity leg can only ever fire for opens no
 * entity click accounts for, where the crosshair is a guess. It must bind nothing. The third narrows the kept block leg
 * the same way from inside: a block a spectator could not have opened is not evidence either, however confidently the
 * crosshair rests on it.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlSpectatorContainerCaptureTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
            BlockPos chest = new BlockPos(stand.getX(), stand.getY(), stand.getZ() + 2);
            ContainerDriver.placeFilledChest(fixture.server(), chest);
            context.waitFor(client -> client.level.getBlockEntity(chest) instanceof ChestBlockEntity);

            // The gamemode's effect, not the command's return: runCommand reports nothing when a command is
            // refused, and a silently refused switch would make this pass as the creative scenario it mirrors.
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
                client.gameRenderer.pick(1.0f);
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
     * The entity half of the fallback, on the loader that observes a spectator's entity click: the crosshair must not
     * stand in for provenance there. The minecart is opened through the game mode directly, which reaches neither the
     * use key nor the loader's use hook, so the menu arrives exactly as a server-opened GUI does, with the crosshair
     * parked on a 27-slot container vehicle whose size the guard cannot tell from the menu's. Binding here would write
     * whatever opened into that vehicle. A spectator's real click on the same minecart is observed and binds through
     * the intent chain, so nothing this scenario forbids is a capture the loader could otherwise have attributed. That
     * premise is asserted below rather than assumed: if it were ever false this loader would silently lose EVERY
     * spectator entity-container capture while this scenario stayed green, because forbidding the crosshair bind looks
     * identical either way from here.
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
                ((EntityHitResult) client.hitResult).getEntity(), InteractionHand.MAIN_HAND));
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

        // The premise the refusal above rests on: a spectator's REAL click on the same minecart is observed
        // on this loader and binds through the intent chain. Without this the scenario only proves that the
        // crosshair was refused, which is equally true of a loader that observes nothing at all.
        CaptureDriver observed = CaptureDriver.start(context,
                new DownloadTarget("wdl-spectator-entity-click", "wdl-spectator-entity-click", DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        observed.tick(5);
        context.runOnClient(client -> {
            client.gameRenderer.pick(1.0f);
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
     * The block leg is kept on this loader, but it must still refuse a block a spectator could not have opened. An
     * ender chest is vanilla's one menu-opening block with no menu provider, and the server's spectator branch opens
     * only from the provider, so no ender open a spectator caused can exist. Left unfiltered, an ender chest under the
     * crosshair claims whatever open does arrive, and an ender bind merges into the player tag in level.dat rather than
     * into a chunk.
     *
     * <p>The open is the minecart's, driven through the game mode so it reaches no use hook, and the crosshair is
     * parked on the ender chest instead. Both are twenty-seven-slot chest menus, so nothing downstream can separate
     * them: the discriminator is the block under the crosshair, which is exactly the wrong evidence.
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
            client.gameMode.interact(client.player, cart, InteractionHand.MAIN_HAND);
        });
        ContainerDriver.awaitMenuSlotItem(context, run, Items.DIAMOND);
        run.tick(5);

        Check.that(!run.isCaptured(captured -> captured.enderCaptured()),
                "an open a spectator's ender chest could not have caused was bound to the ender inventory");

        Path saveRoot = run.stopAndAwaitSave();
        List<String> enderItems = CaptureReadback.levelData(saveRoot).getCompoundOrEmpty("Player")
                .getListOrEmpty("EnderItems").compoundStream()
                .map(item -> item.getString("id").orElse("?"))
                .toList();
        Check.that(!enderItems.contains("minecraft:diamond"),
                "the minecart's contents were written into the player's ender inventory: " + enderItems);
    }

    /** Whether the client's crosshair is resolved onto a container vehicle (the entity-bind precondition). */
    private static boolean isLookingAtVehicle(Minecraft client) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity() instanceof ContainerEntity;
    }
}
