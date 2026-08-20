// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.reobftest;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

// Compile-only reobf fixture. It references surviving Mojmap Minecraft members that each carry a
// mojmap to srg entry (CompoundTag.putInt/getInt/putString/getString, BlockPos.below/above), so
// reobfJar rewrites every Minecraft reference to its 1.13.2 searge id and checkReobf sees only valid
// SRG names. The class's own world.thearchive.wdl name is absent from the mapping, so tiny-remapper
// leaves it untouched, which is the reobf contract the island's shipped output relies on.
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
