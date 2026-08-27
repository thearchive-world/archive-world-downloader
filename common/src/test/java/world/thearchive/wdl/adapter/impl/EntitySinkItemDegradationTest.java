// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.EntitySink;
import world.thearchive.wdl.testsupport.BadStacks;
import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the item and equipment degradation this band exhibits. Unlike the unresolved-leash case
 * ({@link EntitySinkLeashDegradationTest}), a disk-codec-invalid item component throws the entity save out entirely on
 * this band: {@link ItemStack#save} ends in {@code getOrThrow()}, and {@code Mob.addAdditionalSaveData} and
 * {@code ItemEntity.addAdditionalSaveData} call it directly per stack with no codec-level recovery, so a mob equipped
 * with a bad bow, or an item entity carrying a bad stack, would otherwise abort {@code encodeChunk} with an exception
 * rather than save with the item quietly gone. Both fixtures below use a raw {@code set()} of
 * {@link DataComponents#DAMAGE} to -1, a {@code NON_NEGATIVE_INT} field that {@code set()} does no validation against;
 * this band's {@code ItemEnchantments} {@code LEVEL_CODEC} is {@code intRange(0, 255)}, so a level-0 enchantment saves
 * fine here and cannot stand in as the unsavable fixture the way it does on later bands. {@link EntitySinkImpl} repairs
 * the stack up front instead of relying on that throw, so these tests assert on the saved NBT itself, not just that the
 * entity survived: a regression that let the bad stack reach save unrepaired would make {@code encodeChunk} throw, not
 * merely return NBT with the item missing.
 */
class EntitySinkItemDegradationTest {
    private final EntitySink sink = new EntitySinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void aMobWithBadEquippedComponentSavesRepairedAndTheLiveItemIsRestored() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        ItemStack bow = new ItemStack(Items.BOW);
        bow.set(DataComponents.DAMAGE, -1);
        EquipMob mob = new EquipMob();
        mob.setItemSlot(EquipmentSlot.MAINHAND, bow);
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), bow).error().isPresent(),
                "precondition: the equipped bow is genuinely unsavable");

        CompoundTag chunk = sink.encodeChunk(List.of(mob), new ChunkPos(0, 0), registries, false);

        assertNotNull(chunk, "the mob is saved");
        // Assert on the SAVED NBT, not just non-null: without the fix entity.save throws out of the codec's
        // getOrThrow for the bad component, so encodeChunk would error rather than return a tag at all. Neither
        // TagValueInput nor EntityEquipment exists on this band; Mob writes equipment as the ArmorItems/HandItems
        // ListTags, mainhand at HandItems index 0, each entry itemStack.save(registryAccess()).
        CompoundTag entityTag = chunk.getList("Entities", Tag.TAG_COMPOUND).getCompound(0);
        CompoundTag savedTag = entityTag.getList("HandItems", Tag.TAG_COMPOUND).getCompound(0);
        ItemStack savedBow = ItemStack.parse(registries, savedTag).orElse(ItemStack.EMPTY);
        assertFalse(savedBow.isEmpty(), "the repaired bow reached the saved NBT, not silently dropped");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), savedBow).error().isEmpty(),
                "the saved bow is savable (repaired)");
        assertSame(bow, mob.getItemBySlot(EquipmentSlot.MAINHAND),
                "the live equipment is restored to the original instance (no lingering mutation)");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), mob.getItemBySlot(EquipmentSlot.MAINHAND))
                .error().isPresent(), "the restored original still carries the bad component");
    }

    @Test
    void aDroppedItemWithBadComponentSavesRepaired() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.DAMAGE, -1);
        DropItem drop = new DropItem();
        drop.setItem(sword);
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), sword).error().isPresent(),
                "precondition: the carried item is genuinely unsavable");

        CompoundTag chunk = sink.encodeChunk(List.of(drop), new ChunkPos(0, 0), registries, false);

        assertNotNull(chunk, "the item entity is saved");
        CompoundTag entityTag = chunk.getList("Entities", Tag.TAG_COMPOUND).getCompound(0);
        ItemStack savedItem = ItemStack.parse(registries, entityTag.getCompound("Item")).orElse(ItemStack.EMPTY);
        assertFalse(savedItem.isEmpty(), "the repaired carried item reached the saved NBT, not silently dropped");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), savedItem).error().isEmpty(),
                "the saved item is savable (repaired)");
        assertSame(sword, drop.getItem(), "the live carried item is restored");
    }

    private static final class EquipMob extends Mob {
        private EquipMob() {
            super(EntityType.PIG, HeadlessLevel.get());
        }

        @Override
        public HumanoidArm getMainArm() {
            return HumanoidArm.RIGHT;
        }

        @Override
        public boolean hurt(DamageSource source, float amount) {
            return false;
        }
    }

    /** A headless ItemEntity double carrying a caller-set stack. */
    private static final class DropItem extends ItemEntity {
        private DropItem() {
            super(EntityType.ITEM, HeadlessLevel.get());
        }
    }
}
