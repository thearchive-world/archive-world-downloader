// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

import world.thearchive.wdl.adapter.PlayerSink;

public final class PlayerSinkImpl implements PlayerSink {
    @Override
    public CompoundTag capturePlayer(Player player) {
        CompoundTag tag = new CompoundTag();
        player.saveWithoutId(tag);
        return tag;
    }

    @Override
    public CompoundTag captureInventory(Inventory inventory) {
        CompoundTag tag = new CompoundTag();
        tag.put("Inventory", inventory.save(new ListTag()));
        tag.putInt("SelectedItemSlot", inventory.selected);
        return tag;
    }
}
