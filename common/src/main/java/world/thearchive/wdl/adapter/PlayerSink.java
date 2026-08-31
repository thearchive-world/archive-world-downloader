// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * Per-band player-serialize axis: serialize the local player into the vanilla {@code "Player"} compound via
 * {@code player.saveWithoutId}, the identical call vanilla's own {@code PlayerDataStorage.save} uses. The capture
 * pipeline routes the local player here rather than through the entity axis ({@link EntitySink}), mirroring vanilla's
 * player/entity-region save split.
 *
 * <p>Per-band because the serialize API drifts. The single step is client-coupled (a live {@code Player}); the headless
 * guard is the pure downstream ({@link PlayerTag}, {@link ItemLocationScrub}, the save apply).
 */
public interface PlayerSink {
    /**
     * Serialize {@code player} into a {@code "Player"}-compound tag (no {@code id}): the {@code Entity} super fields
     * ({@code Pos}/{@code Rotation}/{@code UUID}/...) plus {@code Player.addAdditionalSaveData}
     * ({@code Inventory}/{@code SelectedItemSlot}/{@code EnderItems}/ {@code abilities}/...). Server-free.
     */
    CompoundTag capturePlayer(Player player);
}
