// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import com.google.common.collect.ImmutableList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.EmptyTickList;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.LevelType;
import net.minecraft.world.level.TickList;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.scores.Scoreboard;
import org.jspecify.annotations.Nullable;

/**
 * A do-nothing overworld {@link Level} for headless entity fixtures. At 1.15.2 the {@code Mob} constructor binds a
 * level, so a mob cannot be built against a null one; this supplies the overworld dimension and an inactive profiler
 * through the pre-1.16 {@code Level} constructor, with a null chunk source and inert stubs for the abstract members no
 * fixture calls. The vanilla bootstrap (via {@link TestRegistries}) must have populated the dimension registry first.
 */
public final class HeadlessLevel extends Level {
    private final TagManager tags = new TagManager();

    private HeadlessLevel() {
        super(new LevelData(new LevelSettings(0L, GameType.SURVIVAL, false, false, LevelType.NORMAL), "MpServer"),
                DimensionType.OVERWORLD, (level, dimension) -> null, InactiveProfiler.INACTIVE, true);
    }

    /** A fresh headless overworld; runs the vanilla bootstrap first so the dimension registry is populated. */
    public static HeadlessLevel get() {
        TestRegistries.bootstrap();
        return new HeadlessLevel();
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {}

    @Override
    public void playSound(@Nullable Player player, double x, double y, double z, SoundEvent sound,
            SoundSource source, float volume, float pitch) {}

    @Override
    public void playSound(@Nullable Player player, Entity entity, SoundEvent sound, SoundSource source,
            float volume, float pitch) {}

    @Override
    public @Nullable Entity getEntity(int id) {
        return null;
    }

    @Override
    public @Nullable MapItemSavedData getMapData(String id) {
        return null;
    }

    @Override
    public void setMapData(MapItemSavedData data) {}

    @Override
    public int getFreeMapId() {
        return 0;
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
    public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {}

    @Override
    public Biome getUncachedNoiseBiome(int x, int y, int z) {
        return Biomes.PLAINS;
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

    @Override
    public List<? extends Player> players() {
        return ImmutableList.of();
    }
}
