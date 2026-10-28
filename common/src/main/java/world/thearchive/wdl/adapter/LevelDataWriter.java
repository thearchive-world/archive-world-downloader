// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorage;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.CuratedGameRule;
import world.thearchive.wdl.core.GameRuleResolution;
import world.thearchive.wdl.core.WorldOutputConfig;

public interface LevelDataWriter {
    /**
     * Build the world metadata, returning the vanilla holder together with the game-rule resolution.
     *
     * <p>{@code worldName} is the world's {@code LevelName}. A null or empty value falls back to the writer's default
     * name.
     */
    LevelData buildLevelData(WorldOutputConfig worldOutput, @Nullable String worldName);

    default void warmWorldgen() {}

    void save(LevelStorage storage, LevelData data, @Nullable CapturedPlayer player);

    /**
     * The prior download's captured player tag, read from its on-disk home given the prior {@code levelDatFile}, or
     * null when the folder is fresh or no player was written. A present but unreadable file throws.
     */
    @Nullable
    CompoundTag readPriorPlayer(Path levelDatFile);

    /**
     * This band's curated safe game-rule set: one {@link CuratedGameRule} per curated rule, carrying its curated value
     * and the enabled/disabled toggle-position values.
     */
    List<CuratedGameRule> curatedGameRules();

    static final class LevelData {
        private final net.minecraft.world.level.storage.LevelData worldData;
        private final GameRuleResolution gameRules;

        public LevelData(net.minecraft.world.level.storage.LevelData worldData, GameRuleResolution gameRules) {
            this.worldData = worldData;
            this.gameRules = gameRules;
        }

        public net.minecraft.world.level.storage.LevelData worldData() {
            return worldData;
        }

        public GameRuleResolution gameRules() {
            return gameRules;
        }
    }
}
