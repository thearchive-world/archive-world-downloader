// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.customNameOf;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.namedBlockEntity;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

class ContainerMergeRoundTripTest {
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static NBTTagCompound chestTag(int x, int y, int z) {
        return namedBlockEntity("minecraft:chest", x, y, z, "keep-me");
    }

    private static int countNonEmpty(NonNullList<ItemStack> items) {
        int count = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    @Test
    void capturedItemsRoundTripThroughMergeAndVanillaCodec() {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, new ItemStack(Items.DIAMOND, 5));
        items.set(13, new ItemStack(Items.STICK, 2));
        items.set(26, new ItemStack(Blocks.PLANKS, 64));

        NBTTagCompound holder = sink.captureItems(items);
        assertTrue(holder.hasKey("Items"), "captureItems must build the vanilla Items holder");

        NBTTagCompound merged = sink.merge(chestTag(10, 64, -7), holder);

        assertEquals("minecraft:chest", merged.getString("id"));
        assertEquals(10, merged.getInteger("x"));
        assertEquals(64, merged.getInteger("y"));
        assertEquals(-7, merged.getInteger("z"));
        assertEquals("keep-me", customNameOf(merged));

        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        ItemStackHelper.loadAllItems(merged, back);

        assertEquals(3, countNonEmpty(back), "exactly the three captured stacks come back");
        assertEquals(Items.DIAMOND, back.get(0).getItem());
        assertEquals(5, back.get(0).getCount());
        assertEquals(Items.STICK, back.get(13).getItem());
        assertEquals(2, back.get(13).getCount());
        assertEquals(Item.getItemFromBlock(Blocks.PLANKS), back.get(26).getItem());
        assertEquals(64, back.get(26).getCount());
    }

    @Test
    void mergeDoesNotMutateTheCapturedBlockEntityTag() {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, new ItemStack(Items.DIAMOND, 1));
        NBTTagCompound holder = sink.captureItems(items);

        NBTTagCompound blockEntity = chestTag(0, 0, 0);
        sink.merge(blockEntity, holder);

        assertTrue(blockEntity.getTagList("Items", 10).isEmpty(),
                "merge must write a copy, never mutate the input BE tag");
    }

    @Test
    void openedButEmptyContainerMergesToNoItems() {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);

        NBTTagCompound merged = sink.merge(chestTag(1, 1, 1), sink.captureItems(items));

        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        ItemStackHelper.loadAllItems(merged, back);
        assertEquals(0, countNonEmpty(back), "an opened-but-empty container stays empty and uncorrupted");
    }
}
