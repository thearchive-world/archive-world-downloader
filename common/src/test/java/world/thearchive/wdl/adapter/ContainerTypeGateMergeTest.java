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
import java.util.Map;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The Gate 1 guard: a stashed container holder carries the captured block-entity type id as a
 * {@code wdl_block_entity_id} string, and {@link ContainerMerge#mergeChunkStash} overlays its {@code "Items"} only onto
 * a block entity of the same type. When the block at a captured position was replaced by another Items-bearing block
 * entity (a shulker box broken, a chest placed at the same coordinates), the stale holder is drained without merging,
 * so the downloaded chest never inherits the shulker box's items. A holder carrying no {@code wdl_block_entity_id} (the
 * interaction-predicted path, reconciled upstream) overlays unconditionally.
 */
class ContainerTypeGateMergeTest {
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /** A 27-slot {@code "Items"} holder carrying {@code stack} at {@code slot}, tagged with the recorded type. */
    private NBTTagCompound holder(String recordedTypeId, int slot, ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(slot, stack);
        NBTTagCompound holder = sink.captureItems(items);
        if (recordedTypeId != null) {
            holder.setString("wdl_block_entity_id", recordedTypeId);
        }
        return holder;
    }

    @Test
    void aStaleBarrelHolderDoesNotOverlayTheReplacementChest() {
        BlockPos pos = new BlockPos(10, 70, 20);
        NBTTagCompound chunkTag = chunkTagWith(blockEntity("minecraft:chest", 10, 70, 20));

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(pos, holder("minecraft:shulker_box", 2, new ItemStack(Items.EMERALD, 7)));

        int merged = ContainerMerge.mergeChunkStash(sink, chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(0, merged, "a shulker box's items must not merge onto a chest at the same pos");
        assertFalse(stash.containsKey(pos), "the stale entry is still drained as the chunk leaves memory");
        assertTrue(
                findByPos(chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10), 10, 70, 20)
                        .getTagList("Items", 10).hasNoTags(),
                "the replacement chest keeps its own (empty) contents, not the shulker box's");
    }

    @Test
    void aMatchingTypeStillOverlaysAndNeverWritesTheMarker() {
        BlockPos pos = new BlockPos(10, 70, 20);
        NBTTagCompound chunkTag = chunkTagWith(blockEntity("minecraft:shulker_box", 10, 70, 20));

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(pos, holder("minecraft:shulker_box", 2, new ItemStack(Items.EMERALD, 7)));

        int merged = ContainerMerge.mergeChunkStash(sink, chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(1, merged, "a shulker box holder merges onto a shulker box at the same pos");
        NBTTagCompound shulkerBox = findByPos(chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10), 10, 70,
                20);
        assertFalse(shulkerBox.getTagList("Items", 10).hasNoTags(), "the shulker box gains its captured contents");
        assertFalse(shulkerBox.hasKey("wdl_block_entity_id"),
                "the type marker rides only on the holder, never onto disk");
    }

    @Test
    void aHolderWithoutRecordedTypeOverlaysUnchanged() {
        BlockPos pos = new BlockPos(10, 70, 20);
        NBTTagCompound chunkTag = chunkTagWith(blockEntity("minecraft:chest", 10, 70, 20));

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(pos, holder(null, 2, new ItemStack(Items.EMERALD, 7))); // the interaction path carries no type

        int merged = ContainerMerge.mergeChunkStash(sink, chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(1, merged, "a holder that makes no type claim overlays as before the gate");
        assertFalse(findByPos(chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10), 10, 70, 20)
                .getTagList("Items", 10).hasNoTags());
    }

    @Test
    void aMismatchedHolderBrokenToAirStillNoOpsAndDrains() {
        BlockPos pos = new BlockPos(10, 70, 20);
        // The block was broken to air: no block entity at pos, only an unrelated chest elsewhere in the chunk.
        NBTTagCompound chunkTag = chunkTagWith(blockEntity("minecraft:chest", 11, 70, 20));

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(pos, holder("minecraft:shulker_box", 0, new ItemStack(Items.DIAMOND, 1)));

        int merged = ContainerMerge.mergeChunkStash(sink, chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(0, merged, "no block entity at the pos (broken to air) -> nothing merges");
        assertFalse(stash.containsKey(pos), "still drained as the chunk leaves memory");
        assertTrue(
                findByPos(chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10), 11, 70, 20)
                        .getTagList("Items", 10).hasNoTags(),
                "the unrelated chest is left untouched");
    }
}
