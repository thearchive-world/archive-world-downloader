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
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Entity-borne container axis: a chest minecart's contents reach the {@code entities/} region only when the player
 * opens it. Like a block container, the vehicle's {@code Items} never arrive in a spawn or chunk packet over a
 * connection; they sync into the open menu's slots, so the capture must drive the open to bind (on the entity's UUID)
 * and record them, and the merge folds them onto the matching entity tag. The minecart itself is captured through the
 * same prime/packet path the entity axis proves, so a present item asserts the entity-borne open reached disk.
 *
 * <p>The bind in {@code LiveCaptureSession.captureOpenContainer} resolves the entity the player CLICKED, latched by the
 * loader's use hook, so the drive aims at the minecart, confirms the crosshair is on it, and opens it with the use key,
 * the one path that reaches that hook. The second scenario is that rule's negative: the same minecart opened with no
 * provenance at all must capture nothing.
 */
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

            // The use key, not a direct gameMode.interact: an entity interact reaches the loader's use hook only
            // through MC's own use-key handling, and that hook is what latches the clicked entity the bind
            // resolves from. A direct call opens the same menu but leaves the open unattributed, which binds
            // nothing. The screen is dismissed first, or the keybind press is swallowed.
            context.runOnClient(client -> {
                client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
                Check.that(isLookingAtVehicle(client),
                        "crosshair drifted off the chest minecart before opening: " + client.hitResult);
                client.gui.setScreen(null);
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

    /**
     * Second scenario: an open no click accounts for binds nothing, even with a bindable vehicle under the crosshair.
     * Interacting through the game mode directly reaches neither the use key nor the loader's use hook, so the menu
     * opens with no provenance, which is what a server-opened GUI looks like to the client. The crosshair is
     * deliberately left on the minecart whose menu it is, the coincidence the old fallback bound on: where the player
     * looks is not evidence of what opened, and a capture here is a mis-bind waiting for the two slot counts to
     * coincide.
     */
    private static void captureUnattributedOpenBindsNothing(ClientGameTestContext context, UUID cartId,
            ChunkPos cartChunk) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.gui.setScreen(null);
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
     * Third scenario, the corruption guard: an open with no provenance is not the ridden vehicle's, even though the
     * ridden vehicle is the one thing on screen that could plausibly have opened it. Riding a chest boat, the player
     * opens a chest minecart through a path that latches nothing, and both containers hold 27 slots, so the slot-count
     * guard cannot separate them: a claim here writes the minecart's contents into the boat, which is where the boat's
     * own key-open would have gone. The ridden boat saves as the player's {@code RootVehicle} in level.dat, so that is
     * where a claim would show up.
     */
    private static UUID captureUnattributedOpenAboardChestBoat(ClientGameTestContext context,
            TestServerContext server, BlockPos stand, Vec3 cartCenter, UUID cartId) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.gui.setScreen(null);
        });
        double boatX = stand.getX() + 0.5;
        double boatZ = stand.getZ() + 0.5;
        server.runCommand("summon minecraft:oak_chest_boat " + boatX + " " + stand.getY() + " " + boatZ);
        // Both the wait and the crosshair predicate name the boat's own type and then its own identity, never
        // "any container vehicle": the chest minecart from the earlier scenarios is one too and is where the
        // crosshair was left, so a loose predicate is satisfied before the boat has even arrived, and the mount
        // interact then lands on the minecart, which opens rather than boards. The run dies later on an
        // anonymous timeout that reads as infrastructure flake rather than as a mis-aimed drive.
        context.waitFor(client -> nearbyChestBoat(client).isPresent());
        UUID boatId = context.computeOnClient(client -> nearbyChestBoat(client).orElseThrow().getUUID());
        ContainerDriver.aimEyesAt(context, new Vec3(boatX, stand.getY() + 0.3, boatZ));
        ContainerDriver.awaitCrosshair(context, client -> isLookingAt(client, boatId), "chest boat " + boatId);
        // Not sneaking, so the interact boards the boat rather than opening its chest.
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

    /**
     * Fourth scenario, the positive the ridden-vehicle axis exists for: the boat the player rides IS captured when the
     * client asks to open it. The request is what the capture observes, so the drive sends exactly the request
     * vanilla's inventory key sends and presses no key at all. That distinction is the whole point: a request the
     * client sent while no key state was sampled is precisely the case a key poll misses, and it is the routine one,
     * since a tap shorter than a client tick leaves the key down at no tick boundary while vanilla still opens the
     * boat.
     *
     * <p>The crosshair is left wherever the previous scenario put it, on the minecart, so a bind that reads the view
     * rather than the request would land on the wrong vehicle and fail the same assertion.
     */
    private static void captureRiddenChestBoatOnItsOpenRequest(ClientGameTestContext context,
            TestServerContext server, UUID boatId) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.gui.setScreen(null);
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

    /** The chest boat summoned at the player's feet, absent until it reaches the client. */
    private static Optional<Entity> nearbyChestBoat(Minecraft client) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(4)).stream()
                .filter(entity -> entity.getType() == EntityTypes.OAK_CHEST_BOAT)
                .findFirst();
    }

    /** Whether the crosshair is resolved onto the entity with {@code uuid}. */
    private static boolean isLookingAt(Minecraft client, UUID uuid) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity().getUUID().equals(uuid);
    }

    /** Whether the client's crosshair is resolved onto a container vehicle (the entity-bind precondition). */
    private static boolean isLookingAtVehicle(Minecraft client) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity() instanceof ContainerEntity;
    }
}
