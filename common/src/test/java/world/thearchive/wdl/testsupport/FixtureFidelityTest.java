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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class FixtureFidelityTest {
    private static final UUID VEHICLE = new UUID(0x1111_1111_1111_1111L, 0x2222_2222_2222_2222L);

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    private static CompoundTag handBuiltEntry(String itemId, boolean withSlot, boolean withCount) {
        CompoundTag entry = new CompoundTag();
        if (withSlot) {
            entry.putByte("Slot", (byte) 1);
        }
        entry.putString("id", itemId);
        if (withCount) {
            entry.putInt("count", 1);
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
        String message = reject(() -> FixtureFidelity.assertItemsHolderShape(
                holderOf(handBuiltEntry("minecraft:diamond", false, true),
                        handBuiltEntry("minecraft:emerald", false, true))));
        assertTrue(message.contains("element(s)"), "the divergence must name the lost entry: " + message);
    }

    @Test
    void anItemEntryWithoutCountIsRejected() {
        String message = reject(() -> FixtureFidelity
                .assertItemsHolderShape(holderOf(handBuiltEntry("minecraft:diamond", true, false))));
        assertTrue(message.contains("count"), "the divergence must name the omitted key: " + message);
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
        vehicle.put("Items", holderOf(handBuiltEntry("minecraft:diamond", false, true)).getListOrEmpty("Items"));

        reject(() -> EntityFixtures.entityChunkTagWith(vehicle));
    }

    @Test
    void theChunkChokePointStampsTheKeyThatOnlyTheChunkLayerWrites() {
        CompoundTag chunkTag = BlockEntityFixtures
                .chunkTagWith(BlockEntityFixtures.blockEntity("minecraft:chest", 1, 64, 1));
        assertEquals(false,
                BlockEntityFixtures.findByPos(chunkTag, 1, 64, 1).getBooleanOr(FixtureFidelity.KEEP_PACKED, true));
    }

    @Test
    void aProducerBuiltContainerVehicleIsAccepted() {
        assertDoesNotThrow(() -> EntityFixtures.entityChunkTagWith(
                EntityFixtures.containerVehicle("minecraft:chest_minecart", VEHICLE, "minecraft:diamond")));
    }
}
