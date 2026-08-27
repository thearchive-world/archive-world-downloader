// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.init.Items;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the band-agnostic {@link PlayerTag} operations on an already-serialized player tag: the strip
 * knob (drop {@code Inventory}/{@code SelectedItemSlot}, which at this band also carries the armor and offhand slots),
 * the death-location strip (a harmless no-op here; neither key exists in a 1.12.2 player tag), the {@code Dimension}
 * write and the read that a resume routes by, and the ender-items remap ({@code Items} -> {@code EnderItems}). Pure
 * NBT, so it round-trips headless on hand-built tags and {@link ContainerSink}-captured holders.
 */
class PlayerTagTest {
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /** A player-like tag carrying the strip-able keys plus an unrelated field that must always survive. */
    private static NBTTagCompound playerTag() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Inventory", new NBTTagList());
        tag.setInteger("SelectedItemSlot", 4);
        tag.setTag("EnderItems", new NBTTagList());
        tag.setShort("Air", (short) 300); // an Entity field the strips must never touch
        return tag;
    }

    @Test
    void stripKnobInventoryOffRemovesInventoryAndSelectedSlot() {
        NBTTagCompound tag = playerTag();
        PlayerTag.applyStripKnobs(tag, false, true);
        assertFalse(tag.hasKey("Inventory"), "Inventory dropped, which at this band carries armor and offhand too");
        assertFalse(tag.hasKey("SelectedItemSlot"), "SelectedItemSlot dropped with it");
        assertTrue(tag.hasKey("EnderItems"), "ender items kept when only the inventory knob is off");
        assertTrue(tag.hasKey("Air"), "an unrelated Entity field survives");
    }

    @Test
    void stripKnobEnderChestOffRemovesEnderItemsOnly() {
        NBTTagCompound tag = playerTag();
        PlayerTag.applyStripKnobs(tag, true, false);
        assertFalse(tag.hasKey("EnderItems"), "EnderItems dropped");
        assertTrue(tag.hasKey("Inventory"), "inventory kept when only the ender knob is off");
        assertTrue(tag.hasKey("SelectedItemSlot"));
    }

    @Test
    void stripKnobsAllOnKeepEverything() {
        NBTTagCompound tag = playerTag();
        PlayerTag.applyStripKnobs(tag, true, true);
        assertTrue(tag.hasKey("Inventory"));
        assertTrue(tag.hasKey("SelectedItemSlot"));
        assertTrue(tag.hasKey("EnderItems"));
    }

    @Test
    void stripDeathLocationIsNoOpAtThisBand() {
        // Neither key exists in a 1.12.2 player tag (LastDeathLocation is 1.19, current_explosion_impact_pos is
        // 1.21), so the strip has nothing to remove and the rest of the tag is untouched either way.
        NBTTagCompound tag = playerTag();

        PlayerTag.stripDeathLocation(tag);

        assertFalse(tag.hasKey("LastDeathLocation"));
        assertFalse(tag.hasKey("current_explosion_impact_pos"));
        assertTrue(tag.hasKey("Air"), "the strip leaves the rest of the tag intact");
    }

    @Test
    void setDimensionWritesCanonicalVanillaIdForCanonicalKey() {
        NBTTagCompound tag = playerTag();
        PlayerTag.setDimension(tag, DimensionType.NETHER);
        assertEquals(DimensionType.NETHER.getId(), tag.getInteger("Dimension"));
    }

    @Test
    void dimensionOfReadsBackWhatSetDimensionWrote() {
        NBTTagCompound tag = playerTag();
        PlayerTag.setDimension(tag, DimensionType.NETHER);
        assertEquals(DimensionType.NETHER, PlayerTag.dimensionOf(tag));
    }

    @Test
    void dimensionOfRefusesAnyDimensionThisCaptureNeverWrote() {
        // A resume reads this off a prior level.dat, so a tag from anywhere else must not resolve to a folder
        // by accident: the overworld is what an unroutable id would collapse onto if this fell back.
        NBTTagCompound tag = playerTag();
        assertNull(PlayerTag.dimensionOf(tag), "a player tag with no Dimension key names no dimension");
        tag.setInteger("Dimension", 99); // an id outside the three a capture ever writes
        assertNull(PlayerTag.dimensionOf(tag), "nor does one naming a dimension the capture never routes to");
    }

    @Test
    void setPositionWritesTheCaptureAnchorInTheShapesVanillaReadsBack() {
        // Vanilla reads Pos as three doubles and Rotation as two floats in yaw-then-pitch order, and a list of any
        // other shape or order loads silently as the origin, so the saved player would land somewhere the capture
        // never anchored with no error anywhere.
        NBTTagCompound tag = playerTag();

        PlayerTag.setPosition(tag, new BlockPos(12, 70, -34), 90.0f, -15.0f);

        NBTTagList pos = tag.getTagList("Pos", 6);
        assertEquals(3, pos.tagCount(), "the anchor is written as three doubles");
        assertEquals(12.0, pos.getDoubleAt(0));
        assertEquals(70.0, pos.getDoubleAt(1));
        assertEquals(-34.0, pos.getDoubleAt(2));
        NBTTagList rotation = tag.getTagList("Rotation", 5);
        assertEquals(2, rotation.tagCount(), "the rotation is written as two floats");
        assertEquals(90.0f, rotation.getFloatAt(0), "yaw first");
        assertEquals(-15.0f, rotation.getFloatAt(1), "then pitch");
    }

    @Test
    void setEnderItemsRemapsTheCapturedItemsListIntoEnderItems() {
        NBTTagCompound tag = playerTag(); // EnderItems starts as the empty list writeToNBT wrote
        NonNullList<ItemStack> ender = NonNullList.withSize(27, ItemStack.EMPTY);
        ender.set(5, new ItemStack(Items.ENDER_PEARL, 9));
        NBTTagCompound holder = sink.captureItems(ender); // an "Items" list in slot-tagged form

        PlayerTag.setEnderItems(tag, holder);

        assertTrue(tag.getTag("EnderItems") instanceof NBTTagList, "EnderItems is now the captured list");
        NBTTagCompound probe = new NBTTagCompound();
        probe.setTag("Items", tag.getTagList("EnderItems", 10)); // read the remapped list via the same codec
        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        ItemStackHelper.loadAllItems(probe, back);
        assertEquals(Items.ENDER_PEARL, back.get(5).getItem(), "the captured ender item lands at its EnderItems slot");
        assertEquals(9, back.get(5).getCount());
    }

    @Test
    void setRootVehicleWritesTheVanillaAttachAndEntityShape() {
        NBTTagCompound tag = playerTag();
        UUID directVehicle = UUID.fromString("0fedcba9-8765-4321-fedc-ba9876543210");
        NBTTagCompound vehicleTag = EntityFixtures.entityTag("minecraft:chest_boat");

        PlayerTag.setRootVehicle(tag, directVehicle, vehicleTag);

        NBTTagCompound rootVehicle = tag.getCompoundTag("RootVehicle");
        assertEquals(directVehicle,
                rootVehicle.getUniqueId("Attach"),
                "Attach is the direct vehicle UUID the pre-1.16 AttachMost/AttachLeast form serializes");
        assertEquals("minecraft:chest_boat", rootVehicle.getCompoundTag("Entity").getString("id"),
                "the vehicle NBT nests under Entity, the shape loadAndSpawnParentVehicle spawns from");
        assertTrue(tag.hasKey("Air"), "the rest of the player tag is untouched");
    }

    private static NBTTagCompound priorPlayerWithRootVehicle() {
        NBTTagCompound prior = new NBTTagCompound();
        NBTTagCompound rootVehicle = new NBTTagCompound();
        rootVehicle.setTag("Entity", EntityFixtures.entityTag("minecraft:chest_boat"));
        prior.setTag("RootVehicle", rootVehicle);
        return prior;
    }

    @Test
    void restorePriorMountContentsIgnoresAnUnseatedResume() {
        NBTTagCompound prior = priorPlayerWithRootVehicle();
        NBTTagCompound fresh = playerTag(); // un-seated this session, no RootVehicle

        boolean restored = PlayerTag.restorePriorMountContents(prior, fresh);

        assertFalse(restored,
                "an un-seated resume restores nothing: a dismounted mount is a normal world entity, not player-state");
        assertFalse(fresh.hasKey("RootVehicle"),
                "the Player slot is left without a mount, so no same-UUID collision");
    }

    @Test
    void restorePriorMountContentsNeedsBothEntityCompounds() {
        NBTTagCompound prior = priorPlayerWithRootVehicle();
        NBTTagCompound fresh = playerTag();
        fresh.setTag("RootVehicle", new NBTTagCompound()); // seated shape present, but no Entity serialized

        boolean restored = PlayerTag.restorePriorMountContents(prior, fresh);

        assertFalse(restored, "with no fresh Entity compound there is nothing to match or fold into");
        assertFalse(fresh.getCompoundTag("RootVehicle").hasKey("Entity"),
                "nothing is grafted onto the entity-less RootVehicle");
    }

    @Test
    void restorePriorMountContentsYieldsToTheFreshMount() {
        NBTTagCompound prior = priorPlayerWithRootVehicle();
        NBTTagCompound fresh = playerTag();
        NBTTagCompound freshEntity = EntityFixtures.entityTag("minecraft:oak_boat");
        PlayerTag.setRootVehicle(fresh, UUID.fromString("11111111-2222-3333-4444-555555555555"), freshEntity);

        boolean carried = PlayerTag.restorePriorMountContents(prior, fresh);

        assertFalse(carried, "the fresh mount is authoritative, nothing carries back");
        assertEquals("minecraft:oak_boat",
                fresh.getCompoundTag("RootVehicle").getCompoundTag("Entity").getString("id"),
                "the fresh mount wins");
    }

    @Test
    void restorePriorMountContentsCarriesNothingWhenThePriorHasNone() {
        NBTTagCompound prior = new NBTTagCompound();
        NBTTagCompound fresh = playerTag();

        assertFalse(PlayerTag.restorePriorMountContents(prior, fresh), "no prior mount, nothing to carry");
        assertFalse(fresh.hasKey("RootVehicle"));
    }

    /** A RootVehicle whose Entity carries the mount's own UUID and an Items list, the seated-mount capture shape. */
    private static NBTTagCompound mountRootVehicle(UUID mountUuid, NBTTagList items) {
        NBTTagCompound rootVehicle = new NBTTagCompound();
        NBTTagCompound entity = EntityFixtures.entity("minecraft:chest_boat", mountUuid);
        entity.setTag("Items", items);
        rootVehicle.setTag("Entity", entity);
        return rootVehicle;
    }

    private static NBTTagCompound playerSeatedIn(NBTTagCompound rootVehicle) {
        NBTTagCompound player = playerTag();
        player.setTag("RootVehicle", rootVehicle);
        return player;
    }

    private static NBTTagList mountItems(NBTTagCompound player) {
        return player.getCompoundTag("RootVehicle").getCompoundTag("Entity").getTagList("Items", 10);
    }

    @Test
    void restorePriorMountContentsWhenResumingSeatedInTheSameMount() {
        UUID mount = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        NBTTagCompound prior = playerSeatedIn(
                mountRootVehicle(mount, itemList("minecraft:diamond", "minecraft:gold_ingot")));
        NBTTagCompound fresh = playerSeatedIn(mountRootVehicle(mount, new NBTTagList())); // seated, mount not reopened

        boolean carried = PlayerTag.restorePriorMountContents(prior, fresh);

        assertTrue(carried, "the prior download's contents restore onto the same seated mount");
        assertEquals(2, mountItems(fresh).tagCount(), "the empty seated capture is refilled from the prior download");
        assertEquals(mount, fresh.getCompoundTag("RootVehicle").getCompoundTag("Entity").getUniqueId("UUID"),
                "the fresh mount identity is untouched");
    }

    @Test
    void restorePriorMountContentsYieldsToTheReopenedSeatedMount() {
        UUID mount = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        NBTTagCompound prior = playerSeatedIn(
                mountRootVehicle(mount, itemList("minecraft:diamond", "minecraft:gold_ingot")));
        NBTTagCompound fresh = playerSeatedIn(
                mountRootVehicle(mount, itemList("minecraft:emerald"))); // reopened this session

        boolean carried = PlayerTag.restorePriorMountContents(prior, fresh);

        assertFalse(carried, "a reopened seated mount is authoritative, nothing restores");
        assertEquals(1, mountItems(fresh).tagCount(), "the freshly captured contents win");
    }

    @Test
    void restorePriorMountContentsNeverGraftsAcrossTheMountSwitch() {
        NBTTagCompound prior = playerSeatedIn(mountRootVehicle(
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), itemList("minecraft:diamond")));
        NBTTagCompound fresh = playerSeatedIn(mountRootVehicle(
                UUID.fromString("11111111-2222-3333-4444-555555555555"), new NBTTagList())); // a different mount

        boolean carried = PlayerTag.restorePriorMountContents(prior, fresh);

        assertFalse(carried, "a cross-resume mount switch never grafts one mount's contents onto another");
        assertTrue(mountItems(fresh).isEmpty(), "the different fresh mount is left empty");
    }

    @Test
    void restorePriorMountContentsRestoresNothingWhenThePriorMountHeldNothing() {
        UUID mount = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        NBTTagCompound prior = playerSeatedIn(mountRootVehicle(mount, new NBTTagList())); // prior mount was empty too
        NBTTagCompound fresh = playerSeatedIn(mountRootVehicle(mount, new NBTTagList()));

        assertFalse(PlayerTag.restorePriorMountContents(prior, fresh), "an empty prior mount is not a recovery");
        assertTrue(mountItems(fresh).isEmpty(), "the fresh seated mount stays empty");
    }

    /**
     * The same mount {@link #mountRootVehicle} builds, nested under a carrier that pushed itself under it. The record
     * vanilla stores is the tree from its root, so a mount in that state is not the root of its own record and the two
     * fixtures differ in nothing else.
     */
    private static NBTTagCompound nestedMountRootVehicle(UUID carrierUuid, UUID mountUuid, NBTTagList items) {
        NBTTagCompound rootVehicle = new NBTTagCompound();
        NBTTagCompound mount = mountRootVehicle(mountUuid, items).getCompoundTag("Entity");
        rootVehicle.setTag("Entity",
                EntityFixtures.entityCarrying(EntityFixtures.entity("minecraft:minecart", carrierUuid), mount));
        return rootVehicle;
    }

    private static NBTTagList nestedMountItems(NBTTagCompound player) {
        return player.getCompoundTag("RootVehicle").getCompoundTag("Entity")
                .getTagList("Passengers", 10).getCompoundTagAt(0).getTagList("Items", 10);
    }

    @Test
    void restorePriorMountContentsCarriesOntoTheSameMountNestedUnderTheFreshRoot() {
        UUID mount = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        NBTTagCompound prior = playerSeatedIn(
                mountRootVehicle(mount, itemList("minecraft:diamond", "minecraft:gold_ingot")));
        NBTTagCompound fresh = playerSeatedIn(nestedMountRootVehicle(
                UUID.fromString("6b1d5f2c-9a30-4e11-b8c7-5d0e3a71f402"), mount, new NBTTagList()));

        boolean carried = PlayerTag.restorePriorMountContents(prior, fresh);

        assertTrue(carried, "the same mount is the same mount whether or not something has pushed itself under it");
        assertEquals(2, nestedMountItems(fresh).tagCount(),
                "so the prior download's loot restores onto the node that owns it, not onto the fresh root");
    }

    @Test
    void restorePriorMountContentsNeverGraftsWhenNoNodeMatchesAnywhereInTheTree() {
        NBTTagCompound prior = playerSeatedIn(nestedMountRootVehicle(
                UUID.fromString("6b1d5f2c-9a30-4e11-b8c7-5d0e3a71f402"),
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), itemList("minecraft:diamond")));
        NBTTagCompound fresh = playerSeatedIn(nestedMountRootVehicle(
                UUID.fromString("22222222-3333-4444-5555-666666666666"),
                UUID.fromString("11111111-2222-3333-4444-555555555555"), new NBTTagList()));

        boolean carried = PlayerTag.restorePriorMountContents(prior, fresh);

        assertFalse(carried, "a genuine mount switch matches no node on either side, so nothing grafts");
        assertTrue(nestedMountItems(fresh).isEmpty(), "the different fresh mount is left empty");
    }

    private static NBTTagList itemList(String... ids) {
        return ItemFixtures.items(ids);
    }

    private static NBTTagCompound priorPlayer(NBTTagList enderItems) {
        NBTTagCompound player = new NBTTagCompound();
        player.setTag("EnderItems", enderItems);
        return player;
    }

    @Test
    void aPriorEnderChestCarriesForwardWhenTheFreshOneIsEmpty() {
        NBTTagCompound prior = priorPlayer(itemList("minecraft:diamond", "minecraft:gold_ingot"));
        NBTTagCompound fresh = playerTag(); // EnderItems is the empty list writeToNBT wrote (not re-opened)

        boolean carried = PlayerTag.carryForwardEnderItems(prior, fresh);

        assertTrue(carried, "the prior ender chest carried forward on the resume");
        assertEquals(2, fresh.getTagList("EnderItems", 10).tagCount(), "the prior contents survive the resume");
    }

    @Test
    void aFreshEnderChestIsKeptOverThePrior() {
        NBTTagCompound prior = priorPlayer(itemList("minecraft:dirt"));
        NBTTagCompound fresh = priorPlayer(itemList("minecraft:diamond", "minecraft:emerald")); // reopened this session

        boolean carried = PlayerTag.carryForwardEnderItems(prior, fresh);

        assertFalse(carried, "the re-captured ender chest is authoritative, nothing carried back");
        assertEquals(2, fresh.getTagList("EnderItems", 10).tagCount(), "the fresh contents win");
    }

    @Test
    void anEmptyPriorEnderChestCarriesNothing() {
        NBTTagCompound prior = priorPlayer(new NBTTagList());
        NBTTagCompound fresh = playerTag();

        assertFalse(PlayerTag.carryForwardEnderItems(prior, fresh), "an empty prior is not a recovery");
    }

    @Test
    void aPriorWithNoEnderItemsKeyCarriesNothing() {
        NBTTagCompound prior = new NBTTagCompound(); // a pre-engine or absent prior player tag
        NBTTagCompound fresh = playerTag();

        assertFalse(PlayerTag.carryForwardEnderItems(prior, fresh));
    }
}
