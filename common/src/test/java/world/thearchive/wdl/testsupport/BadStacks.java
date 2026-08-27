// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import io.netty.buffer.Unpooled;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Builders for stacks/components that are legal in memory and over the wire but illegal on disk, for degradation tests.
 */
public final class BadStacks {
    private BadStacks() {}

    /** The registry-aware NBT ops the disk codec uses. */
    public static RegistryOps<Tag> ops(RegistryAccess registries) {
        return RegistryOps.create(NbtOps.INSTANCE, registries);
    }

    /**
     * Build an {@link ItemEnchantments} carrying the given levels, including level 0. The disk codec rejects level 0
     * and {@code Mutable.set} drops it, so the value goes in over the wire {@code STREAM_CODEC}, which has no such
     * floor. On 1.21.3 the stream codec still carries a trailing {@code showInTooltip} boolean after the map, so the
     * wire form written here appends it too.
     */
    public static ItemEnchantments enchantments(RegistryAccess registries, Map<Holder<Enchantment>, Integer> levels) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        buf.writeVarInt(levels.size());
        levels.forEach((holder, level) -> {
            Enchantment.STREAM_CODEC.encode(buf, holder);
            buf.writeVarInt(level);
        });
        buf.writeBoolean(true);
        return ItemEnchantments.STREAM_CODEC.decode(buf);
    }
}
