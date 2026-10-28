// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

import world.thearchive.wdl.adapter.PlayerSink;

public final class PlayerSinkImpl implements PlayerSink {
    /**
     * Below 1.15 vanilla {@code ItemStack.writeToNBT} puts the live stack's own {@code tag} compound into its output,
     * so the returned tag is detached before it is handed on: the caller owns it, and the client keeps nothing the
     * map-id remap, the coordinate scrub or the save writer could reach.
     */
    @Override
    public NBTTagCompound capturePlayer(EntityPlayer player) {
        NBTTagCompound tag = new NBTTagCompound();
        player.writeToNBT(tag);
        return tag.copy();
    }
}
