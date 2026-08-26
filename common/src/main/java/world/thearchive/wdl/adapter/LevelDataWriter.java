// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.ISaveHandler;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.CuratedGameRule;
import world.thearchive.wdl.core.GameRuleResolution;
import world.thearchive.wdl.core.WorldOutputConfig;

/**
 * Per-band {@code level.dat} axis: build the metadata for a captured world.
 *
 * <p>A multiplayer client cannot recover the server's worldgen (the server's seed and generator settings are never
 * synced), so the captured world is a <b>superflat VOID</b> world (all air) for overworld/nether/end: the captured
 * chunks supply the real terrain from their region files, and everything un-captured is air rather than mismatched
 * regenerated terrain. At 1.12.2 the generator is stored as a {@code WorldType} plus its options string in
 * {@code level.dat}, so no worldgen registries are needed to write it.
 */
public interface LevelDataWriter {
    /**
     * Build the world metadata, returning the vanilla holder together with the game-rule resolution. Unlike the modern
     * bands this takes no client registries: pre-1.16 {@code level.dat} records only the generator name and its
     * options, and the void generator's biome comes from the static biome registry.
     *
     * <p>{@code worldOutput} drives the band's game-rule writes (the curated safe set with the user's validated
     * overrides applied on top) and the world-open state (noon, clear weather), both gated on their masters. The
     * returned {@link LevelData#gameRules()} carries the override diagnostics (an unparseable value dropped, an id
     * unknown at this band) for the caller to log and surface.
     *
     * <p>{@code worldName} is the world's {@code LevelName} (the download screen's typed name on a new download; the
     * existing name on a resume so the world is not renamed). A null or empty value falls back to the writer's default
     * name.
     */
    LevelData buildLevelData(WorldOutputConfig worldOutput, @Nullable String worldName);

    /**
     * Reconstruct this band's client-side worldgen ahead of time, off the render thread. The default is a no-op, which
     * is correct at 1.12.2: the captured VOID/DEFAULT/FLAT world stores only a generator name and options and needs no
     * worldgen reconstruction, so no band below the 1.16 registry rework overrides this.
     */
    default void warmWorldgen() {}

    /**
     * Write the built {@link LevelData} to {@code storage} via the band-correct vanilla save form, optionally attaching
     * a captured player. This lives on the SPI (not in the shared capture session) because the vanilla signatures drift
     * across bands: at 1.12.2 the write is {@code ISaveHandler.saveWorldInfoWithPlayer(WorldInfo, playerTag)} (or
     * {@code saveWorldInfo(WorldInfo)} with no player), where the modern bands take a {@code LevelStorageAccess} and a
     * {@code RegistryAccess}.
     *
     * <p>With a non-null {@code player}: flip {@code GameType}, set the world spawn to the capture position, write the
     * captured {@code Difficulty}, and route the captured tag into the {@code "Player"} slot. With {@code null}: no
     * player, default spawn, the void world's survival default.
     */
    void save(ISaveHandler storage, LevelData data, @Nullable CapturedPlayer player);

    /**
     * The prior download's captured player tag, read from this band's own on-disk home given the prior
     * {@code levelDatFile}, or null when the folder is fresh or no player was written. This is the read mirror of
     * {@link #save}'s player write: at 1.12.2 the player is a {@code level.dat "Player"} compound. A resume consumes
     * this to carry the prior ender chest and mount contents forward without the player reopening them. A present but
     * unreadable file throws (the caller degrades it to a skipped carry-forward, fail-soft).
     */
    @Nullable
    NBTTagCompound readPriorPlayer(Path levelDatFile);

    /**
     * This band's curated safe game-rule set as the settings menu binds it: one {@link CuratedGameRule} per curated
     * rule, carrying its curated value and the enabled/disabled toggle-position values, the integer fire rule's enabled
     * value read from the live rule default. Surfaces the set that {@code buildLevelData} writes so the menu shows each
     * rule's real default and revert target without re-hardcoding a per-band set.
     */
    List<CuratedGameRule> curatedGameRules();

    /**
     * A built vanilla {@link net.minecraft.world.storage.WorldInfo} paired with the game-rule resolution (the effective
     * rules were already applied to it; the diagnostics are for the caller to log and surface).
     */
    static final class LevelData {
        private final net.minecraft.world.storage.WorldInfo worldData;
        private final GameRuleResolution gameRules;

        public LevelData(net.minecraft.world.storage.WorldInfo worldData, GameRuleResolution gameRules) {
            this.worldData = worldData;
            this.gameRules = gameRules;
        }

        public net.minecraft.world.storage.WorldInfo worldData() {
            return worldData;
        }

        public GameRuleResolution gameRules() {
            return gameRules;
        }
    }
}
