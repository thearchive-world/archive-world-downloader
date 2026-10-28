// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
import world.thearchive.wdl.core.CoveredChunkIndex;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.SavedChunkIndex;
import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.TestRegistries;

class LiveCaptureSessionDimensionRebindTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /**
     * Entity and container capture are off so the constructor publishes no process-wide capture into the static
     * activation slots, which only the finish path clears; the toggles are asserted rather than assumed, since an
     * unrecognized config key falls back to the default, which is on for both.
     */
    private static LiveCaptureSession session(Path configDirectory) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        WdlConfig config = WdlConfig.parse(properties);
        assertFalse(config.captureEntities(), "the fixture must not publish an entity capture");
        assertFalse(config.captureContainers(), "the fixture must not publish an interaction capture");
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, DimensionType.field_18954, DimensionType.field_18954,
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    @Test
    void theChunkCountSumsEveryDimensionFollowed(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        capture(session, new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(2, 0));

        session.rebindDimension(DimensionType.NETHER, DimensionType.NETHER);
        capture(session, new ChunkPos(30, 30), new ChunkPos(31, 30));

        assertEquals(5, session.counts().chunks(),
                "the overworld's three chunks are on disk as much as the nether's two, so the headline count "
                        + "is the sum and not the dimension the player happens to be standing in");
    }

    @Test
    void oneDimensionsCapturesDoNotDedupAnothersAtTheSamePosition(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        ChunkPos shared = new ChunkPos(0, 0);
        capture(session, shared);

        session.rebindDimension(DimensionType.NETHER, DimensionType.NETHER);

        assertFalse(capturedPositions(session).contains(shared.toLong()),
                "the entered dimension starts from its own set, or its first chunk reads as already captured and "
                        + "the capture skips a chunk that has never been written");
        capture(session, shared);
        assertEquals(2, session.counts().chunks(), "and the same position in two dimensions is two chunks on disk");
    }

    @Test
    void rebindDimensionSetsBothTheLayoutTargetAndTheLiveDimensionId(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);

        session.rebindDimension(DimensionType.NETHER, DimensionType.NETHER);

        assertEquals(DimensionType.NETHER, field(session, "targetDimension"),
                "the layout follows the entered dimension's type, so the save writes DIM-1");
        assertEquals("minecraft:the_nether", field(session, "liveDimensionId"),
                "while the packet-side stores stay keyed by the name entities are announced under");
    }

    private static void capture(LiveCaptureSession session, ChunkPos... positions) throws Exception {
        LongOpenHashSet captured = capturedPositions(session);
        for (ChunkPos pos : positions) {
            captured.add(pos.toLong());
        }
    }

    private static LongOpenHashSet capturedPositions(LiveCaptureSession session) throws Exception {
        return (LongOpenHashSet) field(session, "allCaptured");
    }

    private static Object field(LiveCaptureSession session, String name) throws Exception {
        Field declared = LiveCaptureSession.class.getDeclaredField(name);
        declared.setAccessible(true);
        return declared.get(session);
    }
}
