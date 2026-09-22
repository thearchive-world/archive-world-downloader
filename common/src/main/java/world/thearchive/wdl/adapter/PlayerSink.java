// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

/**
 * Per-band player-serialize axis: serialize the local player into the vanilla {@code "Player"} compound via
 * {@code player.saveWithoutId}, the identical call vanilla's own player save uses. The capture pipeline routes the
 * local player here rather than through the entity axis ({@link EntitySink}), mirroring vanilla's player/entity-region
 * save split.
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

    /**
     * Serialize {@code inventory} alone into an otherwise empty {@code "Player"}-shaped compound, in the
     * {@code Inventory} list and {@code SelectedItemSlot} form {@code Player.addAdditionalSaveData} writes them. The
     * salvage source for a finish whose {@link #capturePlayer} threw: the inventory serialize touches nothing but the
     * stacks, so it stands when the whole-entity write cannot. Server-free.
     */
    CompoundTag captureInventory(Inventory inventory);
}
