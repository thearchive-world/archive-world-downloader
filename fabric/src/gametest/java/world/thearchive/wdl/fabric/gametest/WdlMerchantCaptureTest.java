// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

@SuppressWarnings("UnstableApiUsage")
public class WdlMerchantCaptureTest implements FabricClientGameTest {
    /**
     * A cartographer standing still, one offer selling an explorer map: a filled map whose {@code map_id} is a high,
     * unique session id the client never received an image for (as a real offer map, uncarried, is), plus the structure
     * marker vanilla puts on such a map. The high id makes the remap observable, since the archive hands out low ids.
     */
    private static final int OFFER_MAP_SESSION_ID = 30000;

    private static String summonCartographer(double x, double y, double z) {
        return "summon minecraft:villager " + x + " " + y + " " + z + " {NoAI:1b,"
                + "VillagerData:{type:\"minecraft:plains\",profession:\"minecraft:cartographer\",level:2},Xp:9,"
                + "Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:8},"
                + "sell:{id:\"minecraft:filled_map\",count:1,components:{"
                + "\"minecraft:map_id\":" + OFFER_MAP_SESSION_ID + ","
                + "\"minecraft:map_decorations\":{\"structure\":"
                + "{type:\"minecraft:red_x\",x:1000.0d,z:1000.0d,rotation:0.0f}}}},"
                + "maxUses:12,uses:0,rewardExp:1b,priceMultiplier:0.2f,demand:0,specialPrice:0,xp:5}]}}";
    }

    private record OpenedVillager(UUID id, ChunkPos chunk, Vec3 eyes) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();
            OpenedVillager villager = captureCartographerMapTrade(context, server);
            captureNoSlotTouchOffersChange(context, server, villager);
            captureOutlineRim(context, server, villager);
            captureResumeCarriesTradesForward(context, server, villager);
            captureTradesOffWhenEntitiesDisabled(context, server, villager);
        }
    }

    private static OpenedVillager captureCartographerMapTrade(ClientGameTestContext context,
            TestServerContext server) {
        BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
        double villagerX = stand.getX() + 0.5;
        double villagerY = stand.getY();
        double villagerZ = stand.getZ() + 2 + 0.5;
        server.runCommand(summonCartographer(villagerX, villagerY, villagerZ));
        context.waitFor(client -> nearbyVillager(client).isPresent());

        Vec3 villagerEyes = new Vec3(villagerX, villagerY + 1.3, villagerZ);
        ContainerDriver.aimEyesAt(context, villagerEyes);
        ContainerDriver.awaitCrosshair(context, WdlMerchantCaptureTest::isLookingAtVillager, "villager");
        UUID villagerId = context
                .computeOnClient(client -> ((EntityHitResult) client.hitResult).getEntity().getUUID());
        ChunkPos villagerChunk = context
                .computeOnClient(client -> ((EntityHitResult) client.hitResult).getEntity().chunkPosition());

        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-merchant", "wdl-merchant", DownloadMode.NEW), WdlConfig.DEFAULTS);
        run.tick(5);

        // The use key, not a direct gameMode.interact: an entity interact reaches the loader's client-side use hook
        // only through MC's own use-key handling, and that hook is what latches the clicked villager the bind resolves
        // from. The screen is dismissed first, or the keybind press is swallowed.
        context.runOnClient(client -> {
            client.hitResult = client.player.raycastHitResult(4.0f, client.getCameraEntity());
            Check.that(isLookingAtVillager(client),
                    "crosshair drifted off the villager before opening: " + client.hitResult);
            client.setScreen(null);
        });
        context.getInput().holdKey(options -> options.keyUse);
        context.waitFor(client -> client.player.containerMenu instanceof MerchantMenu merchant
                && !merchant.getOffers().isEmpty());
        run.tick(5);
        context.getInput().releaseKey(options -> options.keyUse);
        run.tick(5);

        Check.that(run.isCaptured(captured -> captured.containsEntity(villagerId)),
                "opening the villager did not enter its UUID into the captured-set: " + villagerId);

        Path saveRoot = run.stopAndAwaitSave();

        CompoundTag entityChunk = CaptureReadback.readEntityChunk(saveRoot, villagerChunk)
                .orElseThrow(() -> new AssertionError("entity chunk " + villagerChunk + " is missing from the save"));
        CompoundTag villager = CaptureReadback.entities(entityChunk).stream()
                .filter(entity -> CaptureReadback.entityUuid(entity).filter(villagerId::equals).isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("the villager is absent from the entities region: "
                        + CaptureReadback.entities(entityChunk).stream()
                                .map(entity -> entity.getStringOr("id", "?")).toList()));

        CompoundTag sell = villager.getCompoundOrEmpty("Offers").getListOrEmpty("Recipes").getCompoundOrEmpty(0)
                .getCompoundOrEmpty("sell");
        Check.that(sell.getStringOr("id", "").equals("minecraft:filled_map"),
                "the captured offer's sell item is not the cartographer's map: " + sell);
        Check.that(villager.getIntOr("Xp", -1) == 9,
                "the villager's trade experience was not captured: Xp=" + villager.getIntOr("Xp", -1));

        CompoundTag components = sell.getCompoundOrEmpty("components");
        int onDiskMapId = components.getIntOr("minecraft:map_id", Integer.MIN_VALUE);
        Check.that(onDiskMapId != OFFER_MAP_SESSION_ID,
                "the offer map's session id was not remapped to a collision-safe archive id: still "
                        + OFFER_MAP_SESSION_ID);
        Check.that(onDiskMapId >= 0, "the remapped offer map id is not a valid archive id: " + onDiskMapId);
        Check.that(components.contains("minecraft:map_decorations"),
                "the explorer map's structure marker (the kept residual) was dropped from the captured offer");
        return new OpenedVillager(villagerId, villagerChunk, villagerEyes);
    }

    private static void captureNoSlotTouchOffersChange(ClientGameTestContext context, TestServerContext server,
            OpenedVillager villager) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.setScreen(null);
        });
        ContainerDriver.aimEyesAt(context, villager.eyes());
        ContainerDriver.awaitCrosshair(context, WdlMerchantCaptureTest::isLookingAtVillager, "villager");

        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-merchant-change", "wdl-merchant-change", DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        run.tick(5);
        context.runOnClient(client -> {
            client.hitResult = client.player.raycastHitResult(4.0f, client.getCameraEntity());
            Check.that(isLookingAtVillager(client), "crosshair drifted off the villager before re-opening it");
            client.setScreen(null);
        });
        context.getInput().holdKey(options -> options.keyUse);
        // Capture the original one-offer state first, so the broken (gated) version stalls here and the added offer
        // never reaches it.
        context.waitFor(client -> client.player.containerMenu instanceof MerchantMenu merchant
                && merchant.getOffers().size() == 1);
        run.tick(5);

        // Inject a real content delta and resend it with no slot interaction: add a second offer and restock, which
        // resends the mutated offers to the trading player. A bare restock with no delta resends nothing new.
        server.runOnServer(minecraftServer -> {
            if (minecraftServer.overworld().getEntity(villager.id()) instanceof Villager trader) {
                trader.getOffers().add(new MerchantOffer(new ItemCost(Items.EMERALD, 1),
                        new ItemStack(Items.DIAMOND), 12, 0, 0.0f));
                trader.restock();
            }
        });
        context.waitFor(client -> client.player.containerMenu instanceof MerchantMenu merchant
                && merchant.getOffers().size() == 2);
        run.tick(5);
        context.getInput().releaseKey(options -> options.keyUse);
        run.tick(5);

        Path saveRoot = run.stopAndAwaitSave();

        CompoundTag entityChunk = CaptureReadback.readEntityChunk(saveRoot, villager.chunk())
                .orElseThrow(
                        () -> new AssertionError("entity chunk " + villager.chunk() + " is missing from the save"));
        CompoundTag trader = CaptureReadback.entities(entityChunk).stream()
                .filter(entity -> CaptureReadback.entityUuid(entity).filter(villager.id()::equals).isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("the villager is absent from the entities region after re-open"));
        int recipeCount = trader.getCompoundOrEmpty("Offers").getListOrEmpty("Recipes").size();
        Check.that(recipeCount == 2,
                "the no-slot-touch offers change was not captured: the disk holds " + recipeCount + " recipes, not 2");
    }

    private static void captureOutlineRim(ClientGameTestContext context, TestServerContext server,
            OpenedVillager cartographer) {
        BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
        server.runCommand(summonNitwit(stand.getX() - 2 + 0.5, stand.getY(), stand.getZ() + 0.5));
        context.waitFor(client -> otherVillager(client, cartographer.id()).isPresent());
        UUID nitwitId = context.computeOnClient(client -> otherVillager(client, cartographer.id())
                .orElseThrow(() -> new AssertionError("the nitwit fixture never reached the client"))
                .getUUID());

        context.runOnClient(client -> {
            client.player.closeContainer();
            client.setScreen(null);
        });
        ContainerDriver.aimEyesAt(context, cartographer.eyes());
        ContainerDriver.awaitCrosshair(context, WdlMerchantCaptureTest::isLookingAtVillager, "villager");

        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-merchant-rim", "wdl-merchant-rim", DownloadMode.NEW), WdlConfig.DEFAULTS);
        run.tick(5);

        Check.that(rimmed(context, run, cartographer.id()),
                "the cartographer was not rimmed before its trades were captured");
        Check.that(!rimmed(context, run, nitwitId), "the nitwit was rimmed even though it has no trades");

        context.runOnClient(client -> {
            client.hitResult = client.player.raycastHitResult(4.0f, client.getCameraEntity());
            Check.that(isLookingAtVillager(client),
                    "crosshair drifted off the cartographer before re-opening it: " + client.hitResult);
            client.setScreen(null);
        });
        context.getInput().holdKey(options -> options.keyUse);
        context.waitFor(client -> client.player.containerMenu instanceof MerchantMenu merchant
                && !merchant.getOffers().isEmpty());
        run.tick(5);
        context.getInput().releaseKey(options -> options.keyUse);
        run.tick(5);

        Check.that(run.isCaptured(captured -> captured.containsEntity(cartographer.id())),
                "opening the cartographer did not enter its UUID into the captured-set: " + cartographer.id());

        boolean cleared = false;
        for (int tick = 0; tick < 40 && !cleared; tick++) {
            cleared = !rimmed(context, run, cartographer.id());
            if (!cleared) {
                context.waitTick();
            }
        }
        Check.that(cleared, "the cartographer's rim never cleared within 40 ticks after its trades were captured");

        run.stopAndAwaitSave();
    }

    private static void captureResumeCarriesTradesForward(ClientGameTestContext context, TestServerContext server,
            OpenedVillager cartographer) {
        context.runOnClient(client -> {
            client.player.closeContainer();
            client.setScreen(null);
        });
        ContainerDriver.aimEyesAt(context, cartographer.eyes());
        ContainerDriver.awaitCrosshair(context, WdlMerchantCaptureTest::isLookingAtVillager, "villager");

        CaptureDriver newRun = CaptureDriver.start(context,
                new DownloadTarget("wdl-merchant-resume", "wdl-merchant-resume", DownloadMode.NEW),
                WdlConfig.DEFAULTS);
        newRun.tick(5);
        context.runOnClient(client -> {
            client.hitResult = client.player.raycastHitResult(4.0f, client.getCameraEntity());
            Check.that(isLookingAtVillager(client),
                    "crosshair drifted off the cartographer before opening it: " + client.hitResult);
            client.setScreen(null);
        });
        context.getInput().holdKey(options -> options.keyUse);
        context.waitFor(client -> client.player.containerMenu instanceof MerchantMenu merchant
                && !merchant.getOffers().isEmpty());
        newRun.tick(5);
        context.getInput().releaseKey(options -> options.keyUse);
        newRun.tick(5);

        Path newSave = newRun.stopAndAwaitSave();
        CompoundTag beforeResume = readCartographerNode(newSave, cartographer);
        Check.that(!beforeResume.getCompoundOrEmpty("Offers").getListOrEmpty("Recipes").isEmpty(),
                "the NEW capture seeding the resume fixture did not record the cartographer's offers");
        Check.that(beforeResume.getIntOr("Xp", -1) == 9,
                "the NEW capture seeding the resume fixture did not record the cartographer's trade experience");
        float originalHealth = beforeResume.getFloatOr("Health", -1f);
        Check.that(originalHealth > 0,
                "the NEW capture recorded no plausible health for the cartographer: " + originalHealth);

        context.runOnClient(client -> client.player.closeContainer());
        context.waitFor(client -> client.player.containerMenu == client.player.inventoryMenu);

        float woundedHealth = originalHealth - 3.0f;
        server.runOnServer(minecraftServer -> {
            if (minecraftServer.overworld().getEntity(cartographer.id()) instanceof Villager trader) {
                trader.setHealth(woundedHealth);
            }
        });
        context.waitFor(client -> client.level.getEntity(cartographer.id()) instanceof LivingEntity mob
                && mob.getHealth() == woundedHealth);

        CaptureDriver resumeRun = CaptureDriver.start(context,
                new DownloadTarget("wdl-merchant-resume", "wdl-merchant-resume", DownloadMode.RESUME),
                WdlConfig.DEFAULTS);
        Path resumedSave = resumeRun.tick(30).stopAndAwaitSave();

        CompoundTag afterResume = readCartographerNode(resumedSave, cartographer);
        Check.that(afterResume.getFloatOr("Health", -1f) == woundedHealth,
                "the cartographer's chunk was not genuinely re-captured on resume (health reads "
                        + afterResume.getFloatOr("Health", -1f) + ", not the wounded " + woundedHealth
                        + "), so the carry-forward checks below would pass for the wrong reason");
        Check.that(!afterResume.getCompoundOrEmpty("Offers").getListOrEmpty("Recipes").isEmpty(),
                "RESUME overwrote the cartographer's trade offers with the reconstructed empty node");
        Check.that(afterResume.getIntOr("Xp", -1) == 9,
                "RESUME overwrote the cartographer's trade experience with the reconstructed Xp:0");
    }

    private static void captureTradesOffWhenEntitiesDisabled(ClientGameTestContext context, TestServerContext server,
            OpenedVillager cartographer) {
        Properties entitiesOff = new Properties();
        entitiesOff.setProperty("captureEntities", "false");
        WdlConfig offConfig = WdlConfig.parse(entitiesOff);

        context.runOnClient(client -> {
            client.player.closeContainer();
            client.setScreen(null);
        });
        ContainerDriver.aimEyesAt(context, cartographer.eyes());
        ContainerDriver.awaitCrosshair(context, WdlMerchantCaptureTest::isLookingAtVillager, "villager");

        CaptureDriver run = CaptureDriver.start(context,
                new DownloadTarget("wdl-merchant-off", "wdl-merchant-off", DownloadMode.NEW), offConfig);
        run.tick(5);

        Check.that(!rimmed(context, run, cartographer.id()),
                "captureEntities off still rimmed the un-captured cartographer");

        context.runOnClient(client -> {
            client.hitResult = client.player.raycastHitResult(4.0f, client.getCameraEntity());
            Check.that(isLookingAtVillager(client),
                    "crosshair drifted off the cartographer before opening it: " + client.hitResult);
            client.setScreen(null);
        });
        context.getInput().holdKey(options -> options.keyUse);
        context.waitFor(client -> client.player.containerMenu instanceof MerchantMenu merchant
                && !merchant.getOffers().isEmpty());
        run.tick(5);
        context.getInput().releaseKey(options -> options.keyUse);
        run.tick(5);

        Check.that(!run.isCaptured(captured -> captured.containsEntity(cartographer.id())),
                "captureEntities off still entered the opened villager into the captured-set");

        Path saveRoot = run.stopAndAwaitSave();

        boolean offersReachedDisk = CaptureReadback.readEntityChunk(saveRoot, cartographer.chunk())
                .flatMap(chunk -> CaptureReadback.entities(chunk).stream()
                        .filter(entity -> CaptureReadback.entityUuid(entity).filter(cartographer.id()::equals)
                                .isPresent())
                        .findFirst())
                .map(node -> !node.getCompoundOrEmpty("Offers").getListOrEmpty("Recipes").isEmpty())
                .orElse(false);
        Check.that(!offersReachedDisk, "captureEntities off still captured the cartographer's offers to disk");
    }

    private static CompoundTag readCartographerNode(Path saveRoot, OpenedVillager cartographer) {
        CompoundTag entityChunk = CaptureReadback.readEntityChunk(saveRoot, cartographer.chunk())
                .orElseThrow(() -> new AssertionError(
                        "entity chunk " + cartographer.chunk() + " is missing from " + saveRoot));
        return CaptureReadback.entities(entityChunk).stream()
                .filter(entity -> CaptureReadback.entityUuid(entity).filter(cartographer.id()::equals).isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("the cartographer is absent from the entities region under "
                        + saveRoot));
    }

    private static boolean rimmed(ClientGameTestContext context, CaptureDriver run, UUID entityId) {
        List<AABB> rims = run.entityRimBoxes();
        AABB box = context.computeOnClient(client -> {
            Entity entity = client.level.getEntity(entityId);
            if (entity == null) {
                throw new AssertionError("entity " + entityId + " is not present on the client");
            }
            return entity.getBoundingBox();
        });
        return rims.stream().anyMatch(rim -> rim.intersects(box));
    }

    private static String summonNitwit(double x, double y, double z) {
        return "summon minecraft:villager " + x + " " + y + " " + z + " {NoAI:1b,"
                + "VillagerData:{type:\"minecraft:plains\",profession:\"minecraft:nitwit\",level:1}}";
    }

    private static Optional<Entity> otherVillager(Minecraft client, UUID excluding) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(8)).stream()
                .filter(entity -> entity.getType() == EntityType.VILLAGER && !entity.getUUID().equals(excluding))
                .findFirst();
    }

    private static Optional<Entity> nearbyVillager(Minecraft client) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(6)).stream()
                .filter(entity -> entity.getType() == EntityType.VILLAGER)
                .findFirst();
    }

    private static boolean isLookingAtVillager(Minecraft client) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity().getType() == EntityType.VILLAGER;
    }
}
