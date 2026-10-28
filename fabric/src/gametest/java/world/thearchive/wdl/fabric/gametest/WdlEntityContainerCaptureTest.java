// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

@SuppressWarnings("UnstableApiUsage")
public class WdlEntityContainerCaptureTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
            double cartX = stand.getX() + 0.5;
            double cartY = stand.getY();
            double cartZ = stand.getZ() + 2 + 0.5;
            server.runCommand("summon minecraft:chest_minecart " + cartX + " " + cartY + " " + cartZ);
            server.runCommand("item replace entity @e[type=minecraft:chest_minecart,limit=1] container.0 "
                    + "with minecraft:diamond 7");
            context.waitFor(client -> client.level.getEntities((Entity) null,
                    client.player.getBoundingBox().inflate(8)).stream().anyMatch(ContainerEntity.class::isInstance));

            Vec3 cartCenter = new Vec3(cartX, cartY + 0.35, cartZ);
            ContainerDriver.aimEyesAt(context, cartCenter);
            ContainerDriver.awaitCrosshair(context, WdlEntityContainerCaptureTest::isLookingAtVehicle,
                    "chest minecart");
            ChunkPos cartChunk = context
                    .computeOnClient(client -> ((EntityHitResult) client.hitResult).getEntity().chunkPosition());
            UUID cartId = context
                    .computeOnClient(client -> ((EntityHitResult) client.hitResult).getEntity().getUUID());

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-entity-container", "wdl-entity-container", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            run.tick(5);

            // The use key, not a direct gameMode.interact: an entity interact reaches the loader's client-side use hook
            // only through MC's own use-key handling, and that hook is what latches the clicked entity the bind
            // resolves from. A direct call opens the same menu but leaves the open unattributed, which binds nothing.
            // The screen is dismissed first, or the keybind press is swallowed.
            context.runOnClient(client -> {
                client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
                Check.that(isLookingAtVehicle(client),
                        "crosshair drifted off the chest minecart before opening: " + client.hitResult);
                client.setScreen(null);
            });
            context.getInput().holdKey(options -> options.keyUse);
            ContainerDriver.awaitMenuSlotItem(context, run, Items.DIAMOND);
            context.getInput().releaseKey(options -> options.keyUse);
            run.tick(5);

            Check.that(run.isCaptured(captured -> captured.containsEntity(cartId)),
                    "opening the chest minecart did not enter its UUID into the captured-set: " + cartId);

            Path saveRoot = run.stopAndAwaitSave();

            CompoundTag entityChunk = CaptureReadback.readEntityChunk(saveRoot, cartChunk)
                    .orElseThrow(() -> new AssertionError("entity chunk " + cartChunk + " is missing from the save"));
            List<String> cartItems = CaptureReadback.entities(entityChunk).stream()
                    .filter(entity -> entity.getString("id").orElse("").equals("minecraft:chest_minecart"))
                    .findFirst()
                    .map(CaptureReadback::itemIds)
                    .orElseThrow(() -> new AssertionError("the chest minecart is absent from the entities region: "
                            + CaptureReadback.entities(entityChunk).stream()
                                    .map(entity -> entity.getString("id").orElse("?")).toList()));
            Check.that(cartItems.contains("minecraft:diamond"),
                    "the opened chest minecart's planted item is absent from its captured Items: " + cartItems);

            captureUnattributedOpenBindsNothing(context, cartId, cartChunk);
            UUID boatId = captureUnattributedOpenAboardChestBoat(context, server, stand, cartCenter, cartId);
            captureRiddenChestBoatOnItsOpenRequest(context, server, boatId);
        }
    }

    private static void captureUnattributedOpenBindsNothing(ClientGameTestContext context, UUID cartId,
            ChunkPos cartChunk) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.setScreen(null);
        });
        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-unattributed-open", "wdl-unattributed-open", DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        run.tick(5);
        ContainerDriver.awaitCrosshair(context, WdlEntityContainerCaptureTest::isLookingAtVehicle,
                "chest minecart");
        context.runOnClient(client -> client.gameMode.interact(client.player,
                ((EntityHitResult) client.hitResult).getEntity(), (EntityHitResult) client.hitResult,
                InteractionHand.MAIN_HAND));
        ContainerDriver.awaitMenuSlotItem(context, run, Items.DIAMOND);
        run.tick(5);

        Check.that(!run.isCaptured(captured -> captured.containsEntity(cartId)),
                "an unattributed open was bound to the vehicle under the crosshair: " + cartId);

        Path saveRoot = run.stopAndAwaitSave();
        List<String> cartItems = CaptureReadback.readEntityChunk(saveRoot, cartChunk)
                .map(CaptureReadback::entities)
                .orElse(List.of()).stream()
                .filter(entity -> CaptureReadback.entityUuid(entity).filter(cartId::equals).isPresent())
                .findFirst()
                .map(CaptureReadback::itemIds)
                .orElse(List.of());
        Check.that(!cartItems.contains("minecraft:diamond"),
                "an unattributed open reached disk on the crosshair's vehicle: " + cartItems);
    }

    /**
     * Riding a chest boat, the player opens a chest minecart through a path that latches nothing, and both containers
     * hold 27 slots, so the slot-count guard cannot separate them: a claim here writes the minecart's contents into the
     * boat, which is where the boat's own key-open would have gone.
     */
    private static UUID captureUnattributedOpenAboardChestBoat(ClientGameTestContext context,
            TestServerContext server, BlockPos stand, Vec3 cartCenter, UUID cartId) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.setScreen(null);
        });
        double boatX = stand.getX() + 0.5;
        double boatZ = stand.getZ() + 0.5;
        server.runCommand("summon minecraft:oak_chest_boat " + boatX + " " + stand.getY() + " " + boatZ);
        // Both the wait and the crosshair predicate name the boat's own type and then its own identity, never "any
        // container vehicle": the chest minecart from the earlier scenarios is one too, so a loose predicate is
        // satisfied before the boat has even arrived.
        context.waitFor(client -> nearbyChestBoat(client).isPresent());
        UUID boatId = context.computeOnClient(client -> nearbyChestBoat(client).orElseThrow().getUUID());
        ContainerDriver.aimEyesAt(context, new Vec3(boatX, stand.getY() + 0.3, boatZ));
        ContainerDriver.awaitCrosshair(context, client -> isLookingAt(client, boatId), "chest boat " + boatId);
        context.runOnClient(client -> client.gameMode.interact(client.player,
                ((EntityHitResult) client.hitResult).getEntity(), (EntityHitResult) client.hitResult,
                InteractionHand.MAIN_HAND));
        context.waitFor(client -> client.player.getVehicle() instanceof ContainerEntity);

        ContainerDriver.aimEyesAt(context, cartCenter);
        ContainerDriver.awaitCrosshair(context, client -> isLookingAt(client, cartId), "chest minecart");
        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-open-aboard-boat", "wdl-open-aboard-boat", DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        run.tick(5);
        context.runOnClient(client -> client.gameMode.interact(client.player,
                ((EntityHitResult) client.hitResult).getEntity(), (EntityHitResult) client.hitResult,
                InteractionHand.MAIN_HAND));
        ContainerDriver.awaitMenuSlotItem(context, run, Items.DIAMOND);
        run.tick(5);

        Check.that(!run.isCaptured(captured -> captured.containsEntity(boatId)),
                "an unattributed open was claimed by the ridden chest boat: " + boatId);

        Path saveRoot = run.stopAndAwaitSave();
        CompoundTag boat = CaptureReadback.capturedPlayer(saveRoot)
                .getCompoundOrEmpty("RootVehicle").getCompoundOrEmpty("Entity");
        Check.that(boat.getString("id").orElse("").equals("minecraft:oak_chest_boat"),
                "the ridden chest boat is absent from the saved player, so the guard would assert nothing: "
                        + boat.getString("id").orElse("none"));
        List<String> boatItems = CaptureReadback.itemIds(boat);
        Check.that(!boatItems.contains("minecraft:diamond"),
                "the minecart's contents were written into the ridden chest boat: " + boatItems);
        return boatId;
    }

    private static void captureRiddenChestBoatOnItsOpenRequest(ClientGameTestContext context,
            TestServerContext server, UUID boatId) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.setScreen(null);
        });
        server.runCommand("item replace entity @e[type=minecraft:oak_chest_boat,limit=1] container.0 "
                + "with minecraft:emerald 3");

        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-boat-open-request", "wdl-boat-open-request", DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        run.tick(5);
        context.runOnClient(client -> {
            Check.that(client.player.getVehicle() instanceof ContainerEntity,
                    "the player is no longer aboard the chest boat, so the scenario would prove nothing");
            client.player.sendOpenInventory();
        });
        ContainerDriver.awaitMenuSlotItem(context, run, Items.EMERALD);
        run.tick(5);

        Check.that(run.isCaptured(captured -> captured.containsEntity(boatId)),
                "the ridden chest boat's own open was not attributed to it: " + boatId);

        Path saveRoot = run.stopAndAwaitSave();
        CompoundTag boat = CaptureReadback.capturedPlayer(saveRoot)
                .getCompoundOrEmpty("RootVehicle").getCompoundOrEmpty("Entity");
        Check.that(boat.getString("id").orElse("").equals("minecraft:oak_chest_boat"),
                "the ridden chest boat is absent from the saved player, so the assertion below proves nothing: "
                        + boat.getString("id").orElse("none"));
        List<String> boatItems = CaptureReadback.itemIds(boat);
        Check.that(boatItems.contains("minecraft:emerald"),
                "the ridden chest boat's planted item is absent from its captured Items: " + boatItems);
    }

    private static Optional<Entity> nearbyChestBoat(Minecraft client) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(4)).stream()
                .filter(entity -> entity.getType() == EntityType.OAK_CHEST_BOAT)
                .findFirst();
    }

    private static boolean isLookingAt(Minecraft client, UUID uuid) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity().getUUID().equals(uuid);
    }

    private static boolean isLookingAtVehicle(Minecraft client) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity() instanceof ContainerEntity;
    }
}
