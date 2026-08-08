// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the band-agnostic {@link PlayerTag} operations on an already-serialized player tag: the strip
 * knobs (drop {@code Inventory}/{@code SelectedItemSlot}/{@code equipment}/{@code EnderItems}), the unconditional
 * Death-location double strip ({@code LastDeathLocation} + {@code current_explosion_impact_pos}), the {@code Dimension}
 * write and the read that a resume routes by, and the ender-items remap ({@code Items} -> {@code EnderItems}). Pure
 * NBT, so it round-trips headless on hand-built tags and {@link ContainerSink}-captured holders.
 */
class PlayerTagTest {
    private static RegistryAccess registries;
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    /** A player-like tag carrying the strip-able keys plus an unrelated field that must always survive. */
    private static CompoundTag playerTag() {
        CompoundTag tag = new CompoundTag();
        tag.put("Inventory", new ListTag());
        tag.putInt("SelectedItemSlot", 4);
        CompoundTag equipment = new CompoundTag();
        equipment.put("offhand", new CompoundTag());
        tag.put("equipment", equipment); // offhand and armor live here, not in Inventory, since 1.21.5
        tag.put("EnderItems", new ListTag());
        tag.putShort("Air", (short) 300); // an Entity field the strips must never touch
        return tag;
    }

    @Test
    void stripKnobInventoryOffRemovesInventorySelectedSlotAndEquipment() {
        CompoundTag tag = playerTag();
        PlayerTag.applyStripKnobs(tag, false, true);
        assertFalse(tag.contains("Inventory"), "Inventory dropped");
        assertFalse(tag.contains("SelectedItemSlot"), "SelectedItemSlot dropped with it");
        assertFalse(tag.contains("equipment"), "offhand and armor dropped with the inventory they belong to");
        assertTrue(tag.contains("EnderItems"), "ender items kept when only the inventory knob is off");
        assertTrue(tag.contains("Air"), "an unrelated Entity field survives");
    }

    @Test
    void stripKnobEnderChestOffRemovesEnderItemsOnly() {
        CompoundTag tag = playerTag();
        PlayerTag.applyStripKnobs(tag, true, false);
        assertFalse(tag.contains("EnderItems"), "EnderItems dropped");
        assertTrue(tag.contains("Inventory"), "inventory kept when only the ender knob is off");
        assertTrue(tag.contains("SelectedItemSlot"));
        assertTrue(tag.contains("equipment"), "equipment kept with the inventory it belongs to");
    }

    @Test
    void stripKnobsAllOnKeepEverything() {
        CompoundTag tag = playerTag();
        PlayerTag.applyStripKnobs(tag, true, true);
        assertTrue(tag.contains("Inventory"));
        assertTrue(tag.contains("SelectedItemSlot"));
        assertTrue(tag.contains("equipment"));
        assertTrue(tag.contains("EnderItems"));
    }

    @Test
    void stripDeathLocationRemovesBothCoordinateFieldsAndNoToggleRestoresThem() {
        CompoundTag tag = playerTag();
        tag.put("LastDeathLocation", new CompoundTag());
        tag.put("current_explosion_impact_pos", new ListTag()); // value shape irrelevant; the strip drops the key

        PlayerTag.stripDeathLocation(tag);

        assertFalse(tag.contains("LastDeathLocation"), "the last death location is removed");
        assertFalse(tag.contains("current_explosion_impact_pos"), "the explosion-impact coordinate too");

        // There is no flag on stripDeathLocation, and applying the keep-everything knobs cannot bring them back.
        PlayerTag.applyStripKnobs(tag, true, true);
        assertFalse(tag.contains("LastDeathLocation"), "no config combination restores the death location");
        assertFalse(tag.contains("current_explosion_impact_pos"), "nor the explosion-impact coordinate");
        assertTrue(tag.contains("Air"), "the strip leaves the rest of the tag intact");
    }

    @Test
    void setDimensionWritesCanonicalVanillaIdForCanonicalKey() {
        CompoundTag tag = playerTag();
        PlayerTag.setDimension(tag, VanillaDimensions.forType(BuiltinDimensionTypes.NETHER));
        assertEquals("minecraft:the_nether", tag.getString("Dimension"));
    }

    @Test
    void setDimensionWritesGivenIdVerbatim() {
        CompoundTag tag = playerTag();
        ResourceKey<Level> datapackDimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("examplepack", "skylands"));
        PlayerTag.setDimension(tag, datapackDimension);
        assertEquals("examplepack:skylands", tag.getString("Dimension"),
                "setDimension writes the id it is handed verbatim; the by-type canonicalization is VanillaDimensions");
    }

    @Test
    void dimensionOfReadsBackWhatSetDimensionWrote() {
        CompoundTag tag = playerTag();
        PlayerTag.setDimension(tag, VanillaDimensions.forType(BuiltinDimensionTypes.NETHER));
        assertEquals(Level.NETHER, PlayerTag.dimensionOf(tag));
    }

    @Test
    void dimensionOfRefusesAnyDimensionThisCaptureNeverWrote() {
        // A resume reads this off a prior level.dat, so a tag from anywhere else must not resolve to a folder
        // by accident: the overworld is what an unroutable id would collapse onto if this fell back.
        CompoundTag tag = playerTag();
        assertNull(PlayerTag.dimensionOf(tag), "a player tag with no Dimension key names no dimension");
        ResourceKey<Level> datapackDimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("examplepack", "skylands"));
        PlayerTag.setDimension(tag, datapackDimension);
        assertNull(PlayerTag.dimensionOf(tag), "nor does one naming a dimension the capture never routes to");
    }

    @Test
    void setEnderItemsRemapsTheCapturedItemsListIntoEnderItems() {
        CompoundTag tag = playerTag(); // EnderItems starts as the empty list saveWithoutId wrote
        NonNullList<ItemStack> ender = NonNullList.withSize(27, ItemStack.EMPTY);
        ender.set(5, new ItemStack(Items.ENDER_PEARL, 9));
        CompoundTag holder = sink.captureItems(ender, registries); // an "Items" list in ItemStackWithSlot form

        PlayerTag.setEnderItems(tag, holder);

        assertTrue(tag.get("EnderItems") instanceof ListTag, "EnderItems is now the captured list");
        CompoundTag probe = new CompoundTag();
        probe.put("Items", tag.getList("EnderItems", Tag.TAG_COMPOUND)); // read the remapped list via the same codec
        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(probe, back, registries);
        assertEquals(Items.ENDER_PEARL, back.get(5).getItem(), "the captured ender item lands at its EnderItems slot");
        assertEquals(9, back.get(5).getCount());
    }

    @Test
    void setRootVehicleWritesTheVanillaAttachAndEntityShape() {
        CompoundTag tag = playerTag();
        UUID directVehicle = UUID.fromString("0fedcba9-8765-4321-fedc-ba9876543210");
        CompoundTag vehicleTag = EntityFixtures.entityTag("minecraft:chest_boat");

        PlayerTag.setRootVehicle(tag, directVehicle, vehicleTag);

        CompoundTag rootVehicle = tag.getCompound("RootVehicle");
        assertEquals(directVehicle,
                UUIDUtil.CODEC.parse(NbtOps.INSTANCE, rootVehicle.get("Attach")).result().orElse(null),
                "Attach is the direct vehicle UUID in the UUIDUtil.CODEC four-int form ServerPlayer reads");
        assertEquals("minecraft:chest_boat", rootVehicle.getCompound("Entity").getString("id"),
                "the vehicle NBT nests under Entity, the shape loadAndSpawnParentVehicle spawns from");
        assertTrue(tag.contains("Air"), "the rest of the player tag is untouched");
    }

    private static CompoundTag priorPlayerWithRootVehicle() {
        CompoundTag prior = new CompoundTag();
        CompoundTag rootVehicle = new CompoundTag();
        rootVehicle.put("Entity", EntityFixtures.entityTag("minecraft:chest_boat"));
        prior.put("RootVehicle", rootVehicle);
        return prior;
    }

    @Test
    void restorePriorMountContentsIgnoresAnUnseatedResume() {
        CompoundTag prior = priorPlayerWithRootVehicle();
        CompoundTag fresh = playerTag(); // un-seated this session, no RootVehicle

        boolean restored = PlayerTag.restorePriorMountContents(prior, fresh);

        assertFalse(restored,
                "an un-seated resume restores nothing: a dismounted mount is a normal world entity, not player-state");
        assertFalse(fresh.contains("RootVehicle"),
                "the Player slot is left without a mount, so no same-UUID collision");
    }

    @Test
    void restorePriorMountContentsNeedsBothEntityCompounds() {
        CompoundTag prior = priorPlayerWithRootVehicle();
        CompoundTag fresh = playerTag();
        fresh.put("RootVehicle", new CompoundTag()); // seated shape present, but no Entity serialized

        boolean restored = PlayerTag.restorePriorMountContents(prior, fresh);

        assertFalse(restored, "with no fresh Entity compound there is nothing to match or fold into");
        assertFalse(fresh.getCompound("RootVehicle").contains("Entity"),
                "nothing is grafted onto the entity-less RootVehicle");
    }

    @Test
    void restorePriorMountContentsYieldsToTheFreshMount() {
        CompoundTag prior = priorPlayerWithRootVehicle();
        CompoundTag fresh = playerTag();
        CompoundTag freshEntity = EntityFixtures.entityTag("minecraft:oak_boat");
        PlayerTag.setRootVehicle(fresh, UUID.fromString("11111111-2222-3333-4444-555555555555"), freshEntity);

        boolean carried = PlayerTag.restorePriorMountContents(prior, fresh);

        assertFalse(carried, "the fresh mount is authoritative, nothing carries back");
        assertEquals("minecraft:oak_boat",
                fresh.getCompound("RootVehicle").getCompound("Entity").getString("id"),
                "the fresh mount wins");
    }

    @Test
    void restorePriorMountContentsCarriesNothingWhenThePriorHasNone() {
        CompoundTag prior = new CompoundTag();
        CompoundTag fresh = playerTag();

        assertFalse(PlayerTag.restorePriorMountContents(prior, fresh), "no prior mount, nothing to carry");
        assertFalse(fresh.contains("RootVehicle"));
    }

    /** A RootVehicle whose Entity carries the mount's own UUID and an Items list, the seated-mount capture shape. */
    private static CompoundTag mountRootVehicle(UUID mountUuid, ListTag items) {
        CompoundTag rootVehicle = new CompoundTag();
        CompoundTag entity = EntityFixtures.entity("minecraft:chest_boat", mountUuid);
        entity.put("Items", items);
        rootVehicle.put("Entity", entity);
        return rootVehicle;
    }

    private static CompoundTag playerSeatedIn(CompoundTag rootVehicle) {
        CompoundTag player = playerTag();
        player.put("RootVehicle", rootVehicle);
        return player;
    }

    private static ListTag mountItems(CompoundTag player) {
        return player.getCompound("RootVehicle").getCompound("Entity").getList("Items", Tag.TAG_COMPOUND);
    }

    @Test
    void restorePriorMountContentsWhenResumingSeatedInTheSameMount() {
        UUID mount = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        CompoundTag prior = playerSeatedIn(
                mountRootVehicle(mount, itemList("minecraft:diamond", "minecraft:gold_ingot")));
        CompoundTag fresh = playerSeatedIn(mountRootVehicle(mount, new ListTag())); // seated, container not reopened

        boolean carried = PlayerTag.restorePriorMountContents(prior, fresh);

        assertTrue(carried, "the prior download's contents restore onto the same seated mount");
        assertEquals(2, mountItems(fresh).size(), "the empty seated capture is refilled from the prior download");
        assertEquals(mount, UUIDUtil.CODEC.parse(NbtOps.INSTANCE,
                fresh.getCompound("RootVehicle").getCompound("Entity").get("UUID")).result().orElse(null),
                "the fresh mount identity is untouched");
    }

    @Test
    void restorePriorMountContentsYieldsToTheReopenedSeatedMount() {
        UUID mount = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        CompoundTag prior = playerSeatedIn(
                mountRootVehicle(mount, itemList("minecraft:diamond", "minecraft:gold_ingot")));
        CompoundTag fresh = playerSeatedIn(
                mountRootVehicle(mount, itemList("minecraft:emerald"))); // reopened this session

        boolean carried = PlayerTag.restorePriorMountContents(prior, fresh);

        assertFalse(carried, "a reopened seated mount is authoritative, nothing restores");
        assertEquals(1, mountItems(fresh).size(), "the freshly captured contents win");
    }

    @Test
    void restorePriorMountContentsNeverGraftsAcrossTheMountSwitch() {
        CompoundTag prior = playerSeatedIn(mountRootVehicle(
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), itemList("minecraft:diamond")));
        CompoundTag fresh = playerSeatedIn(mountRootVehicle(
                UUID.fromString("11111111-2222-3333-4444-555555555555"), new ListTag())); // a different mount

        boolean carried = PlayerTag.restorePriorMountContents(prior, fresh);

        assertFalse(carried, "a cross-resume mount switch never grafts one mount's contents onto another");
        assertTrue(mountItems(fresh).isEmpty(), "the different fresh mount is left empty");
    }

    @Test
    void restorePriorMountContentsRestoresNothingWhenThePriorMountHeldNothing() {
        UUID mount = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        CompoundTag prior = playerSeatedIn(mountRootVehicle(mount, new ListTag())); // the prior mount was empty too
        CompoundTag fresh = playerSeatedIn(mountRootVehicle(mount, new ListTag()));

        assertFalse(PlayerTag.restorePriorMountContents(prior, fresh), "an empty prior mount is not a recovery");
        assertTrue(mountItems(fresh).isEmpty(), "the fresh seated mount stays empty");
    }

    /**
     * The same mount {@link #mountRootVehicle} builds, nested under a carrier that pushed itself under it. The record
     * vanilla stores is the tree from its root, so a mount in that state is not the root of its own record and the two
     * fixtures differ in nothing else.
     */
    private static CompoundTag nestedMountRootVehicle(UUID carrierUuid, UUID mountUuid, ListTag items) {
        CompoundTag rootVehicle = new CompoundTag();
        CompoundTag mount = mountRootVehicle(mountUuid, items).getCompound("Entity");
        rootVehicle.put("Entity",
                EntityFixtures.entityCarrying(EntityFixtures.entity("minecraft:minecart", carrierUuid), mount));
        return rootVehicle;
    }

    private static ListTag nestedMountItems(CompoundTag player) {
        return player.getCompound("RootVehicle").getCompound("Entity")
                .getList("Passengers", Tag.TAG_COMPOUND).getCompound(0).getList("Items", Tag.TAG_COMPOUND);
    }

    @Test
    void restorePriorMountContentsCarriesOntoTheSameMountNestedUnderTheFreshRoot() {
        UUID mount = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        CompoundTag prior = playerSeatedIn(
                mountRootVehicle(mount, itemList("minecraft:diamond", "minecraft:gold_ingot")));
        CompoundTag fresh = playerSeatedIn(nestedMountRootVehicle(
                UUID.fromString("6b1d5f2c-9a30-4e11-b8c7-5d0e3a71f402"), mount, new ListTag()));

        boolean carried = PlayerTag.restorePriorMountContents(prior, fresh);

        assertTrue(carried, "the same mount is the same mount whether or not something has pushed itself under it");
        assertEquals(2, nestedMountItems(fresh).size(),
                "so the prior download's loot restores onto the node that owns it, not onto the fresh root");
    }

    @Test
    void restorePriorMountContentsNeverGraftsWhenNoNodeMatchesAnywhereInTheTree() {
        CompoundTag prior = playerSeatedIn(nestedMountRootVehicle(
                UUID.fromString("6b1d5f2c-9a30-4e11-b8c7-5d0e3a71f402"),
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), itemList("minecraft:diamond")));
        CompoundTag fresh = playerSeatedIn(nestedMountRootVehicle(
                UUID.fromString("22222222-3333-4444-5555-666666666666"),
                UUID.fromString("11111111-2222-3333-4444-555555555555"), new ListTag()));

        boolean carried = PlayerTag.restorePriorMountContents(prior, fresh);

        assertFalse(carried, "a genuine mount switch matches no node on either side, so nothing grafts");
        assertTrue(nestedMountItems(fresh).isEmpty(), "the different fresh mount is left empty");
    }

    private static ListTag itemList(String... ids) {
        return ItemFixtures.items(ids);
    }

    private static CompoundTag priorPlayer(ListTag enderItems) {
        CompoundTag player = new CompoundTag();
        player.put("EnderItems", enderItems);
        return player;
    }

    @Test
    void aPriorEnderChestCarriesForwardWhenTheFreshOneIsEmpty() {
        CompoundTag prior = priorPlayer(itemList("minecraft:diamond", "minecraft:gold_ingot"));
        CompoundTag fresh = playerTag(); // EnderItems is the empty list saveWithoutId wrote (not re-opened)

        boolean carried = PlayerTag.carryForwardEnderItems(prior, fresh);

        assertTrue(carried, "the prior ender chest carried forward on the resume");
        assertEquals(2, fresh.getList("EnderItems", Tag.TAG_COMPOUND).size(), "the prior contents survive the resume");
    }

    @Test
    void aFreshEnderChestIsKeptOverThePrior() {
        CompoundTag prior = priorPlayer(itemList("minecraft:dirt"));
        CompoundTag fresh = priorPlayer(itemList("minecraft:diamond", "minecraft:emerald")); // re-opened this session

        boolean carried = PlayerTag.carryForwardEnderItems(prior, fresh);

        assertFalse(carried, "the re-captured ender chest is authoritative, nothing carried back");
        assertEquals(2, fresh.getList("EnderItems", Tag.TAG_COMPOUND).size(), "the fresh contents win");
    }

    @Test
    void anEmptyPriorEnderChestCarriesNothing() {
        CompoundTag prior = priorPlayer(new ListTag());
        CompoundTag fresh = playerTag();

        assertFalse(PlayerTag.carryForwardEnderItems(prior, fresh), "an empty prior is not a recovery");
    }

    @Test
    void aPriorWithNoEnderItemsKeyCarriesNothing() {
        CompoundTag prior = new CompoundTag(); // a pre-engine or absent prior player tag
        CompoundTag fresh = playerTag();

        assertFalse(PlayerTag.carryForwardEnderItems(prior, fresh));
    }
}
