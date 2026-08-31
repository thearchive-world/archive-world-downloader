// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Per-band player-serialize axis: serialize the local player into the vanilla {@code "Player"} compound via
 * {@code player.writeToNBT(NBTTagCompound)}. The capture pipeline routes the local player here rather than through the
 * entity axis ({@link EntitySink}), mirroring vanilla's player/entity-region save split.
 *
 * <p>Per-band because the serialize API drifts. The single step is client-coupled (a live {@code EntityPlayer}); the
 * headless guard is the pure downstream ({@link PlayerTag}, {@link ItemLocationScrub}, the save apply).
 */
public interface PlayerSink {
    /**
     * Serialize {@code player} into a {@code "Player"}-compound tag (no {@code id}): the {@code Entity} super fields
     * ({@code Pos}/{@code Rotation}/{@code UUID}/...).
     */
    NBTTagCompound capturePlayer(EntityPlayer player);
}
