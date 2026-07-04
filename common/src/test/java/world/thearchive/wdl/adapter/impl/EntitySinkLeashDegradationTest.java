// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.EntitySink;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the prime-path leash degradation. A leashed mob whose leash holder the client never resolved
 * carries a {@link Leashable.LeashData} with a null holder and no delayed attachment; vanilla's {@code LeashData} codec
 * throws on exactly that state (an {@code Objects.requireNonNull} in its encode function), so the live
 * {@code entity.save} throws and the sink drops the whole entity, taking a chested mob's container with it. That
 * unresolved state is what ViaBackwards leaves after down-translating the reworked 26.x leash into the 1.21.11 shape;
 * native fence leashes resolve to a knot and save fine. The sink degrades: strip the unsavable leash and write the
 * entity (and its container) unleashed, mirroring the reconstruct path which leaves an unresolved leash link unset.
 * Because {@code save} recurses into passengers, the strip spans the passenger subtree; a leash the codec can still
 * encode (a resolved holder, or a delayed attachment) is left intact.
 *
 * <p>A real chested {@code Mob} cannot be built headless (its constructor dereferences the {@code Level}, as
 * {@link NamedMobPersistenceTest} notes); a bare {@link Entity} constructor does not, so the double below is a real
 * {@code Entity} that also implements {@link Leashable} and writes an {@code Items} container. It drives the exact
 * failing vanilla code, {@code Leashable.writeLeashData} over a holder-unresolved {@code LeashData}, without a live
 * game.
 */
class EntitySinkLeashDegradationTest {
    private final EntitySink sink = new EntitySinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void unresolvableLeashIsStrippedSoTheEntityAndItsContainerStillSave() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        ChunkPos pos = new ChunkPos(0, 0);

        ContainerLeashableEntity mob = new ContainerLeashableEntity();
        mob.setDelayedLeashHolderId(4242); // the client leash link to a holder it never resolved
        assertTrue(mob.mayBeLeashed(), "precondition: the mob carries a leash");
        assertFalse(mob.isLeashed(), "precondition: its holder is unresolved (the rejecting state)");

        CompoundTag chunk = sink.encodeChunk(List.of(mob), pos, registries, false);

        assertNotNull(chunk, "the leashed container entity must not be dropped");
        ListTag entities = chunk.getListOrEmpty("Entities");
        assertEquals(1, entities.size(), "the leashed container entity must still be written");

        CompoundTag saved = entities.getCompoundOrEmpty(0);
        ListTag items = saved.getListOrEmpty("Items");
        assertEquals(1, items.size(), "the container contents must survive the leash degradation");
        assertFalse(saved.contains("leash"), "the unresolvable leash link is dropped by design, not saved");

        assertTrue(mob.mayBeLeashed(), "the live entity's leash is restored: no lingering mutation");
    }

    @Test
    void anUnsavableLeashOnPassengerNoLongerSinksTheVehicleGroup() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        ChunkPos pos = new ChunkPos(0, 0);

        ContainerLeashableEntity rider = new ContainerLeashableEntity();
        rider.setDelayedLeashHolderId(4242); // the passenger carries the unresolvable leash, not the vehicle
        ContainerLeashableEntity vehicle = new ContainerLeashableEntity();
        seatPassenger(rider, vehicle);

        CompoundTag chunk = sink.encodeChunk(List.of(vehicle), pos, registries, false);

        assertNotNull(chunk, "the vehicle group must not be dropped by the passenger's unsavable leash");
        CompoundTag savedVehicle = chunk.getListOrEmpty("Entities").getCompoundOrEmpty(0);
        ListTag passengers = savedVehicle.getListOrEmpty("Passengers");
        assertEquals(1, passengers.size(), "the passenger must be nested under the vehicle");

        CompoundTag savedRider = passengers.getCompoundOrEmpty(0);
        assertEquals(1, savedRider.getListOrEmpty("Items").size(), "the passenger's container must survive");
        assertFalse(savedRider.contains("leash"), "the passenger's unsavable leash is stripped, not dropped whole");

        assertTrue(rider.mayBeLeashed(), "the live passenger's leash is restored: no lingering mutation");
    }

    @Test
    void aResolvedLeashIsPreservedNotStripped() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        ChunkPos pos = new ChunkPos(0, 0);

        ContainerLeashableEntity holder = new ContainerLeashableEntity();
        ContainerLeashableEntity mob = new ContainerLeashableEntity();
        mob.setLeashedTo(holder, false); // a leash whose holder the client did resolve
        assertTrue(mob.isLeashed(), "precondition: the holder is resolved (a savable leash)");

        CompoundTag chunk = sink.encodeChunk(List.of(mob), pos, registries, false);

        assertNotNull(chunk, "a resolvably leashed entity must still be written");
        CompoundTag saved = chunk.getListOrEmpty("Entities").getCompoundOrEmpty(0);
        assertTrue(saved.contains("leash"), "a resolvable leash is kept, only the unsavable one is stripped");
        assertEquals(1, saved.getListOrEmpty("Items").size(), "its container is written as well");
    }

    @Test
    void aDelayedAttachmentLeashIsNotStripped() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        ChunkPos pos = new ChunkPos(0, 0);

        ContainerLeashableEntity mob = new ContainerLeashableEntity();
        CompoundTag leashInput = new CompoundTag();
        leashInput.store("leash", BlockPos.CODEC, new BlockPos(1, 2, 3)); // a delayed attachment the codec can encode
        mob.readLeashData(TagValueInput.create(ProblemReporter.DISCARDING, registries, leashInput));
        assertTrue(mob.mayBeLeashed() && !mob.isLeashed(), "precondition: holder null, but a savable attachment");

        CompoundTag chunk = sink.encodeChunk(List.of(mob), pos, registries, false);

        assertNotNull(chunk, "a delayed-attachment leash still saves the entity");
        CompoundTag saved = chunk.getListOrEmpty("Entities").getCompoundOrEmpty(0);
        assertTrue(saved.contains("leash"), "only the throwing state is stripped; a savable attachment is kept");
    }

    /**
     * Seat {@code rider} on {@code vehicle} by setting vanilla's own passenger fields. A real
     * {@code Entity.startRiding} dereferences the {@code Level} (null here), so the relationship is wired directly,
     * which keeps {@code save}'s passenger recursion and the sink's passenger walk faithful.
     */
    private static void seatPassenger(Entity rider, Entity vehicle) {
        try {
            Field vehicleField = Entity.class.getDeclaredField("vehicle");
            vehicleField.setAccessible(true);
            vehicleField.set(rider, vehicle);
            Field passengersField = Entity.class.getDeclaredField("passengers");
            passengersField.setAccessible(true);
            passengersField.set(vehicle, ImmutableList.of(rider));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not seat the test passenger", e);
        }
    }

    /**
     * A headless client-shaped stand-in for a chested, leashable mob (see the class javadoc): a real {@link Entity}
     * carrying an {@code Items} container and a {@link Leashable} leash.
     */
    private static final class ContainerLeashableEntity extends Entity implements Leashable {
        private Leashable.@Nullable LeashData leashData;

        private ContainerLeashableEntity() {
            super(EntityType.PIG, null);
        }

        @Override
        public Leashable.@Nullable LeashData getLeashData() {
            return this.leashData;
        }

        @Override
        public void setLeashData(Leashable.@Nullable LeashData leashData) {
            this.leashData = leashData;
        }

        @Override
        protected void addAdditionalSaveData(ValueOutput output) {
            this.writeLeashData(output, this.leashData); // vanilla default: throws on the unresolved holder
            ValueOutput.TypedOutputList<ItemStackWithSlot> items = output.list("Items", ItemStackWithSlot.CODEC);
            items.add(new ItemStackWithSlot(0, new ItemStack(Items.DIAMOND, 3)));
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {}

        @Override
        protected void readAdditionalSaveData(ValueInput input) {}

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            return false;
        }
    }
}
