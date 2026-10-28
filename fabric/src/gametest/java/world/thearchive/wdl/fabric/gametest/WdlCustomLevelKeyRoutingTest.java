// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Every vanilla dimension answers its level key and its type-routed key with the same string ({@code Level.NETHER} IS
 * {@code minecraft:the_nether}), so only a world whose key differs from its type can tell the two derivations apart,
 * and the headless fixtures are handed the save target already derived. A datapack dimension of type
 * {@code minecraft:overworld} ships in this source set's own resources to supply exactly that mismatch.
 *
 * <p>Both derivation sites are driven, because they are separate lines that can diverge: the session's constructor
 * derives the target for the world a download starts in, and the dimension rebind derives it again for a world the
 * player travels into mid-download.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlCustomLevelKeyRoutingTest implements FabricClientGameTest {
    private static final String CUSTOM_KEY_WORLD = "wdltest:second_overworld";

    // Far from the server overworld's spawn, and load-bearing rather than arbitrary: the custom-key world
    // shares the save target with the overworld, so only distance keeps their captures in separate chunks and
    // lets a chunk read back here prove the custom-key world's own terrain landed there.
    private static final BlockPos STAND = new BlockPos(2048, -60, 2048);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            CaptureDriver crossed = CaptureDriver.start(context,
                    new DownloadTarget("wdl-custom-key-warp", "wdl-custom-key-warp", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            crossed.tick(5);
            warpToCustomKeyWorld(context, server);
            crossed.tick(20);
            // An entity as well as terrain, because the two axes derive their dimension differently and only a world
            // whose key differs from its type can tell them apart. Deriving either side from the other looks correct in
            // every vanilla world, where the two strings are equal.
            server.runCommand("execute in " + CUSTOM_KEY_WORLD + " run summon minecraft:armor_stand "
                    + (STAND.getX() + 0.5) + " " + STAND.getY() + " " + (STAND.getZ() + 0.5));
            crossed.tick(25);
            Path crossedSave = crossed.stopAndAwaitSave();
            assertRoutedByType(crossedSave, "the world the player traveled into");
            assertStandRoutedByType(crossedSave);

            CaptureDriver begun = CaptureDriver.start(context,
                    new DownloadTarget("wdl-custom-key-start", "wdl-custom-key-start", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            begun.tick(45);
            assertRoutedByType(begun.stopAndAwaitSave(), "the world the download began in");
        }
    }

    /**
     * Reading the chunk back is also the positive control, without which the folder check passes for a download that
     * captured nothing at all.
     */
    private static void assertRoutedByType(Path saveRoot, String which) {
        Check.that(CaptureReadback.readChunk(saveRoot, ChunkPos.containing(STAND)).isPresent(),
                "no chunk of " + which + " reached " + CaptureReadback.overworldRegionDir(saveRoot)
                        + ", so its terrain was not laid out under the folder its dimension type owns");
        Path keyRouted = saveRoot.resolve("dimensions").resolve("wdltest").resolve("second_overworld");
        Check.that(!Files.exists(keyRouted),
                which + " was laid out by its level key rather than its dimension type: " + keyRouted);
    }

    private static void assertStandRoutedByType(Path saveRoot) {
        Optional<CompoundTag> entityChunk = CaptureReadback.readEntityChunk(saveRoot, ChunkPos.containing(STAND));
        Check.that(entityChunk.isPresent(), "no entity chunk of the custom-key world reached "
                + CaptureReadback.dimensionDir(saveRoot, "minecraft", "overworld").resolve("entities")
                + ", so nothing it held was written under its dimension type");
        List<String> ids = CaptureReadback.entities(entityChunk.get()).stream()
                .map(entity -> entity.getString("id").orElse("?"))
                .toList();
        Check.that(ids.contains("minecraft:armor_stand"),
                "the stand summoned in the custom-key world is absent from its entities region: " + ids);
    }

    private static void warpToCustomKeyWorld(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("execute in " + CUSTOM_KEY_WORLD + " run tp @a " + STAND.getX() + " "
                + STAND.getY() + " " + STAND.getZ());
        for (int tick = 0; tick <= ClientGameTestContext.DEFAULT_TIMEOUT; tick++) {
            if (context.computeOnClient(client -> CUSTOM_KEY_WORLD.equals(levelKeyOf(client.level.dimension())))) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("the client never entered " + CUSTOM_KEY_WORLD + "; it is still in "
                + context.computeOnClient(client -> levelKeyOf(client.level.dimension())));
    }

    private static String levelKeyOf(ResourceKey<Level> dimension) {
        return dimension.identifier().toString();
    }
}
