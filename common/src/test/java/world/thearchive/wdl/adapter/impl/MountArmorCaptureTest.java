// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecartEmpty;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityLlama;
import net.minecraft.entity.passive.HorseArmorType;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.util.math.ChunkPos;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.EntitySink;
import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the mount slot-1 write: a horse's armor and a llama's carpet. Both are real stacks in the
 * mount's own inventory slot 1, a container the server never sends, so vanilla's writer finds the slot empty and both
 * mounts archive bare.
 *
 * <p>Every fixture leaves that slot EMPTY and reaches the synced source instead, and that is the whole point: a mount
 * armored or carpeted the ordinary way carries the key from vanilla's own writer, so a slot-1 fixture would pass with
 * this class's production code deleted. Live-entity tests drive {@code encodeChunk} and {@code captureRootVehicle}, so
 * removing either call site in {@link EntitySinkImpl} reddens one of them; the tag-level tests pin the on-disk
 * contract.
 */
class MountArmorCaptureTest {
    private final EntitySink sink = new EntitySinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /** The capture reads only the synced tier, so a named armor archives plain: the accepted residual, pinned here. */
    @Test
    void anArmoredHorseIsWrittenWithThePlainArmorOfItsSyncedTier() {
        ItemStack armor = new ItemStack(Items.DIAMOND_HORSE_ARMOR);
        armor.setStackDisplayName("Old Faithful");
        EntityHorse horse = armoredHorse(armor);

        NBTTagCompound saved = onlyEntityOf(
                sink.encodeChunk(ImmutableList.<Entity>of(horse), new ChunkPos(0, 0), false));

        assertTrue(saved.hasKey("ArmorItem", 10),
                "a horse the client sees armored must reach disk carrying the key vanilla reads its armor from");
        ItemStack loaded = new ItemStack(saved.getCompoundTag("ArmorItem"));
        assertTrue(loaded.getItem() == Items.DIAMOND_HORSE_ARMOR,
                "the tier names the item exactly, this band shipping one armor per tier and no leather one");
        assertEquals(1, loaded.getCount(), "one armor, the stack a horse wears");
        assertFalse(loaded.hasTagCompound(),
                "a plain armor: the synced tier is all the write has, so an anvil name cannot survive it");
    }

    @Test
    void aBareHorseIsWrittenWithoutArmor() {
        EntityHorse horse = new EntityHorse(HeadlessLevel.get());
        assertEquals(HorseArmorType.NONE, horse.getHorseArmorType(),
                "precondition: the client sees this horse wearing nothing");

        NBTTagCompound saved = onlyEntityOf(
                sink.encodeChunk(ImmutableList.<Entity>of(horse), new ChunkPos(0, 0), false));

        assertFalse(saved.hasKey("ArmorItem"), "a bare horse must not be given armor it never wore");
    }

    @Test
    void aCarpetedLlamaIsWrittenWithThePlainCarpetOfItsSyncedColor() {
        EntityLlama llama = carpetedLlama(EnumDyeColor.RED);

        NBTTagCompound saved = onlyEntityOf(
                sink.encodeChunk(ImmutableList.<Entity>of(llama), new ChunkPos(0, 0), false));

        assertTrue(saved.hasKey("DecorItem", 10),
                "a llama the client sees carpeted must reach disk carrying the key vanilla reads its carpet from");
        NBTTagCompound decor = saved.getCompoundTag("DecorItem");
        assertEquals("minecraft:carpet", decor.getString("id"),
                "this band has one carpet item, the color riding in its damage value");
        assertEquals(EnumDyeColor.RED.getMetadata(), decor.getShort("Damage"),
                "the color is the one thing the client is told, so it must be right");
        ItemStack loaded = new ItemStack(decor);
        assertEquals(1, loaded.getCount(), "one carpet, the stack a llama wears");
        assertFalse(loaded.hasTagCompound(),
                "a plain carpet: the synced color is all the write has, so nothing more may be invented");
    }

    @Test
    void aBareLlamaIsWrittenWithoutAnyCarpet() {
        EntityLlama llama = new EntityLlama(HeadlessLevel.get());
        assertTrue(llama.getColor() == null, "precondition: the client sees this llama wearing nothing");

        NBTTagCompound saved = onlyEntityOf(
                sink.encodeChunk(ImmutableList.<Entity>of(llama), new ChunkPos(0, 0), false));

        assertFalse(saved.hasKey("DecorItem"), "a bare llama must not be given a carpet it never wore");
    }

    /**
     * This band needs no reverse table, the synced value being the carpet's own damage value, so what is checked here
     * is that all sixteen colors round-trip to sixteen distinct damage values rather than collapsing.
     */
    @Test
    void everyDyeColorWritesTheCarpetOfItsOwnDamageValue() {
        for (EnumDyeColor color : EnumDyeColor.values()) {
            NBTTagCompound saved = onlyEntityOf(
                    sink.encodeChunk(ImmutableList.<Entity>of(carpetedLlama(color)), new ChunkPos(0, 0), false));

            NBTTagCompound decor = saved.getCompoundTag("DecorItem");
            assertEquals("minecraft:carpet", decor.getString("id"), "every color writes the one carpet item");
            assertEquals(color.getMetadata(), decor.getShort("Damage"),
                    "the carpet written for " + color.getName() + " must carry that color's own damage value");
        }
    }

    /**
     * A mount riding something else reaches disk only as a nested compound, because the entities path refuses a
     * passenger its own entry. A plain minecart picks up a parked mount with no size gate, which is the ordinary way
     * this state arises in play.
     */
    @Test
    void anArmoredMountNestedUnderItsVehicleIsWrittenArmoredToo() {
        EntityMinecartEmpty cart = new EntityMinecartEmpty(HeadlessLevel.get());
        EntityHorse horse = armoredHorse(new ItemStack(Items.IRON_HORSE_ARMOR));
        horse.startRiding(cart, true);
        assertTrue(horse.isRiding() && cart.isBeingRidden(), "precondition: the mount rides the cart");

        NBTTagCompound savedCart = onlyEntityOf(
                sink.encodeChunk(ImmutableList.<Entity>of(cart, horse), new ChunkPos(0, 0), false));

        assertFalse(savedCart.hasKey("ArmorItem"), "the vehicle itself wears nothing");
        NBTTagCompound savedHorse = savedCart.getTagList("Passengers", 10).getCompoundTagAt(0);
        assertTrue(savedHorse.hasKey("ArmorItem", 10),
                "an armored mount nested under its vehicle must be written armored, not skipped for being nested");
    }

    /**
     * The mount a player is sitting on is refused by the entities path and reaches disk only through the second
     * serialize, so the write has to be on both paths or the most ordinary case of all archives bare.
     */
    @Test
    void aCarpetedMountIsWrittenCarpetedOnTheRootVehiclePath() {
        NBTTagCompound saved = sink.captureRootVehicle(carpetedLlama(EnumDyeColor.PURPLE), false);

        assertNotNull(saved, "precondition: the mount is serialized at all");
        assertTrue(saved.hasKey("DecorItem", 10),
                "the ridden-mount path must write slot 1 too, not only the entities path");
    }

    /**
     * Vanilla drops any passenger whose own save it refuses, so the saved list is shorter than the live one and the two
     * cannot be indexed in lockstep. Both mounts are armored, and differently, so a stamp walking either list by
     * position writes the wrong armor rather than none.
     */
    @Test
    void anArmoredMountIsFoundByUuidWhenAnEarlierPassengerIsDroppedFromTheSavedList() {
        EntityMinecartEmpty cart = new EntityMinecartEmpty(HeadlessLevel.get());
        EntityHorse refused = armoredHorse(new ItemStack(Items.GOLDEN_HORSE_ARMOR));
        refused.startRiding(cart, true);
        EntityHorse horse = armoredHorse(new ItemStack(Items.IRON_HORSE_ARMOR));
        horse.startRiding(cart, true);
        refused.setDead();
        assertEquals(2, cart.getPassengers().size(), "precondition: the live list still holds both passengers");

        NBTTagCompound savedCart = onlyEntityOf(
                sink.encodeChunk(ImmutableList.<Entity>of(cart, refused, horse), new ChunkPos(0, 0), false));

        NBTTagList passengers = savedCart.getTagList("Passengers", 10);
        assertEquals(1, passengers.tagCount(), "precondition: the refused passenger is dropped from the saved list");
        assertEquals("minecraft:iron_horse_armor",
                passengers.getCompoundTagAt(0).getCompoundTag("ArmorItem").getString("id"),
                "the armored mount must be found by its UUID, not by its position among the live passengers");
    }

    @Test
    void aSlotOneItemAlreadyOnTheTagIsLeftByteForByteAlone() {
        ItemStack named = new ItemStack(Items.GOLDEN_HORSE_ARMOR);
        named.setStackDisplayName("Old Faithful");
        NBTBase original = named.writeToNBT(new NBTTagCompound());
        NBTTagCompound saved = new NBTTagCompound();
        saved.setTag("ArmorItem", original.copy());

        NBTTagCompound worn = new NBTTagCompound();
        worn.setTag("ArmorItem", new ItemStack(Items.IRON_HORSE_ARMOR).writeToNBT(new NBTTagCompound()));
        EntitySinkImpl.applyMountArmor(saved, worn);

        assertEquals(original, saved.getTag("ArmorItem"),
                "a stack that did reach slot 1 keeps everything it carries, not merely its item");
    }

    @Test
    void anEmptyPatchWritesNothingAtAll() {
        NBTTagCompound saved = new NBTTagCompound();

        EntitySinkImpl.applyMountArmor(saved, new NBTTagCompound());

        assertTrue(saved.isEmpty(), "an empty patch authorizes no write at all");
    }

    private static NBTTagCompound onlyEntityOf(@Nullable NBTTagCompound chunk) {
        assertNotNull(chunk, "precondition: the entity group is written at all");
        NBTTagList entities = chunk.getTagList("Entities", 10);
        assertEquals(1, entities.tagCount(), "precondition: exactly one entity reaches the chunk's entity list");
        return entities.getCompoundTagAt(0);
    }

    /** A real horse in the exact state a client holds: the synced armor tier set, inventory slot 1 still empty. */
    private static EntityHorse armoredHorse(ItemStack armor) {
        EntityHorse horse = new EntityHorse(HeadlessLevel.get());
        horse.setHorseArmorStack(armor); // writes only the synced tier and stack, never the inventory
        assertFalse(HorseArmorType.NONE == horse.getHorseArmorType(),
                "fixture: the client must see this horse wearing armor");
        assertTrue(mountSlotOneIsEmpty(horse),
                "fixture: slot 1 must stay empty, or vanilla writes the key itself and the test proves nothing");
        return horse;
    }

    /** A real llama in the exact state a client holds: the synced dye color set, inventory slot 1 still empty. */
    private static EntityLlama carpetedLlama(EnumDyeColor color) {
        EntityLlama llama = new EntityLlama(HeadlessLevel.get());
        llama.getDataManager().set(colorId(), color.getMetadata());
        assertEquals(color, llama.getColor(), "fixture: the client must see this llama wearing that color");
        assertTrue(mountSlotOneIsEmpty(llama),
                "fixture: slot 1 must stay empty, or vanilla writes the key itself and the test proves nothing");
        return llama;
    }

    /**
     * Whether the mount's own inventory slot 1 is empty. This band exposes no public reader for that container, so the
     * protected field is reached directly; the assertion it backs is what stops the fixture from letting vanilla's own
     * writer emit the key.
     */
    private static boolean mountSlotOneIsEmpty(AbstractHorse mount) {
        try {
            Field field = AbstractHorse.class.getDeclaredField("horseChest");
            field.setAccessible(true);
            return ((IInventory) field.get(mount)).getStackInSlot(1).isEmpty();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not reach the mount inventory", e);
        }
    }

    /**
     * The synced carpet-color accessor, reached by reflection because it is private and every public route to it runs
     * through inventory slot 1. The same shape {@code SaddleCaptureTest} uses to reach the mount inventory.
     */
    private static DataParameter<Integer> colorId() {
        try {
            Field field = EntityLlama.class.getDeclaredField("DATA_COLOR_ID");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            DataParameter<Integer> color = (DataParameter<Integer>) field.get(null);
            return color;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not reach the synced carpet color", e);
        }
    }
}
