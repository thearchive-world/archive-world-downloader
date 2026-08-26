// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.profiler.Profiler;
import net.minecraft.world.DimensionType;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.WorldInfo;
import org.jspecify.annotations.Nullable;

/**
 * A do-nothing overworld {@link World} for headless entity fixtures. The {@code EntityLiving} constructor binds a
 * world, so a mob cannot be built against a null one; this supplies the overworld provider and a fresh profiler through
 * the 1.12.2 {@code World} constructor, with a null save handler and inert stubs for its two abstract members.
 */
public final class HeadlessLevel extends World {
    private HeadlessLevel() {
        super(null,
                new WorldInfo(new WorldSettings(0L, GameType.SURVIVAL, false, false, WorldType.DEFAULT), "MpServer"),
                DimensionType.OVERWORLD.createDimension(), new Profiler(), true);
        this.provider.setWorld(this);
    }

    /** A fresh headless overworld; runs the vanilla bootstrap first so the block/item registries are populated. */
    public static HeadlessLevel get() {
        TestRegistries.bootstrap();
        return new HeadlessLevel();
    }

    @Override
    protected @Nullable IChunkProvider createChunkProvider() {
        return null;
    }

    @Override
    protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
        return false;
    }
}
