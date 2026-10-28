// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.nio.file.Path;

public interface VersionAdapter {
    ChunkCodec chunkCodec();

    EntitySink entitySink();

    ContainerSink containerSink();

    LecternSink lecternSink();

    PlayerSink playerSink();

    MapSink mapSink();

    LevelDataWriter levelDataWriter();

    /** A save-layout axis rooted at {@code saveRoot}. */
    WorldPaths worldPaths(Path saveRoot);
}
