// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.dimension.DimensionType;

public final class CapturedPlayer {
    private final CompoundTag playerTag;
    private final BlockPos spawnPos;
    private final float yaw;
    private final float pitch;
    private final DimensionType dimension;
    private final GameType gameType;
    private final Difficulty difficulty;

    CapturedPlayer(CompoundTag playerTag, BlockPos spawnPos, float yaw, float pitch, DimensionType dimension,
            GameType gameType, Difficulty difficulty) {
        this.playerTag = playerTag;
        this.spawnPos = spawnPos;
        this.yaw = yaw;
        this.pitch = pitch;
        this.dimension = dimension;
        this.gameType = gameType;
        this.difficulty = difficulty;
    }

    public CompoundTag playerTag() {
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

    public Difficulty difficulty() {
        return difficulty;
    }
}
