// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.WorldType;

/**
 * Maps a captured dimension to the vanilla single-player dimension it is written under, keyed by its dimension TYPE
 * rather than its level key. For a server with non-standard level keys (e.g. {@code minecraft:worlds/2b2t/2b2t_1}),
 * routing by type lays the save out under the vanilla dimension's own folder rather than one derived from the custom
 * level key.
 *
 * <p>The consequence for two server worlds is ACCEPTED rather than guarded: worlds routing to one folder share it, so
 * their terrain interleaves and their captured container contents can land on each other. A download therefore covers
 * one server world per folder, and keeping to one is the user's part.
 */
final class VanillaDimensions {
    private VanillaDimensions() {}

    static ResourceKey<Level> forType(@Nullable ResourceKey<DimensionType> typeKey) {
        if (BuiltinDimensionTypes.NETHER.equals(typeKey)) {
            return Level.NETHER;
        }
        if (BuiltinDimensionTypes.END.equals(typeKey)) {
            return Level.END;
        }
        return Level.OVERWORLD;
    }

    static @Nullable ResourceKey<Level> forId(String id) {
        if (Level.NETHER.location().toString().equals(id)) {
            return Level.NETHER;
        }
        if (Level.END.location().toString().equals(id)) {
            return Level.END;
        }
        return Level.OVERWORLD.location().toString().equals(id) ? Level.OVERWORLD : null;
    }

    /**
     * On a version that blends old chunks, keying on {@code targetDimension} (a {@link #forType} result) rather than
     * the raw level key is what makes an overworld under a custom level key blend correctly.
     */
    static boolean shouldSynthesizeBlending(WorldType worldType, ResourceKey<Level> targetDimension) {
        return worldType == WorldType.DEFAULT && Level.OVERWORLD.equals(targetDimension);
    }
}
