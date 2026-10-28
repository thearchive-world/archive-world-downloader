// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

public record CapturedPlayer(CompoundTag playerTag, BlockPos spawnPos, float yaw, float pitch,
        ResourceKey<Level> dimension, GameType gameType, Difficulty difficulty) {}
