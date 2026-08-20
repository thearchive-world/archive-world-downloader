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

/**
 * Composition-correctness gate for the heuristically composed intermediary-to-Mojmap 1.13.2 bridge mapping. The compile
 * gate proves the Mojmap names resolve and Loom's provision proves the mapping is tiny-remapper-consistent, but neither
 * proves the mapping binds each name to the right game class: a wrong-but-valid class or member match compiles and
 * provisions fine yet points at the wrong bytecode at runtime. Only running the remapped jar catches that, so this
 * boots the real vanilla registries and exercises the exact shallowly-used, structurally-interchangeable classes where
 * such a mis-map hides.
 *
 * <p>Running {@code Bootstrap.bootStrap()} alone is the broad net: it drives hundreds of static initializers and
 * registry writes through real vanilla code, so a mis-mapped class, method descriptor or field along that path throws.
 * The assertions then pin the specific interchangeable types: a {@code CompoundTag} string round-trip, a
 * {@code BlockPos} construct-and-read, and a registry lookup whose two-way identity (the object at
 * {@code minecraft:stone} is {@code Blocks.STONE}, and that block's registered id is {@code minecraft:stone}) fails if
 * the {@code STONE} field name landed on a different block or the block registry name landed on a different table.
 */
class RegistriesSmokeTest {
    @Test
    void bootstrapsAndBindsVanillaClasses() {
        TestRegistries.bootstrap();

        // CompoundTag put/get round-trips the value the NBT codecs read and write.
        CompoundTag tag = new CompoundTag();
        tag.putString("wdl_test_marker", "archive");
        assertEquals("archive", tag.getString("wdl_test_marker"));

        // BlockPos construct-and-read: the coordinate accessors come off Vec3i at this band.
        BlockPos pos = new BlockPos(1, 2, 3);
        assertEquals(1, pos.getX());
        assertEquals(2, pos.getY());
        assertEquals(3, pos.getZ());

        // The block registry binds minecraft:stone both ways: the object at the id is the STONE singleton, and that
        // singleton's registered id is minecraft:stone. A STONE field mapped onto a different block, or a block
        // registry mapped onto a different table, breaks one of these.
        ResourceLocation stoneId = new ResourceLocation("minecraft", "stone");
        assertSame(Blocks.STONE, Registry.BLOCK.get(stoneId));
        assertEquals(stoneId, Registry.BLOCK.getKey(Blocks.STONE));

        // Pin the Block class mapping on a block that is a plain Block at 1.13.2. minecraft:stone is not: at this band
        // it is an instance of a Mojang-unnamed Block subclass, so minecraft:granite carries the exact-class check.
        assertSame(Block.class, Registry.BLOCK.get(new ResourceLocation("minecraft", "granite")).getClass());
    }
}
