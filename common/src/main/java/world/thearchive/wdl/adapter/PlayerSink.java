// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

public interface PlayerSink {
    /**
     * Serialize {@code player} into a player-record tag (no {@code id}).
     */
    NBTTagCompound capturePlayer(EntityPlayer player);
}
