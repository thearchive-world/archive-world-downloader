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
 * A server world whose level KEY is non-standard is laid out under the folder its vanilla dimension TYPE owns. This is
 * the supported shape the by-type routing exists for: the capture derives its save target from the dimension type, so a
 * world the server calls {@code wdltest:second_overworld} writes to {@code region/} and a singleplayer open finds its
 * terrain, rather than to a {@code dimensions/wdltest/second_overworld/} folder vanilla would only read back with the
 * same datapack installed.
 *
 * <p>No headless fixture can reach it. Every vanilla dimension answers its level key and its type-routed key with the
 * same string ({@code Level.NETHER} IS {@code minecraft:the_nether}), so only a world whose key differs from its type
 * can tell the two derivations apart, and the headless fixtures are handed the save target already derived. A datapack
 * dimension of type {@code minecraft:overworld} ships in this source set's own resources to supply exactly that
 * mismatch.
 *
 * <p>Both derivation sites are driven, because they are separate lines that can diverge: the session's constructor
 * derives the target for the world a download starts in, and the dimension rebind derives it again for a world the
 * player travels into mid-download.
 *
 * <p>The entity axis rides along for the same reason, and it is the only test anywhere that can see it. A held entity
 * is stamped with the server's OWN level key, read from the packet that announced the world, while its terrain is filed
 * by the type-routed key; every vanilla world makes those two strings equal, so deriving either from the other is
 * invisible everywhere except here, where it writes the entity into no folder at all.
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

            // The rebind derivation: start in the server's own overworld and follow the player across.
            CaptureDriver crossed = CaptureDriver.start(context,
                    new DownloadTarget("wdl-custom-key-warp", "wdl-custom-key-warp", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            crossed.tick(5);
            warpToCustomKeyWorld(context, server);
            crossed.tick(20);
            // An entity as well as terrain, because the two axes derive their dimension differently and only a
            // world whose key differs from its type can tell them apart. Terrain is filed by the type-routed
            // key; a held entity is stamped with the server's OWN key, taken from the packet that announced the
            // world, and the drain that writes it compares against that same key. Deriving either side from the
            // other looks correct in every vanilla world, where the two strings are equal, and here writes this
            // stand nowhere at all.
            server.runCommand("execute in " + CUSTOM_KEY_WORLD + " run summon minecraft:armor_stand "
                    + (STAND.getX() + 0.5) + " " + STAND.getY() + " " + (STAND.getZ() + 0.5));
            crossed.tick(25);
            Path crossedSave = crossed.stopAndAwaitSave();
            assertRoutedByType(crossedSave, "the world the player traveled into");
            assertStandRoutedByType(crossedSave);

            // The constructor derivation: a download begun while already inside the custom-key world.
            CaptureDriver begun = CaptureDriver.start(context,
                    new DownloadTarget("wdl-custom-key-start", "wdl-custom-key-start", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            begun.tick(45);
            assertRoutedByType(begun.stopAndAwaitSave(), "the world the download began in");
        }
    }

    /**
     * The chunk the player stood in is under {@code <save>/region}, and no {@code dimensions/} folder exists. The two
     * halves are one assertion: deriving the target from the level key instead of the type sends that chunk to
     * {@code dimensions/wdltest/second_overworld/region}, which empties the first half and creates the folder the
     * second half forbids. Reading the chunk back is also the positive control, without which the folder check passes
     * for a download that captured nothing at all.
     */
    private static void assertRoutedByType(Path saveRoot, String which) {
        Check.that(CaptureReadback.readChunk(saveRoot, new ChunkPos(STAND)).isPresent(),
                "no chunk of " + which + " reached " + saveRoot.resolve("region")
                        + ", so its terrain was not laid out under the folder its dimension type owns");
        Path nested = saveRoot.resolve("dimensions");
        Check.that(!Files.exists(nested),
                which + " was laid out by its level key rather than its dimension type: " + nested);
    }

    /**
     * The stand the custom-key world holds is in {@code <save>/entities}, the folder its dimension TYPE owns. Absent
     * means the entity axis lost the world's identity: its held frame carries the server's own level key while the
     * drain looked for another, so no pass ever matched it and the finish counted it unwritten.
     */
    private static void assertStandRoutedByType(Path saveRoot) {
        Optional<CompoundTag> entityChunk = CaptureReadback.readEntityChunk(saveRoot, new ChunkPos(STAND));
        Check.that(entityChunk.isPresent(), "no entity chunk of the custom-key world reached "
                + saveRoot.resolve("entities") + ", so nothing it held was written under its dimension type");
        List<String> ids = CaptureReadback.entities(entityChunk.get()).stream()
                .map(entity -> entity.getString("id").orElse("?"))
                .toList();
        Check.that(ids.contains("minecraft:armor_stand"),
                "the stand summoned in the custom-key world is absent from its entities region: " + ids);
    }

    /**
     * Teleport into the datapack dimension and wait for the client to be told about it. The wait is on the client's own
     * level KEY: the destination is flat in both worlds, so anything weaker would pass without the player having moved.
     */
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
