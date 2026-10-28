// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The inference is scoped to the mainhand and armor slots; the offhand is excluded because a piglin holds a bartering
 * gold ingot there without persistence.
 */
final class NaturalEquipment {
    private NaturalEquipment() {}

    static final List<EquipmentSlot> PICKUP_SLOTS = List.of(
            EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private enum ArmorKind {
        BASE, GOLDEN, NONE
    }

    private record Profile(Set<Item> mainhand, ArmorKind armor) {}

    /**
     * Non-armor head items a spawn may legitimately wear: the Halloween pumpkins (zombie/skeleton) and the raid or
     * patrol captain ominous banner.
     */
    private static final Set<Item> NATURAL_HEAD_EXTRAS = Set.of(
            Items.CARVED_PUMPKIN, Items.JACK_O_LANTERN, Items.WHITE_BANNER);

    private static final Map<EquipmentSlot, Set<Item>> BASE_ARMOR = Map.of(
            EquipmentSlot.HEAD, Set.of(Items.LEATHER_HELMET, Items.COPPER_HELMET, Items.GOLDEN_HELMET,
                    Items.CHAINMAIL_HELMET, Items.IRON_HELMET, Items.DIAMOND_HELMET),
            EquipmentSlot.CHEST, Set.of(Items.LEATHER_CHESTPLATE, Items.COPPER_CHESTPLATE, Items.GOLDEN_CHESTPLATE,
                    Items.CHAINMAIL_CHESTPLATE, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE),
            EquipmentSlot.LEGS, Set.of(Items.LEATHER_LEGGINGS, Items.COPPER_LEGGINGS, Items.GOLDEN_LEGGINGS,
                    Items.CHAINMAIL_LEGGINGS, Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS),
            EquipmentSlot.FEET, Set.of(Items.LEATHER_BOOTS, Items.COPPER_BOOTS, Items.GOLDEN_BOOTS,
                    Items.CHAINMAIL_BOOTS, Items.IRON_BOOTS, Items.DIAMOND_BOOTS));

    private static final Map<EquipmentSlot, Set<Item>> GOLDEN_ARMOR = Map.of(
            EquipmentSlot.HEAD, Set.of(Items.GOLDEN_HELMET),
            EquipmentSlot.CHEST, Set.of(Items.GOLDEN_CHESTPLATE),
            EquipmentSlot.LEGS, Set.of(Items.GOLDEN_LEGGINGS),
            EquipmentSlot.FEET, Set.of(Items.GOLDEN_BOOTS));

    private static final Map<EntityType<?>, Profile> PROFILES = Map.ofEntries(
            Map.entry(EntityType.ZOMBIE,
                    new Profile(Set.of(Items.IRON_SWORD, Items.IRON_SPEAR, Items.IRON_SHOVEL), ArmorKind.BASE)),
            Map.entry(EntityType.HUSK,
                    new Profile(Set.of(Items.IRON_SWORD, Items.IRON_SPEAR, Items.IRON_SHOVEL), ArmorKind.BASE)),
            Map.entry(EntityType.ZOMBIE_VILLAGER,
                    new Profile(Set.of(Items.IRON_SWORD, Items.IRON_SPEAR, Items.IRON_SHOVEL), ArmorKind.BASE)),
            Map.entry(EntityType.DROWNED,
                    new Profile(Set.of(Items.TRIDENT, Items.FISHING_ROD), ArmorKind.NONE)),
            Map.entry(EntityType.SKELETON, new Profile(Set.of(Items.BOW), ArmorKind.BASE)),
            Map.entry(EntityType.STRAY, new Profile(Set.of(Items.BOW), ArmorKind.BASE)),
            Map.entry(EntityType.BOGGED, new Profile(Set.of(Items.BOW), ArmorKind.BASE)),
            Map.entry(EntityType.PARCHED, new Profile(Set.of(Items.BOW), ArmorKind.BASE)),
            Map.entry(EntityType.WITHER_SKELETON, new Profile(Set.of(Items.STONE_SWORD), ArmorKind.NONE)),
            Map.entry(EntityType.PIGLIN,
                    new Profile(Set.of(Items.CROSSBOW, Items.GOLDEN_SWORD, Items.GOLDEN_SPEAR), ArmorKind.GOLDEN)),
            Map.entry(EntityType.ZOMBIFIED_PIGLIN,
                    new Profile(Set.of(Items.GOLDEN_SWORD, Items.GOLDEN_SPEAR, Items.WARPED_FUNGUS_ON_A_STICK),
                            ArmorKind.NONE)),
            Map.entry(EntityType.PIGLIN_BRUTE, new Profile(Set.of(Items.GOLDEN_AXE), ArmorKind.NONE)),
            Map.entry(EntityType.PILLAGER, new Profile(Set.of(Items.CROSSBOW), ArmorKind.NONE)),
            Map.entry(EntityType.VINDICATOR, new Profile(Set.of(Items.IRON_AXE), ArmorKind.NONE)),
            Map.entry(EntityType.VEX, new Profile(Set.of(Items.IRON_SWORD), ArmorKind.NONE)));

    static boolean isGearedType(EntityType<?> type) {
        return PROFILES.containsKey(type);
    }

    static boolean isNaturalFor(EntityType<?> type, EquipmentSlot slot, ItemStack item) {
        Profile profile = PROFILES.get(type);
        if (profile == null) {
            return true; // an unknown type is never inferred against
        }
        if (slot == EquipmentSlot.OFFHAND) {
            return true;
        }
        if (slot == EquipmentSlot.MAINHAND) {
            return profile.mainhand().contains(item.getItem());
        }
        if (slot == EquipmentSlot.HEAD && NATURAL_HEAD_EXTRAS.contains(item.getItem())) {
            return true;
        }
        return switch (profile.armor()) {
            case BASE -> BASE_ARMOR.getOrDefault(slot, Set.of()).contains(item.getItem());
            case GOLDEN -> GOLDEN_ARMOR.getOrDefault(slot, Set.of()).contains(item.getItem());
            case NONE -> false;
        };
    }

    static boolean wasLootEquipped(Mob mob) {
        EntityType<?> type = mob.getType();
        if (!isGearedType(type)) {
            return false;
        }
        for (EquipmentSlot slot : PICKUP_SLOTS) {
            ItemStack item = mob.getItemBySlot(slot);
            if (!item.isEmpty() && !isNaturalFor(type, slot, item)) {
                return true;
            }
        }
        return false;
    }
}
