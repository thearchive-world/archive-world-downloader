// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

import world.thearchive.wdl.adapter.PlayerSink;

public final class PlayerSinkImpl implements PlayerSink {
    @Override
    public CompoundTag capturePlayer(Player player, RegistryAccess registries) {
        CompoundTag tag = new CompoundTag();
        player.saveWithoutId(tag);
        return tag;
    }
}
