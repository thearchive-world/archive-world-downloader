// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.chunkTagWith;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.customNameOf;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPos;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.namedBlockEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

class InteractionMergeRoundTripTest {
    private static RegistryAccess registries;

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    private static CompoundTag taggedBlockEntity(String id, int x, int y, int z) {
        return namedBlockEntity(id, x, y, z, "keep-me");
    }

    private static ItemStack readRecordItem(CompoundTag jukeboxBlockEntityTag) {
        Optional<ItemStack> back = ItemStack.CODEC
                .parse(registries.createSerializationContext(NbtOps.INSTANCE), jukeboxBlockEntityTag.get("RecordItem"))
                .result();
        assertTrue(back.isPresent(), "the merged RecordItem must decode via vanilla ItemStack.CODEC");
        return back.get();
    }

    private static List<BeehiveBlockEntity.Occupant> readBees(CompoundTag beehiveBlockEntityTag) {
        // Vanilla BeehiveBlockEntity.loadAdditional's read
        return BeehiveBlockEntity.Occupant.LIST_CODEC
                .parse(registries.createSerializationContext(NbtOps.INSTANCE), beehiveBlockEntityTag.get("bees"))
                .result().orElse(List.of());
    }

    @Test
    void jukeboxDiscRoundTripsThroughCaptureMergeAndVanillaCodec() {
        BlockPos jukeboxPos = new BlockPos(10, 70, 20);
        CompoundTag chunkTag = chunkTagWith(
                taggedBlockEntity("minecraft:jukebox", 10, 70, 20),
                taggedBlockEntity("minecraft:furnace", 11, 70, 20));

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(jukeboxPos, InteractionCapture.captureRecordItem(new ItemStack(Items.MUSIC_DISC_CAT), registries));
        BlockPos elsewhere = new BlockPos(100, 70, 200);
        stash.put(elsewhere, InteractionCapture.captureRecordItem(new ItemStack(Items.MUSIC_DISC_13), registries));

        int merged = ContainerMerge.mergeHolderChunkStash(chunkTag, new ChunkPos(jukeboxPos), stash).merged();

        assertEquals(1, merged, "only the flushed chunk's jukebox merges");
        assertFalse(stash.containsKey(jukeboxPos), "the flushed chunk's stash entry is drained");
        assertTrue(stash.containsKey(elsewhere), "another chunk's stash entry is left until its own flush");

        ListTag blockEntities = chunkTag.getList("block_entities", Tag.TAG_COMPOUND);
        CompoundTag jukeboxBlockEntity = findByPos(blockEntities, 10, 70, 20);
        assertEquals("minecraft:jukebox", jukeboxBlockEntity.getString("id"), "id survives");
        assertEquals("keep-me", customNameOf(jukeboxBlockEntity), "an unrelated field is not clobbered");
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
        stash.put(jukeboxPos, InteractionCapture.captureRecordItem(new ItemStack(Items.MUSIC_DISC_CAT), registries));

        ContainerMerge.mergeHolderChunkStash(chunkTag, new ChunkPos(jukeboxPos), stash).merged();

        CompoundTag jukeboxBlockEntity = findByPos(chunkTag.getList("block_entities", Tag.TAG_COMPOUND), 10, 70, 20);
        assertTrue(jukeboxBlockEntity.getBoolean("IsPlaying"), "the captured jukebox is marked playing");
    }

    @Test
    void beehiveOccupantsRoundTripThroughCaptureMergeAndVanillaCodec() {
        BlockPos hivePos = new BlockPos(-3, 64, 7);
        CompoundTag chunkTag = chunkTagWith(
                taggedBlockEntity("minecraft:beehive", -3, 64, 7),
                taggedBlockEntity("minecraft:chest", -2, 64, 7));

        List<BeehiveBlockEntity.Occupant> occupants = List.of(BeehiveBlockEntity.Occupant.create(120),
                BeehiveBlockEntity.Occupant.create(45));
        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(hivePos, InteractionCapture.captureBees(occupants, registries));

        int merged = ContainerMerge.mergeHolderChunkStash(chunkTag, new ChunkPos(hivePos), stash).merged();

        assertEquals(1, merged, "the flushed chunk's beehive merges");
        assertFalse(stash.containsKey(hivePos), "the stash entry is drained as the tag leaves memory");

        ListTag blockEntities = chunkTag.getList("block_entities", Tag.TAG_COMPOUND);
        CompoundTag hiveBlockEntity = findByPos(blockEntities, -3, 64, 7);
        assertEquals("keep-me", customNameOf(hiveBlockEntity), "an unrelated field is not clobbered");
        List<BeehiveBlockEntity.Occupant> back = readBees(hiveBlockEntity);
        assertEquals(2, back.size(), "both occupants survive the round-trip");
        assertEquals(120, back.get(0).ticksInHive(), "the first occupant's ticksInHive survives");
        assertEquals(45, back.get(1).ticksInHive(), "the second occupant's ticksInHive survives");
        assertFalse(findByPos(blockEntities, -2, 64, 7).contains("bees"), "the neighbor block entity is untouched");
    }

    @Test
    void mergeDrainsButDoesNotMergeWhenNoBlockEntityAtThePos() {
        BlockPos pos = new BlockPos(1, 64, 1);
        CompoundTag chunkTag = chunkTagWith(taggedBlockEntity("minecraft:jukebox", 2, 64, 1));

        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(pos, InteractionCapture.captureRecordItem(new ItemStack(Items.MUSIC_DISC_11), registries));

        int merged = ContainerMerge.mergeHolderChunkStash(chunkTag, new ChunkPos(pos), stash).merged();

        assertEquals(0, merged, "no captured block entity at the stashed pos -> nothing merges");
        assertFalse(stash.containsKey(pos), "the entry is still drained: the chunk is leaving memory");
        assertFalse(findByPos(chunkTag.getList("block_entities", Tag.TAG_COMPOUND), 2, 64, 1).contains("RecordItem"),
                "the unrelated jukebox is left alone");
    }
}
