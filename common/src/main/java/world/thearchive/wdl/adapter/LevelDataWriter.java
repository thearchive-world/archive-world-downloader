// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.CuratedGameRule;
import world.thearchive.wdl.core.GameRuleResolution;
import world.thearchive.wdl.core.WorldOutputConfig;

public interface LevelDataWriter {
    /**
     * Build the world metadata, returning it together with the {@link RegistryAccess} the world-gen settings encode
     * against. Where the version's level.dat carries the world-gen settings, fails LOUD if they cannot be fully
     * encoded.
     *
     * <p>{@code worldName} is the world's {@code LevelName}. A null or empty value falls back to the writer's default
     * name.
     */
    LevelData buildLevelData(RegistryAccess clientRegistries, WorldOutputConfig worldOutput,
            @Nullable String worldName);

    /**
     * Reconstruct this band's client-side worldgen registries ahead of time, off the render thread, so
     * {@link #buildLevelData} finds them already built instead of decoding them synchronously on the client tick.
     *
     * <p>The default is a no-op. A band that <b>does</b> reconstruct worldgen must override this or its first such
     * download silently freezes the render thread.
     */
    default void warmWorldgen() {}

    void save(LevelStorageSource.LevelStorageAccess access, LevelData data, @Nullable CapturedPlayer player);

    /**
     * The prior download's captured player tag, read from this band's own on-disk home given the prior
     * {@code levelDatFile}, or null when the folder is fresh or no player was written. A present but unreadable file
     * throws.
     */
    @Nullable
    CompoundTag readPriorPlayer(Path levelDatFile);

    /**
     * This band's curated safe game-rule set: one {@link CuratedGameRule} per curated rule, carrying its curated value
     * and the enabled/disabled toggle-position values.
     */
    List<CuratedGameRule> curatedGameRules();

    record LevelData(WorldData worldData, RegistryAccess registries, GameRuleResolution gameRules) {}
}
