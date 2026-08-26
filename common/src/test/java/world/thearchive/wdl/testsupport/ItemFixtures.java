// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.util.NonNullList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.ResourceLocation;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Items;

/**
 * Item NBT built by the vanilla writers rather than by hand: {@link ItemStackHelper#saveAllItems} for an
 * {@code "Items"} list and {@code ItemStack#writeToNBT} for a single stored stack (a lectern's {@code "Book"}, a
 * jukebox's {@code "RecordItem"}).
 *
 * <p>Hand-built entries are the shape the fixture-fidelity gate exists to reject: vanilla always writes {@code "Slot"}
 * and {@code "Count"}, and an entry missing {@code "Slot"} decodes to slot 0, so a slot-aware rule under test sees
 * every entry collapsed onto one slot.
 */
public final class ItemFixtures {
    private ItemFixtures() {}

    /**
     * The stack for {@code itemId}, count 1.
     *
     * <p>An unregistered id is rejected rather than resolved: {@code Item.REGISTRY.getObject} returns {@code null} for
     * an id the registry does not hold, and a null item would build an empty stack, since the vanilla writer skips
     * empty stacks the fixture would come back as an empty list that this gate then accepts, an empty list being its
     * own round trip. That is the degenerate fixture the gate exists to reject, arriving through the builder.
     */
    public static ItemStack stack(String itemId) {
        ResourceLocation id = new ResourceLocation(itemId);
        Item item = Item.REGISTRY.getObject(id);
        if (item == null) {
            throw new AssertionError(
                    itemId + " is not a registered item, so this fixture would be silently empty");
        }
        return new ItemStack(item);
    }

    /** The stack for {@code itemId} carrying {@code customName}, count 1. */
    public static ItemStack namedStack(String itemId, String customName) {
        ItemStack stack = stack(itemId);
        stack.setStackDisplayName(customName);
        return stack;
    }

    /** The {@code "Items"} list vanilla writes for {@code itemIds} at ascending slots from 0. */
    public static NBTTagList items(String... itemIds) {
        return items(stacks(itemIds));
    }

    /** The {@code "Items"} list vanilla writes for {@code stacks} at ascending slots from 0. */
    public static NBTTagList items(ItemStack... stacks) {
        return itemsAtSlots(ascending(stacks.length), stacks);
    }

    /** The {@code "Items"} list vanilla writes for one {@code itemId} per named slot. */
    public static NBTTagList itemsAtSlots(int[] slots, String... itemIds) {
        return itemsAtSlots(slots, stacks(itemIds));
    }

    /** The {@code "Items"} list vanilla writes for one stack per named slot. */
    public static NBTTagList itemsAtSlots(int[] slots, ItemStack... stacks) {
        return holder(slots, stacks).getTagList("Items", 10);
    }

    /** The whole {@code {"Items": [...]}} holder vanilla writes for {@code itemIds} at ascending slots from 0. */
    public static NBTTagCompound itemsHolder(String... itemIds) {
        return itemsHolder(stacks(itemIds));
    }

    /** The whole {@code {"Items": [...]}} holder vanilla writes for {@code stacks} at ascending slots from 0. */
    public static NBTTagCompound itemsHolder(ItemStack... stacks) {
        return holder(ascending(stacks.length), stacks);
    }

    /** The single {@code "Items"} entry vanilla writes for {@code itemId} at {@code slot}. */
    public static NBTTagCompound entryAtSlot(int slot, String itemId) {
        return itemsAtSlots(new int[] { slot }, itemId).getCompoundTagAt(0);
    }

    /**
     * An {@code "Items"} entry with no {@code "Slot"}, which vanilla never writes but a corrupted or foreign save can
     * hold, and which its decode silently lands on slot 0. Only for a case whose subject is that entry; a fixture
     * standing in for producer output belongs on {@link #entryAtSlot}.
     */
    public static NBTTagCompound malformedEntryWithoutSlot(String itemId) {
        NBTTagCompound entry = entryAtSlot(0, itemId);
        entry.removeTag("Slot");
        return entry;
    }

    /** The tag vanilla writes for a single stored stack, as a lectern's {@code "Book"} carries it. */
    public static NBTTagCompound itemTag(String itemId) {
        return itemTag(stack(itemId));
    }

    /**
     * The tag vanilla writes for {@code stack}, as a lectern's {@code "Book"} or a jukebox's {@code "RecordItem"}
     * carries it: {@code ItemStack#writeToNBT}, the same call every block-entity single-stack field uses (a
     * {@code {id, Count, tag}} compound with {@code Count} as a byte).
     */
    public static NBTTagCompound itemTag(ItemStack stack) {
        return stack.writeToNBT(new NBTTagCompound());
    }

    /**
     * A written book of {@code pageCount} pages, already marked {@code resolved} so the fixture stands in for a book a
     * player has opened. The page count is load-bearing wherever a lectern's {@code "Page"} matters: vanilla clamps the
     * saved page into the book's own page range, so a book with no pages can only ever be on page -1. Each page is a
     * JSON-encoded text component string, the shape {@code ItemWrittenBook.resolveContents} leaves behind.
     */
    public static ItemStack writtenBook(int pageCount) {
        NBTTagList pages = new NBTTagList();
        for (int page = 0; page < pageCount; page++) {
            pages.appendTag(
                    new NBTTagString(
                            ITextComponent.Serializer.componentToJson(new TextComponentString("page " + page))));
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.setTagCompound(new NBTTagCompound());
        NBTTagCompound tag = book.getTagCompound();
        tag.setString("title", "title");
        tag.setString("author", "author");
        tag.setInteger("generation", 0);
        tag.setBoolean("resolved", true);
        tag.setTag("pages", pages);
        return book;
    }

    private static ItemStack[] stacks(String[] itemIds) {
        ItemStack[] stacks = new ItemStack[itemIds.length];
        for (int i = 0; i < itemIds.length; i++) {
            stacks[i] = stack(itemIds[i]);
        }
        return stacks;
    }

    private static int[] ascending(int length) {
        int[] slots = new int[length];
        for (int i = 0; i < length; i++) {
            slots[i] = i;
        }
        return slots;
    }

    private static NBTTagCompound holder(int[] slots, ItemStack[] contents) {
        if (slots.length != contents.length) {
            throw new IllegalArgumentException("slots and contents differ in length");
        }
        int size = 0;
        for (int slot : slots) {
            size = Math.max(size, slot + 1);
        }
        NonNullList<ItemStack> stacks = NonNullList.withSize(size, ItemStack.EMPTY);
        for (int i = 0; i < slots.length; i++) {
            stacks.set(slots[i], contents[i]);
        }
        return ItemStackHelper.saveAllItems(new NBTTagCompound(), stacks);
    }
}
