// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

public interface PlayerSink {
    /**
     * Serialize {@code player} into a player-record tag (no {@code id}).
     */
    CompoundTag capturePlayer(Player player, RegistryAccess registries);
}
