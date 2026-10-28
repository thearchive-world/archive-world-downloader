// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.world.level.dimension.DimensionType;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.WorldType;

/**
 * The consequence for two server worlds is ACCEPTED rather than guarded: worlds routing to one folder share it, so
 * their terrain interleaves and their captured container contents can land on each other. A download therefore covers
 * one server world per folder, and keeping to one is the user's part.
 */
final class VanillaDimensions {
    private VanillaDimensions() {}

    static DimensionType forType(@Nullable DimensionType type) {
        if (type == DimensionType.NETHER) {
            return DimensionType.NETHER;
        }
        if (type == DimensionType.field_18956) {
            return DimensionType.field_18956;
        }
        return DimensionType.field_18954;
    }

    static @Nullable DimensionType forId(String id) {
        if (DimensionType.getName(DimensionType.NETHER).toString().equals(id)) {
            return DimensionType.NETHER;
        }
        if (DimensionType.getName(DimensionType.field_18956).toString().equals(id)) {
            return DimensionType.field_18956;
        }
        return DimensionType.getName(DimensionType.field_18954).toString().equals(id) ? DimensionType.field_18954
                : null;
    }

    static boolean shouldSynthesizeBlending(WorldType worldType, DimensionType targetDimension) {
        return worldType == WorldType.DEFAULT && DimensionType.field_18954.equals(targetDimension);
    }
}
