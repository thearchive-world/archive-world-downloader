// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.reobftest;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.HitResult;

// Negative reobf fixture. Its compiled bytecode carries Mojmap Minecraft names in the constant pool
// (net/minecraft/nbt/CompoundTag, net/minecraft/core/BlockPos, and members putString/putInt/below) plus
// one member the bridge leaves at its Legacy Fabric intermediary name, HitResult.field_595. The
// checkReobfNegative task feeds these UN-reobfuscated bytes to the same gate scan, which must reject
// them: the Mojmap class names are not valid 1.13.2 SRG classes (arm a), and neither the Mojmap member
// names nor the intermediary field_595 are searge ids (arm b). field_595 is the load-bearing case: it
// shares the field_ prefix with searge field ids, so only validating the name against joined.tsrg (not a
// prefix test) fires on it. It proves the gate fires; a gate never seen to fire is not trusted.
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
