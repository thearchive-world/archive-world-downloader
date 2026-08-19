// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.ContainerAssociation;
import world.thearchive.wdl.core.OpenClickIntent;
import world.thearchive.wdl.core.SpectatorCrosshairFallback;
import world.thearchive.wdl.platform.PlatformBridge;

/**
 * The MC-typed recognize-and-lift for an open container menu: given the open menu, the local player, and the clicked
 * target, decide what the container is (single chest, ender chest, double chest, chested animal, or container vehicle)
 * and lift its synthetic client slots into an {@code "Items"} holder. Pure decision and serialization, carrying no
 * capture state: {@link LiveCaptureSession} owns the per-tick orchestration, the {@link ContainerAssociation} bind, and
 * the stash and outline state, and calls in here for recognition and the slot lift.
 *
 * <p>Package-private, the analog of {@link MenuChangeTracker}; the {@code *Capture} siblings
 * ({@link EntityPacketCapture}, {@link InteractionCapture}) are public only because per-loader code reaches them across
 * packages, which does not apply here. Main-thread only, like the rest of capture.
 */
final class ContainerCapture {
    private static final Logger LOGGER = LogManager.getLogger(ContainerCapture.class);

    // The chest slots in a horse menu start at this menu index: slot 0 is the saddle and slot 1 is the
    // body-armor on every band. Vanilla names the same 2 in AbstractMountInventoryMenu.SLOT_INVENTORY_START
    // and, on 1.21.4, HorseInventoryMenu.SLOT_HORSE_INVENTORY_START; the first is an instance field and the
    // second a static one, so those are name references to the invariant rather than a constant to import.
    private static final int SLOT_INVENTORY_START = 2;

    // A lectern block entity keeps its book access private and hands it only to the menu it builds, so the
    // lectern's own container size cannot be read off the live block the way every other block target's can.
    // This is the size LecternMenu itself checks its container against on construction, which the vanilla
    // lectern is the only producer of.
    static final int LECTERN_CONTAINER_SIZE = 1;

    private final VersionAdapter adapter;
    private final RegistryAccess registries;
    private final @Nullable OpenClickTracker openClickTracker;
    private final boolean loaderObservesSpectatorBlockClick;
    private final boolean loaderObservesSpectatorEntityClick;

    ContainerCapture(VersionAdapter adapter, PlatformBridge bridge, RegistryAccess registries,
            @Nullable OpenClickTracker openClickTracker) {
        this.adapter = adapter;
        this.registries = registries;
        this.openClickTracker = openClickTracker;
        this.loaderObservesSpectatorBlockClick = bridge.observesSpectatorBlockClick();
        this.loaderObservesSpectatorEntityClick = bridge.observesSpectatorEntityClick();
    }

    /**
     * The target a freshly-opened container menu belongs to: the block or entity the open resolved to if a fresh click
     * seeded this open, an empty target carrying the vehicle intent for a latched ridden-vehicle open whose vehicle is
     * STILL the one ridden (the vehicle bind claims it on that intent), else empty. The clicked target is the fix for
     * open-time drift (the crosshair keeps moving until the menu freezes the camera), and an open no click accounts for
     * is left unattributed: where the player LOOKS is not evidence of what opened, so binding a menu to the crosshair
     * writes its contents into whatever block or entity the view happens to rest on once the slot counts coincide.
     *
     * <p>A spectator keeps the crosshair, and it is LOAD-BEARING there, not a vestige of the pre-click behavior: a
     * loader use hook that declines to fire for a spectator leaves the click unobserved, so every container a spectator
     * opens on that axis would bind nothing without this. Vanilla is not the obstacle (a spectator's use packet still
     * goes out, built and sent by {@code startPrediction} after {@code MultiPlayerGameMode.performUseItemOn} has
     * already returned {@code CONSUME}, and the server opens the menu for any clicked block carrying a
     * {@code MenuProvider}); the loader hook is, so do not delete this branch on the reasoning that the click chain
     * already covers the gamemode. The open-time drift the clicked target removes elsewhere is accepted here, because
     * the alternative on a blind axis is capturing nothing at all. {@code WdlSpectatorContainerCaptureTest} in the
     * Fabric gametest tier is the standing proof: it goes red the moment the block leg is removed.
     *
     * <p>Which leg may run, and when, is {@link SpectatorCrosshairFallback}, decided MC-free so both loader
     * configurations are pinned headlessly; this extracts the live booleans it reads.
     */
    OpenTarget resolveOpenTarget(Minecraft minecraft, LocalPlayer player) {
        OpenClickTracker tracker = this.openClickTracker;
        if (tracker != null) {
            OpenClickIntent.Target resolved = tracker.resolve();
            if (resolved == OpenClickIntent.Target.BLOCK) {
                BlockPos clicked = BlockPos.of(tracker.resolvedBlockPosKey());
                LOGGER.debug("open target: clicked block {}", clicked);
                return new OpenTarget(clicked, null, false);
            }
            if (resolved == OpenClickIntent.Target.ENTITY) {
                LOGGER.debug("open target: clicked entity {}", tracker.clickedEntity());
                return new OpenTarget(null, tracker.clickedEntity(), false);
            }
            if (resolved == OpenClickIntent.Target.VEHICLE) {
                // An open-inventory request the client sent while riding a container vehicle: the open is that
                // vehicle's own menu as far as the latch can tell, so the target stays empty and the vehicle
                // intent it carries is what the
                // ridden-vehicle bind claims it on. The crosshair is deliberately not consulted: whatever it
                // rests on did not cause this open.
                Entity vehicle = player.getVehicle();
                if (vehicle != null && vehicle.getId() == tracker.resolvedVehicleId()) {
                    LOGGER.debug("open target: ridden vehicle (open-inventory request)");
                    return new OpenTarget(null, null, true);
                }
                // The player has changed or left the vehicle since the request went out, so this menu is not
                // the one that request asked for. Binding it would write one vehicle's contents onto another,
                // which no slot-count guard separates: two container vehicles of the same size are identical
                // to it. Drop instead.
                LOGGER.info("open target: none (the vehicle the open-inventory request named is no longer ridden)");
                return new OpenTarget(null, null, false);
            }
            if (resolved == OpenClickIntent.Target.SUPERSEDED) {
                // This open belongs to an intent a later one overwrote, so it binds nothing at all.
                LOGGER.info("open target: superseded (a later intent overwrote the one this open belongs to)");
                return new OpenTarget(null, null, false);
            }
        }
        HitResult hit = minecraft.hitResult;
        BlockHitResult blockHit = hit instanceof BlockHitResult && hit.getType() == HitResult.Type.BLOCK
                ? (BlockHitResult) hit
                : null;
        EntityHitResult entityHit = hit instanceof EntityHitResult && hit.getType() == HitResult.Type.ENTITY
                ? (EntityHitResult) hit
                : null;
        // The provider read is gated on the gamemode here, not left to the rule below: Java evaluates
        // arguments eagerly, so passing it unguarded would run a mod-overridable getMenuProvider and an
        // on-demand getBlockEntity for every unattributed open in every gamemode, on a path with no catch
        // between it and the capture tick. The rule still decides; this only withholds an input it cannot use.
        boolean spectator = player.isSpectator();
        boolean blockOpensForSpectator = spectator && blockHit != null
                && spectatorCouldOpen(player.level, blockHit.getBlockPos());
        SpectatorCrosshairFallback.Axis axis = SpectatorCrosshairFallback.axisFor(spectator,
                loaderObservesSpectatorBlockClick, loaderObservesSpectatorEntityClick, blockHit != null,
                entityHit != null, blockOpensForSpectator);
        if (axis == SpectatorCrosshairFallback.Axis.BLOCK && blockHit != null) {
            LOGGER.debug("open target: spectator crosshair block {}", blockHit.getBlockPos());
            return new OpenTarget(blockHit.getBlockPos().immutable(), null, false);
        }
        if (axis == SpectatorCrosshairFallback.Axis.ENTITY && entityHit != null) {
            LOGGER.debug("open target: spectator crosshair entity {}", entityHit.getEntity());
            return new OpenTarget(null, entityHit.getEntity(), false);
        }
        LOGGER.info("open target: none (no fresh click or vehicle open request seeded this open)");
        return new OpenTarget(null, null, false);
    }

    /**
     * Whether a SPECTATOR's click on this block could have opened a menu at all. The same predicate the click latch
     * applies, deliberately shared rather than restated: a block that cannot open a menu for this player must neither
     * seed a latch nor be read off the crosshair, and two copies of that rule would drift. See
     * {@link OpenClickTracker#opensMenuFor} for why the spectator answer is narrower.
     */
    private static boolean spectatorCouldOpen(Level level, BlockPos pos) {
        return OpenClickTracker.opensMenuFor(level, pos, true);
    }

    /**
     * The block or entity a freshly-opened container menu belongs to; at most one is non-null. {@code vehicleIntent} is
     * true only for an open seeded by an open-inventory request the client sent while riding a container vehicle it
     * still rides. That is what was latched, not proof that this menu is the one the request asked for; the pairing
     * rests on the intent chain's one-open-per-action assumption. It is what the ridden-vehicle bind claims on, so an
     * open with no target and no intent belongs to nobody.
     */
    static final class OpenTarget {
        private final @Nullable BlockPos block;
        private final @Nullable Entity entity;
        private final boolean vehicleIntent;

        OpenTarget(@Nullable BlockPos block, @Nullable Entity entity, boolean vehicleIntent) {
            this.block = block;
            this.entity = entity;
            this.vehicleIntent = vehicleIntent;
        }

        @Nullable
        BlockPos block() {
            return block;
        }

        @Nullable
        Entity entity() {
            return entity;
        }

        boolean vehicleIntent() {
            return vehicleIntent;
        }
    }

    /** Whether the open target is a block whose block entity is an ender chest (the ender discriminator). */
    boolean isEnderChestAt(ClientLevel level, @Nullable BlockPos target) {
        return target != null && level.getBlockEntity(target) instanceof EnderChestBlockEntity;
    }

    /**
     * Whether this is a genuine double-chest open: a six-row (54-slot) {@link ChestMenu} opened over an open target
     * that is one half of a double chest. The double discriminator. The six-row gate is the cheap "this is a 54 open"
     * signal; it also rejects any sub-54 {@code ChestMenu} seen while the client still renders a double half during a
     * transient block-state / menu-open sync race (which the sum guard would otherwise claim then drop, silently losing
     * it) so that a 27-slot menu falls through to the single-block container bind instead. The helper checks the menu
     * type itself, so the dispatch needs no second {@code instanceof}.
     */
    boolean isDoubleChestOpen(ClientLevel level, AbstractContainerMenu menu, @Nullable BlockPos target) {
        return menu instanceof ChestMenu && ((ChestMenu) menu).getRowCount() == 6
                && isDoubleChestHalfAt(level, target);
    }

    /** Whether the open target is a block that is one half of a double chest (the double discriminator). */
    private boolean isDoubleChestHalfAt(ClientLevel level, @Nullable BlockPos target) {
        if (target == null) {
            return false;
        }
        BlockState state = level.getBlockState(target);
        return level.getBlockEntity(target) instanceof ChestBlockEntity
                && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
    }

    /**
     * Whether the container-vehicle axis claims this open: either the open target is a container vehicle (right-click a
     * chest minecart, or sneak-right-click a chest boat while aiming at it) or the open carries a vehicle intent and
     * the player rides one. The riding case is the press-E open flow: a chest boat opens its menu through
     * {@code player.getVehicle()} (it is a {@code HasCustomInventoryScreen}, so the inventory key opens it server-side)
     * and fires no use event, so the clicked target alone misses the way most players open a chest boat; observing the
     * outgoing request records that flow as its intent. The precedence itself is
     * {@link ContainerAssociation#shouldClaimVehicleOpen}, decided MC-free; this extracts the live booleans.
     */
    boolean shouldClaimVehicleOpen(LocalPlayer player, @Nullable Entity target, boolean vehicleIntentOpen) {
        return ContainerAssociation.shouldClaimVehicleOpen(target instanceof AbstractMinecartContainer,
                player.getVehicle() instanceof AbstractMinecartContainer, vehicleIntentOpen);
    }

    /**
     * The chested animal {@code menu} is the inventory of, or {@code null} when it is not one. The menu names its own
     * mount ({@link MountMenuReader}), so this is both the bind target and the dispatch gate: neither depends on the
     * open target or the ridden vehicle, so a menu opened for one chested animal can never be bound to another the
     * crosshair happens to rest on. Non-null only for a chested animal, so a mount menu for a chestless mount, and
     * every other menu, falls through to the axes below it, and the horse menu never reaches the menu-type-blind
     * vehicle branch.
     */
    @Nullable
    AbstractChestedHorse chestedAnimal(AbstractContainerMenu menu) {
        return MountMenuReader.mountOf(menu) instanceof AbstractChestedHorse
                ? (AbstractChestedHorse) MountMenuReader.mountOf(menu)
                : null;
    }

    /**
     * Count the menu's non-player slots: the block container's slots (the client backs them with a SimpleContainer).
     */
    static int countBlockSlots(AbstractContainerMenu menu, LocalPlayer player) {
        Container playerInventory = player.inventory;
        int count = 0;
        for (Slot slot : menu.slots) {
            if (slot.container != playerInventory) {
                count++;
            }
        }
        return count;
    }

    /**
     * Count a chested animal's chest slots in the open menu: the non-player slots from {@link #SLOT_INVENTORY_START} on
     * (the saddle and body slots lead and are skipped by the index floor; the player inventory is skipped by container
     * identity). Equals the animal's own chest size, the mis-bind guard.
     */
    static int countChestSlots(AbstractContainerMenu menu, LocalPlayer player) {
        Container playerInventory = player.inventory;
        int count = 0;
        for (int i = SLOT_INVENTORY_START; i < menu.slots.size(); i++) {
            if (menu.slots.get(i).container != playerInventory) {
                count++;
            }
        }
        return count;
    }

    /**
     * Serialize a menu's non-player block slots (each at its container-slot index) into an {@code "Items"} holder via
     * the per-band {@link ContainerSink}, or {@code null} when the menu exposes no block slots this tick. Shared by the
     * container and ender-chest stashes (both lift the same synthetic client slots).
     */
    @Nullable
    CompoundTag captureBlockSlots(AbstractContainerMenu menu, LocalPlayer player) {
        Container playerInventory = player.inventory;
        Container blockContainer = null;
        for (Slot slot : menu.slots) {
            if (slot.container != playerInventory) {
                blockContainer = slot.container;
                break;
            }
        }
        if (blockContainer == null) {
            return null; // bound but no block slots this tick; nothing to capture
        }
        int size = blockContainer.getContainerSize();
        NonNullList<ItemStack> items = NonNullList.withSize(size, ItemStack.EMPTY);
        // 1.16.5 Slot has no getContainerSlot accessor, so the container index is the menu order of the
        // non-player slots: a client block container adds its slots consecutively in container-index order.
        int index = 0;
        for (Slot slot : menu.slots) {
            if (slot.container == playerInventory) {
                continue;
            }
            if (index < size) {
                items.set(index, slot.getItem());
            }
            index++;
        }
        return adapter.containerSink().captureItems(items, registries);
    }

    /**
     * Ride the brewing stand's persisted menu-only state on {@code holder}: vanilla's exact key names and NBT types
     * (BrewTime short, Fuel byte), so the merged block entity loads unchanged. Pure so the key/type contract is
     * testable headless; the caller reads the live menu's two data values.
     */
    static void putBrewingState(CompoundTag holder, int brewingTicks, int fuel) {
        holder.putShort("BrewTime", (short) brewingTicks);
        holder.putByte("Fuel", (byte) fuel);
    }

    /**
     * Serialize only the chest slots of an open chested-animal menu (the non-player slots from
     * {@link #SLOT_INVENTORY_START}) into an {@code "Items"} holder via the per-band {@link ContainerSink}, or
     * {@code null} when the menu exposes no chest slots this tick (a chestless animal). Distinct from
     * {@link #captureBlockSlots}: the chest is numbered 0-based in MENU ORDER, not by {@code Slot.getContainerSlot()},
     * which is what makes the lift band-agnostic. Kept separate on purpose.
     */
    @Nullable
    CompoundTag captureChestSlots(AbstractContainerMenu menu, LocalPlayer player) {
        Container playerInventory = player.inventory;
        List<ItemStack> chest = new ArrayList<>();
        for (int i = SLOT_INVENTORY_START; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container != playerInventory) {
                // The 0-based menu-order index here, not getContainerSlot(), is the chest-relative Items slot:
                // getContainerSlot() is 0-based on 1.21.11 but 1-based on 1.21.4 (the saddle is container index
                // 0 there), so the menu-order count is what absorbs the per-band saddle offset and keeps the
                // chest from shifting by one on 1.21.4. Do not reindex by getContainerSlot() or fold this into
                // captureBlockSlots.
                chest.add(slot.getItem());
            }
        }
        if (chest.isEmpty()) {
            return null; // bound but no chest slots this tick; nothing to capture
        }
        NonNullList<ItemStack> items = NonNullList.withSize(chest.size(), ItemStack.EMPTY);
        for (int i = 0; i < chest.size(); i++) {
            items.set(i, chest.get(i));
        }
        return adapter.containerSink().captureItems(items, registries);
    }

    /**
     * Serialize the menu's non-player slots whose container index is in {@code [lo, hi)} into a 0-based {@code "Items"}
     * holder (re-based to {@code index - lo}) via the per-band {@link ContainerSink}, or {@code null} when that range
     * is empty this tick. The client's double-chest menu is one {@code SimpleContainer(54)} with contiguous container
     * indices 0..53, so {@code [0, 27)} is the RIGHT chest and {@code [27, 54)} the LEFT (the {@code CompoundContainer}
     * (RIGHT, LEFT) order). Kept separate from {@link #captureChestSlots} on purpose (see the slot-index note below).
     */
    @Nullable
    CompoundTag captureHalfSlots(AbstractContainerMenu menu, LocalPlayer player, int low, int high) {
        Container playerInventory = player.inventory;
        NonNullList<ItemStack> items = NonNullList.withSize(high - low, ItemStack.EMPTY);
        boolean any = false;
        // 1.16.5 Slot has no getContainerSlot accessor, so the container index is the menu order of the
        // non-player slots. The client double-chest menu is one SimpleContainer(54) with contiguous indices
        // 0..53, so menu order equals the container index; do not unify this lift with captureChestSlots, whose
        // menu-order numbering serves the same chest-relative role.
        int index = 0;
        for (Slot slot : menu.slots) {
            if (slot.container == playerInventory) {
                continue;
            }
            if (index >= low && index < high) {
                items.set(index - low, slot.getItem());
                any = true;
            }
            index++;
        }
        return any ? adapter.containerSink().captureItems(items, registries) : null;
    }
}
