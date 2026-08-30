// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.EntitySink;
import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the horse-family saddle write. Below the 1.21.5 equipment-slot cut the saddle is a real stack
 * in the mount's own inventory slot 0, which the server never sends to the client, so vanilla's writer finds slot 0
 * empty and a saddled horse archives unsaddled. Only the saddled bit of the synced entity-data flags arrives, so the
 * capture writes a plain saddle from it and lets vanilla's load path re-derive the flag.
 *
 * <p>The fixture must set the flag while leaving slot 0 empty, and that is the whole point. Vanilla itself writes
 * {@code SaddleItem} from slot 0, so a horse saddled the ordinary way would carry the key with this class's production
 * code deleted, and every assertion below would pass against nothing. This band's own saddled setter writes only the
 * synced byte and never the inventory, so the fixture uses it and reproduces the client's state exactly, which is the
 * only state that tells the two writers apart.
 *
 * <p>Three live-entity tests drive {@code encodeChunk} end to end and one drives {@code captureRootVehicle}, so
 * removing either call site in {@link EntitySinkImpl} reddens one of them; the two tag-level tests pin the on-disk
 * contract itself.
 */
class SaddleCaptureTest {
    private final EntitySink sink = new EntitySinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    @Test
    void aSaddledMountIsWrittenSaddledThoughItsInventoryIsEmpty() {
        Horse horse = saddledHorse();

        CompoundTag chunk = sink.encodeChunk(ImmutableList.<Entity>of(horse), new ChunkPos(0, 0), false);

        assertNotNull(chunk, "precondition: the horse is written at all");
        CompoundTag saved = chunk.getList("Entities", 10).getCompound(0);
        assertTrue(saved.contains("SaddleItem", 10),
                "a mount the client sees as saddled must reach disk carrying the key vanilla reads its saddle from");
        ItemStack loaded = ItemStack.of(saved.getCompound("SaddleItem"));
        assertTrue(loaded.getItem() == Items.SADDLE,
                "vanilla keeps the stack only when it is a saddle, so anything else is silently dropped on load");
        assertEquals(1, loaded.getCount(), "one saddle, the stack a mount wears");
        assertTrue(loaded.getTag() == null,
                "a plain saddle: the client holds no source for a saddle's components, so none may be invented");
    }

    @Test
    void anUnsaddledMountIsWrittenBare() {
        Horse horse = new Horse(EntityType.HORSE, HeadlessLevel.get());
        assertFalse(horse.isSaddled(), "precondition: the client sees this mount as unsaddled");

        CompoundTag chunk = sink.encodeChunk(ImmutableList.<Entity>of(horse), new ChunkPos(0, 0), false);

        assertNotNull(chunk, "precondition: the horse is written at all");
        CompoundTag saved = chunk.getList("Entities", 10).getCompound(0);
        assertFalse(saved.contains("SaddleItem"),
                "an unsaddled mount must not be given a saddle it never wore");
    }

    /**
     * A mount riding something else reaches disk only as a nested compound, because {@code shouldSaveEntity} refuses a
     * passenger its own entry. A plain minecart picks up a parked mount with no size gate, which is the ordinary way
     * this state arises in play.
     */
    @Test
    void aSaddledMountNestedUnderItsVehicleIsWrittenSaddledToo() {
        Minecart cart = new Minecart(EntityType.MINECART, HeadlessLevel.get());
        Horse horse = saddledHorse();
        horse.startRiding(cart, true);
        assertTrue(horse.isPassenger() && cart.isVehicle(), "precondition: the mount rides the cart");

        CompoundTag chunk = sink.encodeChunk(ImmutableList.<Entity>of(cart, horse), new ChunkPos(0, 0), false);

        assertNotNull(chunk, "precondition: the vehicle group is written at all");
        ListTag entities = chunk.getList("Entities", 10);
        assertEquals(1, entities.size(), "precondition: the passenger has no standalone entry of its own");
        CompoundTag savedCart = entities.getCompound(0);
        assertFalse(savedCart.contains("SaddleItem"), "the vehicle itself wears nothing");
        CompoundTag savedHorse = savedCart.getList("Passengers", 10).getCompound(0);
        assertTrue(savedHorse.contains("SaddleItem", 10),
                "a saddled mount nested under its vehicle must be written saddled, not skipped for being nested");
    }

    /**
     * The mount a player is sitting on is refused by the entities path (vanilla persists it in the player's own
     * RootVehicle record) and reaches disk only through this second serialize, so the saddle has to be written on both
     * paths or the most ordinary case of all, a player riding their saddled horse, archives bare.
     */
    @Test
    void aRiddenSaddledMountIsWrittenSaddledOnTheRootVehiclePath() {
        CompoundTag saved = sink.captureRootVehicle(saddledHorse(), false);

        assertNotNull(saved, "precondition: the ridden mount is serialized at all");
        assertTrue(saved.contains("SaddleItem", 10),
                "the ridden-mount path must write the saddle too, not only the entities path");
    }

    @Test
    void aSaddleAlreadyOnTheTagIsLeftByteForByteAlone() {
        ItemStack named = new ItemStack(Items.SADDLE);
        named.setHoverName(new TextComponent("Old Faithful"));
        net.minecraft.nbt.Tag original = named.save(new CompoundTag());

        CompoundTag saved = new CompoundTag();
        saved.put("SaddleItem", original.copy());
        EntitySinkImpl.applySaddleItem(saved, true);

        assertEquals(original, saved.get("SaddleItem"),
                "a stack that did reach slot 0 keeps everything it carries, not merely its name");
    }

    @Test
    void anUnsaddledFlagWritesNothingAtAll() {
        CompoundTag saved = new CompoundTag();
        EntitySinkImpl.applySaddleItem(saved, false);

        assertFalse(saved.contains("SaddleItem"), "the flag is the only thing that authorizes the write");
    }

    /** A real horse in the exact state a client holds: the synced saddled bit set, inventory slot 0 still empty. */
    private static Horse saddledHorse() {
        Horse horse = new Horse(EntityType.HORSE, HeadlessLevel.get());
        horse.setSaddled(true); // writes only the synced byte, never the inventory: the state under test
        assertTrue(horse.isSaddled(), "fixture: the client must see this mount as saddled");
        assertTrue(saddleSlotIsEmpty(horse),
                "fixture: slot 0 must stay empty, or vanilla writes the key itself and the test proves nothing");
        return horse;
    }

    /**
     * Whether the mount's own inventory slot 0 is empty. This band has no {@code Entity.getSlot}, so the protected
     * container is reached directly; the assertion it backs is what stops the fixture from letting vanilla's own writer
     * emit the key.
     */
    private static boolean saddleSlotIsEmpty(AbstractHorse horse) {
        try {
            Field field = AbstractHorse.class.getDeclaredField("inventory");
            field.setAccessible(true);
            return ((Container) field.get(horse)).getItem(0).isEmpty();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not reach the mount inventory", e);
        }
    }
}
