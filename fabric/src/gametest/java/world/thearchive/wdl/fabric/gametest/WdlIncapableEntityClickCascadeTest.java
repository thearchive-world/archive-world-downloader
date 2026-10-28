// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * An employed or {@code NONE} villager stays menu-capable precisely because a nitwit is the one profession state
 * provably immune to a profession-sync race.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlIncapableEntityClickCascadeTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();
            BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
            capturePigClickThenChest(context, server, stand);
            captureNitwitClickThenChest(context, server, stand);
        }
    }

    private static void capturePigClickThenChest(ClientGameTestContext context, TestServerContext server,
            BlockPos stand) {
        double pigX = stand.getX() - 2 + 0.5;
        double pigY = stand.getY();
        double pigZ = stand.getZ() + 0.5;
        server.runCommand("summon minecraft:pig " + pigX + " " + pigY + " " + pigZ + " {NoAI:1b}");
        context.waitFor(client -> nearbyPig(client).isPresent());

        BlockPos chest = new BlockPos(stand.getX(), stand.getY(), stand.getZ() + 2);
        ContainerDriver.placeFilledChest(server, chest);
        context.waitFor(client -> client.level.getBlockEntity(chest) instanceof ChestBlockEntity);

        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-incapable-cascade-pig", "wdl-incapable-cascade-pig", DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        run.tick(5);

        ContainerDriver.aimEyesAt(context, new Vec3(pigX, pigY + 0.5, pigZ));
        ContainerDriver.awaitCrosshair(context, WdlIncapableEntityClickCascadeTest::isLookingAtPig, "pig");
        context.runOnClient(client -> {
            client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
            Check.that(isLookingAtPig(client),
                    "crosshair drifted off the pig before clicking: " + client.hitResult);
            client.gui.setScreen(null);
        });
        // The use key, not a direct gameMode.interact: that reaches neither the use key nor the loader's client-side
        // use hook, and this scenario needs the click to actually latch a pending entity intent.
        context.getInput().holdKey(options -> options.keyUse);
        run.tick(5);
        context.getInput().releaseKey(options -> options.keyUse);
        context.runOnClient(client -> Check.that(client.player.containerMenu == client.player.inventoryMenu,
                "the pig click opened a menu, so this scenario is not the menu-incapable case it claims to be"));

        openChestAndAssertCaptured(context, run, chest);
    }

    private static void captureNitwitClickThenChest(ClientGameTestContext context, TestServerContext server,
            BlockPos stand) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.gui.setScreen(null);
        });

        double villagerX = stand.getX() + 2 + 0.5;
        double villagerY = stand.getY();
        double villagerZ = stand.getZ() + 0.5;
        server.runCommand(summonNitwit(villagerX, villagerY, villagerZ));
        context.waitFor(client -> nearbyVillager(client).isPresent());

        BlockPos chest = new BlockPos(stand.getX(), stand.getY(), stand.getZ() - 2);
        ContainerDriver.placeFilledChest(server, chest);
        context.waitFor(client -> client.level.getBlockEntity(chest) instanceof ChestBlockEntity);

        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-incapable-cascade-nitwit", "wdl-incapable-cascade-nitwit",
                        DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        run.tick(5);

        ContainerDriver.aimEyesAt(context, new Vec3(villagerX, villagerY + 1.0, villagerZ));
        ContainerDriver.awaitCrosshair(context, WdlIncapableEntityClickCascadeTest::isLookingAtVillager,
                "nitwit villager");
        context.runOnClient(client -> {
            client.hitResult = client.player.raycastHitResult(1.0f, client.getCameraEntity());
            Check.that(isLookingAtVillager(client),
                    "crosshair drifted off the nitwit before clicking: " + client.hitResult);
            client.gui.setScreen(null);
        });
        context.getInput().holdKey(options -> options.keyUse);
        run.tick(5);
        context.getInput().releaseKey(options -> options.keyUse);
        context.runOnClient(client -> Check.that(client.player.containerMenu == client.player.inventoryMenu,
                "the nitwit click opened a trade menu, so this scenario is not the menu-incapable case it "
                        + "claims to be"));

        openChestAndAssertCaptured(context, run, chest);
    }

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
                "the chest opened right after a menu-incapable entity click did not enter the captured-set "
                        + "(the incapable click poisoned the chest's own open): " + chest);

        Path saveRoot = run.stopAndAwaitSave();
        List<String> chestItems = ContainerDriver.capturedChestItems(saveRoot, chest);
        Check.that(chestItems.contains(ContainerDriver.PLANTED_ITEM),
                "the chest's planted item is absent from its captured Items despite the preceding "
                        + "menu-incapable entity click: " + chestItems);
    }

    private static String summonNitwit(double x, double y, double z) {
        return "summon minecraft:villager " + x + " " + y + " " + z + " {NoAI:1b,"
                + "VillagerData:{type:\"minecraft:plains\",profession:\"minecraft:nitwit\",level:1}}";
    }

    private static Optional<Entity> nearbyPig(Minecraft client) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(6)).stream()
                .filter(entity -> entity.getType() == EntityTypes.PIG)
                .findFirst();
    }

    private static Optional<Entity> nearbyVillager(Minecraft client) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(6)).stream()
                .filter(entity -> entity.getType() == EntityTypes.VILLAGER)
                .findFirst();
    }

    private static boolean isLookingAtPig(Minecraft client) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity().getType() == EntityTypes.PIG;
    }

    private static boolean isLookingAtVillager(Minecraft client) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity().getType() == EntityTypes.VILLAGER;
    }
}
