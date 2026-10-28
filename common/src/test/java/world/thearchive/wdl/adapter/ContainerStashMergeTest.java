// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.chunkTagWith;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPos;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.BlockEntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

class ContainerStashMergeTest {
    private final ContainerSink sink = new ContainerSinkImpl();

    private static final ContainerSink ITEMS_ONLY_SINK = new ContainerSink() {
        @Override
        public CompoundTag captureItems(NonNullList<ItemStack> items) {
            throw new AssertionError("not used in merge tests");
        }

        @Override
        public CompoundTag merge(CompoundTag blockEntityTag, CompoundTag capturedItemsHolder) {
            CompoundTag merged = blockEntityTag.copy();
            Tag items = capturedItemsHolder.get("Items");
            if (items != null) {
                merged.put("Items", items.copy());
            }
            return merged;
        }
    };

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private CompoundTag holderWith(int slot, ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(slot, stack);
        return sink.captureItems(items);
    }

    @Test
    void mergeChunkStashFillsTheMatchingBlockEntityAndDrainsOnlyThatChunksEntries() {
        BlockPos chestPos = new BlockPos(10, 70, 20);
        CompoundTag chunkTag = chunkTagWith(
                blockEntity("minecraft:chest", 10, 70, 20),
                blockEntity("minecraft:furnace", 11, 70, 20));

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(chestPos, holderWith(2, new ItemStack(Items.EMERALD, 7)));
        BlockPos elsewhere = new BlockPos(100, 70, 200);
        stash.put(elsewhere, holderWith(0, new ItemStack(Items.DIAMOND, 1)));

        int merged = ContainerMerge.mergeChunkStash(sink, chunkTag, new ChunkPos(chestPos), stash).merged();

        assertEquals(1, merged, "only the flushed chunk's container merges");
        assertFalse(stash.containsKey(chestPos), "the flushed chunk's stash entry is drained as the tag leaves memory");
        assertTrue(stash.containsKey(elsewhere), "another chunk's stash entry is left until its own flush");

        ListTag blockEntities = chunkTag.getCompound("Level").getList("TileEntities", 10);
        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(findByPos(blockEntities, 10, 70, 20), back);
        assertEquals(Items.EMERALD, back.get(2).getItem(), "the chest gains exactly the captured stack");
        assertEquals(7, back.get(2).getCount());
        assertTrue(findByPos(blockEntities, 11, 70, 20).getList("Items", 10).isEmpty(),
                "the neighbor block entity is untouched");
    }

    @Test
    void mergeChunkStashDrainsButDoesNotMergeWhenNoBlockEntityAtThePos() {
        BlockPos pos = new BlockPos(1, 64, 1);
        CompoundTag chunkTag = chunkTagWith(blockEntity("minecraft:chest", 2, 64, 1));

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(pos, holderWith(0, new ItemStack(Items.DIAMOND, 1)));

        int merged = ContainerMerge.mergeChunkStash(sink, chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(0, merged, "no captured block entity at the stashed pos -> nothing merges");
        assertFalse(stash.containsKey(pos),
                "the entry is still drained: the chunk is leaving memory, so it cannot wait");
        assertTrue(
                findByPos(chunkTag.getCompound("Level").getList("TileEntities", 10), 2, 64, 1)
                        .getList("Items", 10).isEmpty(),
                "the unrelated chest is left alone");
    }

    @Test
    void mergeChunkStashCountsThrowingMergeAsFailedButStillDrains() {
        ContainerSink throwingSink = new ContainerSink() {
            @Override
            public CompoundTag captureItems(NonNullList<ItemStack> items) {
                throw new AssertionError("the failure path never serializes items");
            }

            @Override
            public CompoundTag merge(CompoundTag blockEntityTag, CompoundTag capturedItemsHolder) {
                throw new IllegalStateException("a band merge blew up");
            }
        };
        BlockPos chestPos = new BlockPos(10, 70, 20);
        CompoundTag chunkTag = chunkTagWith(blockEntity("minecraft:chest", 10, 70, 20));
        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(chestPos, new CompoundTag()); // an empty holder makes no type claim, so the merge is reached

        MergeTally tally = ContainerMerge.mergeChunkStash(throwingSink, chunkTag, new ChunkPos(chestPos), stash);

        assertEquals(0, tally.merged(), "a throwing merge folds in nothing");
        assertEquals(1, tally.failed(), "and the loss is counted for the honest partial-save report");
        assertFalse(stash.containsKey(chestPos), "the entry is still drained: its chunk is leaving memory");
    }

    @Test
    void whitelistedStateKeysReachTheMergedBlockEntity() {
        // Gate 1 (recordedTypeMatches) runs before the merge and drops the entry when the holder's
        // wdl_block_entity_id does not equal the block entity's saved id, so the block entity id here MUST be
        // minecraft:brewing_stand to match the holder's claim, or all four state-key assertions would fail on correct
        // code.
        BlockPos pos = new BlockPos(10, 70, 20);
        CompoundTag chunkTag = chunkTagWith(blockEntity("minecraft:brewing_stand", 10, 70, 20));

        CompoundTag holder = new CompoundTag();
        holder.put("Items", new ListTag());
        holder.putIntArray("disabled_slots", new int[] { 0, 4 });
        holder.putInt("triggered", 1);
        holder.putShort("BrewTime", (short) 123);
        holder.putByte("Fuel", (byte) 7);
        holder.putString("wdl_block_entity_id", "minecraft:brewing_stand");
        holder.putString("junk", "never");
        holder.put("Book", ItemFixtures.itemTag(ItemFixtures.writtenBook(1)));
        holder.put("RecordItem", ItemFixtures.itemTag("minecraft:music_disc_cat"));
        holder.put("bees", BlockEntityFixtures.bees(20));

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(pos, holder);

        ContainerMerge.mergeChunkStash(ITEMS_ONLY_SINK, chunkTag, new ChunkPos(pos), stash);

        CompoundTag mergedBlockEntity = findByPos(chunkTag, 10, 70, 20);
        assertArrayEquals(new int[] { 0, 4 }, mergedBlockEntity.getIntArray("disabled_slots"));
        assertEquals(1, mergedBlockEntity.getInt("triggered"));
        assertEquals((short) 123, mergedBlockEntity.getShort("BrewTime"));
        assertEquals((byte) 7, mergedBlockEntity.getByte("Fuel"));
        assertFalse(mergedBlockEntity.contains("wdl_block_entity_id"), "the internal holder key never reaches disk");
        assertFalse(mergedBlockEntity.contains("junk"), "a non-whitelisted holder key never reaches disk");
        assertFalse(mergedBlockEntity.contains("Book"), "a Book key is content, not open-time state");
        assertFalse(mergedBlockEntity.contains("RecordItem"), "and so is a jukebox disc");
        assertFalse(mergedBlockEntity.contains("bees"), "and a bees key is not open-time state either");
    }

    @Test
    void holderWithoutStateKeysMergesItemsOnly() {
        BlockPos pos = new BlockPos(10, 70, 20);
        CompoundTag brewingStand = blockEntity("minecraft:brewing_stand", 10, 70, 20);
        brewingStand.putShort("BrewTime", (short) 220);
        brewingStand.putByte("Fuel", (byte) 12);
        CompoundTag chunkTag = chunkTagWith(brewingStand);

        CompoundTag holder = new CompoundTag();
        holder.put("Items", new ListTag());

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(pos, holder);

        ContainerMerge.mergeChunkStash(ITEMS_ONLY_SINK, chunkTag, new ChunkPos(pos), stash);

        CompoundTag mergedBlockEntity = findByPos(chunkTag, 10, 70, 20);
        assertEquals((short) 220, mergedBlockEntity.getShort("BrewTime"));
        assertEquals((byte) 12, (mergedBlockEntity.contains("Fuel") ? mergedBlockEntity.getByte("Fuel") : (byte) -1));
        assertFalse(mergedBlockEntity.contains("disabled_slots"));
        assertFalse(mergedBlockEntity.contains("triggered"));
    }
}
