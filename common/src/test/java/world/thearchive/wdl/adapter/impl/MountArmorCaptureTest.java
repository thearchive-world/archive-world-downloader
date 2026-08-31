// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
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
        TestRegistries.frozen();
    }

    /** The client holds the real stack on this band, so the assertion is byte equality, not merely the right item. */
    @Test
    void anArmoredHorseIsWrittenWithTheArmorItsInventoryNeverHeld() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        ItemStack armor = new ItemStack(Items.DIAMOND_HORSE_ARMOR);
        armor.setHoverName(Component.literal("Old Faithful"));
        Horse horse = armoredHorse(armor);

        CompoundTag saved = onlyEntityOf(sink.encodeChunk(List.of(horse), new ChunkPos(0, 0), registries, false));

        assertTrue(saved.contains("ArmorItem", Tag.TAG_COMPOUND),
                "a horse the client sees armored must reach disk carrying the key vanilla reads its armor from");
        assertEquals(armor.save(new CompoundTag()), saved.getCompound("ArmorItem"),
                "the client holds the real stack here, so nothing it carries may be dropped on the way to disk");
    }

    @Test
    void aBareHorseIsWrittenWithoutArmor() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Horse horse = new Horse(EntityType.HORSE, HeadlessLevel.get());
        assertTrue(horse.getArmor().isEmpty(), "precondition: the client sees this horse wearing nothing");

        CompoundTag saved = onlyEntityOf(sink.encodeChunk(List.of(horse), new ChunkPos(0, 0), registries, false));

        assertFalse(saved.contains("ArmorItem"), "a bare horse must not be given armor it never wore");
    }

    @Test
    void aCarpetedLlamaIsWrittenWithThePlainCarpetOfItsSyncedColor() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Llama llama = carpetedLlama(DyeColor.RED);

        CompoundTag saved = onlyEntityOf(sink.encodeChunk(List.of(llama), new ChunkPos(0, 0), registries, false));

        assertTrue(saved.contains("DecorItem", Tag.TAG_COMPOUND),
                "a llama the client sees carpeted must reach disk carrying the key vanilla reads its carpet from");
        ItemStack loaded = ItemStack.of(saved.getCompound("DecorItem"));
        assertTrue(loaded.is(Items.RED_CARPET), "the color is the one thing the client is told, so it must be right");
        assertEquals(1, loaded.getCount(), "one carpet, the stack a llama wears");
        assertTrue(loaded.getTag() == null,
                "a plain carpet: the synced color is all the write has, so nothing more may be invented");
    }

    @Test
    void aBareLlamaIsWrittenWithoutAnyCarpet() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Llama llama = new Llama(EntityType.LLAMA, HeadlessLevel.get());
        assertTrue(llama.getSwag() == null, "precondition: the client sees this llama wearing nothing");

        CompoundTag saved = onlyEntityOf(sink.encodeChunk(List.of(llama), new ChunkPos(0, 0), registries, false));

        assertFalse(saved.contains("DecorItem"), "a bare llama must not be given a carpet it never wore");
    }

    /** Checked through the registry name each color names itself, so a transposition in the table cannot pass. */
    @Test
    void everyDyeColorWritesTheCarpetThatColorNames() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        for (DyeColor color : DyeColor.values()) {
            CompoundTag saved = onlyEntityOf(
                    sink.encodeChunk(List.of(carpetedLlama(color)), new ChunkPos(0, 0), registries, false));

            assertEquals("minecraft:" + color.getName() + "_carpet", saved.getCompound("DecorItem").getString("id"),
                    "the carpet written for " + color.getName() + " must be that color's own carpet");
        }
    }

    @Test
    void aTraderLlamaIsWrittenCarpetedToo() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        TraderLlama llama = new TraderLlama(EntityType.TRADER_LLAMA, HeadlessLevel.get());
        setSwag(llama, DyeColor.CYAN);

        CompoundTag saved = onlyEntityOf(sink.encodeChunk(List.of(llama), new ChunkPos(0, 0), registries, false));

        assertEquals("minecraft:cyan_carpet", saved.getCompound("DecorItem").getString("id"),
                "a trader llama inherits the carpet path whole");
    }

    /**
     * A mount riding something else reaches disk only as a nested compound, because the entities path refuses a
     * passenger its own entry. A plain minecart picks up a parked mount with no size gate, which is the ordinary way
     * this state arises in play.
     */
    @Test
    void anArmoredMountNestedUnderItsVehicleIsWrittenArmoredToo() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Minecart cart = new Minecart(EntityType.MINECART, HeadlessLevel.get());
        Horse horse = armoredHorse(new ItemStack(Items.IRON_HORSE_ARMOR));
        horse.startRiding(cart, true);
        assertTrue(horse.isPassenger() && cart.isVehicle(), "precondition: the mount rides the cart");

        CompoundTag savedCart = onlyEntityOf(
                sink.encodeChunk(List.of(cart, horse), new ChunkPos(0, 0), registries, false));

        assertFalse(savedCart.contains("ArmorItem"), "the vehicle itself wears nothing");
        CompoundTag savedHorse = savedCart.getList("Passengers", Tag.TAG_COMPOUND).getCompound(0);
        assertTrue(savedHorse.contains("ArmorItem", Tag.TAG_COMPOUND),
                "an armored mount nested under its vehicle must be written armored, not skipped for being nested");
    }

    /**
     * The mount a player is sitting on is refused by the entities path and reaches disk only through the second
     * serialize, so the write has to be on both paths or the most ordinary case of all archives bare.
     */
    @Test
    void aCarpetedMountIsWrittenCarpetedOnTheRootVehiclePath() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();

        CompoundTag saved = sink.captureRootVehicle(carpetedLlama(DyeColor.PURPLE), registries, false);

        assertNotNull(saved, "precondition: the mount is serialized at all");
        assertTrue(saved.contains("DecorItem", Tag.TAG_COMPOUND),
                "the ridden-mount path must write slot 1 too, not only the entities path");
    }

    /**
     * Vanilla drops any passenger whose own save it refuses, so the saved list is shorter than the live one and the two
     * cannot be indexed in lockstep. Both mounts are armored, and differently, so a stamp walking either list by
     * position writes the wrong armor rather than none.
     */
    @Test
    void anArmoredMountIsFoundByUuidWhenAnEarlierPassengerIsDroppedFromTheSavedList() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Minecart cart = new Minecart(EntityType.MINECART, HeadlessLevel.get());
        Horse refused = armoredHorse(new ItemStack(Items.GOLDEN_HORSE_ARMOR));
        refused.startRiding(cart, true);
        Horse horse = armoredHorse(new ItemStack(Items.IRON_HORSE_ARMOR));
        horse.startRiding(cart, true);
        refused.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        assertEquals(2, cart.getPassengers().size(), "precondition: the live list still holds both passengers");

        CompoundTag savedCart = onlyEntityOf(
                sink.encodeChunk(List.of(cart, refused, horse), new ChunkPos(0, 0), registries, false));

        ListTag passengers = savedCart.getList("Passengers", Tag.TAG_COMPOUND);
        assertEquals(1, passengers.size(), "precondition: the refused passenger is dropped from the saved list");
        assertEquals("minecraft:iron_horse_armor",
                passengers.getCompound(0).getCompound("ArmorItem").getString("id"),
                "the armored mount must be found by its UUID, not by its position among the live passengers");
    }

    @Test
    void aSlotOneItemAlreadyOnTheTagIsLeftByteForByteAlone() {
        ItemStack named = new ItemStack(Items.GOLDEN_HORSE_ARMOR);
        named.setHoverName(Component.literal("Old Faithful"));
        Tag original = named.save(new CompoundTag());
        CompoundTag saved = new CompoundTag();
        saved.put("ArmorItem", original.copy());

        CompoundTag worn = new CompoundTag();
        worn.put("ArmorItem", new ItemStack(Items.IRON_HORSE_ARMOR).save(new CompoundTag()));
        EntitySinkImpl.applyMountArmor(saved, worn);

        assertEquals(original, saved.get("ArmorItem"),
                "a stack that did reach slot 1 keeps everything it carries, not merely its item");
    }

    @Test
    void anEmptyPatchWritesNothingAtAll() {
        CompoundTag saved = new CompoundTag();

        EntitySinkImpl.applyMountArmor(saved, new CompoundTag());

        assertTrue(saved.isEmpty(), "an empty patch authorizes no write at all");
    }

    private static CompoundTag onlyEntityOf(@Nullable CompoundTag chunk) {
        assertNotNull(chunk, "precondition: the entity group is written at all");
        ListTag entities = chunk.getList("Entities", Tag.TAG_COMPOUND);
        assertEquals(1, entities.size(), "precondition: exactly one entity reaches the chunk's entity list");
        return entities.getCompound(0);
    }

    /** A real horse in the exact state a client holds: the chest equipment slot set, inventory slot 1 still empty. */
    private static Horse armoredHorse(ItemStack armor) {
        Horse horse = new Horse(EntityType.HORSE, HeadlessLevel.get());
        horse.setItemSlot(EquipmentSlot.CHEST, armor);
        assertFalse(horse.getArmor().isEmpty(), "fixture: the client must see this horse wearing armor");
        assertTrue(horse.getSlot(401).get().isEmpty(),
                "fixture: slot 1 must stay empty, or vanilla writes the key itself and the test proves nothing");
        return horse;
    }

    /** A real llama in the exact state a client holds: the synced dye color set, inventory slot 1 still empty. */
    private static Llama carpetedLlama(DyeColor color) {
        Llama llama = new Llama(EntityType.LLAMA, HeadlessLevel.get());
        setSwag(llama, color);
        return llama;
    }

    private static void setSwag(Llama llama, DyeColor color) {
        llama.getEntityData().set(swagId(), color.getId());
        assertEquals(color, llama.getSwag(), "fixture: the client must see this llama wearing that color");
        assertTrue(llama.getSlot(401).get().isEmpty(),
                "fixture: slot 1 must stay empty, or vanilla writes the key itself and the test proves nothing");
    }

    /**
     * The synced carpet-color accessor, reached by reflection because it is private and every public route to it runs
     * through inventory slot 1. The same shape {@code SaddleCaptureTest} uses to reach the saddled flag.
     */
    private static EntityDataAccessor<Integer> swagId() {
        try {
            Field field = Llama.class.getDeclaredField("DATA_SWAG_ID");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            EntityDataAccessor<Integer> swag = (EntityDataAccessor<Integer>) field.get(null);
            return swag;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not reach the synced carpet color", e);
        }
    }
}
