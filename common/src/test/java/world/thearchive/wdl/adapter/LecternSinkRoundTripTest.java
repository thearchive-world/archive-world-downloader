// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.customNameOf;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.namedBlockEntity;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.LecternSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

class LecternSinkRoundTripTest {
    private static RegistryAccess registries;
    private final LecternSink sink = new LecternSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    private static CompoundTag lecternTag(int x, int y, int z) {
        return namedBlockEntity("minecraft:lectern", x, y, z, "keep-me");
    }

    private static ItemStack writtenBook() {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough("The Title"), "An Author", 0,
                List.of(Filterable.passThrough(Component.literal("Page one")),
                        Filterable.passThrough(Component.literal("Page two"))),
                true));
        return book;
    }

    private static ItemStack writableBook() {
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(
                List.of(Filterable.passThrough("draft page one"), Filterable.passThrough("draft page two"))));
        return book;
    }

    private static ItemStack readBackBook(CompoundTag merged) {
        Optional<ItemStack> back = ItemStack.CODEC
                .parse(registries.createSerializationContext(NbtOps.INSTANCE), merged.get("Book")).result();
        assertTrue(back.isPresent(), "the merged Book must decode via vanilla ItemStack.CODEC");
        return back.get();
    }

    @Test
    void captureBookProducesBookCompoundAndPageInt() {
        CompoundTag holder = sink.captureBook(writtenBook(), 1, registries);

        assertInstanceOf(CompoundTag.class, holder.get("Book"),
                "ItemStack.CODEC serializes a stack to a compound under Book");
        assertEquals(1, holder.getIntOr("Page", -1), "the reading page is stored as a plain int under Page");
    }

    @Test
    void writtenBookRoundTripsThroughMergeAndVanillaCodec() {
        CompoundTag holder = sink.captureBook(writtenBook(), 1, registries);
        CompoundTag merged = sink.merge(lecternTag(10, 64, -7), holder);

        assertEquals("minecraft:lectern", merged.getStringOr("id", ""));
        assertEquals(10, merged.getIntOr("x", 0));
        assertEquals(64, merged.getIntOr("y", 0));
        assertEquals(-7, merged.getIntOr("z", 0));
        assertEquals("keep-me", customNameOf(merged));
        assertEquals(1, merged.getIntOr("Page", -1), "the reading page survives the merge");

        ItemStack back = readBackBook(merged);
        assertEquals(Items.WRITTEN_BOOK, back.getItem());
        WrittenBookContent content = back.get(DataComponents.WRITTEN_BOOK_CONTENT);
        assertEquals("The Title", content.title().raw(), "the book title survives the round-trip");
        assertEquals("An Author", content.author());
        assertEquals(2, content.pages().size(), "both pages survive");
        assertEquals("Page one", content.pages().get(0).raw().getString());
        assertEquals("Page two", content.pages().get(1).raw().getString());
    }

    @Test
    void writableBookRoundTripsThroughMergeAndVanillaCodec() {
        CompoundTag holder = sink.captureBook(writableBook(), 0, registries);
        CompoundTag merged = sink.merge(lecternTag(1, 1, 1), holder);

        assertEquals(0, merged.getIntOr("Page", -1));
        ItemStack back = readBackBook(merged);
        assertEquals(Items.WRITABLE_BOOK, back.getItem(), "an unsigned writable book is a valid lectern content");
        WritableBookContent content = back.get(DataComponents.WRITABLE_BOOK_CONTENT);
        assertEquals(2, content.pages().size());
        assertEquals("draft page one", content.pages().get(0).raw());
        assertEquals("draft page two", content.pages().get(1).raw());
    }

    @Test
    void mergeDoesNotMutateTheCapturedBlockEntityTag() {
        CompoundTag holder = sink.captureBook(writtenBook(), 0, registries);

        CompoundTag blockEntity = lecternTag(0, 0, 0);
        sink.merge(blockEntity, holder);

        assertFalse(blockEntity.contains("Book"), "merge must write a copy, never mutate the input BE tag");
    }
}
