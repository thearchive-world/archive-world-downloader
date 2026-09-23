// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.nio.file.Path;

/**
 * The per-era-band Service Provider Interface: aggregates the eight version-specific axes the loader-agnostic core
 * orchestrates over. Exactly one implementation is registered per branch (via {@code META-INF/services}) and resolved
 * through {@link java.util.ServiceLoader}.
 *
 * <p>The SPI has no world-height axis: height travels inside the captured chunk snapshot.
 */
public interface VersionAdapter {
    ChunkCodec chunkCodec();

    EntitySink entitySink();

    ContainerSink containerSink();

    LecternSink lecternSink();

    PlayerSink playerSink();

    MapSink mapSink();

    LevelDataWriter levelDataWriter();

    /** A save-layout axis rooted at {@code saveRoot} (each captured world gets its own). */
    WorldPaths worldPaths(Path saveRoot);
}
