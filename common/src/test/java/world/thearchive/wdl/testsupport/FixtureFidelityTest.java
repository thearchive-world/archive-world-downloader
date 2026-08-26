// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
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
        TestRegistries.bootstrap();
    }

    /** The hand-built entry at the root of the recorded defect: no {@code "Slot"}, so every entry decodes to 0. */
    private static NBTTagCompound handBuiltEntry(String itemId, boolean withSlot, boolean withCount) {
        NBTTagCompound entry = new NBTTagCompound();
        if (withSlot) {
            entry.setByte("Slot", (byte) 1);
        }
        entry.setString("id", itemId);
        if (withCount) {
            // Vanilla's own ItemStack#writeToNBT writes "Count" as a byte; a count under any other key or shape
            // decodes to 0, which reads as empty and ItemStackHelper.saveAllItems then drops the entry entirely,
            // so the deliberately-omitted key below stops being the fixture's only divergence.
            entry.setByte("Count", (byte) 1);
        }
        return entry;
    }

    private static NBTTagCompound holderOf(NBTTagCompound... entries) {
        NBTTagList items = new NBTTagList();
        for (NBTTagCompound entry : entries) {
            items.appendTag(entry);
        }
        NBTTagCompound holder = new NBTTagCompound();
        holder.setTag("Items", items);
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
        // "Slot" directly), a missing Count decodes to 0, which is treated as empty; ItemStackHelper.saveAllItems
        // then skips it entirely, so the producer's list comes back one element short rather than missing a
        // single named key.
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
        NBTTagCompound handBuilt = new NBTTagCompound();
        handBuilt.setString("id", "minecraft:chest");
        handBuilt.setInteger("x", 10);
        handBuilt.setInteger("y", 70);
        handBuilt.setInteger("z", 20);

        String message = reject(() -> FixtureFidelity.assertBlockEntityShape(handBuilt));
        assertTrue(message.contains("Items"),
                "an absent Items is the carry-forward defect's own shape and must be named: " + message);
    }

    @Test
    void aBlockEntityTagMissingAnAlwaysWrittenStateKeyIsRejected() {
        NBTTagCompound brewingStand = BlockEntityFixtures.blockEntity("minecraft:brewing_stand", 6, 64, 6);
        brewingStand.removeTag("BrewTime");

        String message = reject(() -> FixtureFidelity.assertBlockEntityShape(brewingStand));
        assertTrue(message.contains("BrewTime"), "the divergence must name the omitted key: " + message);
    }

    @Test
    void aBlockEntityTagCarryingAnInventedKeyIsRejected() {
        NBTTagCompound jukebox = BlockEntityFixtures.blockEntity("minecraft:jukebox", 1, 64, 1);
        jukebox.setString("wdlProbeField", "keep-me");

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
        NBTTagCompound handBuilt = new NBTTagCompound();
        handBuilt.setString("id", "minecraft:chest");
        handBuilt.setInteger("x", 1);
        handBuilt.setInteger("y", 64);
        handBuilt.setInteger("z", 1);

        reject(() -> BlockEntityFixtures.chunkTagWith(handBuilt));
    }

    @Test
    void theEntityChokePointRejectsHandBuiltItemsHolders() {
        NBTTagCompound vehicle = new NBTTagCompound();
        vehicle.setString("id", "minecraft:chest_minecart");
        vehicle.setTag("Items",
                holderOf(handBuiltEntry("minecraft:diamond", false, true)).getTagList("Items", 10));

        reject(() -> EntityFixtures.entityChunkTagWith(vehicle));
    }

    @Test
    void theChunkChokePointStampsTheKeyThatOnlyTheChunkLayerWrites() {
        // The block entity's own save never writes keepPacked, so it is exempt from the round trip yet still part
        // of what lands on disk beside every saved block entity.
        NBTTagCompound chunkTag = BlockEntityFixtures
                .chunkTagWith(BlockEntityFixtures.blockEntity("minecraft:chest", 1, 64, 1));
        NBTTagCompound blockEntity = BlockEntityFixtures.findByPos(chunkTag, 1, 64, 1);
        assertEquals(false,
                blockEntity.hasKey(FixtureFidelity.KEEP_PACKED) ? blockEntity.getBoolean(FixtureFidelity.KEEP_PACKED)
                        : true);
    }

    @Test
    void aProducerBuiltContainerVehicleIsAccepted() {
        assertDoesNotThrow(() -> EntityFixtures.entityChunkTagWith(
                EntityFixtures.containerVehicle("minecraft:chest_minecart", VEHICLE, "minecraft:diamond")));
    }
}
