// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityMinecartContainer;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.ILockableContainer;
import net.minecraft.world.World;
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
     * three slots onto the three-slot furnace, a mis-bind). A placement target, door, or lever carries no lockable
     * container and stays unlatched; a latched click on one could supersede real intents. The ender chest is latched on
     * its own block entity rather than the lockable-container test, its menu being built in the block's use handler
     * over the per-player ender container.
     */
    public static void dispatchUseBlock(EntityPlayer player, World level, RayTraceResult hit) {
        OpenClickTracker tracker = active;
        if (tracker != null && level.isRemote && player == Minecraft.getMinecraft().player
                && !suppressesBlockUse(player)
                && opensMenuFor(level, hit.getBlockPos(), player.isSpectator())) {
            tracker.intent.recordBlockClick(hit.getBlockPos().toLong(), tracker.currentTick);
        }
    }

    /**
     * Sneaking with an item in the hand being processed skips the block's use for that hand on both sides
     * ({@code PlayerControllerMP} / {@code PlayerInteractionManager}); a click that then places the item owes no open
     * and must not be latched.
     */
    private static boolean suppressesBlockUse(EntityPlayer player) {
        boolean haveSomethingInOurHands = !player.getHeldItemMainhand().isEmpty()
                || !player.getHeldItemOffhand().isEmpty();
        return player.isSneaking() && haveSomethingInOurHands;
    }

    /**
     * The spectator answer is narrower at the ender chest. The server's spectator branch skips the block's use handler,
     * and the ender chest's menu is built inside that handler; so an ordinary player's ender click opens a menu and a
     * spectator's opens nothing. The ender chest also carries its own obstruction rule, which its use handler applies
     * before opening anything, so an ender chest under a redstone conductor opens for nobody.
     *
     * <p>Not a complete test of "this click opens a menu", and must not be extended into one. Lockable-container
     * presence is not sufficient: a shulker box with no room to open is a lockable container and opens nothing for an
     * ordinary player, while a spectator bypasses that check, so its eligibility differs by gamemode in the opposite
     * direction to the ender chest's. A block latched without ever receiving its open leaves an intent the next
     * unattributed open consumes.
     */
    static boolean opensMenuFor(World level, BlockPos pos, boolean spectator) {
        if (level.getTileEntity(pos) instanceof ILockableContainer) {
            return true;
        }
        return !spectator && level.getTileEntity(pos) instanceof TileEntityEnderChest
                && !enderChestObstructed(level, pos);
    }

    private static boolean enderChestObstructed(World level, BlockPos pos) {
        BlockPos above = pos.up();
        return level.getBlockState(above).isNormalCube();
    }

    /**
     * Observe one local-player right-click on an entity, or no-op when no download is active or it is not local.
     */
    public static void dispatchUseEntity(EntityPlayer player, World level, Entity entity) {
        OpenClickTracker tracker = active;
        if (tracker != null && level.isRemote && player == Minecraft.getMinecraft().player
                && anyEntityMayOpenMenu(entity)) {
            tracker.clickedEntity = entity;
            tracker.intent.recordEntityClick(entity.getEntityId(), tracker.currentTick, menuIncapable(entity));
        }
    }

    private static boolean menuIncapable(Entity entity) {
        ResourceLocation key = EntityList.getKey(entity);
        boolean vanilla = key != null && "minecraft".equals(key.getResourceDomain());
        boolean villager = entity instanceof EntityVillager;
        boolean baby = villager && ((EntityVillager) entity).isChild();
        // At this band the villager profession is an int (getProfession); nitwit is profession id 5.
        boolean nitwit = villager && ((EntityVillager) entity).getProfession() == 5;
        return EntityMenuCapability.isMenuIncapable(vanilla, entity instanceof EntityMinecartContainer,
                entity instanceof AbstractHorse, entity instanceof EntityVillager,
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
     * cannot sit and seed a later open.
     */
    void claimOpenInventoryRequest(@Nullable Entity vehicle) {
        if (!openInventoryRequested) {
            return;
        }
        openInventoryRequested = false;
        if (vehicle instanceof EntityMinecartContainer) {
            intent.recordVehicleOpenIntent(vehicle.getEntityId(), currentTick);
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
            intent.dismissEntityClick(clicked.getEntityId());
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
     * open.
     */
    void reset() {
        intent.clear();
        clickedEntity = null;
        openInventoryRequested = false;
    }
}
