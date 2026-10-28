// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

import world.thearchive.wdl.adapter.PlayerSink;

/**
 * Below 1.15 vanilla {@code ItemStack.save} puts the live stack's own {@code tag} compound into its output, so both
 * returned tags are detached before they are handed on: the caller owns them, and the client keeps nothing the map-id
 * remap, the coordinate scrub or the save writer could reach.
 */
public final class PlayerSinkImpl implements PlayerSink {
    @Override
    public CompoundTag capturePlayer(Player player) {
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
