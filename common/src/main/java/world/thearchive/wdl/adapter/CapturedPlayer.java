// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

/**
 * The immutable finish-snapshot of the local player, assembled on the client main thread in {@code finish()} and read
 * by the writer thread when it writes the save. Everything here is already finished, an already-serialized
 * {@code playerTag} plus primitives and band-stable value types, so it crosses the thread boundary safely, the same
 * render-thread-snapshot discipline as the entity and container captures.
 *
 * @param playerTag  the processed {@code saveWithoutId} tag (strips, scrub, dimension, ender remap applied) written as
 *                   the band's saved player (a level.dat {@code "Player"} compound pre-26.x, a
 *                   {@code players/data/<uuid>.dat} entry at 26.x)
 * @param spawnPos   the capture block position, written as the world spawn so a non-inheriting opener still lands at
 *                   the base
 * @param yaw        the capture yaw, for a world spawn that records an angle
 * @param pitch      the capture pitch, for a world spawn that records one
 * @param dimension  the canonical capture dimension (already in {@code playerTag}), for a spawn that records one
 * @param gameType   the gamemode for the level.dat {@code GameType} (creative by default, the real mode on the survival
 *                   opt-out)
 * @param difficulty the captured client difficulty, written to level.dat
 */
public record CapturedPlayer(CompoundTag playerTag, BlockPos spawnPos, float yaw, float pitch,
        ResourceKey<Level> dimension, GameType gameType, Difficulty difficulty) {}
