// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.reobftest;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

public final class ReobfFixture {

    private ReobfFixture() {}

    public static CompoundTag encode(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        BlockPos anchor = pos.below().above();
        tag.putInt("marker", anchor == pos ? 1 : 0);
        tag.putString("id", "wdl");
        return tag;
    }

    public static int decode(CompoundTag tag) {
        return tag.getInt("marker") + tag.getString("id").length();
    }
}
