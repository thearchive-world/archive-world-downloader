// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The 1.10.2 natural-spawn equipment table, reduced to the mobs this band can spawn (the drowned and its trident, the
 * 1.14 raid ominous banner, and the 1.11 vindicator and vex are all dropped) and split back out again where one
 * registration covers several of them, and the pure "was this equipped by a loot pickup" inference.
 *
 * <p>There is no {@code EntityType} type-object before 1.13, so a mob is discriminated by its classic
 * {@code EntityList} registry name ({@link EntityList#getEntityString}) rather than {@code mob.getType()}; that name is
 * a bare unnamespaced {@code String} at this band, so the profile table is keyed by one.
 *
 * <p>Vanilla sets the server-only {@code PersistenceRequired} flag when a mob equips a picked-up item
 * ({@code EntityLiving.setItemStackToSlot} plus the drop-chance flag); the client never receives that flag. An equipped
 * item that no natural spawn of the mob's type could carry in that slot therefore proves the pickup ran, hence proves
 * the mob was persistent. The test is scoped to the mainhand and armor slots, the only slots the generic pickup path
 * fills; the offhand is excluded because a mob can hold an unpersisted item there.
 */
final class NaturalEquipment {
    private NaturalEquipment() {}

    /** The slots the generic pickup path equips (and thus can set persistence for); the offhand is excluded. */
    static final List<EntityEquipmentSlot> PICKUP_SLOTS = ImmutableList.of(EntityEquipmentSlot.MAINHAND,
            EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST, EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET);

    /** Which armor family a type may naturally wear. */
    private enum ArmorKind {
        BASE, NONE
    }

    private static final class Profile {
        private final Set<Item> mainhand;
        private final ArmorKind armor;

        Profile(Set<Item> mainhand, ArmorKind armor) {
            this.mainhand = mainhand;
            this.armor = armor;
        }

        Set<Item> mainhand() {
            return mainhand;
        }

        ArmorKind armor() {
            return armor;
        }
    }

    /**
     * Non-armor head items a spawn may legitimately wear: the Halloween pumpkins (zombie/skeleton). Exempting these for
     * every type stays free of false positives: no despawn-capable mob acquires them in the head slot through a
     * persistence-setting pickup, and a mob that did pick up a pumpkin is the accepted invisible-ambiguous case.
     */
    private static final Set<Item> NATURAL_HEAD_EXTRAS = ImmutableSet.of(
            Item.getItemFromBlock(Blocks.PUMPKIN), Item.getItemFromBlock(Blocks.LIT_PUMPKIN));

    private static final Map<EntityEquipmentSlot, Set<Item>> BASE_ARMOR = ImmutableMap.of(
            EntityEquipmentSlot.HEAD, ImmutableSet.<Item>of(Items.LEATHER_HELMET, Items.GOLDEN_HELMET,
                    Items.CHAINMAIL_HELMET, Items.IRON_HELMET, Items.DIAMOND_HELMET),
            EntityEquipmentSlot.CHEST, ImmutableSet.<Item>of(Items.LEATHER_CHESTPLATE, Items.GOLDEN_CHESTPLATE,
                    Items.CHAINMAIL_CHESTPLATE, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE),
            EntityEquipmentSlot.LEGS, ImmutableSet.<Item>of(Items.LEATHER_LEGGINGS, Items.GOLDEN_LEGGINGS,
                    Items.CHAINMAIL_LEGGINGS, Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS),
            EntityEquipmentSlot.FEET, ImmutableSet.<Item>of(Items.LEATHER_BOOTS, Items.GOLDEN_BOOTS,
                    Items.CHAINMAIL_BOOTS, Items.IRON_BOOTS, Items.DIAMOND_BOOTS));

    /**
     * The five profile keys. {@code Zombie}, {@code Skeleton} and {@code PigZombie} are what {@code EntityList}
     * registers those classes under here; {@code WitherSkeleton} and {@code Stray} are not registrations at this band
     * at all, but the two {@code SkeletonType} variants {@link #profileKey} splits back out of {@code Skeleton}, and no
     * registration spells either, so neither can collide with a real name.
     */
    private static final String ZOMBIE = "Zombie";
    private static final String SKELETON = "Skeleton";
    private static final String WITHER_SKELETON = "WitherSkeleton";
    private static final String STRAY = "Stray";
    private static final String PIG_ZOMBIE = "PigZombie";

    /**
     * Keyed by {@link #profileKey}, not by registry name alone. The husk and the zombie villager are one {@code Zombie}
     * registration at this band and share the zombie's profile, so that collapse is lossless and needs no
     * discriminator. The wither skeleton and the stray are one {@code Skeleton} registration, and that collapse is not:
     * the wither skeleton differs from the other two in both the mainhand pool and the armor family, so its variant is
     * split back out under the name the band above gives it.
     */
    private static final Map<String, Profile> PROFILES = ImmutableMap.<String, Profile>builder()
            .put(ZOMBIE, new Profile(ImmutableSet.of(Items.IRON_SWORD, Items.IRON_SHOVEL), ArmorKind.BASE))
            .put(SKELETON, new Profile(ImmutableSet.of(Items.BOW), ArmorKind.BASE))
            .put(STRAY, new Profile(ImmutableSet.of(Items.BOW), ArmorKind.BASE))
            .put(WITHER_SKELETON, new Profile(ImmutableSet.of(Items.STONE_SWORD), ArmorKind.NONE))
            .put(PIG_ZOMBIE, new Profile(ImmutableSet.of(Items.GOLDEN_SWORD), ArmorKind.NONE))
            .build();

    static boolean isGearedType(String type) {
        return PROFILES.containsKey(type);
    }

    static boolean isNaturalFor(String type, EntityEquipmentSlot slot, ItemStack item) {
        Profile profile = PROFILES.get(type);
        if (profile == null) {
            return true; // an unknown type is never inferred against
        }
        if (slot == EntityEquipmentSlot.OFFHAND) {
            return true; // the offhand is outside the inference: a bartering gold ingot sits there unpersisted
        }
        if (slot == EntityEquipmentSlot.MAINHAND) {
            return profile.mainhand().contains(item.getItem());
        }
        if (slot == EntityEquipmentSlot.HEAD && NATURAL_HEAD_EXTRAS.contains(item.getItem())) {
            return true;
        }
        switch (profile.armor()) {
            case BASE:
                return BASE_ARMOR.getOrDefault(slot, ImmutableSet.of()).contains(item.getItem());
            case NONE:
                return false;
            default:
                throw new IncompatibleClassChangeError();
        }
    }

    /**
     * The profile key for a live mob: the name {@code EntityList} registers its class under, except for the skeleton,
     * whose three 1.11 ids are the single {@code Skeleton} registration here discriminated by {@code SkeletonType}. The
     * two split-out variants are keyed under the names {@code SkeletonType} itself carries, which are also the names
     * {@code EntityList} registers them under at the band above. Null for a class {@code EntityList} maps no name for,
     * which for an {@code EntityLiving} is a test double subclassing a registered type.
     *
     * <p>The discriminator hangs off the resolved name rather than off {@code instanceof}, because the name is an
     * exact-runtime-class lookup and a modded subclass of the skeleton therefore resolves to its own name or to none.
     * Reading it as a skeleton would judge it against vanilla's bow profile, which is the widening the bands above
     * cannot make. That exactness is also what makes the cast safe: {@code addMapping} refuses a duplicate name, so
     * {@code Skeleton} maps to {@code EntitySkeleton} and to nothing else.
     */
    private static @Nullable String profileKey(EntityLiving mob) {
        String name = EntityList.getEntityString(mob);
        if (!SKELETON.equals(name)) {
            return name;
        }
        switch (((EntitySkeleton) mob).getSkeletonType()) {
            case NORMAL:
                return SKELETON;
            case WITHER:
                return WITHER_SKELETON;
            case STRAY:
                return STRAY;
            default:
                throw new IncompatibleClassChangeError();
        }
    }

    static boolean wasLootEquipped(EntityLiving mob) {
        String type = profileKey(mob);
        if (type == null || !isGearedType(type)) {
            return false;
        }
        for (EntityEquipmentSlot slot : PICKUP_SLOTS) {
            // An unequipped slot is null at this band, ItemStack.isEmpty and the EMPTY singleton both arriving at
            // 1.11, so the emptiness test is the null test vanilla's own equipment walks make here.
            ItemStack item = mob.getItemStackFromSlot(slot);
            if (item != null && !isNaturalFor(type, slot, item)) {
                return true;
            }
        }
        return false;
    }
}
