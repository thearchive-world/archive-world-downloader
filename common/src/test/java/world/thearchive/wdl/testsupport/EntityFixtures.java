// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;

/**
 * Entity NBT fixtures: the identity keys an entity tag carries.
 *
 * <p>An entity tag has no producer callable here, because capture derives it from a server packet rather than from a
 * serializable live entity, so the {@code id} and {@code "UUID"} metadata a merge matches on is built directly.
 */
public final class EntityFixtures {
    private EntityFixtures() {}

    /** An entity tag carrying only its {@code id}, the key a recursive load reads before anything else. */
    public static CompoundTag entityTag(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        return tag;
    }

    /** An entity tag carrying the {@code id} and {@code "UUID"} a merge matches on, and nothing else. */
    public static CompoundTag entity(String id, UUID uuid) {
        CompoundTag tag = entityTag(id);
        tag.store("UUID", UUIDUtil.CODEC, uuid);
        return tag;
    }
}
