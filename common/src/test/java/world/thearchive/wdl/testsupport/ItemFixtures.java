// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Item NBT built by the vanilla writers rather than by hand: {@link ContainerHelper#saveAllItems} for an
 * {@code "Items"} list and {@code ItemStack.CODEC} for a single stored stack (a lectern's {@code "Book"}, a jukebox's
 * {@code "RecordItem"}).
 *
 * <p>Hand-built entries are the shape the fixture-fidelity gate exists to reject: vanilla always writes {@code "Slot"}
 * and {@code "count"}, and an entry missing {@code "Slot"} decodes to slot 0, so a slot-aware rule under test sees
 * every entry collapsed onto one slot.
 */
public final class ItemFixtures {
    private static final Identifier AIR_ID = Identifier.parse("minecraft:air");

    private ItemFixtures() {}

    /**
     * The stack for {@code itemId}, count 1.
     *
     * <p>An unregistered id is rejected rather than resolved. {@code BuiltInRegistries.ITEM} is a defaulted registry,
     * so a typo returns air, an air stack is empty, and the vanilla writer skips empty stacks: the fixture would come
     * back as an empty list that this gate then accepts, since an empty list is its own round trip. That is the
     * degenerate fixture the gate exists to reject, arriving through the builder.
     */
    public static ItemStack stack(String itemId) {
        Identifier id = Identifier.parse(itemId);
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.AIR && !AIR_ID.equals(id)) {
            throw new AssertionError(
                    itemId + " is not a registered item, so this fixture would be silently empty");
        }
        return new ItemStack(item);
    }

    /** The stack for {@code itemId} carrying {@code customName}, count 1. */
    public static ItemStack namedStack(String itemId, String customName) {
        ItemStack stack = stack(itemId);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(customName));
        return stack;
    }

    /** The {@code "Items"} list vanilla writes for {@code itemIds} at ascending slots from 0. */
    public static ListTag items(String... itemIds) {
        return items(stacks(itemIds));
    }

    /** The {@code "Items"} list vanilla writes for {@code stacks} at ascending slots from 0. */
    public static ListTag items(ItemStack... stacks) {
        return itemsAtSlots(ascending(stacks.length), stacks);
    }

    /** The {@code "Items"} list vanilla writes for one {@code itemId} per named slot. */
    public static ListTag itemsAtSlots(int[] slots, String... itemIds) {
        return itemsAtSlots(slots, stacks(itemIds));
    }

    /** The {@code "Items"} list vanilla writes for one stack per named slot. */
    public static ListTag itemsAtSlots(int[] slots, ItemStack... stacks) {
        return holder(slots, stacks).getListOrEmpty("Items");
    }

    /** The whole {@code {"Items": [...]}} holder vanilla writes for {@code itemIds} at ascending slots from 0. */
    public static CompoundTag itemsHolder(String... itemIds) {
        return itemsHolder(stacks(itemIds));
    }

    /** The whole {@code {"Items": [...]}} holder vanilla writes for {@code stacks} at ascending slots from 0. */
    public static CompoundTag itemsHolder(ItemStack... stacks) {
        return holder(ascending(stacks.length), stacks);
    }

    /** The single {@code "Items"} entry vanilla writes for {@code itemId} at {@code slot}. */
    public static CompoundTag entryAtSlot(int slot, String itemId) {
        return itemsAtSlots(new int[] { slot }, itemId).getCompoundOrEmpty(0);
    }

    /**
     * An {@code "Items"} entry with no {@code "Slot"}, which vanilla never writes but a corrupted or foreign save can
     * hold, and which its decode silently lands on slot 0. Only for a case whose subject is that entry; a fixture
     * standing in for producer output belongs on {@link #entryAtSlot}.
     */
    public static CompoundTag malformedEntryWithoutSlot(String itemId) {
        CompoundTag entry = entryAtSlot(0, itemId);
        entry.remove("Slot");
        return entry;
    }

    /** The tag vanilla writes for a single stored stack, as a lectern's {@code "Book"} carries it. */
    public static CompoundTag itemTag(String itemId) {
        return itemTag(stack(itemId));
    }

    /** The tag vanilla writes for {@code stack}, as a lectern's {@code "Book"} carries it. */
    public static CompoundTag itemTag(ItemStack stack) {
        RegistryAccess registries = TestRegistries.frozen();
        Tag tag = ItemStack.CODEC
                .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
                .getOrThrow();
        return (CompoundTag) tag;
    }

    /**
     * A written book of {@code pageCount} pages. The page count is load-bearing wherever a lectern's {@code "Page"}
     * matters: vanilla clamps the saved page into the book's own page range, so a book with no pages can only ever be
     * on page -1.
     */
    public static ItemStack writtenBook(int pageCount) {
        List<Filterable<Component>> pages = new ArrayList<>();
        for (int page = 0; page < pageCount; page++) {
            pages.add(Filterable.passThrough(Component.literal("page " + page)));
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough("title"), "author", 0, pages, true));
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

    private static CompoundTag holder(int[] slots, ItemStack[] contents) {
        if (slots.length != contents.length) {
            throw new IllegalArgumentException("slots and contents differ in length");
        }
        RegistryAccess registries = TestRegistries.frozen();
        int size = 0;
        for (int slot : slots) {
            size = Math.max(size, slot + 1);
        }
        NonNullList<ItemStack> stacks = NonNullList.withSize(size, ItemStack.EMPTY);
        for (int i = 0; i < slots.length; i++) {
            stacks.set(slots[i], contents[i]);
        }
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        ContainerHelper.saveAllItems(output, stacks);
        return output.buildResult();
    }
}
