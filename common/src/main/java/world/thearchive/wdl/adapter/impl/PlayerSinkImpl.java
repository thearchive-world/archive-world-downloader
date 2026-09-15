// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

import world.thearchive.wdl.adapter.PlayerSink;

/**
 * 1.13.2 player sink: serializes the local player via vanilla's own {@code player.saveWithoutId} (the identical call
 * {@code PlayerDataStorage.save} uses), so the captured {@code "Player"} compound is byte-for-byte what a vanilla
 * {@code playerdata/<uuid>.dat} would hold, and the inventory alone via {@code Inventory.save}, the call that whole
 * write makes for its {@code Inventory} list.
 *
 * <p>The whole-player step is client-coupled (a live {@code Player}), mirroring
 * {@link world.thearchive.wdl.adapter.EntitySink}'s live {@code entity.save} step; the inventory step and the pure
 * downstream (strips, scrub, level.dat apply) carry the headless guard.
 *
 * <p>Below 1.15 vanilla {@code ItemStack.save} puts the live stack's own {@code tag} compound into its output, so both
 * returned tags are detached before they are handed on: the caller owns them, and the client keeps nothing the map-id
 * remap, the coordinate scrub or the save writer could reach.
 */
public final class PlayerSinkImpl implements PlayerSink {
    @Override
    public CompoundTag capturePlayer(Player player) {
        // saveWithoutId writes the Entity super fields (Pos/Rotation/UUID) plus Player.addAdditionalSaveData
        // (Inventory/SelectedItemSlot/EnderItems/abilities), with no id.
        CompoundTag tag = new CompoundTag();
        player.saveWithoutId(tag);
        return tag.copy();
    }

    @Override
    public CompoundTag captureInventory(Inventory inventory) {
        CompoundTag tag = new CompoundTag();
        tag.put("Inventory", inventory.save(new ListTag()).copy());
        tag.putInt("SelectedItemSlot", inventory.selected);
        return tag;
    }
}
