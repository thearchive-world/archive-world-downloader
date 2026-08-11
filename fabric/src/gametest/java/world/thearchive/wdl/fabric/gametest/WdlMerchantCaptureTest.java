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
import net.minecraft.world.entity.npc.Villager;
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

/**
 * Villager trade axis: a villager's trade offers and trade experience reach the client only when the player opens its
 * trade menu, never in a spawn or chunk packet, so the capture drives the open to bind on the villager's UUID, stashes
 * the offers every tick the menu is open, and folds them onto the villager tag in the {@code entities/} region. Present
 * offers on disk assert the open-time capture reached disk.
 *
 * <p>The sell item of a cartographer's explorer map is the one full item stack an offer carries that a downloader has
 * to process: its {@code map_id} is session-local and would collide with a real map, so it rides the same map archive
 * every captured map takes. Vanilla streams a map's colors only for a carried map, and a trade-result item is not
 * carried, so the client never receives the offer map's picture: the archived map is faithfully blank, and only its id
 * is remapped to a collision-safe archive id. The structure marker ({@code map_decorations}) is the deliberate
 * residual, kept.
 *
 * <p>The bind resolves the entity the player clicked, latched by the loader's use hook, so the drive aims at the
 * villager, confirms the crosshair is on it, and opens it with the use key, the one path that reaches that hook. A
 * direct game-mode interact would open the same menu with no provenance and bind nothing.
 */
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

    /**
     * A villager kept standing after its trade menu was opened, reused by the tests here so none has to summon a second
     * villager under the crosshair.
     */
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

    /**
     * Open a cartographer, download, and read its offers, trade experience, and the remapped explorer-map sell item
     * back off disk. The remapped sell-map id is the end-to-end proof that the flush-time scrub and remap runs on the
     * offer holder in production, not only in its unit test.
     */
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

        // The use key, not a direct gameMode.interact: an entity interact reaches the loader's use hook only through
        // MC's own use-key handling, and that hook is what latches the clicked villager the bind resolves from. The
        // screen is dismissed first, or the keybind press is swallowed.
        context.runOnClient(client -> {
            client.gameRenderer.pick(1.0f);
            Check.that(isLookingAtVillager(client),
                    "crosshair drifted off the villager before opening: " + client.hitResult);
            client.setScreen(null);
        });
        context.getInput().holdKey(options -> options.keyUse);
        // Wait on the offers syncing into the open merchant menu, the narrowest signal the trade screen actually
        // received them, not merely that some screen opened.
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
                                .map(entity -> entity.getString("id")).toList()));

        CompoundTag sell = villager.getCompound("Offers").getList("Recipes", 10).getCompound(0)
                .getCompound("sell");
        Check.that(sell.getString("id").equals("minecraft:filled_map"),
                "the captured offer's sell item is not the cartographer's map: " + sell);
        Check.that(villager.getInt("Xp") == 9,
                "the villager's trade experience was not captured: Xp=" + villager.getInt("Xp"));

        CompoundTag components = sell.getCompound("components");
        int onDiskMapId = components.getInt("minecraft:map_id");
        Check.that(onDiskMapId != OFFER_MAP_SESSION_ID,
                "the offer map's session id was not remapped to a collision-safe archive id: still "
                        + OFFER_MAP_SESSION_ID);
        Check.that(onDiskMapId >= 0, "the remapped offer map id is not a valid archive id: " + onDiskMapId);
        Check.that(components.contains("minecraft:map_decorations"),
                "the explorer map's structure marker (the kept residual) was dropped from the captured offer");
        return new OpenedVillager(villagerId, villagerChunk, villagerEyes);
    }

    /**
     * The change-gate bypass. While the trade screen is open and the player touches nothing, the villager is mutated
     * server-side so a fresh offers packet arrives with no menu-slot interaction, and the change is asserted on disk.
     * The merchant re-stash runs on its own per-tick path, outside the slot-keyed change gate, precisely so a
     * no-slot-touch offers change is captured. A completed trade cannot stand in for this: inserting payment and taking
     * the result moves the three menu slots, so the slot-keyed gate would fire anyway.
     */
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
            client.gameRenderer.pick(1.0f);
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
        // resends the mutated offers to the trading player. A bare restock with no delta resends nothing new, which
        // would leave the broken version falsely green.
        server.runOnServer(minecraftServer -> {
            if (minecraftServer.overworld().getEntity(villager.id()) instanceof Villager trader) {
                trader.getOffers().add(new MerchantOffer(new ItemCost(Items.EMERALD, 1),
                        new ItemStack(Items.DIAMOND), 12, 0, 0.0f));
                trader.restock();
            }
        });
        // The two-offer state is the narrowest distinguishing signal that the resend reached the client.
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
        int recipeCount = trader.getCompound("Offers").getList("Recipes", 10).size();
        Check.that(recipeCount == 2,
                "the no-slot-touch offers change was not captured: the disk holds " + recipeCount + " recipes, not 2");
    }

    /**
     * The outline rim. A tradeable villager draws an unsaved rim until its trades are captured, and a nitwit, which can
     * never trade, never draws one, since a rim it could never clear would be a permanent false to-do. The rim clears
     * the instant the villager's UUID enters the captured set, the same open that captures its offers.
     */
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
            client.gameRenderer.pick(1.0f);
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

    /**
     * Resume carry-forward. A resumed download that re-encounters the cartographer without re-opening it re-serializes
     * it client-reconstructed, with no {@code Offers} and {@code Xp:0}, so the carry-forward must keep the on-disk
     * {@code Offers} and {@code Xp} rather than let that empty node overwrite them.
     *
     * <p>Wounding the cartographer server-side between the two runs is a control the assertions depend on:
     * {@code Health} is a synced field a genuine re-serialize always writes at its live value, so the wounded value on
     * the resumed save rules out the case where the chunk was never re-captured and the old node survived untouched,
     * which would satisfy the trade check for the wrong reason.
     */
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
            client.gameRenderer.pick(1.0f);
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
        Check.that(!beforeResume.getCompound("Offers").getList("Recipes", 10).isEmpty(),
                "the NEW capture seeding the resume fixture did not record the cartographer's offers");
        Check.that(beforeResume.getInt("Xp") == 9,
                "the NEW capture seeding the resume fixture did not record the cartographer's trade experience");
        float originalHealth = beforeResume.getFloat("Health");
        Check.that(originalHealth > 0,
                "the NEW capture recorded no plausible health for the cartographer: " + originalHealth);

        // Close the menu before resuming, so the re-encounter cannot re-stash the offers from an open menu and mask
        // the disk carry-forward this test proves.
        context.runOnClient(client -> client.player.closeContainer());
        context.waitFor(client -> client.player.containerMenu == client.player.inventoryMenu);

        float woundedHealth = originalHealth - 3.0f;
        server.runOnServer(minecraftServer -> {
            if (minecraftServer.overworld().getEntity(cartographer.id()) instanceof Villager trader) {
                trader.setHealth(woundedHealth);
            }
        });
        context.waitFor(client -> entityByUuid(client, cartographer.id()) instanceof LivingEntity mob
                && mob.getHealth() == woundedHealth);

        CaptureDriver resumeRun = CaptureDriver.start(context,
                new DownloadTarget("wdl-merchant-resume", "wdl-merchant-resume", DownloadMode.RESUME),
                WdlConfig.DEFAULTS);
        Path resumedSave = resumeRun.tick(30).stopAndAwaitSave();

        CompoundTag afterResume = readCartographerNode(resumedSave, cartographer);
        Check.that(afterResume.getFloat("Health") == woundedHealth,
                "the cartographer's chunk was not genuinely re-captured on resume (health reads "
                        + afterResume.getFloat("Health") + ", not the wounded " + woundedHealth
                        + "), so the carry-forward checks below would pass for the wrong reason");
        Check.that(!afterResume.getCompound("Offers").getList("Recipes", 10).isEmpty(),
                "RESUME overwrote the cartographer's trade offers with the reconstructed empty node");
        Check.that(afterResume.getInt("Xp") == 9,
                "RESUME overwrote the cartographer's trade experience with the reconstructed Xp:0");
    }

    /**
     * The capture-entities toggle off. The whole villager-trade axis, capture and the outline rim alike, rides
     * {@code config.captureEntities()}, so opening the cartographer with it off saves no trade offers and draws no rim.
     */
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
            client.gameRenderer.pick(1.0f);
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
                .map(node -> !node.getCompound("Offers").getList("Recipes", 10).isEmpty())
                .orElse(false);
        Check.that(!offersReachedDisk, "captureEntities off still captured the cartographer's offers to disk");
    }

    /** The cartographer's entity-region node on {@code saveRoot}, failing loudly if the save lacks it. */
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

    /** Whether some entity-rim box {@code run} draws right now intersects {@code entityId}'s live bounding box. */
    private static boolean rimmed(ClientGameTestContext context, CaptureDriver run, UUID entityId) {
        List<AABB> rims = run.entityRimBoxes();
        AABB box = context.computeOnClient(client -> {
            Entity entity = entityByUuid(client, entityId);
            if (entity == null) {
                throw new AssertionError("entity " + entityId + " is not present on the client");
            }
            return entity.getBoundingBox();
        });
        return rims.stream().anyMatch(rim -> rim.intersects(box));
    }

    /** The no-trades negative fixture for the outline rim: a nitwit villager never rims, having nothing to sell. */
    private static String summonNitwit(double x, double y, double z) {
        return "summon minecraft:villager " + x + " " + y + " " + z + " {NoAI:1b,"
                + "VillagerData:{type:\"minecraft:plains\",profession:\"minecraft:nitwit\",level:1}}";
    }

    /** A villager near the player other than {@code excluding} (disambiguates the nitwit from the cartographer). */
    private static Optional<Entity> otherVillager(Minecraft client, UUID excluding) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(8)).stream()
                .filter(entity -> entity.getType() == EntityType.VILLAGER && !entity.getUUID().equals(excluding))
                .findFirst();
    }

    /** The cartographer summoned near the player, absent until it reaches the client. */
    private static Optional<Entity> nearbyVillager(Minecraft client) {
        return client.level.getEntities((Entity) null, client.player.getBoundingBox().inflate(6)).stream()
                .filter(entity -> entity.getType() == EntityType.VILLAGER)
                .findFirst();
    }

    private static Entity entityByUuid(Minecraft client, UUID id) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.getUUID().equals(id)) {
                return entity;
            }
        }
        return null;
    }

    /** Whether the client's crosshair is resolved onto a villager (the merchant-bind precondition). */
    private static boolean isLookingAtVillager(Minecraft client) {
        return client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getType() == HitResult.Type.ENTITY
                && entityHit.getEntity().getType() == EntityType.VILLAGER;
    }
}
