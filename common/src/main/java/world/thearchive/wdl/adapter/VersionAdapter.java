// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.nio.file.Path;

/**
 * The per-era-band Service Provider Interface: aggregates the seven active version-specific axes the loader-agnostic
 * core orchestrates over. Exactly one implementation is registered per branch (via {@code META-INF/services}) and
 * resolved through {@link java.util.ServiceLoader}.
 *
 * <p>{@code BlockStateModel} and a per-band world-height axis are intentionally absent from the active SPI: each stays
 * dormant until a deferred deep band needs it (the Flattening band &le;1.12.2, and the pre-1.18 fixed-height bands,
 * respectively). Until then the live capture takes min-section-Y from the captured snapshot, so no height seam is
 * wired.
 */
public interface VersionAdapter {
    ChunkCodec chunkCodec();

    EntitySink entitySink();

    ContainerSink containerSink();

    LecternSink lecternSink();

    PlayerSink playerSink();

    LevelDataWriter levelDataWriter();

    /** A save-layout axis rooted at {@code saveRoot} (each captured world gets its own). */
    WorldPaths worldPaths(Path saveRoot);
}
