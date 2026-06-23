// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPosOrNull;

import java.util.List;
import net.minecraft.core.RegistryAccess;
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
        RegistryAccess.Frozen registries = TestRegistries.frozen();

        CompoundTag tag = codec.encode(
                SyntheticChunks.fullWithMalformedBlockEntities(registries, false, List.of(sign(2, 64, 2, "hello"))),
                registries,
                false);

        CompoundTag blockEntity = findByPosOrNull(tag, 2, 64, 2);
        assertNotNull(blockEntity, "the captured sign block entity is present in the encoded tag");
        assertEquals("hello", blockEntity.getStringOr("wdl_test_text", ""),
                "its client-held data survives the re-encode (no regression to empty)");
    }

    @Test
    void reencodeReflectsAnEditedBlockEntity() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();

        CompoundTag before = codec.encode(
                SyntheticChunks.fullWithMalformedBlockEntities(registries, false, List.of(sign(2, 64, 2, "hello"))),
                registries,
                false);
        CompoundTag after = codec.encode(
                SyntheticChunks.fullWithMalformedBlockEntities(registries, false, List.of(sign(2, 64, 2, "world"))),
                registries,
                false);

        assertEquals("hello", findByPosOrNull(before, 2, 64, 2).getStringOr("wdl_test_text", ""));
        assertEquals("world", findByPosOrNull(after, 2, 64, 2).getStringOr("wdl_test_text", ""),
                "re-encoding the changed chunk reflects the edited block-entity data");
    }

    @Test
    void aRemovedBlockEntityIsGoneFromTheReencodedTag() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();

        CompoundTag withSign = codec.encode(
                SyntheticChunks.fullWithMalformedBlockEntities(registries, false, List.of(sign(2, 64, 2, "hello"))),
                registries,
                false);
        CompoundTag withoutSign = codec.encode(
                SyntheticChunks.fullWithBlockEntities(registries, false, List.of()), registries, false);

        assertNotNull(findByPosOrNull(withSign, 2, 64, 2), "captured while present");
        assertNull(findByPosOrNull(withoutSign, 2, 64, 2),
                "after removal the re-encode drops the block entity, leaving no ghost NBT");
    }
}
