// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The gate's own guard. A fidelity check nobody has watched reject anything is not known to reject anything, and one
 * that stops rejecting is indistinguishable from one that never fired, so each case here hands {@link FixtureFidelity}
 * a fixture hand-written in the shape that produced the defects this exists for and asserts it is refused, naming the
 * key.
 */
class FixtureFidelityTest {
    private static final UUID VEHICLE = new UUID(0x1111_1111_1111_1111L, 0x2222_2222_2222_2222L);

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    /** The hand-built entry at the root of the recorded defect: no {@code "Slot"}, so every entry decodes to 0. */
    private static CompoundTag handBuiltEntry(String itemId, boolean withSlot, boolean withCount) {
        CompoundTag entry = new CompoundTag();
        if (withSlot) {
            entry.putByte("Slot", (byte) 1);
        }
        entry.putString("id", itemId);
        if (withCount) {
            // Vanilla's own ItemStack#save writes "Count" as a byte; a count under any other key or shape
            // decodes to 0, which ItemStack.of reads as empty and ContainerHelper.saveAllItems then drops the
            // entry entirely, so the deliberately-omitted key below stops being the fixture's only divergence.
            entry.putByte("Count", (byte) 1);
        }
        return entry;
    }

    private static CompoundTag holderOf(CompoundTag... entries) {
        ListTag items = new ListTag();
        for (CompoundTag entry : entries) {
            items.add(entry);
        }
        CompoundTag holder = new CompoundTag();
        holder.put("Items", items);
        return holder;
    }

    private static String reject(Executable subject) {
        AssertionError error = assertThrows(AssertionError.class, subject);
        String message = error.getMessage();
        assertTrue(message != null && message.startsWith("Fixture fidelity:"),
                "the rejection must name itself as a fidelity failure, not read as an unrelated crash: " + message);
        return message;
    }

    @Test
    void anItemEntryWithoutSlotIsRejected() {
        String message = reject(() -> FixtureFidelity
                .assertItemsHolderShape(holderOf(handBuiltEntry("minecraft:diamond", false, true))));
        assertTrue(message.contains("Slot"), "the divergence must name the omitted key: " + message);
    }

    @Test
    void twoItemEntriesWithoutSlotCollapseOntoOneAndAreRejected() {
        // The exact defect shape: a slot-aware rule sees one entry where the fixture claims two, so a case
        // written to distinguish slots proves nothing and passes under a deliberately broken production line.
        String message = reject(() -> FixtureFidelity.assertItemsHolderShape(
                holderOf(handBuiltEntry("minecraft:diamond", false, true),
                        handBuiltEntry("minecraft:emerald", false, true))));
        assertTrue(message.contains("element(s)"), "the divergence must name the lost entry: " + message);
    }

    @Test
    void anItemEntryWithoutCountIsRejected() {
        // Unlike a missing Slot (which still round-trips as a valid entry at slot 0, so the divergence names
        // "Slot" directly), a missing Count decodes to 0 via ItemStack.of, which ItemStack#isEmpty treats as
        // empty; ContainerHelper.saveAllItems then skips it entirely, so the producer's list comes back one
        // element short rather than missing a single named key.
        String message = reject(() -> FixtureFidelity
                .assertItemsHolderShape(holderOf(handBuiltEntry("minecraft:diamond", true, false))));
        assertTrue(message.contains("element(s)"), "the divergence must name the lost entry: " + message);
    }

    @Test
    void aProducerBuiltItemsHolderIsAccepted() {
        assertDoesNotThrow(() -> FixtureFidelity
                .assertItemsHolderShape(ItemFixtures.itemsHolder("minecraft:diamond", "minecraft:emerald")));
    }

    @Test
    void aBlockEntityTagCarryingOnlyItsMetadataIsRejected() {
        CompoundTag handBuilt = new CompoundTag();
        handBuilt.putString("id", "minecraft:chest");
        handBuilt.putInt("x", 10);
        handBuilt.putInt("y", 70);
        handBuilt.putInt("z", 20);

        String message = reject(() -> FixtureFidelity.assertBlockEntityShape(handBuilt));
        assertTrue(message.contains("Items"),
                "an absent Items is the carry-forward defect's own shape and must be named: " + message);
    }

    @Test
    void aBlockEntityTagMissingAnAlwaysWrittenStateKeyIsRejected() {
        CompoundTag crafter = BlockEntityFixtures.blockEntity("minecraft:crafter", 6, 64, 6);
        crafter.remove("triggered");

        String message = reject(() -> FixtureFidelity.assertBlockEntityShape(crafter));
        assertTrue(message.contains("triggered"), "the divergence must name the omitted key: " + message);
    }

    @Test
    void aBlockEntityTagCarryingAnInventedKeyIsRejected() {
        CompoundTag jukebox = BlockEntityFixtures.blockEntity("minecraft:jukebox", 1, 64, 1);
        jukebox.putString("wdlProbeField", "keep-me");

        String message = reject(() -> FixtureFidelity.assertBlockEntityShape(jukebox));
        assertTrue(message.contains("wdlProbeField"), "the divergence must name the invented key: " + message);
    }

    @Test
    void aProducerBuiltBlockEntityIsAccepted() {
        assertDoesNotThrow(() -> FixtureFidelity
                .assertBlockEntityShape(BlockEntityFixtures.blockEntity("minecraft:chest", 10, 70, 20)));
        assertDoesNotThrow(() -> FixtureFidelity
                .assertBlockEntityShape(BlockEntityFixtures.namedBlockEntity("minecraft:chest", 1, 2, 3, "named")));
    }

    @Test
    void theChunkChokePointRejectsHandBuiltBlockEntities() {
        CompoundTag handBuilt = new CompoundTag();
        handBuilt.putString("id", "minecraft:chest");
        handBuilt.putInt("x", 1);
        handBuilt.putInt("y", 64);
        handBuilt.putInt("z", 1);

        reject(() -> BlockEntityFixtures.chunkTagWith(handBuilt));
    }

    @Test
    void theEntityChokePointRejectsHandBuiltItemsHolders() {
        CompoundTag vehicle = new CompoundTag();
        vehicle.putString("id", "minecraft:chest_minecart");
        vehicle.put("Items",
                holderOf(handBuiltEntry("minecraft:diamond", false, true)).getList("Items", Tag.TAG_COMPOUND));

        reject(() -> EntityFixtures.entityChunkTagWith(vehicle));
    }

    @Test
    void theChunkChokePointStampsTheKeyThatOnlyTheChunkLayerWrites() {
        // The block entity's own save never writes keepPacked, so it is exempt from the round trip yet still part
        // of what lands on disk beside every saved block entity.
        CompoundTag chunkTag = BlockEntityFixtures
                .chunkTagWith(BlockEntityFixtures.blockEntity("minecraft:chest", 1, 64, 1));
        CompoundTag blockEntity = BlockEntityFixtures.findByPos(chunkTag, 1, 64, 1);
        assertEquals(false,
                blockEntity.contains(FixtureFidelity.KEEP_PACKED) ? blockEntity.getBoolean(FixtureFidelity.KEEP_PACKED)
                        : true);
    }

    @Test
    void aProducerBuiltContainerVehicleIsAccepted() {
        assertDoesNotThrow(() -> EntityFixtures.entityChunkTagWith(
                EntityFixtures.containerVehicle("minecraft:chest_minecart", VEHICLE, "minecraft:diamond")));
    }
}
