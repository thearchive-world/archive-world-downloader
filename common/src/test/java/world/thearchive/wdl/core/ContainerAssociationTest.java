// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ContainerAssociationTest {
    private static final long POS = 1234567L;
    private static final int CHEST = 27;
    private static final int LECTERN = 1;
    private static final int ENDER = 27;
    private static final int CRAFTER = 9;

    @Test
    void bindsWhenMenuSlotCountMatchesBlockContainerSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        OptionalLong decision = assoc.open(true, POS, CHEST, CHEST);

        assertEquals(OptionalLong.of(POS), decision, "a confident single-container open must BIND to its pos");
        assertEquals(OptionalLong.of(POS), assoc.boundPos(), "the binding must persist for later stashing");
    }

    @Test
    void bindsForAnyMatchingContainerSize() {
        assertEquals(OptionalLong.of(POS), new ContainerAssociation().open(true, POS, 5, 5),
                "a hopper (5 slots) binds when the menu has 5 block slots");
    }

    @Test
    void dropsWhenNotLookingAtBlock() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.open(false, POS, CHEST, CHEST),
                "no block hit (entity/miss) is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void dropsBlocksWithNoContainerStorage() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.open(true, POS, CHEST, 0),
                "no block-storage container at the looked-at block (non-container / ender chest) -> DROP");
    }

    @Test
    void dropsOnSlotCountMismatch() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.open(true, POS, 54, CHEST),
                "a multi-block container (double chest) has more menu slots than the block holds -> DROP");
    }

    @Test
    void dropsZeroSlotOpenAtZeroStorageBlock() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.open(true, POS, 0, 0),
                "a 0-slot menu at a 0-storage block must DROP, never bind on the 0 == 0 match");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void startsWithNoBinding() {
        assertFalse(new ContainerAssociation().boundPos().isPresent(), "no menu open -> no binding");
    }

    @Test
    void closeClearsBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.open(true, POS, CHEST, CHEST);
        assertTrue(assoc.boundPos().isPresent());

        assoc.close();

        assertFalse(assoc.boundPos().isPresent(), "after close there is no binding");
    }

    @Test
    void dropAfterBindClearsPriorBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.open(true, POS, CHEST, CHEST);

        OptionalLong decision = assoc.open(true, 999L, CHEST, 0);

        assertEquals(OptionalLong.empty(), decision);
        assertFalse(assoc.boundPos().isPresent(), "a dropped open must not leave the previous binding live");
    }

    @Test
    void reopenRebindsToNewPos() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.open(true, POS, CHEST, CHEST);

        assoc.open(true, 4242L, CHEST, CHEST);

        assertEquals(OptionalLong.of(4242L), assoc.boundPos(), "the latest confident open is the live binding");
    }

    @Test
    void lecternBindsOnTheConfidentPair() {
        ContainerAssociation assoc = new ContainerAssociation();

        OptionalLong decision = assoc.openLectern(true, POS, true, LECTERN, LECTERN);

        assertEquals(OptionalLong.of(POS), decision, "a confident lectern open must BIND to its pos");
        assertEquals(OptionalLong.of(POS), assoc.boundPos(), "the binding must persist for later stashing");
    }

    @Test
    void lecternDropsWhenNotLookingAtBlock() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openLectern(false, POS, true, LECTERN, LECTERN),
                "no block hit (entity/miss) is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void lecternDropsWhenBlockIsNotLectern() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openLectern(true, POS, false, LECTERN, LECTERN),
                "a lectern menu over a non-lectern block is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void lecternDropsWhenMenuSlotCountDiffersFromTheLecternSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openLectern(true, POS, true, LECTERN + 1, LECTERN),
                "a lectern menu whose slot count is not the lectern's own size is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void lecternDropsWhenNeitherSideReportsSlots() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openLectern(true, POS, true, 0, 0),
                "0 == 0 is agreement about nothing -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void lecternDropAfterBindClearsPriorBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.openLectern(true, POS, true, LECTERN, LECTERN);

        OptionalLong decision = assoc.openLectern(true, 999L, false, LECTERN, LECTERN);

        assertEquals(OptionalLong.empty(), decision);
        assertFalse(assoc.boundPos().isPresent(), "a dropped lectern open must not leave the previous binding live");
    }

    @Test
    void lecternCloseClearsBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.openLectern(true, POS, true, LECTERN, LECTERN);
        assertTrue(assoc.boundPos().isPresent());

        assoc.close();

        assertFalse(assoc.boundPos().isPresent(), "after close there is no lectern binding");
    }

    @Test
    void enderBindsOnTheConfidentTriple() {
        ContainerAssociation assoc = new ContainerAssociation();

        OptionalLong decision = assoc.openEnderChest(true, POS, true, true, ENDER, ENDER);

        assertEquals(OptionalLong.of(POS), decision, "a confident ender open must BIND to its pos");
        assertEquals(OptionalLong.of(POS), assoc.boundPos(), "the binding must persist for later stashing");
        assertEquals(ContainerAssociation.BindKind.ENDER, assoc.boundKind(), "the bind kind is ENDER");
    }

    @Test
    void enderDropsWhenNotLookingAtBlock() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openEnderChest(false, POS, true, true, ENDER, ENDER),
                "no block hit (entity/miss) is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void enderDropsWhenMenuIsNotChest() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openEnderChest(true, POS, false, true, ENDER, ENDER),
                "a non-chest menu must not bind via the ender path -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void enderDropsWhenBlockIsNotEnderChest() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openEnderChest(true, POS, true, false, ENDER, ENDER),
                "a chest menu over a non-ender-chest block must take the normal-container path, not this -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void enderDropsWhenMenuSlotCountDiffersFromTheEnderInventorySize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openEnderChest(true, POS, true, true, 54, ENDER),
                "a chest menu whose slot count is not the ender inventory's size is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void enderDropsWhenNeitherSideReportsSlots() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openEnderChest(true, POS, true, true, 0, 0),
                "0 == 0 is agreement about nothing -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void enderDropAfterBindClearsPriorBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.openEnderChest(true, POS, true, true, ENDER, ENDER);

        OptionalLong decision = assoc.openEnderChest(true, 999L, true, false, ENDER, ENDER);

        assertEquals(OptionalLong.empty(), decision);
        assertFalse(assoc.boundPos().isPresent(), "a dropped ender open must not leave the previous binding live");
    }

    @Test
    void containerOpenRemembersTheContainerKind() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.open(true, POS, CHEST, CHEST);
        assertEquals(ContainerAssociation.BindKind.CONTAINER, assoc.boundKind());
    }

    @Test
    void lecternOpenRemembersTheLecternKind() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.openLectern(true, POS, true, LECTERN, LECTERN);
        assertEquals(ContainerAssociation.BindKind.LECTERN, assoc.boundKind());
    }

    @Test
    void entityBindsOnTheMatchingTriple() {
        ContainerAssociation assoc = new ContainerAssociation();

        boolean bound = assoc.openEntityContainer(true, true, CHEST, CHEST);

        assertTrue(bound, "a confident container-vehicle open must BIND");
        assertTrue(assoc.boundPos().isPresent(), "the binding must persist for later stashing");
        assertEquals(ContainerAssociation.BindKind.ENTITY, assoc.boundKind(), "the bind kind is ENTITY");
    }

    @Test
    void entityBindsForHopperMinecartSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertTrue(assoc.openEntityContainer(true, true, 5, 5),
                "a hopper minecart (5 slots) binds when the menu has 5 block slots");
        assertEquals(ContainerAssociation.BindKind.ENTITY, assoc.boundKind());
    }

    @Test
    void entityDropsWhenNotLookingAtEntity() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openEntityContainer(false, false, CHEST, CHEST),
                "no entity hit (block/miss) is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void entityDropsWhenEntityIsNotVehicle() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openEntityContainer(true, false, CHEST, CHEST),
                "an entity that is not a container vehicle must not bind -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void entityDropsOnSlotCountMismatch() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openEntityContainer(true, true, 54, CHEST),
                "a menu whose block-slot count differs from the vehicle's size -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void entityDropsOnZeroContainerSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openEntityContainer(true, true, 0, 0),
                "a vehicle reporting size 0 -> DROP (no empty-menu false bind)");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void entityDropAfterBindClearsPriorBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.openEntityContainer(true, true, CHEST, CHEST);

        boolean bound = assoc.openEntityContainer(true, false, CHEST, CHEST);

        assertFalse(bound);
        assertFalse(assoc.boundPos().isPresent(), "a dropped vehicle open must not leave the previous binding live");
    }

    @Test
    void vehicleClaimsLookedAtContainerVehicle() {
        assertTrue(ContainerAssociation.shouldClaimVehicleOpen(true, false, false),
                "a clicked container vehicle (chest minecart) is the vehicle axis's target -> CLAIM");
        assertTrue(ContainerAssociation.shouldClaimVehicleOpen(true, true, false),
                "a clicked container vehicle while also riding one -> CLAIM (the target leg wins)");
        assertTrue(ContainerAssociation.shouldClaimVehicleOpen(true, true, true),
                "a clicked container vehicle while riding one on a vehicle intent -> CLAIM (either leg suffices)");
    }

    @Test
    void vehicleClaimsInventoryKeyOpenWhileRiding() {
        assertTrue(ContainerAssociation.shouldClaimVehicleOpen(false, true, true),
                "a vehicle-intent open while riding a container vehicle -> its own menu -> CLAIM");
    }

    @Test
    void vehicleDoesNotClaimUnattributedOpenWhileRiding() {
        assertFalse(ContainerAssociation.shouldClaimVehicleOpen(false, true, false),
                "a server-opened GUI aboard a container vehicle is not its menu -> NO CLAIM");
    }

    @Test
    void vehicleDoesNotClaimWithoutContainerVehicle() {
        assertFalse(ContainerAssociation.shouldClaimVehicleOpen(false, false, false),
                "no looked-at or ridden container vehicle -> NO CLAIM");
        assertFalse(ContainerAssociation.shouldClaimVehicleOpen(false, false, true),
                "a vehicle intent with no container vehicle anywhere -> NO CLAIM");
    }

    private static final int DONKEY_CHEST = 15;

    @Test
    void chestedAnimalBindsOnTheConfidentQuad() {
        ContainerAssociation assoc = new ContainerAssociation();

        boolean bound = assoc.openChestedAnimal(true, true, DONKEY_CHEST, DONKEY_CHEST);

        assertTrue(bound, "a confident chested-animal open must BIND");
        assertTrue(assoc.boundPos().isPresent(), "the binding must persist for later stashing");
        assertEquals(ContainerAssociation.BindKind.CHESTED_ANIMAL, assoc.boundKind(),
                "the bind kind is CHESTED_ANIMAL");
    }

    @Test
    void chestedAnimalBindsForWeakLlamaSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertTrue(assoc.openChestedAnimal(true, true, 3, 3),
                "a strength-1 llama (3 chest slots) binds when the menu has 3 chest slots");
        assertEquals(ContainerAssociation.BindKind.CHESTED_ANIMAL, assoc.boundKind());
    }

    @Test
    void chestedAnimalDropsWhenNotAtAnimal() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openChestedAnimal(false, false, DONKEY_CHEST, DONKEY_CHEST),
                "no chested animal looked at or ridden is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void chestedAnimalDropsWhenEntityIsNotChestedAnimal() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openChestedAnimal(true, false, DONKEY_CHEST, DONKEY_CHEST),
                "an entity that is not an AbstractChestedHorse must not bind -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void chestedAnimalDropsOnZeroChestSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openChestedAnimal(true, true, 0, 0),
                "a chestless / plain mount reports chest size 0 -> DROP (nothing to capture)");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void chestedAnimalDropsOnSlotCountMismatch() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openChestedAnimal(true, true, 3, DONKEY_CHEST),
                "a menu whose chest-slot count differs from the animal's chest size -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void chestedAnimalDropAfterBindClearsPriorBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.openChestedAnimal(true, true, DONKEY_CHEST, DONKEY_CHEST);

        boolean bound = assoc.openChestedAnimal(true, false, DONKEY_CHEST, DONKEY_CHEST);

        assertFalse(bound);
        assertFalse(assoc.boundPos().isPresent(),
                "a dropped chested-animal open must not leave the previous binding live");
    }

    private static final long PARTNER_POS = 7654321L;
    private static final int DOUBLE = 54;

    @Test
    void doubleChestBindsOnTheConfidentTuple() {
        ContainerAssociation assoc = new ContainerAssociation();

        boolean bound = assoc.openDoubleChest(true, true, POS, PARTNER_POS, DOUBLE, DOUBLE);

        assertTrue(bound, "a confident double-chest open must BIND");
        assertTrue(assoc.boundPos().isPresent(), "the binding must persist for later stashing");
        assertEquals(ContainerAssociation.BindKind.DOUBLE_CHEST, assoc.boundKind(), "the bind kind is DOUBLE_CHEST");
    }

    @Test
    void doubleChestLookingAtRightHalfPutsLookedAtFirst() {
        ContainerAssociation assoc = new ContainerAssociation();

        assoc.openDoubleChest(true, true, POS, PARTNER_POS, DOUBLE, DOUBLE);

        assertEquals(OptionalLong.of(POS), assoc.boundPos(),
                "looking at the RIGHT half, the looked-at pos is the first half (menu slots 0..n/2)");
        assertEquals(OptionalLong.of(PARTNER_POS), assoc.boundSecondaryPos(),
                "the partner (LEFT half) is the second half (menu slots n/2..n)");
    }

    @Test
    void doubleChestLookingAtLeftHalfSwapsTheHalves() {
        ContainerAssociation assoc = new ContainerAssociation();

        assoc.openDoubleChest(true, false, POS, PARTNER_POS, DOUBLE, DOUBLE);

        assertEquals(OptionalLong.of(PARTNER_POS), assoc.boundPos(),
                "looking at the LEFT half, the partner (RIGHT half) is the first half (menu slots 0..n/2)");
        assertEquals(OptionalLong.of(POS), assoc.boundSecondaryPos(),
                "the looked-at LEFT half is the second half (menu slots n/2..n)");
    }

    @Test
    void doubleChestDropsOnZeroCombinedSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openDoubleChest(true, true, POS, PARTNER_POS, 0, 0), "a combined size of 0 -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void doubleChestDropsOnSumMismatch() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openDoubleChest(true, true, POS, PARTNER_POS, DOUBLE, CHEST),
                "a 54-slot menu whose halves sum to only 27 (partner unloaded) -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void doubleChestDropsWhenNotLookingAtBlock() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openDoubleChest(false, true, POS, PARTNER_POS, DOUBLE, DOUBLE),
                "no block hit (entity/miss) is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    @Test
    void doubleChestDropAfterBindClearsPriorBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.openDoubleChest(true, true, POS, PARTNER_POS, DOUBLE, DOUBLE);

        boolean bound = assoc.openDoubleChest(true, true, POS, PARTNER_POS, DOUBLE, CHEST);

        assertFalse(bound);
        assertFalse(assoc.boundPos().isPresent(), "a dropped open must not leave the previous binding live");
        assertEquals(OptionalLong.empty(), assoc.boundSecondaryPos(),
                "a dropped open must not leave the previous secondary pos readable");
    }

    @Test
    void boundSecondaryPosIsEmptyForNonDoubleChestKinds() {
        ContainerAssociation assoc = new ContainerAssociation();
        assertEquals(OptionalLong.empty(), assoc.boundSecondaryPos(), "no binding -> no secondary pos");

        assoc.open(true, POS, CHEST, CHEST);
        assertEquals(OptionalLong.empty(), assoc.boundSecondaryPos(), "a CONTAINER bind has no secondary pos");

        assoc.openEnderChest(true, POS, true, true, ENDER, ENDER);
        assertEquals(OptionalLong.empty(), assoc.boundSecondaryPos(), "an ENDER bind has no secondary pos");

        assoc.openLectern(true, POS, true, LECTERN, LECTERN);
        assertEquals(OptionalLong.empty(), assoc.boundSecondaryPos(), "a LECTERN bind has no secondary pos");

        assoc.openEntityContainer(true, true, CHEST, CHEST);
        assertEquals(OptionalLong.empty(), assoc.boundSecondaryPos(), "an ENTITY bind has no secondary pos");

        assoc.openChestedAnimal(true, true, DONKEY_CHEST, DONKEY_CHEST);
        assertEquals(OptionalLong.empty(), assoc.boundSecondaryPos(), "a CHESTED_ANIMAL bind has no secondary pos");

        assoc.openMerchant(true, true);
        assertEquals(OptionalLong.empty(), assoc.boundSecondaryPos(), "a MERCHANT bind has no secondary pos");
    }

    @Test
    void crafterOpenBindsOnConfidentPair() {
        ContainerAssociation association = new ContainerAssociation();
        OptionalLong bound = association.openCrafter(true, 42L, true, true, CRAFTER, CRAFTER);
        assertTrue(bound.isPresent());
        assertEquals(42L, bound.getAsLong());
        assertEquals(ContainerAssociation.BindKind.CRAFTER, association.boundKind());
    }

    @Test
    void crafterOpenDropsWithoutBlockHit() {
        ContainerAssociation association = new ContainerAssociation();
        assertTrue(association.openCrafter(false, 42L, true, true, CRAFTER, CRAFTER).isEmpty());
    }

    @Test
    void crafterOpenDropsOnNonCrafterMenu() {
        ContainerAssociation association = new ContainerAssociation();
        assertTrue(association.openCrafter(true, 42L, false, true, CRAFTER, CRAFTER).isEmpty());
    }

    @Test
    void crafterOpenDropsOnNonCrafterBlock() {
        ContainerAssociation association = new ContainerAssociation();
        assertTrue(association.openCrafter(true, 42L, true, false, CRAFTER, CRAFTER).isEmpty());
    }

    @Test
    void crafterOpenDropsWhenCraftingSlotCountDiffersFromTheBlockSize() {
        ContainerAssociation association = new ContainerAssociation();
        assertTrue(association.openCrafter(true, 42L, true, true, CRAFTER + 1, CRAFTER).isEmpty());
    }

    @Test
    void crafterOpenDropsWhenNeitherSideReportsSlots() {
        ContainerAssociation association = new ContainerAssociation();
        assertTrue(association.openCrafter(true, 42L, true, true, 0, 0).isEmpty());
    }

    @Test
    void crafterDropClearsPriorBinding() {
        ContainerAssociation association = new ContainerAssociation();
        association.openCrafter(true, 42L, true, true, CRAFTER, CRAFTER);
        association.openCrafter(true, 43L, true, false, CRAFTER, CRAFTER);
        assertTrue(association.boundPos().isEmpty());
    }

    @Test
    void merchantBindsOnVillagerAndMerchantMenu() {
        ContainerAssociation association = new ContainerAssociation();

        boolean bound = association.openMerchant(true, true);

        assertTrue(bound, "a villager whose menu is a merchant menu must BIND");
        assertTrue(association.boundPos().isPresent(), "the binding must persist for later stashing");
        assertEquals(ContainerAssociation.BindKind.MERCHANT, association.boundKind(), "the bind kind is MERCHANT");
    }

    @Test
    void merchantDropsWhenTargetIsNotVillager() {
        ContainerAssociation association = new ContainerAssociation();

        assertFalse(association.openMerchant(false, true),
                "a merchant menu the click tracker did not attribute to a villager is uncertain -> DROP");
        assertNotEquals(ContainerAssociation.BindKind.MERCHANT, association.boundKind());
        assertFalse(association.boundPos().isPresent());
    }

    @Test
    void merchantDropsWhenMenuIsNotMerchant() {
        ContainerAssociation association = new ContainerAssociation();

        assertFalse(association.openMerchant(true, false),
                "a non-merchant menu must not bind via the merchant path -> DROP");
        assertFalse(association.boundPos().isPresent());
    }

    @Test
    void merchantDropAfterBindClearsPriorBinding() {
        ContainerAssociation association = new ContainerAssociation();
        association.openMerchant(true, true);

        boolean bound = association.openMerchant(false, true);

        assertFalse(bound);
        assertFalse(association.boundPos().isPresent(),
                "a dropped merchant open must not leave the previous binding live");
    }
}
