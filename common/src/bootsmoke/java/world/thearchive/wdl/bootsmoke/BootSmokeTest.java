// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.bootsmoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.block.Block;
import net.minecraft.block.BlockStone;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves the Unimined-provisioned MCP 1.12.2 jar actually runs under the Java 8 target, isolated from both {@code main}
 * (seam-red on the net.minecraft-facing files) and {@code test} (Mojmap-typed, will not compile against this jar). This
 * does not prove a mapping is correct the way a remapped band's registry smoke test does; MCP names bind directly with
 * no remap step. It proves the provision composes: the jar loads, its static initializers run, and its classes bind the
 * descriptors the compiled bytecode expects. A {@link NoClassDefFoundError} or {@link NoSuchMethodError} here means the
 * provision is wrong.
 */
class BootSmokeTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void bootstrapsAndBindsVanillaClasses() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("wdl_test_marker", 42);
        assertEquals(42, tag.getInteger("wdl_test_marker"));

        BlockPos pos = new BlockPos(1, 2, 3);
        assertEquals(1, pos.getX());
        assertEquals(2, pos.getY());
        assertEquals(3, pos.getZ());

        ResourceLocation stoneId = new ResourceLocation("stone");
        Block stone = Block.REGISTRY.getObject(stoneId);
        assertSame(Blocks.STONE, stone);
        assertSame(BlockStone.class, stone.getClass());
        assertEquals(stoneId, Block.REGISTRY.getNameForObject(stone));
    }
}
