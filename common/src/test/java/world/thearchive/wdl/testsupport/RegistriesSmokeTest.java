// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.block.Block;
import net.minecraft.block.BlockStone;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

class RegistriesSmokeTest {
    @Test
    void bootstrapsAndBindsVanillaClasses() {
        TestRegistries.bootstrap();

        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("wdl_test_marker", "archive");
        assertEquals("archive", tag.getString("wdl_test_marker"));
        tag.setInteger("wdl_test_marker_int", 42);
        assertEquals(42, tag.getInteger("wdl_test_marker_int"));

        BlockPos pos = new BlockPos(1, 2, 3);
        assertEquals(1, pos.getX());
        assertEquals(2, pos.getY());
        assertEquals(3, pos.getZ());

        ResourceLocation stoneId = new ResourceLocation("minecraft", "stone");
        assertSame(Blocks.STONE, Block.REGISTRY.getObject(stoneId));
        assertEquals(stoneId, Block.REGISTRY.getNameForObject(Blocks.STONE));

        assertSame(Block.class, Block.REGISTRY.getObject(new ResourceLocation("minecraft", "cobblestone")).getClass());
        // Stone pins that a block with a dedicated subclass still binds to it.
        assertSame(BlockStone.class, Blocks.STONE.getClass());
    }
}
