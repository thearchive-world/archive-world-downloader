// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

public interface PlayerSink {
    /**
     * Serialize {@code player} into a player-record tag (no {@code id}).
     */
    CompoundTag capturePlayer(Player player);

    /**
     * Serialize {@code inventory} alone into an otherwise empty {@code "Player"}-shaped compound, in the
     * {@code Inventory} list and {@code SelectedItemSlot} form {@code Player.addAdditionalSaveData} writes them.
     */
    CompoundTag captureInventory(Inventory inventory);
}
