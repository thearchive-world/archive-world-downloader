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
 * rather than its level key. A server with non-standard level keys (e.g. Multiverse's
 * {@code minecraft:worlds/2b2t/2b2t_1}) still uses a vanilla dimension type, so routing by type lays the save out under
 * the vanilla dimension's own folder rather than one derived from the custom level key. Band-agnostic: only stable
 * vanilla constants, so it is byte-identical across the era bands.
 *
 * <p>The consequence for two server worlds is ACCEPTED rather than guarded, and it is stated here because this method
 * is where it comes from: worlds routing to one folder share it, so their terrain interleaves and their captured
 * container contents can land on each other. A download therefore covers one server world per folder, and keeping to
 * one is the user's part.
 */
final class VanillaDimensions {
    private VanillaDimensions() {}

    /**
     * The vanilla single-player dimension for a captured dimension's {@code typeKey} (the dimension type's registry
     * key, or {@code null} for an unregistered holder): the Nether type goes to {@link Level#NETHER}, the End to
     * {@link Level#END}, and everything else (overworld, its variants, and any unrecognized type) to
     * {@link Level#OVERWORLD}.
     */
    static ResourceKey<Level> forType(@Nullable ResourceKey<DimensionType> typeKey) {
        if (BuiltinDimensionTypes.NETHER.equals(typeKey)) {
            return Level.NETHER;
        }
        if (BuiltinDimensionTypes.END.equals(typeKey)) {
            return Level.END;
        }
        return Level.OVERWORLD;
    }

    /**
     * The vanilla single-player dimension a canonical level id names, or null when {@code id} is not one of the three
     * {@link #forType} can return. The read side of {@link #forType} over the only ids a capture ever records: every
     * dimension it routes through is a {@link #forType} result, so an id outside that set names a folder no download of
     * ours wrote and a caller reading one has no dimension to route into.
     */
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
     * Whether a captured chunk in {@code targetDimension} needs synthesized old-generation blending. Only the DEFAULT
     * noise generator blends terrain against the captured edge, and only in the overworld: blending is a
     * {@code NoiseBasedChunkGenerator} mechanism, so FLAT (which writes fixed layers and discards the blender) and VOID
     * (no neighbor terrain) never blend and their output is left untouched; the nether and end are never blended either
     * (the fix is overworld-only). Keying on {@code targetDimension} (a {@link #forType} result) rather than the raw
     * level key is what makes a Multiverse overworld under a custom key blend correctly.
     */
    static boolean shouldSynthesizeBlending(WorldType worldType, ResourceKey<Level> targetDimension) {
        return worldType == WorldType.DEFAULT && Level.OVERWORLD.equals(targetDimension);
    }
}
