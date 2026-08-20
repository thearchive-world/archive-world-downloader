// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.customNameOf;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.namedBlockEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.LecternSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for lectern-book capture: the {@link LecternSink} 1.20.4 path (captureBook -> merge) plus
 * vanilla's own {@code ItemStack.of} read-back (the exact form {@code LecternBlockEntity.loadAdditional} uses) is a
 * self-consistent round-trip: the captured book survives serialization, lands on the lectern block-entity tag under
 * {@code "Book"} with the reading {@code "Page"}, and decodes back to the same book, with no other block-entity field
 * clobbered. Runs for both a signed <b>written</b> book and an unsigned <b>writable</b> book (both are valid lectern
 * contents).
 *
 * <p>Server-free by construction: real {@link ItemStack}s and a hand-built block-entity tag drive the round-trip, so
 * neither a live menu nor a {@code Level} is needed. The one client-coupled step (lifting the book from the live open
 * menu's slot 0) is not exercised headless, exactly as for containers.
 */
class LecternSinkRoundTripTest {
    private final LecternSink sink = new LecternSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /** A captured client lectern BE tag carrying a real non-Book field, to assert no clobber. */
    private static CompoundTag lecternTag(int x, int y, int z) {
        return namedBlockEntity("minecraft:lectern", x, y, z, "keep-me");
    }

    /**
     * A signed written book. Below 1.20.5 there is no {@code WrittenBookContent} component; vanilla's own
     * {@code WrittenBookItem} reads {@code title}/{@code author}/{@code generation}/{@code resolved} and a
     * {@code "pages"} list of JSON-component strings straight off the item tag.
     */
    private static ItemStack writtenBook() {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", "The Title");
        tag.putString("author", "An Author");
        tag.putInt("generation", 0);
        tag.putBoolean("resolved", true);
        ListTag pages = new ListTag();
        pages.add(new StringTag(Component.Serializer.toJson(new TextComponent("Page one"))));
        pages.add(new StringTag(Component.Serializer.toJson(new TextComponent("Page two"))));
        tag.put("pages", pages);
        return book;
    }

    /**
     * An unsigned writable (book and quill) book. Below 1.20.5 there is no {@code WritableBookContent} component;
     * vanilla's own {@code WritableBookItem} reads a {@code "pages"} list of plain (non-JSON) strings.
     */
    private static ItemStack writableBook() {
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        ListTag pages = new ListTag();
        pages.add(new StringTag("draft page one"));
        pages.add(new StringTag("draft page two"));
        book.getOrCreateTag().put("pages", pages);
        return book;
    }

    private static ItemStack readBackBook(CompoundTag merged) {
        // vanilla loadAdditional's exact read
        ItemStack back = ItemStack.of(merged.getCompound("Book"));
        assertTrue(!back.isEmpty(), "the merged Book must decode via vanilla ItemStack.of");
        return back;
    }

    @Test
    void captureBookProducesBookCompoundAndPageInt() {
        CompoundTag holder = sink.captureBook(writtenBook(), 1);

        assertInstanceOf(CompoundTag.class, holder.get("Book"),
                "ItemStack#save serializes a stack to a compound under Book");
        assertEquals(1, (holder.contains("Page") ? holder.getInt("Page") : -1),
                "the reading page is stored as a plain int under Page");
    }

    @Test
    void writtenBookRoundTripsThroughMergeAndVanillaCodec() {
        CompoundTag holder = sink.captureBook(writtenBook(), 1);
        CompoundTag merged = sink.merge(lecternTag(10, 64, -7), holder);

        // No field clobber: id / pos / unrelated fields survive.
        assertEquals("minecraft:lectern", merged.getString("id"));
        assertEquals(10, merged.getInt("x"));
        assertEquals(64, merged.getInt("y"));
        assertEquals(-7, merged.getInt("z"));
        assertEquals("keep-me", customNameOf(merged));
        assertEquals(1, (merged.contains("Page") ? merged.getInt("Page") : -1), "the reading page survives the merge");

        ItemStack back = readBackBook(merged);
        assertEquals(Items.WRITTEN_BOOK, back.getItem());
        CompoundTag content = back.getTag();
        assertEquals("The Title", content.getString("title"), "the book title survives the round-trip");
        assertEquals("An Author", content.getString("author"));
        ListTag pages = content.getList("pages", 8);
        assertEquals(2, pages.size(), "both pages survive");
        assertEquals("Page one", Component.Serializer.fromJson(pages.getString(0)).getString());
        assertEquals("Page two", Component.Serializer.fromJson(pages.getString(1)).getString());
    }

    @Test
    void writableBookRoundTripsThroughMergeAndVanillaCodec() {
        CompoundTag holder = sink.captureBook(writableBook(), 0);
        CompoundTag merged = sink.merge(lecternTag(1, 1, 1), holder);

        assertEquals(0, (merged.contains("Page") ? merged.getInt("Page") : -1));
        ItemStack back = readBackBook(merged);
        assertEquals(Items.WRITABLE_BOOK, back.getItem(), "an unsigned writable book is a valid lectern content");
        ListTag pages = back.getTag().getList("pages", 8);
        assertEquals(2, pages.size());
        assertEquals("draft page one", pages.getString(0));
        assertEquals("draft page two", pages.getString(1));
    }

    @Test
    void mergeDoesNotMutateTheCapturedBlockEntityTag() {
        CompoundTag holder = sink.captureBook(writtenBook(), 0);

        CompoundTag blockEntity = lecternTag(0, 0, 0);
        sink.merge(blockEntity, holder);

        assertFalse(blockEntity.contains("Book"), "merge must write a copy, never mutate the input BE tag");
    }
}
