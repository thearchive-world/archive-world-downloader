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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import world.thearchive.wdl.adapter.PlayerSink;

/**
 * 1.21.4 player sink: serializes the local player via vanilla's own {@code player.saveWithoutId} (the identical call
 * {@code PlayerDataStorage.save} uses), so the captured {@code "Player"} compound is byte-for-byte what a vanilla
 * {@code playerdata/<uuid>.dat} would hold.
 *
 * <p>The single step is client-coupled (a live {@code Player}), mirroring
 * {@link world.thearchive.wdl.adapter.EntitySink}'s live {@code entity.save} step; the pure downstream (strips, scrub,
 * level.dat apply) carries the headless guard.
 */
public final class PlayerSinkImpl implements PlayerSink {
    @Override
    public CompoundTag capturePlayer(Player player, RegistryAccess registries) {
        // saveWithoutId writes the Entity super fields (Pos/Rotation/UUID) plus Player.addAdditionalSaveData
        // (Inventory/SelectedItemSlot/EnderItems/abilities), with no id.
        CompoundTag tag = new CompoundTag();
        List<Runnable> restores = new ArrayList<>();
        try {
            sanitizeEquipment(player, registries, restores);
            player.saveWithoutId(tag);
        } finally {
            // The swaps land on the live player, so a throw from either call above must still put every original back.
            for (Runnable restore : restores) {
                restore.run();
            }
        }
        return tag;
    }

    private static void sanitizeEquipment(Player player, RegistryAccess registries, List<Runnable> restores) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            swapIfUnsavable(player.getItemBySlot(slot), ops, stack -> player.setItemSlot(slot, stack), restores);
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
