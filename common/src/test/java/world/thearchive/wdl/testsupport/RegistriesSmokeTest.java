// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class RegistriesSmokeTest {
    @Test
    void bootstrapsAndBindsVanillaClasses() {
        TestRegistries.bootstrap();

        CompoundTag tag = new CompoundTag();
        tag.putString("wdl_test_marker", "archive");
        assertEquals("archive", tag.getString("wdl_test_marker"));

        BlockPos pos = new BlockPos(1, 2, 3);
        assertEquals(1, pos.getX());
        assertEquals(2, pos.getY());
        assertEquals(3, pos.getZ());

        ResourceLocation stoneId = new ResourceLocation("minecraft", "stone");
        assertSame(Blocks.STONE, Registry.BLOCK.get(stoneId));
        assertEquals(stoneId, Registry.BLOCK.getKey(Blocks.STONE));

        assertSame(Block.class, Registry.BLOCK.get(new ResourceLocation("minecraft", "granite")).getClass());
    }
}
