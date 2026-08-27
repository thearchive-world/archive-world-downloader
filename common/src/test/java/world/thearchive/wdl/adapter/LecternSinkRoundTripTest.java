// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.customNameOf;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.namedBlockEntity;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.LecternSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for lectern-book capture: the {@link LecternSink} path (captureBook -> merge) plus vanilla's own
 * {@code new ItemStack(NBTTagCompound)} read-back (the exact form {@code TileEntityLectern.readFromNBT} uses) is a
 * self-consistent round-trip: the captured book survives serialization, lands on the lectern block-entity tag under
 * {@code "Book"} with the reading {@code "Page"}, and decodes back to the same book, with no other block-entity field
 * clobbered. Runs for both a signed <b>written</b> book and an unsigned <b>writable</b> book (both are valid lectern
 * contents).
 *
 * <p>Server-free by construction: real {@link ItemStack}s and a hand-built block-entity tag drive the round-trip, so
 * neither a live menu nor a {@code World} is needed. The one client-coupled step (lifting the book from the live open
 * menu's slot 0) is not exercised headless, exactly as for containers.
 *
 * <p>At 1.12.2 the merge cases are disabled: lecterns are a 1.14 block entity, so no captured lectern block-entity tag
 * can be built from a real producer here, and lectern-book capture is a documented limit at this band. The pure
 * {@link LecternSink#merge} keeps coverage through the re-pointed {@code ChunkFlushPlanTest} and
 * {@code AsyncSaveWriterTest} fold-wiring tests, which drive it against a stand-in carrier. {@code captureBook} needs
 * only a book item, so it still runs.
 */
class LecternSinkRoundTripTest {
    private static final String LECTERN_ABSENT = "lecterns absent at 1.12.2 (1.14 block entity, documented limit)";

    private final LecternSink sink = new LecternSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /** A captured client lectern BE tag carrying a real non-Book field, to assert no clobber. */
    private static NBTTagCompound lecternTag(int x, int y, int z) {
        return namedBlockEntity("minecraft:lectern", x, y, z, "keep-me");
    }

    /**
     * A signed written book. Vanilla's own {@code ItemWrittenBook} reads {@code title}/{@code author}/
     * {@code generation}/{@code resolved} and a {@code "pages"} list of JSON-component strings straight off the item
     * tag.
     */
    private static ItemStack writtenBook() {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.setTagCompound(new NBTTagCompound());
        NBTTagCompound tag = book.getTagCompound();
        tag.setString("title", "The Title");
        tag.setString("author", "An Author");
        tag.setInteger("generation", 0);
        tag.setBoolean("resolved", true);
        NBTTagList pages = new NBTTagList();
        pages.appendTag(
                new NBTTagString(ITextComponent.Serializer.componentToJson(new TextComponentString("Page one"))));
        pages.appendTag(
                new NBTTagString(ITextComponent.Serializer.componentToJson(new TextComponentString("Page two"))));
        tag.setTag("pages", pages);
        return book;
    }

    /**
     * An unsigned writable (book and quill) book. Vanilla's own {@code ItemWritableBook} reads a {@code "pages"} list
     * of plain (non-JSON) strings.
     */
    private static ItemStack writableBook() {
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        NBTTagList pages = new NBTTagList();
        pages.appendTag(new NBTTagString("draft page one"));
        pages.appendTag(new NBTTagString("draft page two"));
        book.setTagCompound(new NBTTagCompound());
        book.getTagCompound().setTag("pages", pages);
        return book;
    }

    private static ItemStack readBackBook(NBTTagCompound merged) {
        // vanilla readFromNBT's exact read
        ItemStack back = new ItemStack(merged.getCompoundTag("Book"));
        assertTrue(!back.isEmpty(), "the merged Book must decode via vanilla's ItemStack(NBTTagCompound)");
        return back;
    }

    @Test
    void captureBookProducesBookCompoundAndPageInt() {
        NBTTagCompound holder = sink.captureBook(writtenBook(), 1);

        assertInstanceOf(NBTTagCompound.class, holder.getTag("Book"),
                "ItemStack#writeToNBT serializes a stack to a compound under Book");
        assertEquals(1, (holder.hasKey("Page") ? holder.getInteger("Page") : -1),
                "the reading page is stored as a plain int under Page");
    }

    @Test
    @Disabled(LECTERN_ABSENT)
    void writtenBookRoundTripsThroughMergeAndVanillaCodec() {
        NBTTagCompound holder = sink.captureBook(writtenBook(), 1);
        NBTTagCompound merged = sink.merge(lecternTag(10, 64, -7), holder);

        // No field clobber: id / pos / unrelated fields survive.
        assertEquals("minecraft:lectern", merged.getString("id"));
        assertEquals(10, merged.getInteger("x"));
        assertEquals(64, merged.getInteger("y"));
        assertEquals(-7, merged.getInteger("z"));
        assertEquals("keep-me", customNameOf(merged));
        assertEquals(1, (merged.hasKey("Page") ? merged.getInteger("Page") : -1),
                "the reading page survives the merge");

        ItemStack back = readBackBook(merged);
        assertEquals(Items.WRITTEN_BOOK, back.getItem());
        NBTTagCompound content = back.getTagCompound();
        assertEquals("The Title", content.getString("title"), "the book title survives the round-trip");
        assertEquals("An Author", content.getString("author"));
        NBTTagList pages = content.getTagList("pages", 8);
        assertEquals(2, pages.tagCount(), "both pages survive");
        assertEquals("Page one",
                ITextComponent.Serializer.jsonToComponent(pages.getStringTagAt(0)).getUnformattedText());
        assertEquals("Page two",
                ITextComponent.Serializer.jsonToComponent(pages.getStringTagAt(1)).getUnformattedText());
    }

    @Test
    @Disabled(LECTERN_ABSENT)
    void writableBookRoundTripsThroughMergeAndVanillaCodec() {
        NBTTagCompound holder = sink.captureBook(writableBook(), 0);
        NBTTagCompound merged = sink.merge(lecternTag(1, 1, 1), holder);

        assertEquals(0, (merged.hasKey("Page") ? merged.getInteger("Page") : -1));
        ItemStack back = readBackBook(merged);
        assertEquals(Items.WRITABLE_BOOK, back.getItem(), "an unsigned writable book is a valid lectern content");
        NBTTagList pages = back.getTagCompound().getTagList("pages", 8);
        assertEquals(2, pages.tagCount());
        assertEquals("draft page one", pages.getStringTagAt(0));
        assertEquals("draft page two", pages.getStringTagAt(1));
    }

    @Test
    @Disabled(LECTERN_ABSENT)
    void mergeDoesNotMutateTheCapturedBlockEntityTag() {
        NBTTagCompound holder = sink.captureBook(writtenBook(), 0);

        NBTTagCompound blockEntity = lecternTag(0, 0, 0);
        sink.merge(blockEntity, holder);

        assertFalse(blockEntity.hasKey("Book"), "merge must write a copy, never mutate the input BE tag");
    }
}
