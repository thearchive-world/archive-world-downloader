// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;
import org.jspecify.annotations.Nullable;

/**
 * Headless vanilla {@link Level} for plain JUnit tests. At 26.2 {@link Entity} construction dereferences its level for
 * the entity id, so an entity double needs a real level; this one carries no world state and throws from every abstract
 * accessor a save does not touch.
 */
public final class HeadlessLevel extends Level {
    private static @Nullable Level instance;

    private HeadlessLevel(RegistryAccess registries) {
        super(null, Level.OVERWORLD, registries, null, false, false, 0L, 0);
    }

    public static Level get() {
        if (instance == null) {
            instance = new HeadlessLevel(TestRegistries.frozen());
        }
        return instance;
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState old, BlockState current, int updateFlags) {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public void playSeededSound(@Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound,
            SoundSource source, float volume, float pitch, long seed) {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public void playSeededSound(@Nullable Entity except, Entity sourceEntity, Holder<SoundEvent> sound,
            SoundSource source, float volume, float pitch, long seed) {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public void explode(@Nullable Entity source, @Nullable DamageSource damageSource,
            @Nullable ExplosionDamageCalculator damageCalculator, double x, double y, double z, float r, boolean fire,
            ExplosionInteraction interactionType, ParticleOptions smallExplosionParticles,
            ParticleOptions largeExplosionParticles, WeightedList<ExplosionParticleInfo> blockParticles,
            Holder<SoundEvent> explosionSound) {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public String gatherChunkSourceStats() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public void setRespawnData(LevelData.RespawnData respawnData) {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public LevelData.RespawnData getRespawnData() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public @Nullable Entity getEntity(int id) {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public Collection<EnderDragonPart> dragonParts() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public TickRateManager tickRateManager() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public @Nullable MapItemSavedData getMapData(MapId id) {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public void destroyBlockProgress(int id, BlockPos blockPos, int progress) {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position, GameEvent.Context context) {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public void levelEvent(@Nullable Entity source, int type, BlockPos pos, int data) {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public ChunkSource getChunkSource() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public int getSeaLevel() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public WorldBorder getWorldBorder() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity source, AABB testArea) {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public List<? extends Player> players() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public int getHeight() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public int getMinY() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public Scoreboard getScoreboard() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public RecipeAccess recipeAccess() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public ClockManager clockManager() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public EnvironmentAttributeSystem environmentAttributes() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public PotionBrewing potionBrewing() {
        throw new UnsupportedOperationException("headless test level");
    }

    @Override
    public FuelValues fuelValues() {
        throw new UnsupportedOperationException("headless test level");
    }
}
