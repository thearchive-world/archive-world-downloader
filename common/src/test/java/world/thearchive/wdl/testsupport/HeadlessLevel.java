// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;
import org.jspecify.annotations.Nullable;

/**
 * A do-nothing client-side {@link Level} for headless entity fixtures. Below 1.21.2 the {@code Mob} constructor reads
 * {@code level.getProfilerSupplier()}, so a mob can no longer be built against a null level; this supplies the
 * overworld dimension type and the vanilla registries the constructor and its {@code DamageSources} need, an inactive
 * profiler, and inert stubs for the abstract members no fixture calls.
 */
public final class HeadlessLevel extends Level {
    private HeadlessLevel(RegistryAccess registries) {
        super(null, Level.OVERWORLD, registries,
                registries.registryOrThrow(Registries.DIMENSION_TYPE).getHolderOrThrow(BuiltinDimensionTypes.OVERWORLD),
                () -> InactiveProfiler.INSTANCE, true, false, 0L, 0);
    }

    /** A fresh headless overworld backed by the shared {@link TestRegistries}. */
    public static HeadlessLevel get() {
        return new HeadlessLevel(TestRegistries.frozen());
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {}

    @Override
    public void gameEvent(GameEvent event, Vec3 position, GameEvent.Context context) {}

    @Override
    public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {}

    @Override
    public @Nullable ChunkSource getChunkSource() {
        return null;
    }

    @Override
    public @Nullable LevelTickAccess<Block> getBlockTicks() {
        return null;
    }

    @Override
    public @Nullable LevelTickAccess<Fluid> getFluidTicks() {
        return null;
    }

    @Override
    public List<? extends Player> players() {
        return List.of();
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public @Nullable Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
        return null;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return 1.0F;
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return FeatureFlags.DEFAULT_FLAGS;
    }

    @Override
    public void playSeededSound(@Nullable Player player, double x, double y, double z, Holder<SoundEvent> sound,
            SoundSource source, float volume, float pitch, long seed) {}

    @Override
    public void playSeededSound(@Nullable Player player, Entity entity, Holder<SoundEvent> sound, SoundSource source,
            float volume, float pitch, long seed) {}

    @Override
    public String gatherChunkSourceStats() {
        return "";
    }

    @Override
    public @Nullable Entity getEntity(int id) {
        return null;
    }

    @Override
    public @Nullable MapItemSavedData getMapData(String id) {
        return null;
    }

    @Override
    public void setMapData(String id, MapItemSavedData data) {}

    @Override
    public int getFreeMapId() {
        return 0;
    }

    @Override
    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {}

    @Override
    public @Nullable Scoreboard getScoreboard() {
        return null;
    }

    @Override
    public @Nullable RecipeManager getRecipeManager() {
        return null;
    }

    @Override
    protected @Nullable LevelEntityGetter<Entity> getEntities() {
        return null;
    }
}
