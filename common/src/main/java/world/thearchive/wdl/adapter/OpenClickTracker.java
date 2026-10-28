// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HasCustomInventoryScreen;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.EntityMenuCapability;
import world.thearchive.wdl.core.OpenClickIntent;

public final class OpenClickTracker {
    /**
     * The freshness window in ticks (twenty seconds): the click-to-open round trip on a badly lagged connection runs
     * well past ten seconds, and an expired latch leaves the open unattributed, which can drop it. The window is the
     * only ceiling on the leak the structural guards cannot bound, an intent still latched when an open it did not
     * cause arrives.
     */
    private static final long FRESH_WINDOW_TICKS = 400L;

    private static volatile @Nullable OpenClickTracker active;
    private static volatile boolean openInventoryRequested;

    private final OpenClickIntent intent = new OpenClickIntent(FRESH_WINDOW_TICKS);
    private long currentTick;
    private @Nullable Entity clickedEntity;

    static void activate(OpenClickTracker tracker) {
        openInventoryRequested = false;
        active = tracker;
    }

    static void deactivate(OpenClickTracker tracker) {
        if (active == tracker) {
            active = null;
        }
    }

    /**
     * Observe one local-player right-click on a block, or no-op when no download is active or it is not local. Every
     * click that can open a menu must stay in the intent chain: an unlatched menu open still consumes the next latch on
     * resolve, stealing a following container's provenance (an anvil open eating a furnace click binds the anvil's
     * three slots onto the three-slot furnace, a mis-bind), and menu blocks like the crafting family carry no block
     * entity, so menu-provider presence, not block-entity presence, is the load-bearing filter. A placement target,
     * door, or lever reports no provider and stays unlatched; a latched click on one could supersede real intents. The
     * ender chest is the one vanilla menu-opening block with no provider (its menu is built in the block's use handler
     * over the per-player ender container), so it is latched by its block entity; every other {@code openMenu} block
     * either overrides {@code getMenuProvider} or has a {@code MenuProvider} block entity.
     */
    public static void dispatchUseBlock(Player player, Level level, BlockHitResult hit) {
        OpenClickTracker tracker = active;
        if (tracker != null && level.isClientSide() && player == Minecraft.getInstance().player
                && !suppressesBlockUse(player)
                && opensMenuFor(level, hit.getBlockPos(), player.isSpectator())) {
            tracker.intent.recordBlockClick(hit.getBlockPos().asLong(), tracker.currentTick);
        }
    }

    /**
     * Vanilla's own gate: sneaking with a non-empty hand skips the block's use entirely, so no menu can open. Identical
     * predicate on both sides ({@code MultiPlayerGameMode} / {@code ServerPlayerGameMode}), so a click in this state
     * owes no open and must not be latched.
     */
    private static boolean suppressesBlockUse(Player player) {
        boolean haveSomethingInOurHands = !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty();
        return player.isSecondaryUseActive() && haveSomethingInOurHands;
    }

    /**
     * The spectator answer is narrower at the ender chest. The server's spectator branch skips the block's use handler,
     * and the ender chest's menu is built inside that handler; so an ordinary player's ender click opens a menu and a
     * spectator's opens nothing. The ender chest also carries its own obstruction rule, which its use handler applies
     * before opening anything, so an ender chest under a redstone conductor opens for nobody.
     *
     * <p>Not a complete test of "this click opens a menu", and must not be extended into one. Provider presence is not
     * sufficient: a shulker box with no room to open reports a provider and opens nothing for an ordinary player, while
     * a spectator bypasses that check, so its eligibility differs by gamemode in the opposite direction to the ender
     * chest's. A block latched without ever receiving its open leaves an intent the next unattributed open consumes.
     */
    static boolean opensMenuFor(Level level, BlockPos pos, boolean spectator) {
        if (level.getBlockState(pos).getMenuProvider(level, pos) != null) {
            return true;
        }
        return !spectator && level.getBlockEntity(pos) instanceof EnderChestBlockEntity
                && !enderChestObstructed(level, pos);
    }

    private static boolean enderChestObstructed(Level level, BlockPos pos) {
        BlockPos above = pos.above();
        return level.getBlockState(above).isRedstoneConductor(level, above);
    }

    /**
     * Observe one local-player right-click on an entity, or no-op when no download is active or it is not local.
     */
    public static void dispatchUseEntity(Player player, Level level, Entity entity) {
        OpenClickTracker tracker = active;
        if (tracker != null && level.isClientSide() && player == Minecraft.getInstance().player
                && anyEntityMayOpenMenu(entity)) {
            tracker.clickedEntity = entity;
            tracker.intent.recordEntityClick(entity.getId(), tracker.currentTick, menuIncapable(entity));
        }
    }

    /**
     * Baby/nitwit are robust to profession-sync staleness; NONE is deliberately excluded.
     */
    private static boolean menuIncapable(Entity entity) {
        boolean vanilla = "minecraft".equals(
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace());
        boolean villager = entity instanceof Villager;
        boolean baby = villager && ((Villager) entity).isBaby();
        boolean nitwit = villager
                && ((Villager) entity).getVillagerData().profession().is(VillagerProfession.NITWIT);
        return EntityMenuCapability.isMenuIncapable(vanilla, entity instanceof ContainerEntity,
                entity instanceof HasCustomInventoryScreen, entity instanceof AbstractVillager,
                villager, baby, nitwit);
    }

    /**
     * Always true, by decision rather than by omission.
     *
     * <p>The set of entities that can open a menu is not enumerable. Any modded entity can open one, and on a plugin
     * server so can any vanilla entity, so a filter written from the entities known here would be incomplete by
     * construction, and an incomplete filter is worse than none. A click it wrongly rejects still receives its menu,
     * and that menu arrives unlatched and consumes the next latch on resolve, stealing a following container's
     * provenance: a mis-bind, ranked strictly worse than the drop an over-latched click costs.
     */
    private static boolean anyEntityMayOpenMenu(Entity entity) {
        return true;
    }

    void tick() {
        currentTick++;
    }

    /**
     * The signal is drained whether or not it is latched, so a request sent while riding something with no container
     * cannot sit and seed a later open. Eligibility deliberately ignores the entity-capture toggle: the latch is what
     * tells the vehicle's own click-less open apart from an open with no provenance at all, whatever the toggle.
     */
    void claimOpenInventoryRequest(@Nullable Entity vehicle) {
        if (!openInventoryRequested) {
            return;
        }
        openInventoryRequested = false;
        if (vehicle instanceof ContainerEntity) {
            intent.recordVehicleOpenIntent(vehicle.getId(), currentTick);
        }
    }

    /**
     * Dismiss the pending entity click once the clicked entity became the player's vehicle: the interact ended in
     * mounting (boarding a boat, riding up a donkey), which is exclusive with opening a menu, so no open is owed to
     * that click and leaving it latched could poison the next open.
     */
    void dismissClickOnMountedVehicle(@Nullable Entity vehicle) {
        Entity clicked = this.clickedEntity;
        if (vehicle != null && clicked == vehicle) {
            intent.dismissEntityClick(clicked.getId());
        }
    }

    /**
     * Raised from {@link ConnectionTee} on the event loop, where no world state may be read, so it carries nothing;
     * {@link #claimOpenInventoryRequest} does the stamping on the main thread.
     */
    static void signalOpenInventoryRequest() {
        openInventoryRequested = true;
    }

    OpenClickIntent.Target resolve() {
        return intent.resolve(currentTick);
    }

    /** The clicked block pos, meaningful only immediately after {@link #resolve} returned BLOCK. */
    long resolvedBlockPosKey() {
        return intent.blockPosKey();
    }

    /** The clicked entity, meaningful only immediately after {@link #resolve} returned ENTITY. */
    @Nullable
    Entity clickedEntity() {
        return clickedEntity;
    }

    /** The vehicle the latched open-inventory request named, meaningful only right after resolve returned VEHICLE. */
    int resolvedVehicleId() {
        return intent.vehicleId();
    }

    /**
     * Drop any pending click and clicked entity on a dimension rebind or a respawn, so an old pos cannot seed a new
     * open. The upstream request signal is dropped too: it may have been raised before the transition and could
     * otherwise re-latch immediately against whatever is ridden afterwards.
     */
    void reset() {
        intent.clear();
        clickedEntity = null;
        openInventoryRequested = false;
    }
}
