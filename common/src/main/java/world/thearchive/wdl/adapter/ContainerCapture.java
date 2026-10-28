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

final class ContainerCapture {
    private static final Logger LOGGER = LogManager.getLogger(ContainerCapture.class);

    // The chest slots in a horse menu start at this menu index: slot 0 is the saddle and slot 1 is the body-armor.
    private static final int SLOT_INVENTORY_START = 2;

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
     * The clicked target is the fix for open-time drift (the crosshair keeps moving until the menu freezes the camera),
     * and an open no click accounts for is left unattributed: where the player LOOKS is not evidence of what opened, so
     * binding a menu to the crosshair writes its contents into whatever block or entity the view happens to rest on
     * once the slot counts coincide.
     *
     * <p>A spectator keeps the crosshair, and it is LOAD-BEARING there: a loader use hook that declines to fire for a
     * spectator leaves the click unobserved, so every container a spectator opens on that axis would bind nothing
     * without this. The open-time drift the clicked target removes elsewhere is accepted here, because the alternative
     * on a blind axis is capturing nothing at all.
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
                LOGGER.info("open target: superseded (a later intent overwrote the one this open belongs to)");
                return new OpenTarget(null, null, false);
            }
        }
        RayTraceResult hit = minecraft.objectMouseOver;
        RayTraceResult blockHit = hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK ? hit : null;
        RayTraceResult entityHit = hit != null && hit.typeOfHit == RayTraceResult.Type.ENTITY ? hit : null;
        // The provider read is gated on the gamemode here, not left to the rule below: Java evaluates arguments
        // eagerly, so passing it unguarded would run an on-demand getTileEntity for every unattributed open in every
        // gamemode.
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

    private static boolean spectatorCouldOpen(World level, BlockPos pos) {
        return OpenClickTracker.opensMenuFor(level, pos, true);
    }

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

    boolean isEnderChestAt(WorldClient level, @Nullable BlockPos target) {
        return target != null && level.getTileEntity(target) instanceof TileEntityEnderChest;
    }

    /**
     * The six-row gate is the cheap "this is a 54 open" signal; it also rejects any sub-54 {@code ContainerChest} seen
     * while the client still renders a double half so that a 27-slot menu falls through to the single-block container
     * bind instead.
     */
    boolean isDoubleChestOpen(WorldClient level, Container menu, @Nullable BlockPos target) {
        // A ContainerChest adds its block container slots first, so slot 0's inventory is the block container and its
        // size is the six-row (54) signal.
        return menu instanceof ContainerChest && !menu.inventorySlots.isEmpty()
                && menu.inventorySlots.get(0).inventory.getSizeInventory() == 54
                && isDoubleChestHalfAt(level, target);
    }

    private boolean isDoubleChestHalfAt(WorldClient level, @Nullable BlockPos target) {
        return target != null && doubleChestPartner(level, target) != null;
    }

    /**
     * A chest never pairs with a chest of the other kind.
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

    boolean shouldClaimVehicleOpen(EntityPlayerSP player, @Nullable Entity target, boolean vehicleIntentOpen) {
        return ContainerAssociation.shouldClaimVehicleOpen(target instanceof EntityMinecartContainer,
                player.getRidingEntity() instanceof EntityMinecartContainer, vehicleIntentOpen);
    }

    /**
     * The menu names its own mount ({@link MountMenuReader}), so this is both the bind target and the dispatch gate:
     * neither depends on the open target or the ridden vehicle, so a menu opened for one chested animal can never be
     * bound to another the crosshair happens to rest on.
     */
    @Nullable
    AbstractChestHorse chestedAnimal(Container menu) {
        return MountMenuReader.mountOf(menu) instanceof AbstractChestHorse
                ? (AbstractChestHorse) MountMenuReader.mountOf(menu)
                : null;
    }

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
            return null;
        }
        int size = blockContainer.getSizeInventory();
        NonNullList<ItemStack> items = NonNullList.withSize(size, ItemStack.EMPTY);
        // The container index is the menu order of the non-player slots: a client block container adds its slots
        // consecutively in container-index order.
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
     * (BrewTime short, Fuel byte), so the merged block entity loads unchanged.
     */
    static void putBrewingState(NBTTagCompound holder, int brewingTicks, int fuel) {
        holder.setShort("BrewTime", (short) brewingTicks);
        holder.setByte("Fuel", (byte) fuel);
    }

    @Nullable
    NBTTagCompound captureChestSlots(Container menu, EntityPlayerSP player) {
        IInventory playerInventory = player.inventory;
        List<ItemStack> chest = new ArrayList<>();
        for (int i = SLOT_INVENTORY_START; i < menu.inventorySlots.size(); i++) {
            Slot slot = menu.inventorySlots.get(i);
            if (slot.inventory != playerInventory) {
                chest.add(slot.getStack());
            }
        }
        if (chest.isEmpty()) {
            return null;
        }
        // Offset into vanilla's own numbering: a mount's chest shares one inventory with the saddle and armor
        // slots, and its read drops any saved entry below SLOT_INVENTORY_START. A zero-based holder therefore loses
        // its first two stacks and shifts the rest, with no log line and no loss-report row.
        NonNullList<ItemStack> items = NonNullList.withSize(SLOT_INVENTORY_START + chest.size(), ItemStack.EMPTY);
        for (int i = 0; i < chest.size(); i++) {
            items.set(SLOT_INVENTORY_START + i, chest.get(i));
        }
        return adapter.containerSink().captureItems(items);
    }

    /**
     * {@code [0, 27)} is the lower-coordinate chest and {@code [27, 54)} the higher-coordinate one.
     */
    @Nullable
    NBTTagCompound captureHalfSlots(Container menu, EntityPlayerSP player, int low, int high) {
        IInventory playerInventory = player.inventory;
        NonNullList<ItemStack> items = NonNullList.withSize(high - low, ItemStack.EMPTY);
        boolean any = false;
        // The container index is the menu order of the non-player slots. The client double-chest menu wraps one
        // ContainerLocalMenu(54) with contiguous indices 0..53, so menu order equals the container index.
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
