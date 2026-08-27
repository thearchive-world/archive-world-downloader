// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.chunkTagWith;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPos;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.LecternSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the save-time lectern-book injection: {@link ContainerMerge#mergeLecternChunkStash} locates a
 * stashed book's lectern block entity inside the captured chunk tag by {@code BlockPos} (matching the
 * {@code Level.TileEntities} list's {@code x/y/z}) and sets its {@code "Book"}/{@code "Page"}, touching no other block
 * entity, then drains the merged entry as the chunk is flushed. This is the part that must never mis-target (writing
 * the wrong block's book corrupts the archive), so it is proven headless with hand-built chunk tags (no live client, no
 * {@code Level}).
 *
 * <p>Disabled at 1.13.2: lecterns are a 1.14 block entity, so no captured lectern block-entity tag can be built here,
 * and lectern-book capture is a documented limit at this band. {@link ContainerMerge#mergeLecternChunkStash} keeps
 * coverage through the re-pointed {@code ChunkFlushPlanTest} and {@code OrphanedContainerSweepTest}, which drive it
 * against a stand-in carrier.
 */
@Disabled("lecterns are a 1.14 block entity, absent at 1.13.2; the save-time lectern-book injection is a documented "
        + "limit with no valid fixture here")
class LecternStashMergeTest {
    private final LecternSink sink = new LecternSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /** A written book with a custom {@code title}, below 1.20.5's pre-component {@code title}/{@code pages} NBT. */
    private static ItemStack writtenBook(String title) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.setTagCompound(new NBTTagCompound());
        NBTTagCompound tag = book.getTagCompound();
        tag.setString("title", title);
        tag.setString("author", "Author");
        tag.setInteger("generation", 0);
        tag.setBoolean("resolved", true);
        NBTTagList pages = new NBTTagList();
        pages.appendTag(
                new NBTTagString(ITextComponent.Serializer.componentToJson(new TextComponentString("only page"))));
        tag.setTag("pages", pages);
        return book;
    }

    private NBTTagCompound bookHolder(String title, int page) {
        return sink.captureBook(writtenBook(title), page);
    }

    private static String titleAt(NBTTagCompound lecternTag) {
        ItemStack back = new ItemStack(lecternTag.getCompoundTag("Book"));
        assertTrue(!back.isEmpty(), "the merged lectern carries a decodable Book");
        return back.getTagCompound().getString("title");
    }

    @Test
    void mergeLecternChunkStashFillsTheMatchingLecternAndDrainsOnlyThatChunksEntries() {
        BlockPos lecternPos = new BlockPos(10, 70, 20);
        NBTTagCompound chunkTag = chunkTagWith(
                blockEntity("minecraft:lectern", 10, 70, 20),
                blockEntity("minecraft:chest", 11, 70, 20)); // a neighbor BE that must stay untouched

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(lecternPos, bookHolder("Bound Here", 2));
        BlockPos elsewhere = new BlockPos(100, 70, 200); // a lectern in a different chunk, not being flushed
        stash.put(elsewhere, bookHolder("Other Chunk", 0));

        int merged = ContainerMerge.mergeLecternChunkStash(sink, chunkTag, new ChunkPos(lecternPos), stash).merged();

        assertEquals(1, merged, "only the flushed chunk's lectern merges");
        assertFalse(stash.containsKey(lecternPos),
                "the flushed chunk's stash entry is drained as the tag leaves memory");
        assertTrue(stash.containsKey(elsewhere), "another chunk's stash entry is left until its own flush");

        NBTTagList blockEntities = chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10);
        NBTTagCompound lectern = findByPos(blockEntities, 10, 70, 20);
        assertEquals("Bound Here", titleAt(lectern), "the lectern gains exactly the captured book");
        assertEquals(2, (lectern.hasKey("Page") ? lectern.getInteger("Page") : -1), "the reading page lands too");
        assertFalse(findByPos(blockEntities, 11, 70, 20).hasKey("Book"), "the neighbor block entity is untouched");
    }

    @Test
    void mergeLecternChunkStashDrainsButDoesNotMergeWhenNoBlockEntityAtThePos() {
        BlockPos pos = new BlockPos(1, 64, 1);
        NBTTagCompound chunkTag = chunkTagWith(blockEntity("minecraft:lectern", 2, 64, 1)); // a lectern, but elsewhere

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(pos, bookHolder("Lost", 0));

        int merged = ContainerMerge.mergeLecternChunkStash(sink, chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(0, merged, "no captured block entity at the stashed pos -> nothing merges");
        assertFalse(stash.containsKey(pos),
                "the entry is still drained: the chunk is leaving memory, so it cannot wait");
        assertFalse(
                findByPos(chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10), 2, 64, 1)
                        .hasKey("Book"),
                "the unrelated lectern is left alone");
    }
}
