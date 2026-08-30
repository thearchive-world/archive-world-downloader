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
 * code deleted, and every assertion below would pass against nothing. {@link #saddleFlag} therefore reaches the private
 * synced accessor directly, reproducing the client's state, which is the only state that tells the two writers apart.
 * No public route exists: the flag setter is protected and every public path runs through slot 0.
 *
 * <p>Three live-entity tests drive {@code encodeChunk} end to end and one drives {@code captureRootVehicle}, so
 * removing either call site in {@link EntitySinkImpl} reddens one of them; the two tag-level tests pin the on-disk
 * contract itself.
 */
class SaddleCaptureTest {
    private static final int FLAG_SADDLE = 4; // AbstractHorse.FLAG_SADDLE, private there

    private final EntitySink sink = new EntitySinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void aSaddledMountIsWrittenSaddledThoughItsInventoryIsEmpty() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Horse horse = saddledHorse();

        CompoundTag chunk = sink.encodeChunk(List.of(horse), new ChunkPos(0, 0), registries, false);

        assertNotNull(chunk, "precondition: the horse is written at all");
        CompoundTag saved = chunk.getList("Entities", Tag.TAG_COMPOUND).getCompound(0);
        assertTrue(saved.contains("SaddleItem", Tag.TAG_COMPOUND),
                "a mount the client sees as saddled must reach disk carrying the key vanilla reads its saddle from");
        ItemStack loaded = ItemStack.of(saved.getCompound("SaddleItem"));
        assertTrue(loaded.is(Items.SADDLE),
                "vanilla keeps the stack only when it is a saddle, so anything else is silently dropped on load");
        assertEquals(1, loaded.getCount(), "one saddle, the stack a mount wears");
        assertTrue(loaded.getTag() == null,
                "a plain saddle: the client holds no source for a saddle's components, so none may be invented");
    }

    @Test
    void anUnsaddledMountIsWrittenBare() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Horse horse = new Horse(EntityType.HORSE, HeadlessLevel.get());
        assertFalse(horse.isSaddled(), "precondition: the client sees this mount as unsaddled");

        CompoundTag chunk = sink.encodeChunk(List.of(horse), new ChunkPos(0, 0), registries, false);

        assertNotNull(chunk, "precondition: the horse is written at all");
        CompoundTag saved = chunk.getList("Entities", Tag.TAG_COMPOUND).getCompound(0);
        assertFalse(saved.contains("SaddleItem"),
                "an unsaddled mount must not be given a saddle it never wore");
    }

    /**
     * A mount riding something else reaches disk only as a nested compound, because {@code shouldBeSaved} refuses a
     * passenger its own entry. A plain minecart picks up a parked mount with no size gate, which is the ordinary way
     * this state arises in play.
     */
    @Test
    void aSaddledMountNestedUnderItsVehicleIsWrittenSaddledToo() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Minecart cart = new Minecart(EntityType.MINECART, HeadlessLevel.get());
        Horse horse = saddledHorse();
        horse.startRiding(cart, true);
        assertTrue(horse.isPassenger() && cart.isVehicle(), "precondition: the mount rides the cart");

        CompoundTag chunk = sink.encodeChunk(List.of(cart, horse), new ChunkPos(0, 0), registries, false);

        assertNotNull(chunk, "precondition: the vehicle group is written at all");
        ListTag entities = chunk.getList("Entities", Tag.TAG_COMPOUND);
        assertEquals(1, entities.size(), "precondition: the passenger has no standalone entry of its own");
        CompoundTag savedCart = entities.getCompound(0);
        assertFalse(savedCart.contains("SaddleItem"), "the vehicle itself wears nothing");
        CompoundTag savedHorse = savedCart.getList("Passengers", Tag.TAG_COMPOUND).getCompound(0);
        assertTrue(savedHorse.contains("SaddleItem", Tag.TAG_COMPOUND),
                "a saddled mount nested under its vehicle must be written saddled, not skipped for being nested");
    }

    /**
     * The mount a player is sitting on is refused by the entities path (vanilla persists it in the player's own
     * RootVehicle record) and reaches disk only through this second serialize, so the saddle has to be written on both
     * paths or the most ordinary case of all, a player riding their saddled horse, archives bare.
     */
    @Test
    void aRiddenSaddledMountIsWrittenSaddledOnTheRootVehiclePath() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();

        CompoundTag saved = sink.captureRootVehicle(saddledHorse(), registries, false);

        assertNotNull(saved, "precondition: the ridden mount is serialized at all");
        assertTrue(saved.contains("SaddleItem", Tag.TAG_COMPOUND),
                "the ridden-mount path must write the saddle too, not only the entities path");
    }

    @Test
    void aSaddleAlreadyOnTheTagIsLeftByteForByteAlone() {
        ItemStack named = new ItemStack(Items.SADDLE);
        named.setHoverName(Component.literal("Old Faithful"));
        Tag original = named.save(new CompoundTag());

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
        byte current = horse.getEntityData().get(saddleFlag());
        horse.getEntityData().set(saddleFlag(), (byte) (current | FLAG_SADDLE));
        assertTrue(horse.isSaddled(), "fixture: the client must see this mount as saddled");
        assertTrue(horse.getSlot(400).get().isEmpty(),
                "fixture: slot 0 must stay empty, or vanilla writes the key itself and the test proves nothing");
        return horse;
    }

    /**
     * The synced flags accessor, reached by reflection because it is private and no public setter leaves slot 0 empty.
     * The same shape {@code CaptureAnchorTest} uses to reach {@code Entity.vehicle}.
     */
    private static EntityDataAccessor<Byte> saddleFlag() {
        try {
            Field field = AbstractHorse.class.getDeclaredField("DATA_ID_FLAGS");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            EntityDataAccessor<Byte> flags = (EntityDataAccessor<Byte>) field.get(null);
            return flags;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not reach the synced saddled flag", e);
        }
    }
}
