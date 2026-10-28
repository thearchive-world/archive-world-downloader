// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.testsupport.RegionRoundTrip;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

class ChunkRoundTripTest {
    private final ChunkCodec codec = new ChunkCodecImpl();

    @Test
    void chunkRoundTripsSelfConsistently(@TempDir Path directory) {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        LevelHeightAccessor heightAccessor = SyntheticChunks.heightAccessor();

        CompoundTag tag = codec.encode(SyntheticChunks.full(registries, true), registries, false);
        CompoundTag back = RegionRoundTrip.writeThenRead(directory, new ChunkPos(0, 0), tag);

        assertTrue(tag.getIntOr("DataVersion", -1) > 0, "codec must stamp a DataVersion");
        assertEquals(tag.getIntOr("DataVersion", -1), back.getIntOr("DataVersion", -2));

        // OCEAN_FLOOR (LIVE_WORLD, never sent to a client) is omitted so vanilla read() re-primes it.
        CompoundTag heightmaps = back.getCompoundOrEmpty("Heightmaps");
        assertFalse(heightmaps.contains("OCEAN_FLOOR"), "OCEAN_FLOOR must be dropped from the written tag");
        assertTrue(heightmaps.contains("WORLD_SURFACE"), "client-sent heightmaps must be kept");

        SerializableChunkData parsed = SerializableChunkData.parse(heightAccessor,
                PalettedContainerFactory.create(registries),
                back);
        assertNotNull(parsed, "parse() must accept the round-tripped tag");
    }

    @Test
    void isLightOnTracksCapturedLightNotHardcoded() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();

        CompoundTag lit = codec.encode(SyntheticChunks.full(registries, true), registries, false);
        CompoundTag dark = codec.encode(SyntheticChunks.full(registries, false), registries, false);

        assertTrue(lit.getBooleanOr("isLightOn", false), "lightCorrect chunk -> isLightOn=true");
        assertFalse(dark.getBooleanOr("isLightOn", false), "non-lightCorrect chunk -> isLightOn omitted");
    }

    @Test
    void capturedLightRoundTripsInVanillaShape(@TempDir Path directory) {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        LevelHeightAccessor heightAccessor = SyntheticChunks.heightAccessor();
        int minSectionY = heightAccessor.getMinSectionY();

        CompoundTag tag = codec.encode(SyntheticChunks.fullWithLight(registries), registries, false);
        CompoundTag back = RegionRoundTrip.writeThenRead(directory, new ChunkPos(0, 0), tag);

        assertTrue(back.getBooleanOr("isLightOn", false), "lit snapshot -> isLightOn=true");

        CompoundTag bottom = sectionAt(back, minSectionY);
        assertArrayEquals(SyntheticChunks.lightFill(SyntheticChunks.BLOCK_LIGHT_FILL),
                bottom.getByteArray("BlockLight").orElseThrow(), "bottom section block light survives");
        assertArrayEquals(SyntheticChunks.lightFill(SyntheticChunks.SKY_LIGHT_FILL),
                bottom.getByteArray("SkyLight").orElseThrow(), "bottom section sky light survives");

        CompoundTag padding = sectionAt(back, minSectionY - 1);
        assertArrayEquals(SyntheticChunks.lightFill(SyntheticChunks.SKY_LIGHT_FILL),
                padding.getByteArray("SkyLight").orElseThrow(), "below-chunk padding sky light survives");
        assertFalse(padding.contains("block_states"), "padding section carries no block states");

        assertNotNull(SerializableChunkData.parse(heightAccessor,
                PalettedContainerFactory.create(registries), back),
                "parse() must accept a lit tag with a padding section");
    }

    private static CompoundTag sectionAt(CompoundTag chunkTag, int sectionY) {
        return chunkTag.getListOrEmpty("sections").compoundStream()
                .filter(section -> section.getByteOr("Y", Byte.MIN_VALUE) == sectionY)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no written section at Y=" + sectionY));
    }
}
