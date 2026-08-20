// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
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
import world.thearchive.wdl.testsupport.LogCapture;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The finish line naming what a download lost, term by term. Its thirteen counts arrive as thirteen consecutive
 * arguments against thirteen labels the compiler never compares them to, so two of them swapped still compiles,
 * renders, and still reads as a plausible report while attributing a loss to the wrong axis. Only the rendered text
 * tells the pairing apart, so every counter is given a distinct value and the whole line is asserted. It is the line a
 * user is asked for when a download reports partial, and the tally it explains is the same one the completion record
 * stamps clean or partial from, so a term reading against the wrong label sends the reader after the wrong axis of the
 * capture.
 *
 * <p>The reporting step is private and driven reflectively. It runs headlessly only with chat messages and toasts both
 * off: the chat arm resolves the save folder through the client singleton, which no headless session can answer, and
 * both arms surface through a bridge that has no player to reach.
 */
class LiveCaptureSessionFinishLossLineTest {
    private static final String SESSION_LOGGER = LiveCaptureSession.class.getName();

    /**
     * One distinct value per counter, so any two of the thirteen exchanged renders a line that differs from this one;
     * equal values would let the swap they are meant to catch pass.
     */
    private static final int CHUNKS = 1;
    private static final int MAPS = 2;
    private static final int MAP_REMAPS = 3;
    private static final int ID_COUNTS = 4;
    private static final int MAP_MANIFEST = 5;
    private static final int BLOCK_CONTAINERS = 6;
    private static final int ENTITY_CONTAINERS = 7;
    private static final int CONTAINER_VEHICLES = 8;
    private static final int INTERACTION_CAPTURES = 9;
    private static final int STRUCTURAL_ENTITIES = 10;
    private static final int RESUMED_MOUNTS = 11;
    private static final int FINISH_STEPS = 12;
    private static final int VILLAGER_TRADES = 13;

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap(); // vanilla statics, which building a session needs before it runs
    }

    /**
     * A session with no bound level, reporting to nobody. Entity and container capture are off so the constructor
     * publishes no process-wide capture into the static activation slots; chat and toasts are off because the headless
     * bridge throws on both. All four are asserted rather than assumed: an unrecognized config key falls back to the
     * default, which is on for all four.
     */
    private static LiveCaptureSession session(Path configDirectory) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        properties.setProperty("showChatMessages", "false");
        properties.setProperty("showToasts", "false");
        WdlConfig config = WdlConfig.parse(properties);
        assertFalse(config.captureEntities(), "the fixture must not publish an entity capture");
        assertFalse(config.captureContainers(), "the fixture must not publish an interaction capture");
        assertFalse(config.showChatMessages(), "the chat arm reaches for a client this session does without");
        assertFalse(config.showToasts(), "the toast arm reaches for a player this session does without");
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, DimensionType.OVERWORLD, DimensionType.OVERWORLD,
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    /** A save that wrote everything it was given, so the whole failure count on the line is the session's own. */
    private static AsyncSaveWriter.SaveResult cleanWrite() {
        return new AsyncSaveWriter.SaveResult(0, 0, 0, 0, 0, 0, 0, null, null);
    }

    @Test
    void namesEachLossCountAgainstTheTermItWasCounted(@TempDir Path temporary) {
        LiveCaptureSession session = session(temporary);
        recordLosses(session);
        try (LogCapture captured = LogCapture.attach(SESSION_LOGGER)) {
            report(session, cleanWrite());

            assertEquals(2, captured.count(),
                    "the finish says what landed and then what was lost, so the loss line is the second");
            assertEquals("counted capture losses for headless: 1 chunk captures, 2 maps, 3 map remaps, 4 idcounts, "
                    + "5 map manifest, 6 block containers, 7 entity containers, 8 container vehicles, "
                    + "13 villager trades, 9 predicted interactions, 10 structural entities, 11 resumed mounts, "
                    + "12 finish steps",
                    captured.rendered(1),
                    "each count reads against the axis it was counted on, so a reader sent to this line by a "
                            + "partial finish looks for the right thing");
        }
    }

    /** Give each of the thirteen tallies its own value; production accrues them across two threads and a session. */
    private static void recordLosses(LiveCaptureSession session) {
        setCount(session, "chunksCaptureFailed", CHUNKS);
        counter(session, "mapsFailed").set(MAPS);
        setCount(session, "mapsRemapFailed", MAP_REMAPS);
        setCount(session, "idCountsFailed", ID_COUNTS);
        // The manifest term renders a read tally plus the stale-write flag; the flag stays down here so the
        // rendered value is this constant and not this constant plus one.
        setCount(session, "mapManifestReadFailed", MAP_MANIFEST);
        setCount(session, "blockContainersFailed", BLOCK_CONTAINERS);
        setCount(session, "entityContainersFailed", ENTITY_CONTAINERS);
        setCount(session, "containerVehiclesLost", CONTAINER_VEHICLES);
        setCount(session, "villagerTradesLost", VILLAGER_TRADES);
        setCount(session, "interactionCapturesLost", INTERACTION_CAPTURES);
        setCount(session, "structuralEntitiesLost", STRUCTURAL_ENTITIES);
        setCount(session, "resumedMountsLost", RESUMED_MOUNTS);
        setCount(session, "finishStepsFailed", FINISH_STEPS);
    }

    private static AtomicInteger counter(LiveCaptureSession session, String name) {
        try {
            return (AtomicInteger) field(name).get(session);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read " + name, e);
        }
    }

    private static void setCount(LiveCaptureSession session, String name, int value) {
        try {
            field(name).setInt(session, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not record a loss in " + name, e);
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = LiveCaptureSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void report(LiveCaptureSession session, AsyncSaveWriter.SaveResult result) {
        try {
            Method method = LiveCaptureSession.class.getDeclaredMethod("report",
                    AsyncSaveWriter.SaveResult.class);
            method.setAccessible(true);
            method.invoke(session, result);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not report the finish", e);
        }
    }
}
