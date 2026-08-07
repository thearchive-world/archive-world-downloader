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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.LecternSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the save-time lectern-book injection: {@link ContainerMerge#mergeLecternChunkStash} locates a
 * stashed book's lectern block entity inside the captured chunk tag by {@code BlockPos} (matching the
 * {@code "block_entities"} list's {@code x/y/z}) and sets its {@code "Book"}/{@code "Page"}, touching no other block
 * entity, then drains the merged entry as the chunk is flushed. This is the part that must never mis-target (writing
 * the wrong block's book corrupts the archive), so it is proven headless with hand-built chunk tags (no live client, no
 * {@code Level}).
 */
class LecternStashMergeTest {
    private static RegistryAccess registries;
    private final LecternSink sink = new LecternSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    private CompoundTag bookHolder(String title, int page) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(title), "Author", 0,
                List.of(Filterable.passThrough(Component.literal("only page"))), true));
        return sink.captureBook(book, page, registries);
    }

    private static String titleAt(RegistryAccess registries, CompoundTag lecternTag) {
        Optional<ItemStack> back = TagValueInput.create(ProblemReporter.DISCARDING, registries, lecternTag)
                .read("Book", ItemStack.CODEC);
        assertTrue(back.isPresent(), "the merged lectern carries a decodable Book");
        return back.get().get(DataComponents.WRITTEN_BOOK_CONTENT).title().raw();
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

        int merged = ContainerMerge.mergeLecternChunkStash(sink, chunkTag, ChunkPos.containing(lecternPos), stash)
                .merged();

        assertEquals(1, merged, "only the flushed chunk's lectern merges");
        assertFalse(stash.containsKey(lecternPos),
                "the flushed chunk's stash entry is drained as the tag leaves memory");
        assertTrue(stash.containsKey(elsewhere), "another chunk's stash entry is left until its own flush");

        ListTag blockEntities = chunkTag.getListOrEmpty("block_entities");
        CompoundTag lectern = findByPos(blockEntities, 10, 70, 20);
        assertEquals("Bound Here", titleAt(registries, lectern), "the lectern gains exactly the captured book");
        assertEquals(2, lectern.getIntOr("Page", -1), "the reading page lands too");
        assertFalse(findByPos(blockEntities, 11, 70, 20).contains("Book"), "the neighbor block entity is untouched");
    }

    @Test
    void mergeLecternChunkStashDrainsButDoesNotMergeWhenNoBlockEntityAtThePos() {
        BlockPos pos = new BlockPos(1, 64, 1);
        CompoundTag chunkTag = chunkTagWith(blockEntity("minecraft:lectern", 2, 64, 1)); // a lectern, but elsewhere

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(pos, bookHolder("Lost", 0));

        int merged = ContainerMerge.mergeLecternChunkStash(sink, chunkTag, ChunkPos.containing(pos), stash).merged();

        assertEquals(0, merged, "no captured block entity at the stashed pos -> nothing merges");
        assertFalse(stash.containsKey(pos),
                "the entry is still drained: the chunk is leaving memory, so it cannot wait");
        assertFalse(findByPos(chunkTag.getListOrEmpty("block_entities"), 2, 64, 1).contains("Book"),
                "the unrelated lectern is left alone");
    }
}
