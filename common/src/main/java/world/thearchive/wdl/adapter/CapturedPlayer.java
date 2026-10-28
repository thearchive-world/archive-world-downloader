// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;

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
