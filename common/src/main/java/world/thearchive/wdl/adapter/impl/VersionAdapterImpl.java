// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.nio.file.Path;

import world.thearchive.wdl.adapter.ChunkCodec;
import world.thearchive.wdl.adapter.ContainerSink;
import world.thearchive.wdl.adapter.EntitySink;
import world.thearchive.wdl.adapter.LecternSink;
import world.thearchive.wdl.adapter.LevelDataWriter;
import world.thearchive.wdl.adapter.MapSink;
import world.thearchive.wdl.adapter.PlayerSink;
import world.thearchive.wdl.adapter.VersionAdapter;
import world.thearchive.wdl.adapter.WorldPaths;

public final class VersionAdapterImpl implements VersionAdapter {
    static final String BAND_FLOOR = "1.11.2";

    private static final ChunkCodec chunkCodec = new ChunkCodecImpl();

    private static final EntitySink entitySink = new EntitySinkImpl();

    private static final ContainerSink containerSink = new ContainerSinkImpl();

    private static final LecternSink lecternSink = new LecternSinkImpl();

    private static final PlayerSink playerSink = new PlayerSinkImpl();

    private static final MapSink mapSink = new MapSinkImpl();

    private static final LevelDataWriter levelDataWriter = new LevelDataWriterImpl();

    @Override
    public ChunkCodec chunkCodec() {
        return chunkCodec;
    }

    @Override
    public EntitySink entitySink() {
        return entitySink;
    }

    @Override
    public ContainerSink containerSink() {
        return containerSink;
    }

    @Override
    public LecternSink lecternSink() {
        return lecternSink;
    }

    @Override
    public PlayerSink playerSink() {
        return playerSink;
    }

    @Override
    public MapSink mapSink() {
        return mapSink;
    }

    @Override
    public LevelDataWriter levelDataWriter() {
        return levelDataWriter;
    }

    @Override
    public WorldPaths worldPaths(Path saveRoot) {
        return new WorldPathsImpl(saveRoot);
    }
}
