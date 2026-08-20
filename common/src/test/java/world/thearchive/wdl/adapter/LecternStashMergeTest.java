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
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
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
 */
class LecternStashMergeTest {
    private final LecternSink sink = new LecternSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /** A written book with a custom {@code title}, below 1.20.5's pre-component {@code title}/{@code pages} NBT. */
    private static ItemStack writtenBook(String title) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", title);
        tag.putString("author", "Author");
        tag.putInt("generation", 0);
        tag.putBoolean("resolved", true);
        ListTag pages = new ListTag();
        pages.add(new StringTag(Component.Serializer.toJson(new TextComponent("only page"))));
        tag.put("pages", pages);
        return book;
    }

    private CompoundTag bookHolder(String title, int page) {
        return sink.captureBook(writtenBook(title), page);
    }

    private static String titleAt(CompoundTag lecternTag) {
        ItemStack back = ItemStack.of(lecternTag.getCompound("Book"));
        assertTrue(!back.isEmpty(), "the merged lectern carries a decodable Book");
        return back.getTag().getString("title");
    }

    @Test
    void mergeLecternChunkStashFillsTheMatchingLecternAndDrainsOnlyThatChunksEntries() {
        BlockPos lecternPos = new BlockPos(10, 70, 20);
        CompoundTag chunkTag = chunkTagWith(
                blockEntity("minecraft:lectern", 10, 70, 20),
                blockEntity("minecraft:chest", 11, 70, 20)); // a neighbor BE that must stay untouched

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(lecternPos, bookHolder("Bound Here", 2));
        BlockPos elsewhere = new BlockPos(100, 70, 200); // a lectern in a different chunk, not being flushed
        stash.put(elsewhere, bookHolder("Other Chunk", 0));

        int merged = ContainerMerge.mergeLecternChunkStash(sink, chunkTag, new ChunkPos(lecternPos), stash).merged();

        assertEquals(1, merged, "only the flushed chunk's lectern merges");
        assertFalse(stash.containsKey(lecternPos),
                "the flushed chunk's stash entry is drained as the tag leaves memory");
        assertTrue(stash.containsKey(elsewhere), "another chunk's stash entry is left until its own flush");

        ListTag blockEntities = chunkTag.getCompound("Level").getList("TileEntities", 10);
        CompoundTag lectern = findByPos(blockEntities, 10, 70, 20);
        assertEquals("Bound Here", titleAt(lectern), "the lectern gains exactly the captured book");
        assertEquals(2, (lectern.contains("Page") ? lectern.getInt("Page") : -1), "the reading page lands too");
        assertFalse(findByPos(blockEntities, 11, 70, 20).contains("Book"), "the neighbor block entity is untouched");
    }

    @Test
    void mergeLecternChunkStashDrainsButDoesNotMergeWhenNoBlockEntityAtThePos() {
        BlockPos pos = new BlockPos(1, 64, 1);
        CompoundTag chunkTag = chunkTagWith(blockEntity("minecraft:lectern", 2, 64, 1)); // a lectern, but elsewhere

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(pos, bookHolder("Lost", 0));

        int merged = ContainerMerge.mergeLecternChunkStash(sink, chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(0, merged, "no captured block entity at the stashed pos -> nothing merges");
        assertFalse(stash.containsKey(pos),
                "the entry is still drained: the chunk is leaving memory, so it cannot wait");
        assertFalse(
                findByPos(chunkTag.getCompound("Level").getList("TileEntities", 10), 2, 64, 1)
                        .contains("Book"),
                "the unrelated lectern is left alone");
    }
}
