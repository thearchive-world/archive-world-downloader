// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;

public final class BadStacks {
    private BadStacks() {}

    /** The registry-aware NBT ops the disk codec uses. */
    public static RegistryOps<Tag> ops(RegistryAccess registries) {
        return RegistryOps.create(NbtOps.INSTANCE, registries);
    }
}
