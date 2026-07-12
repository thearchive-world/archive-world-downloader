// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * Per-band player-serialize axis: serialize the local player into the vanilla {@code "Player"} compound via
 * {@code player.saveWithoutId(ValueOutput)}, the identical call vanilla's own {@code PlayerDataStorage.save} uses. The
 * capture pipeline routes the local player here rather than through the entity axis ({@link EntitySink}), mirroring
 * vanilla's player/entity-region save split.
 *
 * <p>Per-band because the serialize API drifts: 1.21.11 takes a {@code ValueOutput}/{@code TagValueOutput} (the codec
 * layer), while the &le;1.21.8 sub-band takes the older {@code CompoundTag} save form. Mirrors {@link EntitySink}'s
 * lift (a DISCARDING problem reporter replaces the world-scoped collector). The single step is client-coupled (a live
 * {@code Player}), exactly as {@link EntitySink}'s live {@code entity.save} step is; the headless guard is the pure
 * downstream ({@link PlayerTag}, {@link ItemLocationScrub}, the level.dat apply).
 */
public interface PlayerSink {
    /**
     * Serialize {@code player} into a {@code "Player"}-compound tag (no {@code id}): the {@code Entity} super fields
     * ({@code Pos}/{@code Rotation}/{@code UUID}/...) plus {@code Player.addAdditionalSaveData}
     * ({@code Inventory}/{@code SelectedItemSlot}/{@code EnderItems}/ {@code abilities}/...). Server-free via a
     * discarding problem reporter.
     */
    CompoundTag capturePlayer(Player player, RegistryAccess registries);
}
