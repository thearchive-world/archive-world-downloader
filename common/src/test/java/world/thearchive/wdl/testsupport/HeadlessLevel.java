// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagManager;
import net.minecraft.util.profiling.ActiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.EmptyTickList;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.LevelType;
import net.minecraft.world.level.TickList;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.scores.Scoreboard;
import org.jspecify.annotations.Nullable;

/**
 * A do-nothing overworld {@link Level} for headless entity fixtures. The {@code Mob} constructor binds a level, so a
 * mob cannot be built against a null one; this supplies the overworld dimension and a fresh profiler through the 1.13.2
 * {@code Level} constructor, with a null server, a null dimension-data store, a null chunk source (the abstract
 * {@code method_3712} factory returns none), and inert stubs for the members no fixture calls. The vanilla bootstrap
 * (via {@link TestRegistries}) must have populated the dimension registry first.
 */
public final class HeadlessLevel extends Level {
    private final TagManager tags = new TagManager();

    private HeadlessLevel() {
        super(null, null,
                new LevelData(new LevelSettings(0L, GameType.SURVIVAL, false, false, LevelType.NORMAL), "MpServer"),
                DimensionType.field_18954.method_17203(), new ActiveProfiler(), true);
    }

    /** A fresh headless overworld; runs the vanilla bootstrap first so the dimension registry is populated. */
    public static HeadlessLevel get() {
        TestRegistries.bootstrap();
        return new HeadlessLevel();
    }

    @Override
    protected @Nullable ChunkSource method_3712() {
        return null;
    }

    // hasChunk returns the ChunkAccess at the chunk coordinates despite the boolean-sounding name; there is none here.
    @Override
    public @Nullable ChunkAccess hasChunk(int chunkX, int chunkZ) {
        return null;
    }

    @Override
    public boolean setChunkForced(int chunkX, int chunkZ, boolean forced) {
        return false;
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {}

    @Override
    public void playSound(@Nullable Player player, double x, double y, double z, SoundEvent sound,
            SoundSource source, float volume, float pitch) {}

    @Override
    public @Nullable Entity getEntity(int id) {
        return null;
    }

    @Override
    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {}

    @Override
    public TickList<Block> getBlockTicks() {
        return EmptyTickList.empty();
    }

    @Override
    public TickList<Fluid> getLiquidTicks() {
        return EmptyTickList.empty();
    }

    @Override
    public @Nullable Scoreboard getScoreboard() {
        return null;
    }

    @Override
    public @Nullable RecipeManager getRecipeManager() {
        return null;
    }

    @Override
    public TagManager getTagManager() {
        return this.tags;
    }
}
