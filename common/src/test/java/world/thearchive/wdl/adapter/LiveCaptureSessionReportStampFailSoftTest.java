// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;
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
import world.thearchive.wdl.core.browse.DownloadFolders;
import world.thearchive.wdl.core.report.DownloadIdentity;
import world.thearchive.wdl.core.report.DownloadReportStore;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The guard that the download report cannot cost a download. The stamp runs inside the world-open step, whose recorded
 * start error short-circuits every later write, so a throw there costs every captured chunk. Neither test covers the
 * live Minecraft reads whose failure the stamp degrades: no headless session can reach them.
 */
class LiveCaptureSessionReportStampFailSoftTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen(); // initialize the vanilla static constants the value types touch
    }

    @Test
    void aThrowingStampFinalizesTheSaveThoughTheReportIsLost(@TempDir Path saves, @TempDir Path configDirectory)
            throws Exception {
        LiveCaptureSession session = session(configDirectory);
        LevelStorageSource source = LevelStorageSource.createDefault(saves);

        AtomicBoolean stamped = new AtomicBoolean();
        AsyncSaveWriter writer = session.openWorld(source, saveRoot -> {
            stamped.set(true);
            throw new IllegalStateException("the report stamp read a Minecraft fact that was absent");
        });

        assertTrue(stamped.get(), "the world open must actually run the stamp it was handed");
        assertNotNull(writer, "a throwing report stamp must not abort the world open, or the whole download is lost");
        writer.finish().get(30, TimeUnit.SECONDS);
        assertTrue(Files.exists(saves.resolve("headless").resolve("level.dat")),
                "the save is finalized despite the report stamp failing");
    }

    @Test
    void theDegradedIdentityKeepsUniqueIdsPerSession(@TempDir Path configDirectory) {
        LiveCaptureSession session = session(configDirectory);

        DownloadIdentity first = session.unidentifiedIdentity();
        DownloadIdentity second = session.unidentifiedIdentity();

        assertNotEquals(first.id(), second.id(),
                "a shared id would let one folder's completed record suppress a later interrupted session's sentinel, "
                        + "so a real interruption would stop showing as recoverable");
    }

    @Test
    void theDegradedIdentityKeepsRealStartInstant(@TempDir Path configDirectory) {
        LiveCaptureSession session = session(configDirectory);

        assertNotEquals(Instant.EPOCH, session.unidentifiedIdentity().startedAt(),
                "the epoch would sink a recoverable row to the bottom of the newest-first download list");
    }

    @Test
    void theDegradedIdentityDoesNotClaimAnIdentifiedSource(@TempDir Path configDirectory) {
        LiveCaptureSession session = session(configDirectory);

        assertEquals("unidentified", session.unidentifiedIdentity().sourceKind(),
                "an empty source kind positively asserts a server was read, which is the one thing that failed");
    }

    @Test
    void aFailedFactReadStillMarksTheFolderAsDownload(@TempDir Path saveRoot, @TempDir Path configDirectory) {
        LiveCaptureSession session = session(configDirectory);

        session.stampReport(saveRoot, () -> {
            throw new IllegalStateException("the source read blew up");
        }, () -> {
            throw new IllegalStateException("the world facts read blew up");
        });

        assertTrue(DownloadFolders.isWdlManaged(saveRoot),
                "the sentinel is the only thing marking a folder a download, so without it a fully written save is "
                        + "neither listed nor resumable");
    }

    @Test
    void aFailedWorldFactReadKeepsTheSourceAlreadyRead(@TempDir Path saveRoot, @TempDir Path configDirectory)
            throws Exception {
        LiveCaptureSession session = session(configDirectory);
        DownloadIdentity read = new DownloadIdentity("an-id", Instant.now().truncatedTo(ChronoUnit.SECONDS), "", "",
                "example.test", "a named server", "", "loader", "1", "download", "");

        session.stampReport(saveRoot, () -> read, () -> {
            throw new IllegalStateException("the world facts read blew up");
        });

        assertTrue(new String(Files.readAllBytes(DownloadReportStore.pendingFile(saveRoot)),
                StandardCharsets.UTF_8).contains("a named server"),
                "a world-fact failure must keep a source that was read, or the report titles a known server as "
                        + "unidentified with its address and name blank");
    }

    private static LiveCaptureSession session(Path configDirectory) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        WdlConfig config = WdlConfig.parse(properties);
        assertFalse(config.captureEntities(), "the fixture must not publish an entity capture");
        assertFalse(config.captureContainers(), "the fixture must not publish an interaction capture");
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, Level.OVERWORLD, Level.OVERWORLD, TestRegistries.frozen(),
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }
}
