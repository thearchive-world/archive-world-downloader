// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueOutput;

import world.thearchive.wdl.adapter.PlayerSink;

/**
 * 1.21.8 player sink: serializes the local player via vanilla's own {@code player.saveWithoutId} on the
 * codec/{@code ValueOutput} layer (the identical call {@code PlayerDataStorage.save} uses), so the captured
 * {@code "Player"} compound is byte-for-byte what a vanilla {@code playerdata/<uuid>.dat} would hold.
 *
 * <p>The single step is client-coupled (a live {@code Player}), mirroring
 * {@link world.thearchive.wdl.adapter.EntitySink}'s live {@code entity.save} step; the pure downstream (strips, scrub,
 * level.dat apply) carries the headless guard.
 */
public final class PlayerSinkImpl implements PlayerSink {
    @Override
    public CompoundTag capturePlayer(Player player, RegistryAccess registries) {
        // Lift of PlayerDataStorage.save, server-free: a DISCARDING ProblemReporter replaces the
        // world-scoped collector (the same choice EntitySink/ContainerSink made). saveWithoutId writes the
        // Entity super fields (Pos/Rotation/UUID) + Player.addAdditionalSaveData (Inventory/SelectedItemSlot/
        // EnderItems/abilities/...), with no id.
        TagValueOutput out = DiscardingTagOutput.create(registries);
        List<Runnable> restores = new ArrayList<>();
        try {
            sanitizeCarriedStacks(player, registries, restores);
            player.saveWithoutId(out);
        } finally {
            // Newest first: both passes below reach the held slot, and undoing them in that order puts the
            // original back on the live player even if a slot were ever swapped twice.
            for (int i = restores.size() - 1; i >= 0; i--) {
                restores.get(i).run();
            }
        }
        return out.buildResult();
    }

    private static void sanitizeCarriedStacks(Player player, RegistryAccess registries, List<Runnable> restores) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            swapIfUnsavable(player.getItemBySlot(slot), ops, stack -> player.setItemSlot(slot, stack), restores);
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            int index = slot;
            swapIfUnsavable(inventory.getItem(index), ops, stack -> inventory.setItem(index, stack), restores);
        }
    }

    private static void swapIfUnsavable(ItemStack original, RegistryOps<Tag> ops, Consumer<ItemStack> write,
            List<Runnable> restores) {
        if (original.isEmpty()) {
            return;
        }
        ItemStack clean = ItemStackSanitizer.sanitizeForSave(original, ops);
        if (clean != original) {
            write.accept(clean);
            restores.add(() -> write.accept(original));
        }
    }
}
