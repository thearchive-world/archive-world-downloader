// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.EntitySink;
import world.thearchive.wdl.testsupport.BadStacks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the item and equipment degradation this band exhibits. A disk-codec-invalid item component
 * throws the entity save out entirely on this band: {@link CompoundTag#store} ends in {@code getOrThrow()}, so a mob
 * equipped with a bad bow, or an item entity carrying a bad stack, would otherwise abort {@code encodeChunk} with an
 * exception rather than save with the item quietly gone. Both fixtures below are genuinely unsavable: a raw
 * {@code set()} does no validation on write, so an enchantment holder pinned at level 0, outside the codec's [1:255]
 * range, and a {@link DataComponents#DAMAGE} of -1, a {@code NON_NEGATIVE_INT} field, both cross the network codec fine
 * and fail only once the persistent codec encodes them. {@link EntitySinkImpl} repairs the stack up front instead of
 * relying on that throw, so these tests assert on the saved NBT itself, not just that the entity survived: a regression
 * that let the bad stack reach save unrepaired would make {@code encodeChunk} throw, not merely return NBT with the
 * item missing.
 */
class EntitySinkItemDegradationTest {
    private final EntitySink sink = new EntitySinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void aMobWithLevelZeroEnchantedItemSavesRepairedAndTheLiveItemIsRestored() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Holder<Enchantment> power = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.POWER);
        ItemStack bow = new ItemStack(Items.BOW);
        bow.set(DataComponents.ENCHANTMENTS, BadStacks.enchantments(registries, Map.of(power, 0)));
        EquipMob mob = new EquipMob();
        mob.setItemSlot(EquipmentSlot.MAINHAND, bow);
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), bow).error().isPresent(),
                "precondition: the equipped bow is genuinely unsavable");

        CompoundTag chunk = sink.encodeChunk(List.of(mob), new ChunkPos(0, 0), registries, false);

        assertNotNull(chunk, "the mob is saved");
        // Assert on the SAVED NBT, not just non-null: on this band a disk-codec-invalid component throws
        // entity.save out entirely (CompoundTag.store's getOrThrow) rather than writing a partial, so encodeChunk
        // itself is what is red without the fix. Read the saved equipment back and prove the repaired bow survived.
        CompoundTag entityTag = chunk.getListOrEmpty("Entities").getCompoundOrEmpty(0);
        RegistryOps<Tag> ops = BadStacks.ops(registries);
        ItemStack savedBow = entityTag.read("equipment", EntityEquipment.CODEC, ops).orElseGet(EntityEquipment::new)
                .get(EquipmentSlot.MAINHAND);
        assertFalse(savedBow.isEmpty(), "the repaired bow reached the saved NBT, not silently dropped");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), savedBow).error().isEmpty(),
                "the saved bow is savable (repaired)");
        assertSame(bow, mob.getItemBySlot(EquipmentSlot.MAINHAND),
                "the live equipment is restored to the original instance (no lingering mutation)");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), mob.getItemBySlot(EquipmentSlot.MAINHAND))
                .error().isPresent(), "the restored original still carries the level-0 enchant");
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
        CompoundTag entityTag = chunk.getListOrEmpty("Entities").getCompoundOrEmpty(0);
        RegistryOps<Tag> ops = BadStacks.ops(registries);
        ItemStack savedItem = entityTag.read("Item", ItemStack.CODEC, ops).orElse(ItemStack.EMPTY);
        assertFalse(savedItem.isEmpty(), "the repaired carried item reached the saved NBT, not silently dropped");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), savedItem).error().isEmpty(),
                "the saved item is savable (repaired)");
        assertSame(sword, drop.getItem(), "the live carried item is restored");
    }

    /** A headless Mob double; onEquipItem is overridden because the null level would NPE on equip. */
    private static final class EquipMob extends Mob {
        private EquipMob() {
            super(EntityType.PIG, null);
        }

        // The equipment save reads registryAccess off the level, which is null on this headless entity.
        @Override
        public RegistryAccess registryAccess() {
            return TestRegistries.frozen();
        }

        @Override
        public HumanoidArm getMainArm() {
            return HumanoidArm.RIGHT;
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            return false;
        }

        @Override
        public void onEquipItem(EquipmentSlot slot, ItemStack oldItem, ItemStack newItem) {}
    }

    /** A headless ItemEntity double carrying a caller-set stack. */
    private static final class DropItem extends ItemEntity {
        private DropItem() {
            super(EntityType.ITEM, null);
        }

        // The carried item's save reads registryAccess off the level, which is null on this headless entity.
        @Override
        public RegistryAccess registryAccess() {
            return TestRegistries.frozen();
        }
    }
}
