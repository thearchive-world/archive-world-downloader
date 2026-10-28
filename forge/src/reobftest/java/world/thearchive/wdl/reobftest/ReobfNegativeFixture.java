// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.reobftest;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.HitResult;

// Negative reobf fixture. Its compiled bytecode carries Mojmap Minecraft names in the constant pool
// (net/minecraft/nbt/CompoundTag, net/minecraft/core/BlockPos, and members putString/putInt/below) plus one member the
// bridge leaves at its Legacy Fabric intermediary name, HitResult.field_595.
public final class ReobfNegativeFixture {

    private ReobfNegativeFixture() {}

    public static CompoundTag write(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        BlockPos anchor = pos.below();
        tag.putInt("depth", anchor == pos ? 0 : 1);
        tag.putString("id", "wdl");
        return tag;
    }

    public static Object leakIntermediaryField(HitResult hit) {
        return hit.field_595;
    }
}
