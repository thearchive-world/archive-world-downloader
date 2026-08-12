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
import net.minecraft.world.entity.EntityType;
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
 * The no-menu-click cascade fix: a right-click on an entity that provably opens no server-driven menu (a pig, or a
 * nitwit villager, which never has trade offers to open a screen for) must not poison the next container the player
 * opens. Before the fix, {@code OpenClickTracker} recorded every entity click as menu-capable, so the following chest
 * click superseded the entity's still-pending intent and minted a marker; when the chest's own open then arrived,
 * {@code OpenClickIntent.resolve} consumed that marker first and returned SUPERSEDED, dropping the chest's contents
 * even though the player watched it open. Neither fixture entity ever opens a menu itself, so nothing else in either
 * scenario would ever consume the marker.
 *
 * <p>Each entity click must reach {@code OpenClickTracker.dispatchUseEntity} through the use key, the loader's own hook
 * injection site, the same reason every other entity-click scenario in this suite drives through
 * {@code context.getInput()} rather than a direct {@code gameMode.interact} call; the chest click instead goes through
 * {@code gameMode.useItemOn} directly, {@code OpenClickTracker.dispatchUseBlock}'s own injection site, matching
 * {@link WdlContainerCaptureTest}. The pig covers the vanilla-namespace/non-villager extraction path; the nitwit covers
 * the live villager profession and baby extraction ({@code menuIncapable}'s sole non-trivial branch), the
 * corruption-sensitive case the design's risk asymmetry turns on (an employed or {@code NONE} villager stays
 * menu-capable precisely because a nitwit is the one profession state provably immune to a profession-sync race).
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

    /**
     * Right-click a menu-less pig, then open a filled chest one tick later, and assert the chest is captured (the pig's
     * incapable classification must have suppressed the superseded marker it would otherwise have minted on the chest's
     * overwrite).
     */
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
            client.gameRenderer.pick(1.0f);
            Check.that(isLookingAtPig(client),
                    "crosshair drifted off the pig before clicking: " + client.hitResult);
            client.setScreen(null);
        });
        // The use key, not a direct gameMode.interact: that reaches neither the use key nor the loader's use
        // hook (WdlEntityContainerCaptureTest's unattributed-open scenario proves the direct call is inert
        // here), and this scenario needs the click to actually latch a pending entity intent.
        context.getInput().holdKey(options -> options.keyUse);
        run.tick(5);
        context.getInput().releaseKey(options -> options.keyUse);
        context.runOnClient(client -> Check.that(client.player.containerMenu == client.player.inventoryMenu,
                "the pig click opened a menu, so this scenario is not the menu-incapable case it claims to be"));

        openChestAndAssertCaptured(context, run, chest);
    }

    /**
     * Right-click an adult nitwit villager, then open a filled chest one tick later, and assert the chest is captured.
     * The nitwit branch is {@code menuIncapable}'s only non-trivial live extraction (villager, baby, profession), so
     * unlike the pig this pins the actual field read of a live {@code Villager}'s {@code VillagerData}, not just an
     * {@code instanceof} chain.
     */
    private static void captureNitwitClickThenChest(ClientGameTestContext context, TestServerContext server,
            BlockPos stand) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.setScreen(null);
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
            client.gameRenderer.pick(1.0f);
            Check.that(isLookingAtVillager(client),
                    "crosshair drifted off the nitwit before clicking: " + client.hitResult);
            client.setScreen(null);
        });
        context.getInput().holdKey(options -> options.keyUse);
        run.tick(5);
        context.getInput().releaseKey(options -> options.keyUse);
        // A nitwit's mobInteract returns CONSUME (never startTrading) whenever its offers are empty, which for
        // NITWIT is permanent; this is the load-bearing check that the fixture is genuinely menu-less, not just
        // a trade menu this run happened not to wait for.
        context.runOnClient(client -> Check.that(client.player.containerMenu == client.player.inventoryMenu,
                "the nitwit click opened a trade menu, so this scenario is not the menu-incapable case it "
                        + "claims to be"));

        openChestAndAssertCaptured(context, run, chest);
    }

    /**
     * Open {@code chest} through the block-hook injection site ({@code gameMode.useItemOn}, matching
     * {@link WdlContainerCaptureTest}) and assert its planted contents reached both the live captured-set and disk.
     */
    private static void openChestAndAssertCaptured(ClientGameTestContext context, CaptureDriver run, BlockPos chest) {
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

    /** The nitwit fixture: an adult villager (no Age tag) whose profession can never carry trade offers. */
    private static String summonNitwit(double x, double y, double z) {
        return "summon minecraft:villager " + x + " " + y + " " + z + " {NoAI:1b,"
                + "VillagerData:{type:\"minecraft:plains\",profession:\"minecraft:nitwit\",level:1}}";
    }

    /** The pig summoned near the player, absent until it reaches the client. */
    private static Optional<Entity> nearbyPig(Minecraft client) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(6)).stream()
                .filter(entity -> entity.getType() == EntityType.PIG)
                .findFirst();
    }

    /** The nitwit summoned near the player, absent until it reaches the client. */
    private static Optional<Entity> nearbyVillager(Minecraft client) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(6)).stream()
                .filter(entity -> entity.getType() == EntityType.VILLAGER)
                .findFirst();
    }

    /** Whether the client's crosshair is resolved onto the pig (the entity-click precondition). */
    private static boolean isLookingAtPig(Minecraft client) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity().getType() == EntityType.PIG;
    }

    /** Whether the client's crosshair is resolved onto the villager (the entity-click precondition). */
    private static boolean isLookingAtVillager(Minecraft client) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity().getType() == EntityType.VILLAGER;
    }
}
