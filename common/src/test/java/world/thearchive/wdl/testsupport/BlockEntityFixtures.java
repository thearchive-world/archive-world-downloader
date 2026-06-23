// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jspecify.annotations.Nullable;

/**
 * Shared block-entity NBT fixtures: the tag vanilla writes for a block entity, and a by-position lookup into a chunk's
 * {@code "block_entities"} list.
 *
 * <p>{@link #blockEntity} is the producer's own output rather than a hand-listed set of keys, so a fixture that
 * overlays keys onto the base is overlaying them onto the shape vanilla itself writes.
 */
public final class BlockEntityFixtures {
    private BlockEntityFixtures() {}

    /**
     * The tag vanilla writes for a freshly placed block entity of type {@code id} at {@code x/y/z}: its metadata, its
     * {@code components}, and every key its own save writes unconditionally.
     */
    public static CompoundTag blockEntity(String id, int x, int y, int z) {
        return FixtureFidelity.blockEntityShape(id, x, y, z);
    }

    /**
     * A block entity carrying a key no vanilla writer emits, for the one property that needs exactly that: the chunk
     * codec must pass a block entity's NBT through opaquely, since the tags it re-encodes come from the client and may
     * have been written by a foreign or modded server. A probe vanilla itself round-trips cannot prove that, because a
     * codec that silently rebuilt every tag from vanilla's own load would still preserve it. Not for a fixture standing
     * in for producer output.
     */
    public static CompoundTag blockEntityWithForeignKey(String id, int x, int y, int z, String key,
            String value) {
        CompoundTag tag = blockEntity(id, x, y, z);
        tag.putString(key, value);
        return tag;
    }

    /** The block-entity tag in {@code chunkTag} at {@code x/y/z}, or {@code null} when none matches. */
    public static @Nullable CompoundTag findByPosOrNull(CompoundTag chunkTag, int x, int y, int z) {
        ListTag list = chunkTag.getListOrEmpty("block_entities");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompoundOrEmpty(i);
            if (tag.getIntOr("x", 0) == x && tag.getIntOr("y", 0) == y && tag.getIntOr("z", 0) == z) {
                return tag;
            }
        }
        return null;
    }
}
