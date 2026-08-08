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

/**
 * Per-band {@code level.dat} axis: build the metadata for a captured world.
 *
 * <p>A multiplayer client cannot recover the server's worldgen ({@code LEVEL_STEM}, {@code WORLD_PRESET} and the noise
 * settings it needs are never synced), so the captured world is a <b>superflat VOID</b> world (all air) for
 * overworld/nether/end: the captured chunks supply the real terrain from their region files, and everything un-captured
 * is air rather than mismatched regenerated terrain. A void flat generator needs only {@code BIOME} +
 * {@code DIMENSION_TYPE}, both of which the client does sync, so the dimensions are derived straight from the live
 * {@code ClientLevel} reg.
 */
public interface LevelDataWriter {
    /**
     * Build the world metadata from the client's registries, returning it together with the {@link RegistryAccess} to
     * use for {@code createTag} / {@code LevelStorageAccess.saveDataTag} (the client reg composed with the derived
     * {@code LEVEL_STEM}). Fails LOUD if the world-gen settings cannot be fully encoded (which would otherwise yield a
     * silently-unopenable world).
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
    LevelData buildLevelData(RegistryAccess clientRegistries, WorldOutputConfig worldOutput,
            @Nullable String worldName);

    /**
     * Reconstruct this band's client-side worldgen registries ahead of time, off the render thread, so the first
     * {@code DEFAULT}/{@code FLAT} download's {@link #buildLevelData} finds them already built instead of decoding them
     * synchronously on the client tick. Idempotent and safe to call before any download; the dispatched result is
     * byte-identical to building on demand.
     *
     * <p>The default is a no-op: a band whose captured world needs no worldgen reconstruction (VOID-only, or one that
     * never reaches this cost) inherits it untouched. A band that <b>does</b> reconstruct worldgen must override this
     * or its first such download silently freezes the render thread again.
     */
    default void warmWorldgen() {}

    /**
     * Write the built {@link LevelData} to {@code access} via the band-correct vanilla
     * {@code LevelStorageAccess.saveDataTag} form, optionally attaching a captured player. This lives on the SPI (not
     * in the shared capture session) because the vanilla signatures drift across bands (the {@code saveDataTag}
     * {@code RegistryAccess} argument drops at 26.1.2, and the {@code setSpawn} form differs: {@code RespawnData} on
     * 1.21.10+ vs {@code SpawnX/Y/Z} on 1.21.4), so keeping the call here lets each band own its form while the shared
     * session stays version-agnostic and cherry-pickable.
     *
     * <p>With a non-null {@code player}: flip {@code GameType}, set the world spawn to the capture dimension +
     * position, write the captured {@code Difficulty}, and route the captured tag into the {@code "Player"} slot (the
     * 3-argument {@code saveDataTag}). With {@code null}: today's behavior, no player, default spawn, the void world's
     * survival default.
     */
    void save(LevelStorageSource.LevelStorageAccess access, LevelData data, @Nullable CapturedPlayer player);

    /**
     * The prior download's captured player tag, read from this band's own on-disk home given the prior
     * {@code levelDatFile}, or null when the folder is fresh or no player was written. This is the read mirror of
     * {@link #save}'s player write and lives here for the same reason: the player's location drifts across bands (a
     * {@code level.dat "Player"} compound pre-26.x, a {@code players/data/<uuid>.dat} keyed by level.dat's
     * {@code singleplayer_uuid} at 26.x), so each band reads it from wherever its own {@code save} wrote it. A resume
     * consumes this to carry the prior ender chest and mount contents forward without the player reopening them. A
     * present but unreadable file throws (the caller degrades it to a skipped carry-forward, fail-soft).
     */
    @Nullable
    CompoundTag readPriorPlayer(Path levelDatFile);

    /**
     * This band's curated safe game-rule set as the settings menu binds it: one {@link CuratedGameRule} per curated
     * rule, carrying its curated value and the enabled/disabled toggle-position values, the integer fire rule's enabled
     * value read from the live rule default. Surfaces the set that {@code buildLevelData} writes so the menu shows each
     * rule's real default and revert target without re-hardcoding a per-band set.
     */
    List<CuratedGameRule> curatedGameRules();

    /**
     * A built {@code WorldData} paired with the registries needed to serialize it, plus the game-rule resolution (the
     * effective rules were already applied to the world data; the diagnostics are for the caller to log and surface).
     */
    record LevelData(WorldData worldData, RegistryAccess registries, GameRuleResolution gameRules) {}
}
