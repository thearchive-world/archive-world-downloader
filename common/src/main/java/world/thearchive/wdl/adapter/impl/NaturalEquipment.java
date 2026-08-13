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
 * The 1.21.11 natural-spawn equipment table and the pure "was this equipped by a loot pickup" inference.
 *
 * <p>Vanilla sets the server-only {@code PersistenceRequired} flag when a mob equips a picked-up item
 * ({@code Mob.setItemSlotAndDropWhenKilled}); the client never receives that flag. An equipped item that no natural
 * spawn of the mob's type could carry in that slot therefore proves the pickup ran, hence proves the mob was
 * persistent. The test is scoped to the mainhand and armor slots, the only slots the generic pickup path fills; the
 * offhand is excluded because a piglin holds a bartering gold ingot there without persistence. The enumeration is
 * verified against decompiled 1.21.11 and is band-specific.
 */
final class NaturalEquipment {
    private NaturalEquipment() {}

    /** The slots the generic pickup path equips (and thus can set persistence for); the offhand is excluded. */
    static final List<EquipmentSlot> PICKUP_SLOTS = List.of(
            EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    /** Which armor family a type may naturally wear. */
    private enum ArmorKind {
        BASE, GOLDEN, NONE
    }

    private record Profile(Set<Item> mainhand, ArmorKind armor) {}

    /**
     * Non-armor head items a spawn may legitimately wear: the Halloween pumpkins (zombie/skeleton) and the raid or
     * patrol captain ominous banner. The ominous banner is a plain {@link Items#WHITE_BANNER} carrying a pattern
     * component, and a component-sanitizing server strips that pattern to the bare item, so the match is on the item
     * type. Exempting these for every type stays free of false positives: no despawn-capable mob acquires them in the
     * head slot through a persistence-setting pickup, and a mob that did pick up a pumpkin is the accepted
     * invisible-ambiguous case.
     */
    private static final Set<Item> NATURAL_HEAD_EXTRAS = Set.of(
            Items.CARVED_PUMPKIN, Items.JACK_O_LANTERN, Items.WHITE_BANNER);

    private static final Map<EquipmentSlot, Set<Item>> BASE_ARMOR = Map.of(
            EquipmentSlot.HEAD, Set.of(Items.LEATHER_HELMET, Items.GOLDEN_HELMET,
                    Items.CHAINMAIL_HELMET, Items.IRON_HELMET, Items.DIAMOND_HELMET),
            EquipmentSlot.CHEST, Set.of(Items.LEATHER_CHESTPLATE, Items.GOLDEN_CHESTPLATE,
                    Items.CHAINMAIL_CHESTPLATE, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE),
            EquipmentSlot.LEGS, Set.of(Items.LEATHER_LEGGINGS, Items.GOLDEN_LEGGINGS,
                    Items.CHAINMAIL_LEGGINGS, Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS),
            EquipmentSlot.FEET, Set.of(Items.LEATHER_BOOTS, Items.GOLDEN_BOOTS,
                    Items.CHAINMAIL_BOOTS, Items.IRON_BOOTS, Items.DIAMOND_BOOTS));

    private static final Map<EquipmentSlot, Set<Item>> GOLDEN_ARMOR = Map.of(
            EquipmentSlot.HEAD, Set.of(Items.GOLDEN_HELMET),
            EquipmentSlot.CHEST, Set.of(Items.GOLDEN_CHESTPLATE),
            EquipmentSlot.LEGS, Set.of(Items.GOLDEN_LEGGINGS),
            EquipmentSlot.FEET, Set.of(Items.GOLDEN_BOOTS));

    private static final Map<EntityType<?>, Profile> PROFILES = Map.ofEntries(
            Map.entry(EntityType.ZOMBIE,
                    new Profile(Set.of(Items.IRON_SWORD, Items.IRON_SHOVEL), ArmorKind.BASE)),
            Map.entry(EntityType.HUSK,
                    new Profile(Set.of(Items.IRON_SWORD, Items.IRON_SHOVEL), ArmorKind.BASE)),
            Map.entry(EntityType.ZOMBIE_VILLAGER,
                    new Profile(Set.of(Items.IRON_SWORD, Items.IRON_SHOVEL), ArmorKind.BASE)),
            Map.entry(EntityType.DROWNED,
                    new Profile(Set.of(Items.TRIDENT, Items.FISHING_ROD), ArmorKind.NONE)),
            Map.entry(EntityType.SKELETON, new Profile(Set.of(Items.BOW), ArmorKind.BASE)),
            Map.entry(EntityType.STRAY, new Profile(Set.of(Items.BOW), ArmorKind.BASE)),
            Map.entry(EntityType.BOGGED, new Profile(Set.of(Items.BOW), ArmorKind.BASE)),
            Map.entry(EntityType.WITHER_SKELETON, new Profile(Set.of(Items.STONE_SWORD), ArmorKind.NONE)),
            Map.entry(EntityType.PIGLIN,
                    new Profile(Set.of(Items.CROSSBOW, Items.GOLDEN_SWORD), ArmorKind.GOLDEN)),
            Map.entry(EntityType.ZOMBIFIED_PIGLIN,
                    new Profile(Set.of(Items.GOLDEN_SWORD), ArmorKind.NONE)),
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
            return true; // the offhand is outside the inference: a bartering gold ingot sits there unpersisted
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
