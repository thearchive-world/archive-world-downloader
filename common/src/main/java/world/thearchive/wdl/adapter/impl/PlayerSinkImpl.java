// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

import world.thearchive.wdl.adapter.PlayerSink;

/**
 * 1.12.2 player sink: serializes the local player via vanilla's own {@code player.writeToNBT} (the identical call
 * {@code SaveHandler.writePlayerData} uses), so the captured {@code "Player"} compound is byte-for-byte what a vanilla
 * {@code playerdata/<uuid>.dat} would hold.
 *
 * <p>The single step is client-coupled (a live {@code EntityPlayer}), mirroring
 * {@link world.thearchive.wdl.adapter.EntitySink}'s live {@code entity.writeToNBT} step; the pure downstream (strips,
 * scrub, level.dat apply) carries the headless guard.
 */
public final class PlayerSinkImpl implements PlayerSink {
    /**
     * Below 1.15 vanilla {@code ItemStack.writeToNBT} puts the live stack's own {@code tag} compound into its output,
     * so the returned tag is detached before it is handed on: the caller owns it, and the client keeps nothing the
     * map-id remap, the coordinate scrub or the save writer could reach.
     */
    @Override
    public NBTTagCompound capturePlayer(EntityPlayer player) {
        // writeToNBT writes the Entity super fields (Pos/Rotation/UUID) plus EntityPlayer.writeEntityToNBT
        // (Inventory/SelectedItemSlot/EnderItems/abilities), with no id.
        NBTTagCompound tag = new NBTTagCompound();
        player.writeToNBT(tag);
        return tag.copy();
    }
}
