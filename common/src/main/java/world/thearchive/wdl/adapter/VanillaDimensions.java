// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.world.level.dimension.DimensionType;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.WorldType;

/**
 * Maps a captured dimension to the vanilla single-player dimension it is written under. At 1.15.2 the dimension key is
 * the {@link DimensionType} itself (the pre-1.16 model, before the level-key rework), so both the captured dimension
 * and the routed target are {@code DimensionType}s and every dimension a capture records is one of the three static
 * vanilla types.
 *
 * <p>The consequence for two server worlds is ACCEPTED rather than guarded, and it is stated here because this method
 * is where it comes from: worlds routing to one folder share it, so their terrain interleaves and their captured
 * container contents can land on each other. A download therefore covers one server world per folder, and keeping to
 * one is the user's part.
 */
final class VanillaDimensions {
    private VanillaDimensions() {}

    /**
     * The vanilla single-player dimension for a captured dimension {@code type}: the Nether goes to
     * {@link DimensionType#NETHER}, the End to {@link DimensionType#THE_END}, and everything else (overworld, its
     * variants, and any unrecognized or modded type) to {@link DimensionType#OVERWORLD}.
     */
    static DimensionType forType(@Nullable DimensionType type) {
        if (type == DimensionType.NETHER) {
            return DimensionType.NETHER;
        }
        if (type == DimensionType.THE_END) {
            return DimensionType.THE_END;
        }
        return DimensionType.OVERWORLD;
    }

    /**
     * The vanilla single-player dimension a canonical dimension id names, or null when {@code id} is not one of the
     * three {@link #forType} can return. The read side of {@link #forType} over the only ids a capture ever records:
     * every dimension it routes through is a {@link #forType} result, so an id outside that set names a folder no
     * download of ours wrote and a caller reading one has no dimension to route into.
     */
    static @Nullable DimensionType forId(String id) {
        if (DimensionType.getName(DimensionType.NETHER).toString().equals(id)) {
            return DimensionType.NETHER;
        }
        if (DimensionType.getName(DimensionType.THE_END).toString().equals(id)) {
            return DimensionType.THE_END;
        }
        return DimensionType.getName(DimensionType.OVERWORLD).toString().equals(id) ? DimensionType.OVERWORLD : null;
    }

    /**
     * Whether a captured chunk in {@code targetDimension} needs synthesized old-generation blending. Only the DEFAULT
     * generator blends terrain against the captured edge, and only in the overworld, so FLAT and VOID never blend and
     * the nether and end are never blended either. Keying on {@code targetDimension} (a {@link #forType} result) rather
     * than the raw dimension is what makes a Multiverse overworld under a custom key blend correctly.
     */
    static boolean shouldSynthesizeBlending(WorldType worldType, DimensionType targetDimension) {
        return worldType == WorldType.DEFAULT && DimensionType.OVERWORLD.equals(targetDimension);
    }
}
