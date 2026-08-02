// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Lectern container axis: a lectern stashes a book and has no item slots, so the chest-shaped slot-0 sync wait does not
 * apply (its menu is a {@link LecternMenu} keyed on the book, not a {@code ChestMenu} slot). The book reaches the
 * client only through the open reading menu, so the drive opens the lectern and waits on the synced book; the captured
 * lectern block entity must carry the book with its planted page text.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlLecternCaptureTest implements FabricClientGameTest {
    /** Planted on the lectern's book so the capture is asserted on the book's content, not just its presence. */
    private static final String PAGE_TEXT = "wdl lectern page";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            BlockPos stand = context.computeOnClient(client -> client.player.blockPosition());
            BlockPos lectern = new BlockPos(stand.getX(), stand.getY(), stand.getZ() + 2);
            String at = lectern.getX() + " " + lectern.getY() + " " + lectern.getZ();
            server.runCommand("setblock " + at + " minecraft:lectern[facing=north,has_book=true]");
            // The book's page reaches the client only when the reading menu opens (the block entity's Book is not
            // synced before then, so the readiness check below keys on the synced has_book blockstate). A page is
            // planted so the capture is asserted on the book's content, not merely the book item's presence.
            server.runCommand("data merge block " + at + " {Book:{id:\"minecraft:writable_book\",count:1,"
                    + "components:{\"minecraft:writable_book_content\":{pages:[{raw:\"" + PAGE_TEXT + "\"}]}}}}");
            context.waitFor(client -> hasBook(client.level.getBlockState(lectern)));

            ContainerDriver.aimEyesAt(context, ContainerDriver.center(lectern));
            ContainerDriver.awaitCrosshair(context, client -> ContainerDriver.isLookingAt(client, lectern),
                    "lectern " + lectern);

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-lectern", "wdl-lectern", DownloadMode.NEW), WdlConfig.DEFAULTS);
            run.tick(5);
            context.runOnClient(client -> {
                client.gameRenderer.pick(1.0f);
                Check.that(ContainerDriver.isLookingAt(client, lectern),
                        "crosshair drifted off the lectern before opening: " + client.hitResult);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, (BlockHitResult) client.hitResult);
            });
            ContainerDriver.awaitMenuReady(context, run,
                    client -> client.player.containerMenu instanceof LecternMenu menu && !menu.getBook().isEmpty(),
                    "lectern book");
            run.tick(5);
            Path saveRoot = run.stopAndAwaitSave();

            CompoundTag chunk = CaptureReadback.readChunk(saveRoot, new ChunkPos(lectern))
                    .orElseThrow(() -> new AssertionError("captured chunk for lectern " + lectern + " is missing"));
            CompoundTag blockEntity = CaptureReadback.blockEntityAt(chunk, lectern)
                    .orElseThrow(() -> new AssertionError("no lectern block entity at " + lectern + " in the chunk"));
            CompoundTag book = blockEntity.getCompound("Book")
                    .orElseThrow(() -> new AssertionError("the lectern's book is absent from its captured block "
                            + "entity: " + blockEntity));
            Check.that(book.getString("id").orElse("").equals("minecraft:writable_book"),
                    "the lectern's book is not a writable_book in its captured block entity: " + book);
            String page = book.getCompound("components")
                    .flatMap(components -> components.getCompound("minecraft:writable_book_content"))
                    .stream()
                    .flatMap(content -> content.getListOrEmpty("pages").compoundStream())
                    .map(pageTag -> pageTag.getString("raw").orElse(""))
                    .findFirst()
                    .orElse("");
            Check.that(page.equals(PAGE_TEXT),
                    "the lectern book's page text did not survive capture: expected \"" + PAGE_TEXT + "\", book "
                            + book);
        }
    }

    /** Whether the lectern holds a book, read from the synced has_book blockstate (the book NBT is client-blind). */
    private static boolean hasBook(BlockState state) {
        return state.hasProperty(LecternBlock.HAS_BOOK) && state.getValue(LecternBlock.HAS_BOOK);
    }
}
