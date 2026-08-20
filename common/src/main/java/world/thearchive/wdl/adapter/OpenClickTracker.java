// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.class_2610;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.EntityMenuCapability;
import world.thearchive.wdl.core.OpenClickIntent;

/**
 * The MC-typed, loader-agnostic observer of the local player's right-clicks, so a container menu that opens a beat
 * later binds to the block or entity the open resolved to rather than to the live crosshair. The open-screen packet
 * carries no target and arrives asynchronously; until it does, no screen is open, so the camera is not frozen and the
 * crosshair keeps tracking the view. A player who nudges their aim while the container opens would otherwise have the
 * open-time bind read a drifted crosshair, mis-binding the menu or dropping it. This latches the clicked target.
 *
 * <p>Mirrors {@link InteractionCapture} and {@link EntityPacketCapture}: a connection-scoped {@code static volatile}
 * publication point the per-loader use hooks resolve (the hooks have no session reference), {@code null} when no
 * download is running so the hooks no-op. The pure decision (freshness plus take-once) lives in
 * {@link OpenClickIntent}; this holds only the MC glue, the clicked entity reference the bind needs and a tick clock
 * the session advances, the way {@link world.thearchive.wdl.core.ContainerAssociation ContainerAssociation} keeps the
 * bound entity UUID in the adapter. Created only when container capture is on, the same as the bind path it feeds.
 */
public final class OpenClickTracker {
    /**
     * The freshness window in ticks (twenty seconds): the click-to-open round trip on a badly lagged connection runs
     * well past ten seconds, and an expired latch leaves the open unattributed, which drops it. The window is the only
     * ceiling on the leak the structural guards cannot bound, an intent still latched when an open it did not cause
     * arrives, and an intent reaches that state two ways. Its own open never arrived: a click on a block whose menu the
     * server declines to open is the block form, an open-inventory request the server likewise declines is the vehicle
     * form, and there the bind's vehicle-identity check narrows it to opens arriving while the same vehicle is still
     * ridden. Or its own open did arrive and a superseded marker consumed it, which is not a take, so take-once in
     * {@link OpenClickIntent} bounds the first way and not the second. Either way the bind's slot-count guard drops the
     * claim unless the sizes coincide.
     */
    private static final long FRESH_WINDOW_TICKS = 400L;

    private static volatile @Nullable OpenClickTracker active;
    private static volatile boolean openInventoryRequested;

    private final OpenClickIntent intent = new OpenClickIntent(FRESH_WINDOW_TICKS);
    private long currentTick;
    private @Nullable Entity clickedEntity;

    static void activate(OpenClickTracker tracker) {
        openInventoryRequested = false; // a request sent before this download must not seed its first tick
        active = tracker;
    }

    static void deactivate(OpenClickTracker tracker) {
        if (active == tracker) {
            active = null;
        }
    }

    /**
     * Observe one local-player right-click on a block, or no-op when no download is active or it is not local. Only a
     * block that can open a menu for this player is latched; {@link #opensMenuFor} is that test, and its own contract
     * states what it does and does not settle. Every click that can open a menu must stay in the intent chain: an
     * unlatched menu open still consumes the next latch on resolve, stealing a following container's provenance (an
     * anvil open eating a furnace click binds the anvil's three slots onto the three-slot furnace, a mis-bind), and
     * menu blocks like the crafting family carry no block entity, so menu-provider presence, not block-entity presence,
     * is the load-bearing filter. A placement target, door, or lever reports no provider and stays unlatched; a latched
     * click on one could only supersede real intents. The ender chest is the one vanilla menu-opening block with no
     * provider (its menu is built in the block's use handler over the per-player ender container), so it is latched by
     * its block entity; every other openMenu block either overrides {@code getMenuProvider} or has a
     * {@code MenuProvider} block entity (verified across 1.21.11). A server GUI opened from a provider-less block falls
     * into the documented {@link #FRESH_WINDOW_TICKS} leak class instead of the chain.
     */
    public static void dispatchUseBlock(Player player, Level level, HitResult hit) {
        OpenClickTracker tracker = active;
        if (tracker != null && level.isClientSide() && player == Minecraft.getInstance().player
                && !suppressesBlockUse(player)
                && opensMenuFor(level, hit.method_9344(), player.isSpectator())) {
            tracker.intent.recordBlockClick(hit.method_9344().asLong(), tracker.currentTick);
        }
    }

    /**
     * Vanilla's own gate: sneaking with a non-empty hand skips the block's use entirely and places the item, so no menu
     * can open. Identical predicate on both sides (MultiPlayerGameMode / ServerPlayerGameMode), so a click in this
     * state owes no open and must not be latched.
     */
    private static boolean suppressesBlockUse(Player player) {
        boolean haveSomethingInOurHands = !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty();
        return player.isSneaking() && haveSomethingInOurHands;
    }

    /**
     * Whether a right-click on this block can open a menu for this player. Shared with the open-time bind, which needs
     * the identical question about a spectator's crosshair block, so the two cannot drift apart.
     *
     * <p>The spectator answer is narrower at the ender chest. The server's spectator branch opens a menu solely from
     * the block state's own provider, skipping the block's use handler, and the ender chest's menu is built inside that
     * handler; so an ordinary player's ender click opens a menu and a spectator's opens nothing. The ender chest also
     * carries its own obstruction rule, which its use handler applies before opening anything, so an ender chest under
     * a redstone conductor opens for nobody.
     *
     * <p>Not a complete test of "this click opens a menu", and must not be extended into one. Provider presence is
     * necessary and not sufficient: a shulker box with no room to open reports a provider and opens nothing for an
     * ordinary player, while a spectator bypasses that check, so its eligibility differs by gamemode in the opposite
     * direction to the ender chest's. A block latched without ever receiving its open leaves an intent the next
     * unattributed open consumes.
     */
    static boolean opensMenuFor(Level level, BlockPos pos, boolean spectator) {
        // This band has no unified MenuProvider (a 1.14 abstraction); the container block entities that open a menu
        // are exactly the lockable-container ones (class_2610, implemented by BaseContainerBlockEntity), which is
        // what this recognizes. A provider-less workstation menu (crafting table, enchanting table, anvil) has no
        // block entity to test here, so it is not latched and falls into the documented FRESH_WINDOW_TICKS leak
        // class rather than the intent chain, the accepted band ceiling on this recognizer.
        if (level.getBlockEntity(pos) instanceof class_2610) {
            return true;
        }
        return !spectator && level.getBlockEntity(pos) instanceof EnderChestBlockEntity
                && !enderChestObstructed(level, pos);
    }

    /**
     * Whether an ender chest at {@code pos} is covered by a redstone conductor, vanilla's own gate: its use handler
     * returns before opening anything in that case, so the click owes no open at all.
     */
    private static boolean enderChestObstructed(Level level, BlockPos pos) {
        BlockPos above = pos.above();
        // method_16907 is the solid-cube test the vanilla EnderChestBlock use handler applies to the block above.
        return level.getBlockState(above).method_16907();
    }

    /**
     * Observe one local-player right-click on an entity, or no-op when no download is active or it is not local. Every
     * clicked entity is latched; {@link #anyEntityMayOpenMenu} is the eligibility declaration that says so, and its
     * contract states why no narrower one is admissible.
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
     * Whether this entity provably opens no server-driven container menu in vanilla, so its superseded marker may be
     * suppressed. Extraction only; the decision is {@link EntityMenuCapability}. Baby/nitwit are the sole tradeless
     * villager states robust to profession-sync staleness; NONE is deliberately excluded.
     */
    private static boolean menuIncapable(Entity entity) {
        boolean vanilla = "minecraft".equals(
                Registry.ENTITY_TYPE.getKey(entity.getType()).getNamespace());
        boolean villager = entity instanceof Villager;
        boolean baby = villager && ((Villager) entity).isBaby();
        // At this band the villager profession is an int on the synced data (method_3115); nitwit is profession
        // id 5, the same tradeless state the newer bands read off VillagerProfession.NITWIT.
        boolean nitwit = villager && ((Villager) entity).method_3115() == 5;
        return EntityMenuCapability.isMenuIncapable(vanilla, entity instanceof AbstractMinecartContainer,
                entity instanceof AbstractHorse, entity instanceof Villager,
                villager, baby, nitwit);
    }

    /**
     * Whether a right-click on this entity can open a menu, the entity-side counterpart of {@link #opensMenuFor}.
     * Always true, by decision rather than by omission.
     *
     * <p>The set of entities that can open a menu is not enumerable. Any modded entity can open one, and on a plugin
     * server so can any vanilla entity, so a filter written from the entities known here would be incomplete by
     * construction, and an incomplete filter is worse than none. A click it wrongly rejects still receives its menu,
     * and that menu arrives unlatched and consumes the next latch on resolve, stealing a following container's
     * provenance: a mis-bind, ranked strictly worse than the drop an over-latched click costs. Enumerating entity types
     * here is what this method exists to refuse, not an extension it invites.
     */
    private static boolean anyEntityMayOpenMenu(Entity entity) {
        return true;
    }

    /** Advance the tick clock the freshness window measures against; the session calls this once per capture tick. */
    void tick() {
        currentTick++;
    }

    /**
     * Main side: take any open-inventory request the tee saw and latch it as this vehicle's intent. The request is what
     * vanilla acts on, so unlike a sampled key state it cannot be missed however briefly the key was held, and the
     * client sends exactly one per open it asks for.
     *
     * <p>The signal is drained whether or not it is latched, so a request sent while riding something with no container
     * cannot sit and seed a later open. The eligible set is deliberately narrower than vanilla's own gate for this
     * request. Vanilla sends it for anything implementing AbstractHorse, which is three families on this band (the
     * chest boats, the horses, and the nautiluses); only the container vehicles are latched here, because a chested
     * mount's menu names its own animal and is recognized without click provenance at all, and a nautilus carries no
     * chest, so neither has a container this latch could claim. Eligibility deliberately ignores the entity-capture
     * toggle: the latch is what tells the vehicle's own click-less open apart from an open with no provenance at all,
     * whatever the toggle. The vehicle is read on the tick the request is OBSERVED, which is not always the tick it was
     * sent: the send hops to the connection's event loop, so the signal can surface a tick late. The intent carries the
     * vehicle's network id so the bind can require the same vehicle to still be the one ridden.
     */
    void claimOpenInventoryRequest(@Nullable Entity vehicle) {
        if (!openInventoryRequested) {
            return;
        }
        openInventoryRequested = false;
        if (vehicle instanceof AbstractMinecartContainer) {
            intent.recordVehicleOpenIntent(vehicle.getId(), currentTick);
        }
    }

    /**
     * Dismiss the pending entity click once the clicked entity became the player's vehicle: the interact ended in
     * mounting (entering a chest boat, riding up a donkey), which is exclusive with opening a menu, so no open is owed
     * to that click and leaving it latched would poison the next open. The dismissal covers any click on the now-ridden
     * vehicle, which is benign: the ridden vehicle's own open is reclaimed click-less by the riding leg. Identity
     * comparison on purpose: the dismissal must fire only for the very entity the click landed on.
     */
    void dismissClickOnMountedVehicle(@Nullable Entity vehicle) {
        Entity clicked = this.clickedEntity;
        if (vehicle != null && clicked == vehicle) {
            intent.dismissEntityClick(clicked.getId());
        }
    }

    /**
     * Netty side: the client sent an open-inventory request. Raised from {@link ConnectionTee} on the event loop, where
     * no world state may be read, so it carries nothing; {@link #claimOpenInventoryRequest} does the stamping on the
     * main thread. Not per-tracker, because the tee is installed on the connection whether or not a download is
     * running, and a signal raised while none is cannot outlive its drain.
     */
    static void signalOpenInventoryRequest() {
        openInventoryRequested = true;
    }

    /**
     * Take the pending click if it is fresh: BLOCK exposes {@link #resolvedBlockPosKey}, ENTITY exposes
     * {@link #clickedEntity}.
     */
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
     * open. The upstream request signal is dropped too: it may have been raised before the transition and would
     * otherwise re-latch immediately against whatever is ridden afterwards.
     */
    void reset() {
        intent.clear();
        clickedEntity = null;
        openInventoryRequested = false;
    }
}
