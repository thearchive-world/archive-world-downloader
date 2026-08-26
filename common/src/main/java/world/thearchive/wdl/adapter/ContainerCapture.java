// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecartContainer;
import net.minecraft.entity.passive.AbstractChestHorse;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
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
    // body-armor. Vanilla names the same 2 as the mount inventory start index.
    private static final int SLOT_INVENTORY_START = 2;

    // A lectern block entity does not exist at this band (lecterns are a 1.14 block), so the lectern capture path is
    // inert here; the constant is kept for the shared, band-stable bind wiring the deep-band guard-out leaves in place.
    static final int LECTERN_CONTAINER_SIZE = 1;

    private final VersionAdapter adapter;
    private final @Nullable OpenClickTracker openClickTracker;
    private final boolean loaderObservesSpectatorBlockClick;
    private final boolean loaderObservesSpectatorEntityClick;

    ContainerCapture(VersionAdapter adapter, PlatformBridge bridge, @Nullable OpenClickTracker openClickTracker) {
        this.adapter = adapter;
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
     * opens on that axis would bind nothing without this. The open-time drift the clicked target removes elsewhere is
     * accepted here, because the alternative on a blind axis is capturing nothing at all.
     *
     * <p>Which leg may run, and when, is {@link SpectatorCrosshairFallback}, decided MC-free so both loader
     * configurations are pinned headlessly; this extracts the live booleans it reads.
     */
    OpenTarget resolveOpenTarget(Minecraft minecraft, EntityPlayerSP player) {
        OpenClickTracker tracker = this.openClickTracker;
        if (tracker != null) {
            OpenClickIntent.Target resolved = tracker.resolve();
            if (resolved == OpenClickIntent.Target.BLOCK) {
                BlockPos clicked = BlockPos.fromLong(tracker.resolvedBlockPosKey());
                LOGGER.debug("open target: clicked block {}", clicked);
                return new OpenTarget(clicked, null, false);
            }
            if (resolved == OpenClickIntent.Target.ENTITY) {
                LOGGER.debug("open target: clicked entity {}", tracker.clickedEntity());
                return new OpenTarget(null, tracker.clickedEntity(), false);
            }
            if (resolved == OpenClickIntent.Target.VEHICLE) {
                // An open-inventory request the client sent while riding a container vehicle: the open is that
                // vehicle's own menu as far as the latch can tell, so the target stays empty and the vehicle intent
                // it carries is what the ridden-vehicle bind claims it on. The crosshair is deliberately not
                // consulted: whatever it rests on did not cause this open.
                Entity vehicle = player.getRidingEntity();
                if (vehicle != null && vehicle.getEntityId() == tracker.resolvedVehicleId()) {
                    LOGGER.debug("open target: ridden vehicle (open-inventory request)");
                    return new OpenTarget(null, null, true);
                }
                // The player has changed or left the vehicle since the request went out, so this menu is not the one
                // that request asked for. Binding it would write one vehicle's contents onto another, which no
                // slot-count guard separates: two container vehicles of the same size are identical to it. Drop.
                LOGGER.info("open target: none (the vehicle the open-inventory request named is no longer ridden)");
                return new OpenTarget(null, null, false);
            }
            if (resolved == OpenClickIntent.Target.SUPERSEDED) {
                // This open belongs to an intent a later one overwrote, so it binds nothing at all.
                LOGGER.info("open target: superseded (a later intent overwrote the one this open belongs to)");
                return new OpenTarget(null, null, false);
            }
        }
        // This band's RayTraceResult is a single unified type; the BLOCK/ENTITY discriminator is the typeOfHit field,
        // the block pos is getBlockPos, and the entity is entityHit. There is no BlockHitResult / EntityHitResult
        // split to cast to.
        RayTraceResult hit = minecraft.objectMouseOver;
        RayTraceResult blockHit = hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK ? hit : null;
        RayTraceResult entityHit = hit != null && hit.typeOfHit == RayTraceResult.Type.ENTITY ? hit : null;
        // The provider read is gated on the gamemode here, not left to the rule below: Java evaluates arguments
        // eagerly, so passing it unguarded would run an on-demand getTileEntity for every unattributed open in every
        // gamemode. The rule still decides; this only withholds an input it cannot use.
        boolean spectator = player.isSpectator();
        boolean blockOpensForSpectator = spectator && blockHit != null
                && spectatorCouldOpen(player.world, blockHit.getBlockPos());
        SpectatorCrosshairFallback.Axis axis = SpectatorCrosshairFallback.axisFor(spectator,
                loaderObservesSpectatorBlockClick, loaderObservesSpectatorEntityClick, blockHit != null,
                entityHit != null, blockOpensForSpectator);
        if (axis == SpectatorCrosshairFallback.Axis.BLOCK && blockHit != null) {
            LOGGER.debug("open target: spectator crosshair block {}", blockHit.getBlockPos());
            return new OpenTarget(blockHit.getBlockPos().toImmutable(), null, false);
        }
        if (axis == SpectatorCrosshairFallback.Axis.ENTITY && entityHit != null) {
            LOGGER.debug("open target: spectator crosshair entity {}", entityHit.entityHit);
            return new OpenTarget(null, entityHit.entityHit, false);
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
    private static boolean spectatorCouldOpen(World level, BlockPos pos) {
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
    boolean isEnderChestAt(WorldClient level, @Nullable BlockPos target) {
        return target != null && level.getTileEntity(target) instanceof TileEntityEnderChest;
    }

    /**
     * Whether this is a genuine double-chest open: a six-row (54-slot) {@link ContainerChest} opened over an open
     * target that is one half of a double chest. The double discriminator. The six-row gate is the cheap "this is a 54
     * open" signal; it also rejects any sub-54 {@code ContainerChest} seen while the client still renders a double half
     * during a transient block-state / menu-open sync race so that a 27-slot menu falls through to the single-block
     * container bind instead.
     */
    boolean isDoubleChestOpen(WorldClient level, Container menu, @Nullable BlockPos target) {
        // A ContainerChest adds its block container slots first, so slot 0's inventory is the block container and its
        // size is the six-row (54) signal.
        return menu instanceof ContainerChest && !menu.inventorySlots.isEmpty()
                && menu.inventorySlots.get(0).inventory.getSizeInventory() == 54
                && isDoubleChestHalfAt(level, target);
    }

    /** Whether the open target is a block that is one half of a double chest (the double discriminator). */
    private boolean isDoubleChestHalfAt(WorldClient level, @Nullable BlockPos target) {
        return target != null && doubleChestPartner(level, target) != null;
    }

    /**
     * The matching adjacent chest that forms a double chest with the one at {@code pos}, or null when {@code pos} is
     * not a chest or has no matching neighbor. There is no {@code ChestType} left/right block-state property before
     * 1.13, so a double chest is recognized by the pre-Flattening neighbor probe: both halves are plain
     * {@link TileEntityChest}s, so the four horizontal neighbors are probed in the order {@code +Z, -Z, +X, -X} and the
     * first one that is a chest of the same kind (normal vs trapped, {@code getChestType}) is the partner, exactly as
     * vanilla pairs a large chest. A chest never pairs with a chest of the other kind.
     */
    @Nullable
    static BlockPos doubleChestPartner(World level, BlockPos pos) {
        TileEntity te = level.getTileEntity(pos);
        if (!(te instanceof TileEntityChest)) {
            return null;
        }
        BlockChest.Type kind = ((TileEntityChest) te).getChestType();
        BlockPos[] neighbors = { pos.south(), pos.north(), pos.east(), pos.west() };
        for (BlockPos neighbor : neighbors) {
            TileEntity neighborTe = level.getTileEntity(neighbor);
            if (neighborTe instanceof TileEntityChest && ((TileEntityChest) neighborTe).getChestType() == kind) {
                return neighbor;
            }
        }
        return null;
    }

    /**
     * Whether the container-vehicle axis claims this open: either the open target is a container vehicle (right-click a
     * chest minecart) or the open carries a vehicle intent and the player rides one. The precedence itself is
     * {@link ContainerAssociation#shouldClaimVehicleOpen}, decided MC-free; this extracts the live booleans.
     */
    boolean shouldClaimVehicleOpen(EntityPlayerSP player, @Nullable Entity target, boolean vehicleIntentOpen) {
        return ContainerAssociation.shouldClaimVehicleOpen(target instanceof EntityMinecartContainer,
                player.getRidingEntity() instanceof EntityMinecartContainer, vehicleIntentOpen);
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
    AbstractChestHorse chestedAnimal(Container menu) {
        return MountMenuReader.mountOf(menu) instanceof AbstractChestHorse
                ? (AbstractChestHorse) MountMenuReader.mountOf(menu)
                : null;
    }

    /**
     * Count the menu's non-player slots: the block container's slots.
     */
    static int countBlockSlots(Container menu, EntityPlayerSP player) {
        IInventory playerInventory = player.inventory;
        int count = 0;
        for (Slot slot : menu.inventorySlots) {
            if (slot.inventory != playerInventory) {
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
    static int countChestSlots(Container menu, EntityPlayerSP player) {
        IInventory playerInventory = player.inventory;
        int count = 0;
        for (int i = SLOT_INVENTORY_START; i < menu.inventorySlots.size(); i++) {
            if (menu.inventorySlots.get(i).inventory != playerInventory) {
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
    NBTTagCompound captureBlockSlots(Container menu, EntityPlayerSP player) {
        IInventory playerInventory = player.inventory;
        IInventory blockContainer = null;
        for (Slot slot : menu.inventorySlots) {
            if (slot.inventory != playerInventory) {
                blockContainer = slot.inventory;
                break;
            }
        }
        if (blockContainer == null) {
            return null; // bound but no block slots this tick; nothing to capture
        }
        int size = blockContainer.getSizeInventory();
        NonNullList<ItemStack> items = NonNullList.withSize(size, ItemStack.EMPTY);
        // 1.12.2 Slot has no container-slot accessor, so the container index is the menu order of the non-player
        // slots: a client block container adds its slots consecutively in container-index order.
        int index = 0;
        for (Slot slot : menu.inventorySlots) {
            if (slot.inventory == playerInventory) {
                continue;
            }
            if (index < size) {
                items.set(index, slot.getStack());
            }
            index++;
        }
        return adapter.containerSink().captureItems(items);
    }

    /**
     * Ride the brewing stand's persisted menu-only state on {@code holder}: vanilla's exact key names and NBT types
     * (BrewTime short, Fuel byte), so the merged block entity loads unchanged. Pure so the key/type contract is
     * testable headless; the caller reads the live menu's two data values.
     */
    static void putBrewingState(NBTTagCompound holder, int brewingTicks, int fuel) {
        holder.setShort("BrewTime", (short) brewingTicks);
        holder.setByte("Fuel", (byte) fuel);
    }

    /**
     * Serialize only the chest slots of an open chested-animal menu (the non-player slots from
     * {@link #SLOT_INVENTORY_START}) into an {@code "Items"} holder via the per-band {@link ContainerSink}, or
     * {@code null} when the menu exposes no chest slots this tick (a chestless animal). Distinct from
     * {@link #captureBlockSlots}: the chest is numbered 0-based in MENU ORDER, which is what makes the lift
     * band-agnostic. Kept separate on purpose.
     */
    @Nullable
    NBTTagCompound captureChestSlots(Container menu, EntityPlayerSP player) {
        IInventory playerInventory = player.inventory;
        List<ItemStack> chest = new ArrayList<>();
        for (int i = SLOT_INVENTORY_START; i < menu.inventorySlots.size(); i++) {
            Slot slot = menu.inventorySlots.get(i);
            if (slot.inventory != playerInventory) {
                // The 0-based menu-order index here is the chest-relative Items slot; it absorbs the leading
                // saddle/body slots and keeps the chest from shifting. Do not fold this into captureBlockSlots.
                chest.add(slot.getStack());
            }
        }
        if (chest.isEmpty()) {
            return null; // bound but no chest slots this tick; nothing to capture
        }
        NonNullList<ItemStack> items = NonNullList.withSize(chest.size(), ItemStack.EMPTY);
        for (int i = 0; i < chest.size(); i++) {
            items.set(i, chest.get(i));
        }
        return adapter.containerSink().captureItems(items);
    }

    /**
     * Serialize the menu's non-player slots whose container index is in {@code [low, high)} into a 0-based
     * {@code "Items"} holder (re-based to {@code index - low}) via the per-band {@link ContainerSink}, or {@code null}
     * when that range is empty this tick. The client's double-chest menu is one {@code InventoryLargeChest(54)} with
     * contiguous container indices 0..53, so {@code [0, 27)} is the lower-coordinate chest (slots 0-26) and
     * {@code [27, 54)} the higher-coordinate one, matching vanilla's own large-chest slot order. Kept separate from
     * {@link #captureChestSlots} on purpose.
     */
    @Nullable
    NBTTagCompound captureHalfSlots(Container menu, EntityPlayerSP player, int low, int high) {
        IInventory playerInventory = player.inventory;
        NonNullList<ItemStack> items = NonNullList.withSize(high - low, ItemStack.EMPTY);
        boolean any = false;
        // 1.12.2 Slot has no container-slot accessor, so the container index is the menu order of the non-player
        // slots. The client double-chest menu is one InventoryLargeChest(54) with contiguous indices 0..53, so menu
        // order equals the container index.
        int index = 0;
        for (Slot slot : menu.inventorySlots) {
            if (slot.inventory == playerInventory) {
                continue;
            }
            if (index >= low && index < high) {
                items.set(index - low, slot.getStack());
                any = true;
            }
            index++;
        }
        return any ? adapter.containerSink().captureItems(items) : null;
    }
}
