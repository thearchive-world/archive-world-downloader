// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
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
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CrafterMenu;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import world.thearchive.wdl.core.ContainerAssociation;
import world.thearchive.wdl.core.CrafterSlots;
import world.thearchive.wdl.core.OpenClickIntent;
import world.thearchive.wdl.core.SpectatorCrosshairFallback;
import world.thearchive.wdl.platform.PlatformBridge;

final class ContainerCapture {
    private static final Logger LOGGER = LogUtils.getLogger();

    // The chest slots in a horse menu start at this menu index: slot 0 is the saddle and slot 1 is the body-armor on
    // every band.
    private static final int SLOT_INVENTORY_START = 2;

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
     * The clicked target is the fix for open-time drift (the crosshair keeps moving until the menu freezes the camera),
     * and an open no click accounts for is left unattributed: where the player LOOKS is not evidence of what opened, so
     * binding a menu to the crosshair writes its contents into whatever block or entity the view happens to rest on
     * once the slot counts coincide.
     *
     * <p>A spectator keeps the crosshair, and it is LOAD-BEARING there: a loader use hook that declines to fire for a
     * spectator leaves the click unobserved, so every container a spectator opens on that axis would bind nothing
     * without this. Vanilla is not the obstacle (a spectator's use packet still goes out, and the server opens a
     * clicked chest's menu); the loader hook is, so do not delete this branch on the reasoning that the click chain
     * already covers the gamemode. The open-time drift the clicked target removes elsewhere is accepted here, because
     * the alternative on a blind axis is capturing nothing at all.
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
                LOGGER.info("open target: superseded (a later intent overwrote the one this open belongs to)");
                return new OpenTarget(null, null, false);
            }
        }
        HitResult hit = minecraft.hitResult;
        BlockHitResult blockHit = hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK
                ? block
                : null;
        EntityHitResult entityHit = hit instanceof EntityHitResult entity
                && entity.getType() == HitResult.Type.ENTITY ? entity : null;
        // The provider read is gated on the gamemode here, not left to the rule below: Java evaluates arguments
        // eagerly, so passing it unguarded would run a mod-overridable getMenuProvider and an on-demand getBlockEntity
        // for every unattributed open in every gamemode, on a path with no catch between it and the capture tick.
        boolean spectator = player.isSpectator();
        boolean blockOpensForSpectator = spectator && blockHit != null
                && spectatorCouldOpen(player.level(), blockHit.getBlockPos());
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

    private static boolean spectatorCouldOpen(Level level, BlockPos pos) {
        return OpenClickTracker.opensMenuFor(level, pos, true);
    }

    record OpenTarget(@Nullable BlockPos block, @Nullable Entity entity, boolean vehicleIntent) {}

    boolean isEnderChestAt(ClientLevel level, @Nullable BlockPos target) {
        return target != null && level.getBlockEntity(target) instanceof EnderChestBlockEntity;
    }

    /**
     * The six-row gate is the cheap "this is a 54 open" signal; it also rejects any sub-54 {@code ChestMenu} seen while
     * the client still renders a double half (which the sum guard would otherwise claim then drop, silently losing it)
     * so that a 27-slot menu falls through to the single-block container bind instead.
     */
    boolean isDoubleChestOpen(ClientLevel level, AbstractContainerMenu menu, @Nullable BlockPos target) {
        return menu instanceof ChestMenu chestMenu && chestMenu.getRowCount() == 6
                && isDoubleChestHalfAt(level, target);
    }

    private boolean isDoubleChestHalfAt(ClientLevel level, @Nullable BlockPos target) {
        if (target == null) {
            return false;
        }
        BlockState state = level.getBlockState(target);
        return level.getBlockEntity(target) instanceof ChestBlockEntity
                && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
    }

    /**
     * The riding case, where the version has chest boats, is the press-E open flow: a chest boat opens its menu through
     * {@code player.getVehicle()} (the inventory key opens it server-side) and fires no use event, so the clicked
     * target alone misses it.
     */
    boolean shouldClaimVehicleOpen(LocalPlayer player, @Nullable Entity target, boolean vehicleIntentOpen) {
        return ContainerAssociation.shouldClaimVehicleOpen(target instanceof ContainerEntity,
                player.getVehicle() instanceof ContainerEntity, vehicleIntentOpen);
    }

    /**
     * The menu names its own mount ({@link MountMenuReader}), so this is both the bind target and the dispatch gate:
     * neither depends on the open target or the ridden vehicle, so a menu opened for one chested animal can never be
     * bound to another the crosshair happens to rest on.
     */
    @Nullable
    AbstractChestedHorse chestedAnimal(AbstractContainerMenu menu) {
        return MountMenuReader.mountOf(menu) instanceof AbstractChestedHorse animal ? animal : null;
    }

    static int countBlockSlots(AbstractContainerMenu menu, LocalPlayer player) {
        Container playerInventory = player.getInventory();
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
        Container playerInventory = player.getInventory();
        int count = 0;
        for (int i = SLOT_INVENTORY_START; i < menu.slots.size(); i++) {
            if (menu.slots.get(i).container != playerInventory) {
                count++;
            }
        }
        return count;
    }

    /**
     * Count a crafter menu's crafting-grid slots: the slots backed by the menu's own crafting container, which excludes
     * the recipe-preview result slot sitting on a separate container. Equals the crafter block's own container size,
     * the mis-bind guard.
     */
    static int countCrafterInputSlots(CrafterMenu menu) {
        Container crafting = menu.getContainer();
        int count = 0;
        for (Slot slot : menu.slots) {
            if (slot.container == crafting) {
                count++;
            }
        }
        return count;
    }

    @Nullable
    CompoundTag captureBlockSlots(AbstractContainerMenu menu, LocalPlayer player) {
        Container playerInventory = player.getInventory();
        Container blockContainer = null;
        for (Slot slot : menu.slots) {
            if (slot.container != playerInventory) {
                blockContainer = slot.container;
                break;
            }
        }
        if (blockContainer == null) {
            return null;
        }
        int size = blockContainer.getContainerSize();
        NonNullList<ItemStack> items = NonNullList.withSize(size, ItemStack.EMPTY);
        for (Slot slot : menu.slots) {
            if (slot.container == playerInventory) {
                continue;
            }
            int index = slot.getContainerSlot();
            if (index >= 0 && index < size) {
                items.set(index, slot.getItem());
            }
        }
        return adapter.containerSink().captureItems(items, registries);
    }

    @Nullable
    CompoundTag captureCrafterSlots(CrafterMenu menu) {
        NonNullList<ItemStack> items = selectCrafterInputs(menu.slots, menu.getContainer());
        if (items == null) {
            return null;
        }
        CompoundTag holder = adapter.containerSink().captureItems(items, registries);
        boolean[] disabled = new boolean[CrafterSlots.SLOT_COUNT];
        for (int i = 0; i < disabled.length; i++) {
            disabled[i] = menu.isSlotDisabled(i);
        }
        holder.putIntArray("disabled_slots", CrafterSlots.disabledSlotIndices(disabled));
        holder.putInt("triggered", menu.isPowered() ? 1 : 0);
        return holder;
    }

    /**
     * Only slots whose backing container is {@code crafting} (the menu's own crafting container) are taken: the crafter
     * menu also carries the recipe-preview result slot on a SEPARATE container whose single slot reports container
     * index 0, so a lift keyed on container index alone would clobber input slot 0.
     */
    @Nullable
    static NonNullList<ItemStack> selectCrafterInputs(List<Slot> slots, Container crafting) {
        NonNullList<ItemStack> items = NonNullList.withSize(crafting.getContainerSize(), ItemStack.EMPTY);
        boolean any = false;
        for (Slot slot : slots) {
            if (slot.container == crafting) {
                int index = slot.getContainerSlot();
                if (index >= 0 && index < items.size()) {
                    items.set(index, slot.getItem());
                    any = true;
                }
            }
        }
        return any ? items : null;
    }

    /**
     * Ride the brewing stand's persisted menu-only state on {@code holder}: vanilla's exact key names and NBT types
     * (BrewTime short, Fuel byte), so the merged block entity loads unchanged.
     */
    static void putBrewingState(CompoundTag holder, int brewingTicks, int fuel) {
        holder.putShort("BrewTime", (short) brewingTicks);
        holder.putByte("Fuel", (byte) fuel);
    }

    @Nullable
    CompoundTag captureChestSlots(AbstractContainerMenu menu, LocalPlayer player) {
        Container playerInventory = player.getInventory();
        List<ItemStack> chest = new ArrayList<>();
        for (int i = SLOT_INVENTORY_START; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container != playerInventory) {
                // The 0-based menu-order index here, not getContainerSlot(), is the chest-relative Items slot: on
                // versions that keep the saddle at container index 0, getContainerSlot() counts it, so the menu-order
                // count is what absorbs that saddle offset and keeps the chest from shifting by one. Do not reindex by
                // getContainerSlot() or fold this into captureBlockSlots.
                chest.add(slot.getItem());
            }
        }
        if (chest.isEmpty()) {
            return null;
        }
        NonNullList<ItemStack> items = NonNullList.withSize(chest.size(), ItemStack.EMPTY);
        for (int i = 0; i < chest.size(); i++) {
            items.set(i, chest.get(i));
        }
        return adapter.containerSink().captureItems(items, registries);
    }

    /**
     * The client's double-chest menu is one {@code SimpleContainer(54)} with contiguous container indices 0..53, so
     * {@code [0, 27)} is the RIGHT chest and {@code [27, 54)} the LEFT (the {@code CompoundContainer} (RIGHT, LEFT)
     * order).
     */
    @Nullable
    CompoundTag captureHalfSlots(AbstractContainerMenu menu, LocalPlayer player, int low, int high) {
        Container playerInventory = player.getInventory();
        NonNullList<ItemStack> items = NonNullList.withSize(high - low, ItemStack.EMPTY);
        boolean any = false;
        for (Slot slot : menu.slots) {
            if (slot.container == playerInventory) {
                continue;
            }
            // getContainerSlot() is safe here: the client double-chest menu is one SimpleContainer(54) with no offset,
            // so getContainerSlot() equals menu order. Do not unify this lift with captureChestSlots, whose 0-based
            // menu-order numbering absorbs the saddle offset some versions have (container index 0); merging them would
            // shift a mount's chest by one there.
            int index = slot.getContainerSlot();
            if (index >= low && index < high) {
                items.set(index - low, slot.getItem());
                any = true;
            }
        }
        return any ? adapter.containerSink().captureItems(items, registries) : null;
    }
}
