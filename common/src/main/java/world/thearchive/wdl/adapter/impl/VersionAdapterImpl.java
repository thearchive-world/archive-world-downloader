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

/**
 * The {@link VersionAdapter} plug for this branch (MC 1.20.6; the package note records the era band it serves): binds
 * the chunk-save axes ({@link ChunkCodec}, {@link WorldPaths}, {@link LevelDataWriter}), {@link EntitySink},
 * {@link ContainerSink}, {@link LecternSink}, {@link PlayerSink}, and {@link MapSink} to their concrete
 * implementations. Registered via {@code META-INF/services/world.thearchive.wdl.adapter.VersionAdapter}.
 */
public final class VersionAdapterImpl implements VersionAdapter {
    /**
     * The floor of the era band this plug serves (see the package note). The build's {@code checkPlugBand} task asserts
     * the targeted {@code minecraft_version} from {@code gradle.properties} is at or above this, the intentional guard
     * that a plug never lands on a branch whose MC version predates its band. A version newer than the band is caught
     * instead by compile failure against the divergent vanilla save types.
     */
    static final String BAND_FLOOR = "1.20.6";

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
