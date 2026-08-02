// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.maps.MapId;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Backup fidelity for the map-id manifest: a resume's pre-merge {@code -pre-resume.zip} must preserve the manifest as
 * the prior session left it, so the open-time manifest step must not touch it before the writer-thread backup runs. Two
 * legs share one NEW capture: a matched remap-on resume, whose backup must hold the original manifest bytes, and an
 * off-mode resume into the remap-on folder, whose backup must still hold the pre-resume manifest (an open-time delete
 * would remove it before the zip; that leg is the decisive gate, since a matched resume's open-time rewrite is
 * byte-identical and invisible to leg A). The finalize manifest step still lands the final state on every path, so the
 * off-mode resume's live folder ends manifest-free.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlResumeBackupManifestFidelityTest implements FabricClientGameTest {
    /** The first map created on a fresh world: {@code getFreeMapId} issues 0 first, so the frame references it. */
    private static final int RECEIVED_MAP_ID = 0;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            Path savesDirectory = context.computeOnClient(client -> client.getLevelSource().getBaseDir());
            SaveFixture.reset(savesDirectory, "wdl-backup-manifest");

            ChunkPos chunk = context.computeOnClient(client -> client.player.chunkPosition());
            int frameY = context.computeOnClient(client -> client.player.blockPosition().getY());
            BlockPos framePos = new BlockPos(chunk.getMinBlockX() + 8, frameY, chunk.getMinBlockZ() + 8);

            server.runOnServer(minecraftServer -> {
                MapItem.create(minecraftServer.overworld(), 0, 0, (byte) 0, true, false);
                MapFixture.paintBands(minecraftServer.overworld(), new MapId(RECEIVED_MAP_ID));
            });
            MapFixture.summonFramedMap(server, framePos, RECEIVED_MAP_ID);
            context.waitFor(client -> ContainerDriver.framedMapCount(client) >= 1
                    && client.level.getMapData(new MapId(RECEIVED_MAP_ID)) != null);

            DownloadTarget folder = new DownloadTarget("wdl-backup-manifest", "wdl-backup-manifest",
                    DownloadMode.NEW);
            Path saveRoot = CaptureDriver.capture(context, folder, WdlConfig.DEFAULTS, 30);
            Check.that(MapManifest.existsIn(saveRoot),
                    "the NEW remap-on capture must leave a manifest at " + MapManifest.pathIn(saveRoot));
            byte[] original = readBytes(MapManifest.pathIn(saveRoot));

            // Leg A: a matched remap-on resume. The backup must hold the pre-resume manifest bytes. A
            // byte-identical open-time rewrite would pass this leg; the off-mode leg below is the decisive gate.
            CaptureDriver.capture(context,
                    new DownloadTarget("wdl-backup-manifest", "wdl-backup-manifest", DownloadMode.RESUME),
                    WdlConfig.DEFAULTS, 30);
            Path backup = savesDirectory.resolve("wdl-backup-manifest-pre-resume.zip");
            Check.that(Files.exists(backup), "zipOnResume must write the pre-merge backup");
            byte[] preserved = backupEntryBytes(backup, "wdl-backup-manifest/wdl/map-ids");
            Check.that(preserved != null, "the backup must preserve the original manifest; its entry is missing");
            Check.that(Arrays.equals(original, preserved),
                    "the backup's manifest must be the original bytes; an open-time mutation reached the folder "
                            + "before the backup");

            // Leg B: an off-mode resume into the remap-on folder. An open-time delete would strip the manifest
            // before the backup zips, so the second backup still holding it is the fidelity gate.
            byte[] preResume = readBytes(MapManifest.pathIn(saveRoot));
            Properties offProperties = new Properties();
            offProperties.setProperty("remapMapIds", "false");
            WdlConfig off = WdlConfig.parse(offProperties);
            CaptureDriver.capture(context,
                    new DownloadTarget("wdl-backup-manifest", "wdl-backup-manifest", DownloadMode.RESUME), off, 30);
            Path secondBackup = savesDirectory.resolve("wdl-backup-manifest-pre-resume_(2).zip");
            Check.that(Files.exists(secondBackup), "zipOnResume must write the second pre-merge backup");
            byte[] preservedOffMode = backupEntryBytes(secondBackup, "wdl-backup-manifest/wdl/map-ids");
            Check.that(preservedOffMode != null,
                    "the backup must preserve the original manifest; its entry is missing");
            Check.that(Arrays.equals(preResume, preservedOffMode),
                    "the backup's manifest must be the pre-resume bytes; the open-time step touched the folder "
                            + "before the backup");
            Check.that(!MapManifest.existsIn(saveRoot),
                    "the finalize step must still delete the stale manifest on an off-mode resume");
        }
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte @Nullable [] backupEntryBytes(Path backup, String entryName) {
        try (ZipFile zip = new ZipFile(backup.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            return entry == null ? null : zip.getInputStream(entry).readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
