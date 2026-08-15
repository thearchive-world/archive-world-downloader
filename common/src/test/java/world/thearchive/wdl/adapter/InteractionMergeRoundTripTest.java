// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.chunkTagWith;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.BlockEntityFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the two new band-stable merge writes that interaction-prediction capture adds beside the
 * open-time container/lectern path: a jukebox disc under {@code "RecordItem"} (via {@code ItemStack#save}) and beehive
 * occupants under {@code "Bees"} (the pre-component {@code {EntityData, TicksInHive, MinOccupationTicks}} entries below
 * 1.20.5). Each is the capture-then-merge round-trip proven against vanilla's own read-back (the exact form the block
 * entity's {@code loadAdditional}/{@code load} uses): the captured content survives serialization, lands on the
 * matching captured block entity under its one key with no other field clobbered, decodes back to the same content, and
 * only the flushed chunk's stash entries drain. Server-free: real {@link ItemStack}s and hand-built chunk tags, no live
 * client and no {@code Level}.
 */
class InteractionMergeRoundTripTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    /** A block entity fed through the fidelity gate as-is, so a marker stamped afterward never trips it. */
    private static CompoundTag taggedBlockEntity(String id, int x, int y, int z) {
        return blockEntity(id, x, y, z);
    }

    /** Stamp an unrelated marker field onto the block entity at x/y/z, after the chunk tag's own fidelity check ran. */
    private static void markBlockEntity(CompoundTag chunkTag, int x, int y, int z, String marker) {
        findByPos(chunkTag, x, y, z).putString("wdl_test_marker", marker);
    }

    private static String markerOf(CompoundTag blockEntityTag) {
        return blockEntityTag.getString("wdl_test_marker");
    }

    private static ItemStack readRecordItem(CompoundTag jukeboxBlockEntityTag) {
        // vanilla JukeboxBlockEntity.loadAdditional's exact read
        ItemStack back = ItemStack.of(jukeboxBlockEntityTag.getCompound("RecordItem"));
        assertTrue(!back.isEmpty(), "the merged RecordItem must decode via vanilla ItemStack.of");
        return back;
    }

    /** The ticksInHive of each occupant, in order, via vanilla {@code BeehiveBlockEntity.load}'s exact read. */
    private static List<Integer> readBees(CompoundTag beehiveBlockEntityTag) {
        ListTag bees = beehiveBlockEntityTag.getList("Bees", Tag.TAG_COMPOUND);
        List<Integer> ticksInHive = new ArrayList<>();
        for (int i = 0; i < bees.size(); i++) {
            ticksInHive.add(bees.getCompound(i).getInt("TicksInHive"));
        }
        return ticksInHive;
    }

    @Test
    void jukeboxDiscRoundTripsThroughCaptureMergeAndVanillaCodec() {
        BlockPos jukeboxPos = new BlockPos(10, 70, 20);
        CompoundTag chunkTag = chunkTagWith(
                taggedBlockEntity("minecraft:jukebox", 10, 70, 20),
                taggedBlockEntity("minecraft:furnace", 11, 70, 20)); // a neighbor BE that must stay untouched
        markBlockEntity(chunkTag, 10, 70, 20, "keep-me");

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(jukeboxPos, InteractionCapture.captureRecordItem(new ItemStack(Items.MUSIC_DISC_CAT)));
        BlockPos elsewhere = new BlockPos(100, 70, 200); // a jukebox in a different chunk, not being flushed
        stash.put(elsewhere, InteractionCapture.captureRecordItem(new ItemStack(Items.MUSIC_DISC_13)));

        int merged = ContainerMerge.mergeHolderChunkStash(chunkTag, new ChunkPos(jukeboxPos), stash).merged();

        assertEquals(1, merged, "only the flushed chunk's jukebox merges");
        assertFalse(stash.containsKey(jukeboxPos), "the flushed chunk's stash entry is drained");
        assertTrue(stash.containsKey(elsewhere), "another chunk's stash entry is left until its own flush");

        ListTag blockEntities = chunkTag.getList("block_entities", Tag.TAG_COMPOUND);
        CompoundTag jukeboxBlockEntity = findByPos(blockEntities, 10, 70, 20);
        assertEquals("minecraft:jukebox", jukeboxBlockEntity.getString("id"), "id survives");
        assertEquals("keep-me", markerOf(jukeboxBlockEntity), "an unrelated field is not clobbered");
        assertEquals(Items.MUSIC_DISC_CAT, readRecordItem(jukeboxBlockEntity).getItem(),
                "the jukebox gains exactly the disc");
        assertFalse(findByPos(blockEntities, 11, 70, 20).contains("RecordItem"),
                "the neighbor block entity is untouched");
    }

    @Test
    void jukeboxHolderCarriesPlayingStateSoItShowsNoteParticlesOnLoad() {
        BlockPos jukeboxPos = new BlockPos(10, 70, 20);
        CompoundTag chunkTag = chunkTagWith(taggedBlockEntity("minecraft:jukebox", 10, 70, 20));
        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(jukeboxPos, InteractionCapture.captureRecordItem(new ItemStack(Items.MUSIC_DISC_CAT)));

        ContainerMerge.mergeHolderChunkStash(chunkTag, new ChunkPos(jukeboxPos), stash).merged();

        // Vanilla JukeboxBlockEntity.tick spawns the note particles only while IsPlaying is set and a disc is
        // present, so the captured holder marks the just-inserted disc playing.
        CompoundTag jukeboxBlockEntity = findByPos(chunkTag.getList("block_entities", Tag.TAG_COMPOUND), 10, 70, 20);
        assertTrue(jukeboxBlockEntity.getBoolean("IsPlaying"),
                "the captured jukebox is marked playing so it shows note particles on load");
    }

    @Test
    void beehiveOccupantsRoundTripThroughCaptureMergeAndVanillaCodec() {
        BlockPos hivePos = new BlockPos(-3, 64, 7);
        CompoundTag chunkTag = chunkTagWith(
                taggedBlockEntity("minecraft:beehive", -3, 64, 7),
                taggedBlockEntity("minecraft:chest", -2, 64, 7)); // a neighbor BE that must stay untouched
        markBlockEntity(chunkTag, -3, 64, 7, "keep-me");

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(hivePos, InteractionCapture.captureBees(BlockEntityFixtures.bees(120, 45)));

        int merged = ContainerMerge.mergeHolderChunkStash(chunkTag, new ChunkPos(hivePos), stash).merged();

        assertEquals(1, merged, "the flushed chunk's beehive merges");
        assertFalse(stash.containsKey(hivePos), "the stash entry is drained as the tag leaves memory");

        ListTag blockEntities = chunkTag.getList("block_entities", Tag.TAG_COMPOUND);
        CompoundTag hiveBlockEntity = findByPos(blockEntities, -3, 64, 7);
        assertEquals("keep-me", markerOf(hiveBlockEntity), "an unrelated field is not clobbered");
        List<Integer> back = readBees(hiveBlockEntity);
        assertEquals(2, back.size(), "both occupants survive the round-trip");
        assertEquals(120, back.get(0), "the first occupant's ticksInHive survives");
        assertEquals(45, back.get(1), "the second occupant's ticksInHive survives");
        assertFalse(findByPos(blockEntities, -2, 64, 7).contains("Bees"), "the neighbor block entity is untouched");
    }

    @Test
    void mergeDrainsButDoesNotMergeWhenNoBlockEntityAtThePos() {
        BlockPos pos = new BlockPos(1, 64, 1);
        // A jukebox, but in a different cell than the stashed pos
        CompoundTag chunkTag = chunkTagWith(taggedBlockEntity("minecraft:jukebox", 2, 64, 1));

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(pos, InteractionCapture.captureRecordItem(new ItemStack(Items.MUSIC_DISC_11)));

        int merged = ContainerMerge.mergeHolderChunkStash(chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(0, merged, "no captured block entity at the stashed pos -> nothing merges");
        assertFalse(stash.containsKey(pos), "the entry is still drained: the chunk is leaving memory");
        assertFalse(findByPos(chunkTag.getList("block_entities", Tag.TAG_COMPOUND), 2, 64, 1).contains("RecordItem"),
                "the unrelated jukebox is left alone");
    }
}
