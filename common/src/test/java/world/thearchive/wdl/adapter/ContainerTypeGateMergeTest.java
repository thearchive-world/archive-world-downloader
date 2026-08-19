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
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The Gate 1 guard: a stashed container holder carries the captured block-entity type id as a
 * {@code wdl_block_entity_id} string, and {@link ContainerMerge#mergeChunkStash} overlays its {@code "Items"} only onto
 * a block entity of the same type. When the block at a captured position was replaced by another Items-bearing block
 * entity (a barrel broken, a chest placed at the same coordinates), the stale holder is drained without merging, so the
 * downloaded chest never inherits the barrel's items. A holder carrying no {@code wdl_block_entity_id} (the
 * interaction-predicted path, reconciled upstream) overlays unconditionally.
 */
class ContainerTypeGateMergeTest {
    private static RegistryAccess registries;
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    /** A 27-slot {@code "Items"} holder carrying {@code stack} at {@code slot}, tagged with the recorded type. */
    private CompoundTag holder(String recordedTypeId, int slot, ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(slot, stack);
        CompoundTag holder = sink.captureItems(items, registries);
        if (recordedTypeId != null) {
            holder.putString("wdl_block_entity_id", recordedTypeId);
        }
        return holder;
    }

    @Test
    void aStaleBarrelHolderDoesNotOverlayTheReplacementChest() {
        BlockPos pos = new BlockPos(10, 70, 20);
        CompoundTag chunkTag = chunkTagWith(blockEntity("minecraft:chest", 10, 70, 20));

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(pos, holder("minecraft:barrel", 2, new ItemStack(Items.EMERALD, 7)));

        int merged = ContainerMerge.mergeChunkStash(sink, chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(0, merged, "a barrel's items must not merge onto a chest at the same pos");
        assertFalse(stash.containsKey(pos), "the stale entry is still drained as the chunk leaves memory");
        assertTrue(
                findByPos(chunkTag.getCompound("Level").getList("TileEntities", 10), 10, 70, 20)
                        .getList("Items", 10).isEmpty(),
                "the replacement chest keeps its own (empty) contents, not the barrel's");
    }

    @Test
    void aMatchingTypeStillOverlaysAndNeverWritesTheMarker() {
        BlockPos pos = new BlockPos(10, 70, 20);
        CompoundTag chunkTag = chunkTagWith(blockEntity("minecraft:barrel", 10, 70, 20));

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(pos, holder("minecraft:barrel", 2, new ItemStack(Items.EMERALD, 7)));

        int merged = ContainerMerge.mergeChunkStash(sink, chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(1, merged, "a barrel holder merges onto a barrel at the same pos");
        CompoundTag barrel = findByPos(chunkTag.getCompound("Level").getList("TileEntities", 10), 10, 70,
                20);
        assertFalse(barrel.getList("Items", 10).isEmpty(), "the barrel gains its captured contents");
        assertFalse(barrel.contains("wdl_block_entity_id"),
                "the type marker rides only on the holder, never onto disk");
    }

    @Test
    void aHolderWithoutRecordedTypeOverlaysUnchanged() {
        BlockPos pos = new BlockPos(10, 70, 20);
        CompoundTag chunkTag = chunkTagWith(blockEntity("minecraft:chest", 10, 70, 20));

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(pos, holder(null, 2, new ItemStack(Items.EMERALD, 7))); // the interaction path carries no type

        int merged = ContainerMerge.mergeChunkStash(sink, chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(1, merged, "a holder that makes no type claim overlays as before the gate");
        assertFalse(findByPos(chunkTag.getCompound("Level").getList("TileEntities", 10), 10, 70, 20)
                .getList("Items", 10).isEmpty());
    }

    @Test
    void aMismatchedHolderBrokenToAirStillNoOpsAndDrains() {
        BlockPos pos = new BlockPos(10, 70, 20);
        // The block was broken to air: no block entity at pos, only an unrelated chest elsewhere in the chunk.
        CompoundTag chunkTag = chunkTagWith(blockEntity("minecraft:chest", 11, 70, 20));

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(pos, holder("minecraft:barrel", 0, new ItemStack(Items.DIAMOND, 1)));

        int merged = ContainerMerge.mergeChunkStash(sink, chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(0, merged, "no block entity at the pos (broken to air) -> nothing merges");
        assertFalse(stash.containsKey(pos), "still drained as the chunk leaves memory");
        assertTrue(
                findByPos(chunkTag.getCompound("Level").getList("TileEntities", 10), 11, 70, 20)
                        .getList("Items", 10).isEmpty(),
                "the unrelated chest is left untouched");
    }
}
