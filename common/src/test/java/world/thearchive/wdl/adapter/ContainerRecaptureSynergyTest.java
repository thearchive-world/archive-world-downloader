// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueInput;
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

    private static RegistryAccess registries;
    private final ChunkCodec codec = new ChunkCodecImpl();
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    private CompoundTag stashHolderWith(int slot, ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(slot, stack);
        return sink.captureItems(items, registries);
    }

    private static ItemStack mergedItemAt(ContainerSink sink, CompoundTag chunkTag, int slot) {
        ListTag blockEntities = chunkTag.getListOrEmpty("block_entities");
        CompoundTag chest = blockEntities.getCompoundOrEmpty(0);
        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(TagValueInput.create(ProblemReporter.DISCARDING, registries, chest), back);
        return back.get(slot);
    }

    @Test
    void withoutRecaptureNothingMergesOntoTheMissingChest() {
        // The snapshot-once gap: the chunk was captured before the chest was placed, so its tag has no chest.
        CompoundTag snapshotOnceTag = codec.encode(SyntheticChunks.full(registries, false), registries, false);

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(CHEST_X, CHEST_Y, CHEST_Z), stashHolderWith(2, new ItemStack(Items.EMERALD, 7)));

        int merged = ContainerMerge.mergeChunkStash(sink, snapshotOnceTag,
                ChunkPos.containing(new BlockPos(CHEST_X, CHEST_Y, CHEST_Z)), stash).merged();

        assertEquals(0, merged, "with no re-captured chest block entity there is nothing to merge onto");
    }

    @Test
    void reCapturingThePlacedChestLetsTheStashMergeOntoIt() {
        // After re-capture the chunk's block entities include the placed chest, so the merge lands.
        ChunkSnapshotSource snapshot = SyntheticChunks.fullWithBlockEntities(registries, false,
                List.of(blockEntity("minecraft:chest", CHEST_X, CHEST_Y, CHEST_Z)));
        CompoundTag recapturedTag = codec.encode(snapshot, registries, false);

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(CHEST_X, CHEST_Y, CHEST_Z), stashHolderWith(2, new ItemStack(Items.EMERALD, 7)));

        int merged = ContainerMerge.mergeChunkStash(sink, recapturedTag,
                ChunkPos.containing(new BlockPos(CHEST_X, CHEST_Y, CHEST_Z)), stash).merged();

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
        CompoundTag latestRecapture = null;
        for (int reencode = 0; reencode < 3; reencode++) {
            ChunkSnapshotSource snapshot = SyntheticChunks.fullWithBlockEntities(registries, false,
                    List.of(blockEntity("minecraft:chest", CHEST_X, CHEST_Y, CHEST_Z)));
            latestRecapture = codec.encode(snapshot, registries, false);
        }

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(new BlockPos(CHEST_X, CHEST_Y, CHEST_Z), stashHolderWith(0, new ItemStack(Items.DIAMOND, 3)));

        ChunkPos chunkPos = ChunkPos.containing(new BlockPos(CHEST_X, CHEST_Y, CHEST_Z));
        int merged = ContainerMerge.mergeChunkStash(sink, latestRecapture, chunkPos, stash).merged();
        assertEquals(1, merged, "the single flush merges the stash exactly once");
        assertTrue(stash.isEmpty(), "the stash entry is drained at flush, so it cannot merge again");

        // A second flush-time merge (the stash now drained) adds nothing: re-capture never re-stocks the stash.
        int mergedAgain = ContainerMerge.mergeChunkStash(sink, latestRecapture, chunkPos, stash).merged();
        assertEquals(0, mergedAgain, "a drained stash contributes no further merges, so the count cannot double");
        assertEquals(Items.DIAMOND, mergedItemAt(sink, latestRecapture, 0).getItem(), "the contents remain intact");
    }
}
