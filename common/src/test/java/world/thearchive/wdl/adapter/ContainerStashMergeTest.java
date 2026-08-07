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
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.BlockEntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the save-time container injection: {@link ContainerMerge#mergeChunkStash} locates a stashed
 * container's block entity inside the captured chunk tag by {@code BlockPos} (matching the {@code "block_entities"}
 * list's {@code x/y/z}) and sets its {@code "Items"}, touching no other block entity, then drains the merged entry as
 * the chunk is flushed. This is the part that must never mis-target (writing the wrong block's items corrupts the
 * archive), so it is proven headless with hand-built chunk tags (no live client, no {@code Level}).
 */
class ContainerStashMergeTest {
    private static RegistryAccess registries;
    private final ContainerSink sink = new ContainerSinkImpl();

    // A sink that merges ONLY the holder's "Items", so the state-key tests prove the whitelist copy happens
    // beside the sink rather than inside it.
    private static final ContainerSink ITEMS_ONLY_SINK = new ContainerSink() {
        @Override
        public CompoundTag captureItems(NonNullList<ItemStack> items, RegistryAccess registries) {
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
        registries = TestRegistries.frozen();
    }

    private CompoundTag holderWith(int slot, ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(slot, stack);
        return sink.captureItems(items, registries);
    }

    @Test
    void mergeChunkStashFillsTheMatchingBlockEntityAndDrainsOnlyThatChunksEntries() {
        BlockPos chestPos = new BlockPos(10, 70, 20);
        CompoundTag chunkTag = chunkTagWith(
                blockEntity("minecraft:chest", 10, 70, 20),
                blockEntity("minecraft:furnace", 11, 70, 20)); // a neighbor BE that must stay untouched

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(chestPos, holderWith(2, new ItemStack(Items.EMERALD, 7)));
        BlockPos elsewhere = new BlockPos(100, 70, 200); // a container in a different chunk, not being flushed
        stash.put(elsewhere, holderWith(0, new ItemStack(Items.DIAMOND, 1)));

        int merged = ContainerMerge.mergeChunkStash(sink, chunkTag, ChunkPos.containing(chestPos), stash).merged();

        assertEquals(1, merged, "only the flushed chunk's container merges");
        assertFalse(stash.containsKey(chestPos), "the flushed chunk's stash entry is drained as the tag leaves memory");
        assertTrue(stash.containsKey(elsewhere), "another chunk's stash entry is left until its own flush");

        ListTag blockEntities = chunkTag.getListOrEmpty("block_entities");
        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(
                TagValueInput.create(ProblemReporter.DISCARDING, registries, findByPos(blockEntities, 10, 70, 20)),
                back);
        assertEquals(Items.EMERALD, back.get(2).getItem(), "the chest gains exactly the captured stack");
        assertEquals(7, back.get(2).getCount());
        assertTrue(findByPos(blockEntities, 11, 70, 20).getListOrEmpty("Items").isEmpty(),
                "the neighbor block entity is untouched");
    }

    @Test
    void mergeChunkStashDrainsButDoesNotMergeWhenNoBlockEntityAtThePos() {
        BlockPos pos = new BlockPos(1, 64, 1);
        CompoundTag chunkTag = chunkTagWith(blockEntity("minecraft:chest", 2, 64, 1)); // a chest, but elsewhere

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(pos, holderWith(0, new ItemStack(Items.DIAMOND, 1)));

        int merged = ContainerMerge.mergeChunkStash(sink, chunkTag, ChunkPos.containing(pos), stash).merged();

        assertEquals(0, merged, "no captured block entity at the stashed pos -> nothing merges");
        assertFalse(stash.containsKey(pos),
                "the entry is still drained: the chunk is leaving memory, so it cannot wait");
        assertTrue(findByPos(chunkTag.getListOrEmpty("block_entities"), 2, 64, 1).getListOrEmpty("Items").isEmpty(),
                "the unrelated chest is left alone");
    }

    @Test
    void mergeChunkStashCountsThrowingMergeAsFailedButStillDrains() {
        // A band merge that throws must be isolated: the block's items are lost, the entry is still drained (its
        // chunk is leaving memory, so it cannot wait for a retry), and the loss is counted so the caller reports a
        // partial save honestly instead of a clean one.
        ContainerSink throwingSink = new ContainerSink() {
            @Override
            public CompoundTag captureItems(NonNullList<ItemStack> items, RegistryAccess registries) {
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

        MergeTally tally = ContainerMerge.mergeChunkStash(throwingSink, chunkTag, ChunkPos.containing(chestPos), stash);

        assertEquals(0, tally.merged(), "a throwing merge folds in nothing");
        assertEquals(1, tally.failed(), "and the loss is counted for the honest partial-save report");
        assertFalse(stash.containsKey(chestPos), "the entry is still drained: its chunk is leaving memory");
    }

    @Test
    void whitelistedStateKeysReachTheMergedBlockEntity() {
        // Gate 1 (recordedTypeMatches) runs before the merge and drops the entry when the holder's
        // wdl_block_entity_id does not equal the block entity's saved id, so the block entity id here MUST be
        // minecraft:crafter to match the holder's claim, or all four state-key assertions would fail on correct code.
        BlockPos crafterPos = new BlockPos(10, 70, 20);
        CompoundTag chunkTag = chunkTagWith(blockEntity("minecraft:crafter", 10, 70, 20));

        CompoundTag holder = new CompoundTag();
        holder.put("Items", new ListTag());
        holder.putIntArray("disabled_slots", new int[] { 0, 4 });
        holder.putInt("triggered", 1);
        holder.putShort("BrewTime", (short) 123);
        holder.putByte("Fuel", (byte) 7);
        holder.putString("wdl_block_entity_id", "minecraft:crafter");
        holder.putString("junk", "never");
        // The three captured content keys the sink does not own. They are what tells a whitelist keyed on
        // open-time state apart from one that copies whatever it recognizes: a key this mod captures is not
        // thereby state, and only the sink decides what content reaches the block entity.
        holder.put("Book", ItemFixtures.itemTag(ItemFixtures.writtenBook(1)));
        holder.put("RecordItem", ItemFixtures.itemTag("minecraft:music_disc_cat"));
        holder.put("bees", BlockEntityFixtures.bees(20));

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(crafterPos, holder);

        ContainerMerge.mergeChunkStash(ITEMS_ONLY_SINK, chunkTag, ChunkPos.containing(crafterPos), stash);

        CompoundTag mergedBlockEntity = findByPos(chunkTag, 10, 70, 20);
        assertArrayEquals(new int[] { 0, 4 }, mergedBlockEntity.getIntArray("disabled_slots").orElseThrow());
        assertEquals(1, mergedBlockEntity.getIntOr("triggered", 0));
        assertEquals((short) 123, mergedBlockEntity.getShortOr("BrewTime", (short) 0));
        assertEquals((byte) 7, mergedBlockEntity.getByteOr("Fuel", (byte) 0));
        assertFalse(mergedBlockEntity.contains("wdl_block_entity_id"), "the internal holder key never reaches disk");
        assertFalse(mergedBlockEntity.contains("junk"), "a non-whitelisted holder key never reaches disk");
        assertFalse(mergedBlockEntity.contains("Book"), "a lectern book is content, not open-time state");
        assertFalse(mergedBlockEntity.contains("RecordItem"), "and so is a jukebox disc");
        assertFalse(mergedBlockEntity.contains("bees"), "and so are a beehive's occupants");
    }

    @Test
    void holderWithoutStateKeysMergesItemsOnly() {
        BlockPos crafterPos = new BlockPos(10, 70, 20);
        // The captured crafter carries NON-default state, so "the merge left it alone" is distinguishable from
        // "the merge rewrote it with defaults". At the producer defaults the two are the same tag.
        CompoundTag crafter = blockEntity("minecraft:crafter", 10, 70, 20);
        crafter.putIntArray("disabled_slots", new int[] { 2, 5 });
        crafter.putInt("triggered", 1);
        CompoundTag chunkTag = chunkTagWith(crafter);

        CompoundTag holder = new CompoundTag();
        holder.put("Items", new ListTag());

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(crafterPos, holder);

        ContainerMerge.mergeChunkStash(ITEMS_ONLY_SINK, chunkTag, ChunkPos.containing(crafterPos), stash);

        CompoundTag mergedBlockEntity = findByPos(chunkTag, 10, 70, 20);
        assertArrayEquals(new int[] { 2, 5 }, mergedBlockEntity.getIntArray("disabled_slots").orElseThrow());
        assertEquals(1, mergedBlockEntity.getIntOr("triggered", -1));
        assertFalse(mergedBlockEntity.contains("BrewTime"));
        assertFalse(mergedBlockEntity.contains("Fuel"));
    }
}
