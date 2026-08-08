// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.customNameOf;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.namedBlockEntity;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for container capture: the {@link ContainerSink} 1.21.11 path (captureItems -> merge) plus
 * vanilla's own {@code ContainerHelper.loadAllItems} read-back is a self-consistent round-trip: the captured slots
 * survive serialization, land on the block-entity tag under {@code "Items"}, and decode to the same stacks at the same
 * slots, with no other block-entity field clobbered.
 *
 * <p>Server-free by construction: real {@link ItemStack}s and a hand-built block-entity tag drive the round-trip, so
 * neither a live menu nor a {@code Level} is needed (items, unlike entities, parse back without one). The one
 * client-coupled step (reading the live open menu's container slots) is not exercised headless, exactly as for chunks
 * and entities. This test proves the serialize+merge+decode slice.
 */
class ContainerMergeRoundTripTest {
    private static RegistryAccess registries;
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    /** A captured client chest BE tag carrying a real non-Items field, to assert no clobber. */
    private static CompoundTag chestTag(int x, int y, int z) {
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
        items.set(26, new ItemStack(Items.OAK_PLANKS, 64));

        CompoundTag holder = sink.captureItems(items, registries);
        assertTrue(holder.contains("Items"), "captureItems must build the vanilla Items holder");

        CompoundTag merged = sink.merge(chestTag(10, 64, -7), holder);

        // No field clobber: id / pos / unrelated fields survive.
        assertEquals("minecraft:chest", merged.getString("id"));
        assertEquals(10, merged.getInt("x"));
        assertEquals(64, merged.getInt("y"));
        assertEquals(-7, merged.getInt("z"));
        assertEquals("keep-me", customNameOf(merged));

        // The merged "Items" decode via vanilla loadAllItems back to the same stacks at the same slots.
        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(merged, back, registries);

        assertEquals(3, countNonEmpty(back), "exactly the three captured stacks come back");
        assertEquals(Items.DIAMOND, back.get(0).getItem());
        assertEquals(5, back.get(0).getCount());
        assertEquals(Items.STICK, back.get(13).getItem());
        assertEquals(2, back.get(13).getCount());
        assertEquals(Items.OAK_PLANKS, back.get(26).getItem());
        assertEquals(64, back.get(26).getCount());
    }

    @Test
    void mergeDoesNotMutateTheCapturedBlockEntityTag() {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, new ItemStack(Items.DIAMOND, 1));
        CompoundTag holder = sink.captureItems(items, registries);

        CompoundTag blockEntity = chestTag(0, 0, 0);
        sink.merge(blockEntity, holder);

        assertTrue(blockEntity.getList("Items", Tag.TAG_COMPOUND).isEmpty(),
                "merge must write a copy, never mutate the input BE tag");
    }

    @Test
    void openedButEmptyContainerMergesToNoItems() {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);

        CompoundTag merged = sink.merge(chestTag(1, 1, 1), sink.captureItems(items, registries));

        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(merged, back, registries);
        assertEquals(0, countNonEmpty(back), "an opened-but-empty container stays empty and uncorrupted");
    }
}
