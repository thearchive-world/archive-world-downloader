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
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

class InteractionMergeRoundTripTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /** A block entity fed through the fidelity gate as-is, so a marker stamped afterward never trips it. */
    private static NBTTagCompound taggedBlockEntity(String id, int x, int y, int z) {
        return blockEntity(id, x, y, z);
    }

    private static void markBlockEntity(NBTTagCompound chunkTag, int x, int y, int z, String marker) {
        findByPos(chunkTag, x, y, z).setString("wdl_test_marker", marker);
    }

    private static String markerOf(NBTTagCompound blockEntityTag) {
        return blockEntityTag.getString("wdl_test_marker");
    }

    private static ItemStack readRecordItem(NBTTagCompound jukeboxBlockEntityTag) {
        ItemStack back = new ItemStack(jukeboxBlockEntityTag.getCompoundTag("RecordItem"));
        assertTrue(!back.isEmpty(), "the merged RecordItem must decode via vanilla's ItemStack(NBTTagCompound)");
        return back;
    }

    @Test
    void jukeboxDiscRoundTripsThroughCaptureMergeAndVanillaCodec() {
        BlockPos jukeboxPos = new BlockPos(10, 70, 20);
        NBTTagCompound chunkTag = chunkTagWith(
                taggedBlockEntity("minecraft:jukebox", 10, 70, 20),
                taggedBlockEntity("minecraft:furnace", 11, 70, 20));
        markBlockEntity(chunkTag, 10, 70, 20, "keep-me");

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(jukeboxPos, InteractionCapture.captureRecordItem(new ItemStack(Items.RECORD_CAT)));
        BlockPos elsewhere = new BlockPos(100, 70, 200);
        stash.put(elsewhere, InteractionCapture.captureRecordItem(new ItemStack(Items.RECORD_13)));

        int merged = ContainerMerge.mergeHolderChunkStash(chunkTag, new ChunkPos(jukeboxPos), stash).merged();

        assertEquals(1, merged, "only the flushed chunk's jukebox merges");
        assertFalse(stash.containsKey(jukeboxPos), "the flushed chunk's stash entry is drained");
        assertTrue(stash.containsKey(elsewhere), "another chunk's stash entry is left until its own flush");

        NBTTagList blockEntities = chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10);
        NBTTagCompound jukeboxBlockEntity = findByPos(blockEntities, 10, 70, 20);
        assertEquals("minecraft:jukebox", jukeboxBlockEntity.getString("id"), "id survives");
        assertEquals("keep-me", markerOf(jukeboxBlockEntity), "an unrelated field is not clobbered");
        assertEquals(Items.RECORD_CAT, readRecordItem(jukeboxBlockEntity).getItem(),
                "the jukebox gains exactly the disc");
        assertFalse(findByPos(blockEntities, 11, 70, 20).hasKey("RecordItem"),
                "the neighbor block entity is untouched");
    }

    @Test
    void jukeboxHolderCarriesPlayingStateSoItShowsNoteParticlesOnLoad() {
        BlockPos jukeboxPos = new BlockPos(10, 70, 20);
        NBTTagCompound chunkTag = chunkTagWith(taggedBlockEntity("minecraft:jukebox", 10, 70, 20));
        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(jukeboxPos, InteractionCapture.captureRecordItem(new ItemStack(Items.RECORD_CAT)));

        ContainerMerge.mergeHolderChunkStash(chunkTag, new ChunkPos(jukeboxPos), stash).merged();

        NBTTagCompound jukeboxBlockEntity = findByPos(
                chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10), 10, 70, 20);
        assertTrue(jukeboxBlockEntity.getBoolean("IsPlaying"), "the captured jukebox is marked playing");
    }

    @Test
    void mergeDrainsButDoesNotMergeWhenNoBlockEntityAtThePos() {
        BlockPos pos = new BlockPos(1, 64, 1);
        NBTTagCompound chunkTag = chunkTagWith(taggedBlockEntity("minecraft:jukebox", 2, 64, 1));

        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(pos, InteractionCapture.captureRecordItem(new ItemStack(Items.RECORD_11)));

        int merged = ContainerMerge.mergeHolderChunkStash(chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(0, merged, "no captured block entity at the stashed pos -> nothing merges");
        assertFalse(stash.containsKey(pos), "the entry is still drained: the chunk is leaving memory");
        assertFalse(
                findByPos(chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10), 2, 64, 1)
                        .hasKey("RecordItem"),
                "the unrelated jukebox is left alone");
    }
}
