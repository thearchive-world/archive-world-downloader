// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * The chest syncs only through the open inventory, and a tamed chested animal opens its inventory on a secondary-use
 * (sneaking) interact, so the drive holds the sneak key before interacting. The animal is summoned with {@code NoAI} so
 * the crosshair can be aimed at it, and the diamond is planted in chest inventory slot 0 (the menu exposes the chest
 * from slot index 2, after the saddle and body-armor slots).
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlChestedAnimalCaptureTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
            double animalX = stand.getX() + 0.5;
            double animalY = stand.getY();
            double animalZ = stand.getZ() + 2 + 0.5;
            server.runCommand("summon minecraft:donkey " + animalX + " " + animalY + " " + animalZ
                    + " {Tame:1b,ChestedHorse:1b,NoAI:1b,Items:[{Slot:0b,id:\"minecraft:diamond\",count:7}]}");
            context.waitFor(client -> client.level.getEntities((Entity) null,
                    client.player.getBoundingBox().inflate(8)).stream()
                    .anyMatch(AbstractChestedHorse.class::isInstance));

            // An open screen suppresses keybind input, so dismiss it before holding sneak or the interact never
            // registers as one.
            context.runOnClient(client -> client.gui.setScreen(null));
            context.getInput().holdKey(options -> options.keyShift);
            context.waitTicks(3);
            Vec3 animalCenter = new Vec3(animalX, animalY + 0.75, animalZ);
            ContainerDriver.aimEyesAt(context, animalCenter);
            ContainerDriver.awaitCrosshair(context, WdlChestedAnimalCaptureTest::isLookingAtAnimal, "donkey");
            ChunkPos animalChunk = context.computeOnClient(
                    client -> ((EntityHitResult) client.hitResult).getEntity().chunkPosition());

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-chested-animal", "wdl-chested-animal", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            run.tick(5);
            context.runOnClient(client -> {
                client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
                Check.that(isLookingAtAnimal(client),
                        "crosshair drifted off the donkey before opening: " + client.hitResult);
                EntityHitResult hit = (EntityHitResult) client.hitResult;
                client.gameMode.interact(client.player, hit.getEntity(), hit, InteractionHand.MAIN_HAND);
            });
            ContainerDriver.awaitMenuReady(context, run,
                    client -> client.player.containerMenu instanceof HorseInventoryMenu menu
                            && menu.getSlot(2).getItem().is(Items.DIAMOND),
                    "chested animal chest");
            context.getInput().releaseKey(options -> options.keyShift);
            run.tick(5);
            Path saveRoot = run.stopAndAwaitSave();

            CompoundTag entityChunk = CaptureReadback.readEntityChunk(saveRoot, animalChunk)
                    .orElseThrow(() -> new AssertionError("entity chunk " + animalChunk + " is missing from the save"));
            List<String> animalItems = CaptureReadback.entities(entityChunk).stream()
                    .filter(entity -> entity.getString("id").orElse("").equals("minecraft:donkey"))
                    .findFirst()
                    .map(CaptureReadback::itemIds)
                    .orElseThrow(() -> new AssertionError("the donkey is absent from the entities region: "
                            + CaptureReadback.entities(entityChunk).stream()
                                    .map(entity -> entity.getString("id").orElse("?")).toList()));
            Check.that(animalItems.contains("minecraft:diamond"),
                    "the chested donkey's planted item is absent from its captured Items: " + animalItems);

            captureRiddenAnimal(context, animalX, animalY, animalZ);
        }
    }

    /**
     * The ridden mount saves as the player's {@code RootVehicle}, which is where the assertion looks, rather than into
     * the entities region.
     */
    private static void captureRiddenAnimal(ClientGameTestContext context, double mountX, double mountY,
            double mountZ) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.gui.setScreen(null);
        });
        ContainerDriver.aimEyesAt(context, new Vec3(mountX, mountY + 0.75, mountZ));
        ContainerDriver.awaitCrosshair(context, WdlChestedAnimalCaptureTest::isLookingAtAnimal, "the mount");
        context.runOnClient(client -> {
            client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
            EntityHitResult hit = (EntityHitResult) client.hitResult;
            client.gameMode.interact(client.player, hit.getEntity(), hit, InteractionHand.MAIN_HAND);
        });
        context.waitFor(client -> client.player.getVehicle() instanceof AbstractChestedHorse);

        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-ridden-chested-animal", "wdl-ridden-chested-animal", DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        run.tick(5);
        context.runOnClient(client -> client.player.sendOpenInventory());
        ContainerDriver.awaitMenuReady(context, run,
                client -> client.player.containerMenu instanceof HorseInventoryMenu menu
                        && menu.getSlot(2).getItem().is(Items.DIAMOND),
                "ridden chested animal chest");
        run.tick(5);
        Path saveRoot = run.stopAndAwaitSave();

        List<String> mountItems = CaptureReadback.itemIds(CaptureReadback.capturedPlayer(saveRoot)
                .getCompoundOrEmpty("RootVehicle").getCompoundOrEmpty("Entity"));
        Check.that(mountItems.contains("minecraft:diamond"),
                "the ridden donkey's chest is absent from the saved RootVehicle: " + mountItems);
    }

    private static boolean isLookingAtAnimal(Minecraft client) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity() instanceof AbstractChestedHorse;
    }
}
