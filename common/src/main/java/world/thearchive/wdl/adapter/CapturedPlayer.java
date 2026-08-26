// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;

/**
 * The immutable finish-snapshot of the local player, assembled on the client main thread in {@code finish()} and read
 * by the writer thread when it writes the save. Everything here is already finished, an already-serialized
 * {@code playerTag} plus primitives and band-stable value types, so it crosses the thread boundary safely, the same
 * render-thread-snapshot discipline as the entity and container captures.
 *
 * @param playerTag  the processed {@code writeToNBT} tag (strips, scrub, dimension, ender remap applied) written as the
 *                   band's saved player (a level.dat {@code "Player"} compound pre-26.x, a
 *                   {@code players/data/<uuid>.dat} entry at 26.x)
 * @param spawnPos   the capture block position, written as the world spawn so a non-inheriting opener still lands at
 *                   the base
 * @param yaw        the capture yaw, for the world spawn
 * @param pitch      the capture pitch, for the world spawn
 * @param dimension  the canonical capture dimension, shared by the {@code "Player"} tag and the world spawn
 * @param gameType   the gamemode for the level.dat {@code GameType} (creative by default, the real mode on the survival
 *                   opt-out)
 * @param difficulty the captured client difficulty for the level.dat {@code Difficulty}
 */
public final class CapturedPlayer {
    private final NBTTagCompound playerTag;
    private final BlockPos spawnPos;
    private final float yaw;
    private final float pitch;
    private final DimensionType dimension;
    private final GameType gameType;
    private final EnumDifficulty difficulty;

    CapturedPlayer(NBTTagCompound playerTag, BlockPos spawnPos, float yaw, float pitch, DimensionType dimension,
            GameType gameType, EnumDifficulty difficulty) {
        this.playerTag = playerTag;
        this.spawnPos = spawnPos;
        this.yaw = yaw;
        this.pitch = pitch;
        this.dimension = dimension;
        this.gameType = gameType;
        this.difficulty = difficulty;
    }

    public NBTTagCompound playerTag() {
        return playerTag;
    }

    public BlockPos spawnPos() {
        return spawnPos;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public DimensionType dimension() {
        return dimension;
    }

    public GameType gameType() {
        return gameType;
    }

    public EnumDifficulty difficulty() {
        return difficulty;
    }
}
