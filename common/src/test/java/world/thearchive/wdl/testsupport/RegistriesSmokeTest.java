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

/**
 * Composition-correctness gate for the classic-MCP mapping this band compiles and runs against. The compile gate proves
 * the MCP names resolve and Unimined's provision proves the mapping is tiny-remapper-consistent, but neither proves the
 * mapping binds each name to the right game class: a wrong-but-valid class or member match compiles and provisions fine
 * yet points at the wrong bytecode at runtime. Only running the remapped jar catches that, so this boots the real
 * vanilla registries and exercises the exact shallowly-used, structurally-interchangeable classes where such a mis-map
 * hides.
 *
 * <p>Running {@code Bootstrap.register()} alone is the broad net: it drives hundreds of static initializers and
 * registry writes through real vanilla code, so a mis-mapped class, method descriptor or field along that path throws.
 * The assertions then pin the specific interchangeable types: an {@code NBTTagCompound} string round-trip, a
 * {@code BlockPos} construct-and-read, and a registry lookup whose two-way identity (the object at
 * {@code minecraft:stone} is {@code Blocks.STONE}, and that block's registered id is {@code minecraft:stone}) fails if
 * the {@code STONE} field name landed on a different block or the block registry landed on a different table.
 */
class RegistriesSmokeTest {
    @Test
    void bootstrapsAndBindsVanillaClasses() {
        TestRegistries.bootstrap();

        // NBTTagCompound put/get round-trips the value the NBT codecs read and write.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("wdl_test_marker", "archive");
        assertEquals("archive", tag.getString("wdl_test_marker"));
        // The integer accessors are a separate tag type from the string pair above, so a mis-mapped
        // NBTTagInt would survive the round-trip beside it.
        tag.setInteger("wdl_test_marker_int", 42);
        assertEquals(42, tag.getInteger("wdl_test_marker_int"));

        // BlockPos construct-and-read: the coordinate accessors come off Vec3i at this band.
        BlockPos pos = new BlockPos(1, 2, 3);
        assertEquals(1, pos.getX());
        assertEquals(2, pos.getY());
        assertEquals(3, pos.getZ());

        // The block registry binds minecraft:stone both ways: the object at the id is the STONE singleton, and that
        // singleton's registered id is minecraft:stone. A STONE field mapped onto a different block, or a block
        // registry mapped onto a different table, breaks one of these.
        ResourceLocation stoneId = new ResourceLocation("minecraft", "stone");
        assertSame(Blocks.STONE, Block.REGISTRY.getObject(stoneId));
        assertEquals(stoneId, Block.REGISTRY.getNameForObject(Blocks.STONE));

        // Pin the Block class mapping on a block that is a plain Block at 1.12.2. minecraft:stone is not (BlockStone,
        // pre-Flattening host of the granite/diorite metadata variants), so minecraft:cobblestone carries the
        // exact-class check: it is registered as a bare new Block(Material.ROCK).
        assertSame(Block.class, Block.REGISTRY.getObject(new ResourceLocation("minecraft", "cobblestone")).getClass());
        // Cobblestone above pins that a plain Block stays plain; stone pins the other direction, that a block
        // with a dedicated subclass still binds to it rather than collapsing to the base type.
        assertSame(BlockStone.class, Blocks.STONE.getClass());
    }
}
