// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

import world.thearchive.wdl.adapter.PlayerSink;

/**
 * 1.18.2 player sink: serializes the local player via vanilla's own {@code player.saveWithoutId} (the identical call
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
        player.saveWithoutId(tag);
        return tag;
    }
}
