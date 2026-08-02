// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.Optional;
import java.util.ServiceLoader;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.lighting.LevelLightEngine;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.ChunkCodec;
import world.thearchive.wdl.adapter.ChunkSnapshotSource;
import world.thearchive.wdl.adapter.VersionAdapter;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Light capture in three scenarios: (1) end to end, a captured chunk writes {@code isLightOn=true} with settled block
 * light, a sky layer, and walked-square flag consistency; (2) the quiescence drain, a client-side block edit followed
 * by a codec snapshot in the same client-thread callback (no frame between, so only capture's own drain can propagate
 * the new light); (3) the gate, a column with light disabled snapshots light-free with {@code lightCorrect=false}.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlLightCaptureTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            // Scenario 1: on-disk shape through the full pipeline.
            ChunkPos playerChunk = context.computeOnClient(client -> client.player.chunkPosition());
            BlockPos lamp = new BlockPos(playerChunk.getMinBlockX() + 8, 80, playerChunk.getMinBlockZ() + 8);
            server.runOnServer(minecraftServer -> minecraftServer.overworld().setBlock(lamp,
                    Blocks.GLOWSTONE.defaultBlockState(), 3));
            context.waitFor(client -> client.level.getBlockState(lamp).is(Blocks.GLOWSTONE));

            Path saveRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-light", "wdl-light", DownloadMode.NEW), WdlConfig.DEFAULTS, 40);

            CompoundTag chunk = CaptureReadback.readChunk(saveRoot, playerChunk)
                    .orElseThrow(() -> new AssertionError("captured chunk " + playerChunk + " missing"));
            Check.that(chunk.getBooleanOr("isLightOn", false),
                    "captured chunk must write isLightOn=true");

            int lampSectionY = lamp.getY() >> 4;
            byte[] blockLight = CaptureReadback.sectionLightLayer(chunk, lampSectionY, "BlockLight")
                    .orElseThrow(() -> new AssertionError("no BlockLight at section " + lampSectionY));
            int storedLevel = new DataLayer(blockLight)
                    .get(lamp.getX() & 15, lamp.getY() & 15, lamp.getZ() & 15);
            Check.that(storedLevel == 15,
                    "glowstone cell must store block light 15, got " + storedLevel);
            Check.that(CaptureReadback.sectionLightLayer(chunk, lampSectionY, "SkyLight").isPresent(),
                    "an above-ground section must store a sky layer");

            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    ChunkPos pos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                    Optional<CompoundTag> nearby = CaptureReadback.readChunk(saveRoot, pos);
                    nearby.ifPresent(tag -> Check.that(
                            !hasLightLayers(tag) || tag.getBooleanOr("isLightOn", false),
                            "chunk " + pos + " stores light layers without isLightOn"));
                }
            }

            // Scenario 2: the drain. A client-side edit marks light work (checkBlock) but nothing
            // drains it inside this callback; only capture()'s own hasLightWork/runLightUpdates can
            // propagate the new source before the read. Runs after the save is finished, so the
            // client-only desync never reaches the artifact.
            BlockPos lamp2 = new BlockPos(playerChunk.getMinBlockX() + 2, 80, playerChunk.getMinBlockZ() + 2);
            int snapshotLevel = context.computeOnClient(client -> {
                ChunkCodec codec = ServiceLoader.load(VersionAdapter.class, Wdl.class.getClassLoader())
                        .findFirst().orElseThrow().chunkCodec();
                client.level.setBlock(lamp2, Blocks.GLOWSTONE.defaultBlockState(), 3);
                LevelChunk liveChunk = client.level.getChunk(playerChunk.x, playerChunk.z);
                ChunkSnapshotSource snapshot = codec.capture(liveChunk, client.level.registryAccess());
                return snapshotBlockLight(snapshot, lamp2);
            });
            Check.that(snapshotLevel == 15,
                    "undrained same-callback edit: snapshot must store the new source's light 15, got "
                            + snapshotLevel);

            // Scenario 3: the gate. With the column's light disabled the snapshot must fall back:
            // no layers, lightCorrect=false. Restore the bit afterward.
            boolean gateFellBack = context.computeOnClient(client -> {
                ChunkCodec codec = ServiceLoader.load(VersionAdapter.class, Wdl.class.getClassLoader())
                        .findFirst().orElseThrow().chunkCodec();
                LevelLightEngine engine = client.level.getChunkSource().getLightEngine();
                engine.setLightEnabled(playerChunk, false);
                try {
                    LevelChunk liveChunk = client.level.getChunk(playerChunk.x, playerChunk.z);
                    ChunkSnapshotSource snapshot = codec.capture(liveChunk, client.level.registryAccess());
                    return !snapshot.lightCorrect() && snapshot.sections().stream()
                            .allMatch(section -> section.blockLight() == null && section.skyLight() == null);
                } finally {
                    engine.setLightEnabled(playerChunk, true);
                }
            });
            Check.that(gateFellBack,
                    "a light-disabled column must snapshot light-free with lightCorrect=false");
        }
    }

    private static boolean hasLightLayers(CompoundTag chunkTag) {
        return chunkTag.getListOrEmpty("sections").compoundStream()
                .anyMatch(section -> section.getByteArray("BlockLight").isPresent()
                        || section.getByteArray("SkyLight").isPresent());
    }

    /** The snapshot's stored block light at {@code pos}, or -1 when its section carries no layer. */
    private static int snapshotBlockLight(ChunkSnapshotSource snapshot, BlockPos pos) {
        int sectionY = pos.getY() >> 4;
        return snapshot.sections().stream()
                .filter(section -> section.y() == sectionY)
                .findFirst()
                .map(SerializableChunkData.SectionData::blockLight)
                .map(layer -> layer.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15))
                .orElse(-1);
    }
}
