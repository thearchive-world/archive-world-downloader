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
 * Chested-animal container axis: a donkey's chest binds on the entity's UUID and merges onto the animal's tag in the
 * {@code entities/} region. The chest syncs only through the open inventory, and a tamed chested animal opens its
 * inventory on a secondary-use (sneaking) interact, so the drive holds the sneak key before interacting. The animal is
 * summoned with {@code NoAI} so the crosshair can be aimed at it, and the diamond is planted in chest inventory slot 0
 * (the menu exposes the chest from slot index 2, after the saddle and body-armor slots).
 *
 * <p>What both scenarios actually cover is the menu-derived bind: {@code MountMenuReader} reads the animal the
 * mount-screen packet named, so the bind depends on neither the crosshair nor the ridden vehicle. Neither scenario
 * exercises click provenance, and that is worth knowing rather than assuming: both drive their interact through
 * {@code MultiPlayerGameMode} directly, which this loader's use hook does not observe (it fires from a
 * {@code Minecraft.startUseItem} mixin), so the intent chain stays empty for the whole run and the bind rests entirely
 * on the reader. Nor do they cover the dispatch's branch order against the vehicle path; that conflict needs a
 * container vehicle as the resolved target while a mount menu arrives, which no vanilla action here produces.
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

            // A reconnect earlier in a multi-scenario run can leave a confirm screen focused, and an open screen
            // suppresses keybind input, so dismiss it before holding sneak or the interact never registers as one.
            context.runOnClient(client -> client.setScreen(null));
            context.getInput().holdKey(options -> options.keyShift);
            context.waitTicks(3); // let the sneak state reach the server before the interact (secondary-use gate)
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
                client.gameRenderer.pick(1.0f);
                Check.that(isLookingAtAnimal(client),
                        "crosshair drifted off the donkey before opening: " + client.hitResult);
                EntityHitResult hit = (EntityHitResult) client.hitResult;
                client.gameMode.interact(client.player, hit.getEntity(), InteractionHand.MAIN_HAND);
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
     * Second scenario: the ridden mount's chest binds with no click provenance of any kind. The open is requested the
     * way vanilla's inventory key requests it, which fires no use event, and the resolve therefore hands the bind an
     * empty target; only the menu's own name for its mount can produce the right animal. Break {@code MountMenuReader}
     * or its install and this goes red, because the mount menu then falls through to the block axis with no block and
     * drops. The ridden mount saves as the player's {@code RootVehicle} in level.dat rather than into the entities
     * region, which is where the assertion looks.
     *
     * <p>A same-size decoy animal under the crosshair used to sit here as a mis-bind guard. It was retired once the
     * crosshair stopped reaching this bind at all: with the target empty and the animal read from the menu, no
     * expression in scope could name the decoy, so the assertion could not fail and pinned nothing.
     */
    private static void captureRiddenAnimal(ClientGameTestContext context, double mountX, double mountY,
            double mountZ) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.setScreen(null);
        });
        ContainerDriver.aimEyesAt(context, new Vec3(mountX, mountY + 0.75, mountZ));
        ContainerDriver.awaitCrosshair(context, WdlChestedAnimalCaptureTest::isLookingAtAnimal, "the mount");
        context.runOnClient(client -> {
            client.gameRenderer.pick(1.0f);
            EntityHitResult hit = (EntityHitResult) client.hitResult;
            // Not sneaking, so a tamed chested animal rides rather than opening its inventory.
            client.gameMode.interact(client.player, hit.getEntity(), InteractionHand.MAIN_HAND);
        });
        context.waitFor(client -> client.player.getVehicle() instanceof AbstractChestedHorse);

        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-ridden-chested-animal", "wdl-ridden-chested-animal", DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        run.tick(5);
        // The packet vanilla's inventory key sends, not the key itself: a chested mount is not an eligible
        // vehicle intent either way, so this open reaches the bind with an empty target, which is the state
        // the scenario exists to cover.
        context.runOnClient(client -> client.player.sendOpenInventory());
        ContainerDriver.awaitMenuReady(context, run,
                client -> client.player.containerMenu instanceof HorseInventoryMenu menu
                        && menu.getSlot(2).getItem().is(Items.DIAMOND),
                "ridden chested animal chest");
        run.tick(5);
        Path saveRoot = run.stopAndAwaitSave();

        List<String> mountItems = CaptureReadback.itemIds(CaptureReadback.levelData(saveRoot)
                .getCompoundOrEmpty("Player").getCompoundOrEmpty("RootVehicle").getCompoundOrEmpty("Entity"));
        Check.that(mountItems.contains("minecraft:diamond"),
                "the ridden donkey's chest is absent from the saved RootVehicle: " + mountItems);
    }

    /** Whether the client's crosshair is resolved onto a chested animal (the chested-animal bind precondition). */
    private static boolean isLookingAtAnimal(Minecraft client) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity() instanceof AbstractChestedHorse;
    }
}
