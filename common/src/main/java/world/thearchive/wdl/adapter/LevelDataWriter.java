// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import org.jspecify.annotations.Nullable;

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
     * <p>{@code worldName} is the world's {@code LevelName} (the download screen's typed name on a new download; the
     * existing name on a resume so the world is not renamed). A null or empty value falls back to the writer's default
     * name.
     */
    LevelData buildLevelData(RegistryAccess clientRegistries, @Nullable String worldName);

    /**
     * Write the built {@link LevelData} to {@code access} via the band-correct vanilla
     * {@code LevelStorageAccess.saveDataTag} form. This lives on the SPI (not in the shared capture session) because
     * the vanilla signatures drift across bands (the {@code saveDataTag} {@code RegistryAccess} argument drops at
     * 26.1.2), so keeping the call here lets each band own its form while the shared session stays version-agnostic and
     * cherry-pickable.
     */
    void save(LevelStorageSource.LevelStorageAccess access, LevelData data);

    /** A built {@code WorldData} paired with the registries needed to serialize it. */
    record LevelData(WorldData worldData, RegistryAccess registries) {}
}
