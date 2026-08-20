// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPosOrNull;

import com.google.common.collect.ImmutableList;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.testsupport.BlockEntityFixtures;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * Re-capture fidelity: a re-encode reads live client state, so encoding a chunk whose block-entity set has changed
 * yields a tag that reflects the change. A populated client-held block entity survives the re-encode (no regression to
 * empty), a changed one is reflected, and a removed one is gone from the re-encoded tag. Block-state container fidelity
 * is the separate {@link ChunkRoundTripTest}.
 */
class ChunkReencodeFidelityTest {
    private final ChunkCodec codec = new ChunkCodecImpl();

    /**
     * A sign block entity in saved form carrying a client-held field a re-encode must preserve. The field is
     * deliberately one no vanilla writer emits: a key vanilla itself round-trips would still survive a codec that
     * rebuilt every tag from vanilla's own load, so it could not prove the passthrough this names.
     */
    private static CompoundTag sign(int x, int y, int z, String text) {
        return BlockEntityFixtures.blockEntityWithForeignKey("minecraft:sign", x, y, z, "wdl_test_text", text);
    }

    @Test
    void aPopulatedClientHeldBlockEntitySurvivesReencode() {
        TestRegistries.bootstrap();

        CompoundTag tag = codec.encode(
                SyntheticChunks.fullWithMalformedBlockEntities(false,
                        ImmutableList.of(sign(2, 64, 2, "hello"))),
                false);

        CompoundTag blockEntity = findByPosOrNull(tag, 2, 64, 2);
        assertNotNull(blockEntity, "the captured sign block entity is present in the encoded tag");
        assertEquals("hello", blockEntity.getString("wdl_test_text"),
                "its client-held data survives the re-encode (no regression to empty)");
    }

    @Test
    void reencodeReflectsAnEditedBlockEntity() {
        TestRegistries.bootstrap();

        CompoundTag before = codec.encode(
                SyntheticChunks.fullWithMalformedBlockEntities(false,
                        ImmutableList.of(sign(2, 64, 2, "hello"))),
                false);
        CompoundTag after = codec.encode(
                SyntheticChunks.fullWithMalformedBlockEntities(false,
                        ImmutableList.of(sign(2, 64, 2, "world"))),
                false);

        assertEquals("hello", findByPosOrNull(before, 2, 64, 2).getString("wdl_test_text"));
        assertEquals("world", findByPosOrNull(after, 2, 64, 2).getString("wdl_test_text"),
                "re-encoding the changed chunk reflects the edited block-entity data");
    }

    @Test
    void aRemovedBlockEntityIsGoneFromTheReencodedTag() {
        TestRegistries.bootstrap();

        CompoundTag withSign = codec.encode(
                SyntheticChunks.fullWithMalformedBlockEntities(false,
                        ImmutableList.of(sign(2, 64, 2, "hello"))),
                false);
        CompoundTag withoutSign = codec.encode(
                SyntheticChunks.fullWithBlockEntities(false, ImmutableList.of()), false);

        assertNotNull(findByPosOrNull(withSign, 2, 64, 2), "captured while present");
        assertNull(findByPosOrNull(withoutSign, 2, 64, 2),
                "after removal the re-encode drops the block entity, leaving no ghost NBT");
    }
}
