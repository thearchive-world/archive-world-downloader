// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;

import com.google.common.collect.ImmutableList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.NonNullList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Items;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The container-merge synergy re-capture unlocks: a chest placed AFTER its chunk was first captured has no block entity
 * in the snapshot-once tag, so the open-time stash has nothing to merge onto. Re-capturing the chunk re-encodes its
 * block entities, so the placed chest's block entity now exists and {@link ContainerMerge#mergeChunkStash} lands the
 * stashed {@code Items} on it. The contents come only from the stash, never the re-encode, and the merge runs once per
 * chunk at flush, so repeated re-capture before that single flush cannot double-count or blank the contents.
 */
class ContainerRecaptureSynergyTest {
    private static final int CHEST_X = 4;
    private static final int CHEST_Y = 65;
    private static final int CHEST_Z = 6;

    private final ChunkCodec codec = new ChunkCodecImpl();
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private NBTTagCompound stashHolderWith(int slot, ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(slot, stack);
        return sink.captureItems(items);
    }

    private static ItemStack mergedItemAt(ContainerSink sink, NBTTagCompound chunkTag, int slot) {
        NBTTagList blockEntities = chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10);
        NBTTagCompound chest = blockEntities.getCompoundTagAt(0);
        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        ItemStackHelper.loadAllItems(chest, back);
        return back.get(slot);
    }

    @Test
    void withoutRecaptureNothingMergesOntoTheMissingChest() {
        // The snapshot-once gap: the chunk was captured before the chest was placed, so its tag has no chest.
        NBTTagCompound snapshotOnceTag = codec.encode(SyntheticChunks.full(false), false);

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(CHEST_X, CHEST_Y, CHEST_Z), stashHolderWith(2, new ItemStack(Items.EMERALD, 7)));

        int merged = ContainerMerge.mergeChunkStash(sink, snapshotOnceTag,
                new ChunkPos(new BlockPos(CHEST_X, CHEST_Y, CHEST_Z)), stash).merged();

        assertEquals(0, merged, "with no re-captured chest block entity there is nothing to merge onto");
    }

    @Test
    void reCapturingThePlacedChestLetsTheStashMergeOntoIt() {
        // After re-capture the chunk's block entities include the placed chest, so the merge lands.
        ChunkSnapshotSource snapshot = SyntheticChunks.fullWithBlockEntities(false,
                ImmutableList.of(blockEntity("minecraft:chest", CHEST_X, CHEST_Y, CHEST_Z)));
        NBTTagCompound recapturedTag = codec.encode(snapshot, false);

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(CHEST_X, CHEST_Y, CHEST_Z), stashHolderWith(2, new ItemStack(Items.EMERALD, 7)));

        int merged = ContainerMerge.mergeChunkStash(sink, recapturedTag,
                new ChunkPos(new BlockPos(CHEST_X, CHEST_Y, CHEST_Z)), stash).merged();

        assertEquals(1, merged, "the re-captured chest block entity gives the stash something to merge onto");
        ItemStack landed = mergedItemAt(sink, recapturedTag, 2);
        assertEquals(Items.EMERALD, landed.getItem(), "the stashed contents land on the re-captured chest");
        assertEquals(7, landed.getCount());
    }

    @Test
    void repeatedRecaptureBeforeFlushMergesContentsExactlyOnce() {
        // Re-capture re-encodes the chunk many times while it is hot; each fresh tag carries the (empty) chest
        // block entity. The contents come from the stash and merge only at the single flush, so the count and
        // contents are stable no matter how many re-encodes preceded it.
        NBTTagCompound latestRecapture = null;
        for (int reencode = 0; reencode < 3; reencode++) {
            ChunkSnapshotSource snapshot = SyntheticChunks.fullWithBlockEntities(false,
                    ImmutableList.of(blockEntity("minecraft:chest", CHEST_X, CHEST_Y, CHEST_Z)));
            latestRecapture = codec.encode(snapshot, false);
        }

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(CHEST_X, CHEST_Y, CHEST_Z), stashHolderWith(0, new ItemStack(Items.DIAMOND, 3)));

        ChunkPos chunkPos = new ChunkPos(new BlockPos(CHEST_X, CHEST_Y, CHEST_Z));
        int merged = ContainerMerge.mergeChunkStash(sink, latestRecapture, chunkPos, stash).merged();
        assertEquals(1, merged, "the single flush merges the stash exactly once");
        assertTrue(stash.isEmpty(), "the stash entry is drained at flush, so it cannot merge again");

        // A second flush-time merge (the stash now drained) adds nothing: re-capture never re-stocks the stash.
        int mergedAgain = ContainerMerge.mergeChunkStash(sink, latestRecapture, chunkPos, stash).merged();
        assertEquals(0, mergedAgain, "a drained stash contributes no further merges, so the count cannot double");
        assertEquals(Items.DIAMOND, mergedItemAt(sink, latestRecapture, 0).getItem(), "the contents remain intact");
    }
}
