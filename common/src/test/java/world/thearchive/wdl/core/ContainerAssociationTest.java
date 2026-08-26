// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * The container-association guard, unit-tested MC-free: given the primitive open-time signals the adapter extracts from
 * the live client, decide whether a freshly-opened container menu binds to a block pos or is dropped. The guard never
 * mis-binds: every uncertain case drops, because an empty container is correct but the wrong block's items corrupt the
 * archive.
 *
 * <p>The binding rule is a slot-count match (not container identity): the client builds a container menu with a generic
 * {@code SimpleContainer}, never the block's block entity, so the only reliable client signal that the menu belongs to
 * one block is that its block-slot count equals the block's own container size. A double chest is a 54-slot menu over a
 * 27-slot half -> mismatch -> drop; a block with no storage (non-container, or an ender chest whose contents are
 * per-player) reports size 0 -> drop. All inputs are primitives, so the whole decision is verified with hand-fed events
 * and no running game.
 */
class ContainerAssociationTest {
    private static final long POS = 1234567L; // an opaque packed BlockPos.toLong()
    private static final int CHEST = 27;
    private static final int LECTERN = 1;
    private static final int ENDER = 27;
    private static final int CRAFTER = 9;

    /** A normal open: looking at a single container block whose size matches the menu's block slots. */
    @Test
    void bindsWhenMenuSlotCountMatchesBlockContainerSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        OptionalLong decision = assoc.open(true, POS, CHEST, CHEST);

        assertEquals(OptionalLong.of(POS), decision, "a confident single-container open must BIND to its pos");
        assertEquals(OptionalLong.of(POS), assoc.boundPos(), "the binding must persist for later stashing");
    }

    /** Binding is size-agnostic: it matches any single container (hopper = 5, furnace = 3, ...). */
    @Test
    void bindsForAnyMatchingContainerSize() {
        assertEquals(OptionalLong.of(POS), new ContainerAssociation().open(true, POS, 5, 5),
                "a hopper (5 slots) binds when the menu has 5 block slots");
    }

    /** Entity-borne containers (chest minecart/boat) and air both yield a non-block hit -> DROP. */
    @Test
    void dropsWhenNotLookingAtBlock() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.open(false, POS, CHEST, CHEST),
                "no block hit (entity/miss) is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A block with no block storage reports size 0: non-container blocks AND ender chests (per-player). */
    @Test
    void dropsBlocksWithNoContainerStorage() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.open(true, POS, CHEST, 0),
                "no block-storage container at the looked-at block (non-container / ender chest) -> DROP");
    }

    /** Double chests / size mismatches: a 54-slot menu over a 27-slot block half cannot bind -> DROP. */
    @Test
    void dropsOnSlotCountMismatch() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.open(true, POS, 54, CHEST),
                "a multi-block container (double chest) has more menu slots than the block holds -> DROP");
    }

    /** A zero-slot menu at a zero-storage block: the size > 0 guard, not >= 0, stops a false empty-menu bind. */
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

    /** Closing the menu clears the binding so the next open starts clean. */
    @Test
    void closeClearsBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.open(true, POS, CHEST, CHEST);
        assertTrue(assoc.boundPos().isPresent());

        assoc.close();

        assertFalse(assoc.boundPos().isPresent(), "after close there is no binding");
    }

    /** A drop leaves no stale binding from a prior bound menu. */
    @Test
    void dropAfterBindClearsPriorBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.open(true, POS, CHEST, CHEST);

        OptionalLong decision = assoc.open(true, 999L, CHEST, 0); // a new, non-container open

        assertEquals(OptionalLong.empty(), decision);
        assertFalse(assoc.boundPos().isPresent(), "a dropped open must not leave the previous binding live");
    }

    /** Opening a different container rebinds to the new pos (last open wins). */
    @Test
    void reopenRebindsToNewPos() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.open(true, POS, CHEST, CHEST);

        assoc.open(true, 4242L, CHEST, CHEST);

        assertEquals(OptionalLong.of(4242L), assoc.boundPos(), "the latest confident open is the live binding");
    }

    // --- openLectern: the lectern sibling. A LecternMenu is a fixed 1-slot lectern-specific menu with no
    // double-lectern, so a lectern menu of the lectern's own size over a lectern BE the player is looking at
    // is a confident single-block match.

    /** A normal lectern open: looking at a lectern block whose menu is a lectern menu -> BIND. */
    @Test
    void lecternBindsOnTheConfidentPair() {
        ContainerAssociation assoc = new ContainerAssociation();

        OptionalLong decision = assoc.openLectern(true, POS, true, LECTERN, LECTERN);

        assertEquals(OptionalLong.of(POS), decision, "a confident lectern open must BIND to its pos");
        assertEquals(OptionalLong.of(POS), assoc.boundPos(), "the binding must persist for later stashing");
    }

    /** No block hit (entity hit or miss) -> DROP. */
    @Test
    void lecternDropsWhenNotLookingAtBlock() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openLectern(false, POS, true, LECTERN, LECTERN),
                "no block hit (entity/miss) is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** The looked-at block is not a lectern block entity -> DROP. */
    @Test
    void lecternDropsWhenBlockIsNotLectern() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openLectern(true, POS, false, LECTERN, LECTERN),
                "a lectern menu over a non-lectern block is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A lectern-typed menu carrying more than the lectern's one book slot is not this lectern's -> DROP. */
    @Test
    void lecternDropsWhenMenuSlotCountDiffersFromTheLecternSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openLectern(true, POS, true, LECTERN + 1, LECTERN),
                "a lectern menu whose slot count is not the lectern's own size is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A zero-sized pair must not bind on the equality alone: the size floor is what rejects it. */
    @Test
    void lecternDropsWhenNeitherSideReportsSlots() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openLectern(true, POS, true, 0, 0),
                "0 == 0 is agreement about nothing -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A dropped lectern open must not leave a prior binding live. */
    @Test
    void lecternDropAfterBindClearsPriorBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.openLectern(true, POS, true, LECTERN, LECTERN);

        OptionalLong decision = assoc.openLectern(true, 999L, false, LECTERN, LECTERN); // a new, non-lectern-block open

        assertEquals(OptionalLong.empty(), decision);
        assertFalse(assoc.boundPos().isPresent(), "a dropped lectern open must not leave the previous binding live");
    }

    /** Closing clears a lectern binding so the next open starts clean. */
    @Test
    void lecternCloseClearsBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.openLectern(true, POS, true, LECTERN, LECTERN);
        assertTrue(assoc.boundPos().isPresent());

        assoc.close();

        assertFalse(assoc.boundPos().isPresent(), "after close there is no lectern binding");
    }

    // --- openEnderChest: the ender sibling. An ender chest reports container size 0 (its BE is not a
    // BaseContainerBlockEntity), so the size-match open() can never bind it; it needs its own predicate.
    // An ender chest and a single chest are BOTH a 27-slot ChestMenu, so the menu type cannot tell them
    // apart: the looked-at block's BE being an ender chest is the discriminator.

    /** A normal ender open: looking at an ender-chest block whose menu is a 27-slot chest menu -> BIND. */
    @Test
    void enderBindsOnTheConfidentTriple() {
        ContainerAssociation assoc = new ContainerAssociation();

        OptionalLong decision = assoc.openEnderChest(true, POS, true, true, ENDER, ENDER);

        assertEquals(OptionalLong.of(POS), decision, "a confident ender open must BIND to its pos");
        assertEquals(OptionalLong.of(POS), assoc.boundPos(), "the binding must persist for later stashing");
        assertEquals(ContainerAssociation.BindKind.ENDER, assoc.boundKind(), "the bind kind is ENDER");
    }

    /** No block hit (entity hit or miss) -> DROP. */
    @Test
    void enderDropsWhenNotLookingAtBlock() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openEnderChest(false, POS, true, true, ENDER, ENDER),
                "no block hit (entity/miss) is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** The open menu is not a chest menu -> DROP. */
    @Test
    void enderDropsWhenMenuIsNotChest() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openEnderChest(true, POS, false, true, ENDER, ENDER),
                "a non-chest menu must not bind via the ender path -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** The looked-at block is not an ender chest (e.g. a normal chest) -> DROP. */
    @Test
    void enderDropsWhenBlockIsNotEnderChest() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openEnderChest(true, POS, true, false, ENDER, ENDER),
                "a chest menu over a non-ender-chest block must take the normal-container path, not this -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /**
     * A chest menu of some other size over a real ender chest is not the player's ender inventory -> DROP. The ender
     * leg's own size guard, which a plugin GUI of a different size on an ender-chest click reaches.
     */
    @Test
    void enderDropsWhenMenuSlotCountDiffersFromTheEnderInventorySize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openEnderChest(true, POS, true, true, 54, ENDER),
                "a chest menu whose slot count is not the ender inventory's size is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A zero-sized pair must not bind on the equality alone: the size floor is what rejects it. */
    @Test
    void enderDropsWhenNeitherSideReportsSlots() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertEquals(OptionalLong.empty(), assoc.openEnderChest(true, POS, true, true, 0, 0),
                "0 == 0 is agreement about nothing -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A dropped ender open must not leave a prior binding live. */
    @Test
    void enderDropAfterBindClearsPriorBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.openEnderChest(true, POS, true, true, ENDER, ENDER);

        // a new open over a non-ender block
        OptionalLong decision = assoc.openEnderChest(true, 999L, true, false, ENDER, ENDER);

        assertEquals(OptionalLong.empty(), decision);
        assertFalse(assoc.boundPos().isPresent(), "a dropped ender open must not leave the previous binding live");
    }

    // --- bind kind: ender and normal chests are both ChestMenu, so the stash-time dispatch must consult
    // the remembered bind kind, not the menu type.

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

    // --- openEntityContainer: the entity sibling. A container VEHICLE (chest minecart, hopper minecart,
    // chest boat, chest raft) is recognized by an entity hit on a ContainerEntity whose own container size
    // matches the menu's block-slot count. There is no block pos: the bind target (the entity UUID) lives in
    // the adapter, so this returns a plain bound/dropped flag, and boundPos() carries only the "a menu is
    // bound" signal (its long, 0, is unused for ENTITY). The bind is a stronger triple than the block path
    // (entity hit, ContainerEntity, slot-count match), so the mis-bind guard is the part that must be right.

    /** A normal vehicle open: looking at a 27-slot container vehicle whose menu has 27 block slots -> BIND. */
    @Test
    void entityBindsOnTheMatchingTriple() {
        ContainerAssociation assoc = new ContainerAssociation();

        boolean bound = assoc.openEntityContainer(true, true, CHEST, CHEST);

        assertTrue(bound, "a confident container-vehicle open must BIND");
        assertTrue(assoc.boundPos().isPresent(), "the binding must persist for later stashing");
        assertEquals(ContainerAssociation.BindKind.ENTITY, assoc.boundKind(), "the bind kind is ENTITY");
    }

    /** Binding is size-agnostic across the vehicle family: a hopper minecart is 5 slots. */
    @Test
    void entityBindsForHopperMinecartSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertTrue(assoc.openEntityContainer(true, true, 5, 5),
                "a hopper minecart (5 slots) binds when the menu has 5 block slots");
        assertEquals(ContainerAssociation.BindKind.ENTITY, assoc.boundKind());
    }

    /** No entity hit (looking at a block or a miss) -> DROP. */
    @Test
    void entityDropsWhenNotLookingAtEntity() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openEntityContainer(false, false, CHEST, CHEST),
                "no entity hit (block/miss) is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** Looking at an entity that is not a container vehicle (a pig, a chestless boat) -> DROP. */
    @Test
    void entityDropsWhenEntityIsNotVehicle() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openEntityContainer(true, false, CHEST, CHEST),
                "an entity that is not a ContainerEntity must not bind -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A slot-count mismatch (a 54-slot menu over a 27-slot vehicle) cannot bind -> DROP. */
    @Test
    void entityDropsOnSlotCountMismatch() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openEntityContainer(true, true, 54, CHEST),
                "a menu whose block-slot count differs from the vehicle's size -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A zero container size (the defensive guard mirroring the block path) -> DROP. */
    @Test
    void entityDropsOnZeroContainerSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openEntityContainer(true, true, 0, 0),
                "a vehicle reporting size 0 -> DROP (no empty-menu false bind)");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A dropped vehicle open must not leave a prior binding live. */
    @Test
    void entityDropAfterBindClearsPriorBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.openEntityContainer(true, true, CHEST, CHEST);

        boolean bound = assoc.openEntityContainer(true, false, CHEST, CHEST); // a new, non-vehicle open

        assertFalse(bound);
        assertFalse(assoc.boundPos().isPresent(), "a dropped vehicle open must not leave the previous binding live");
    }

    // --- shouldClaimVehicleOpen: the routing predicate that decides whether the container-vehicle axis
    // claims a freshly-opened menu at all (the block axes handle it otherwise). The ridden-vehicle leg
    // exists only for the open-inventory-request flow (a chest boat opens through the vehicle and fires no use
    // event), which the tee's observation of that request records as a vehicle intent, so the leg fires on that
    // intent and on nothing else. Keyed on the absence of a click instead, it claims every open with no
    // provenance while a player rides: right-click a provider-less shop sign aboard a chest boat, take the
    // plugin's 27-slot GUI, and the 27 == 27 slot match merges the shop's items into the boat.

    /** A looked-at container vehicle (clicked, or the crosshair in spectator) is the axis's own target -> CLAIM. */
    @Test
    void vehicleClaimsLookedAtContainerVehicle() {
        assertTrue(ContainerAssociation.shouldClaimVehicleOpen(true, false, false),
                "a clicked container vehicle (chest minecart) is the vehicle axis's target -> CLAIM");
        assertTrue(ContainerAssociation.shouldClaimVehicleOpen(true, true, false),
                "a clicked container vehicle while also riding one -> CLAIM (the target leg wins)");
        assertTrue(ContainerAssociation.shouldClaimVehicleOpen(true, true, true),
                "a clicked container vehicle while riding one on a vehicle intent -> CLAIM (either leg suffices)");
    }

    /** An open-inventory request sent while riding a container vehicle is the boat's own menu -> CLAIM. */
    @Test
    void vehicleClaimsInventoryKeyOpenWhileRiding() {
        assertTrue(ContainerAssociation.shouldClaimVehicleOpen(false, true, true),
                "a vehicle-intent open while riding a chest boat -> the boat's own menu -> CLAIM");
    }

    /** An open nothing accounts for is claimed by no one, rider or not -> NO CLAIM. */
    @Test
    void vehicleDoesNotClaimUnattributedOpenWhileRiding() {
        assertFalse(ContainerAssociation.shouldClaimVehicleOpen(false, true, false),
                "a server-opened GUI aboard a chest boat is not the boat's menu -> NO CLAIM");
    }

    /** No vehicle in play at all -> NO CLAIM, whatever seeded the open. */
    @Test
    void vehicleDoesNotClaimWithoutContainerVehicle() {
        assertFalse(ContainerAssociation.shouldClaimVehicleOpen(false, false, false),
                "no looked-at or ridden container vehicle -> NO CLAIM");
        assertFalse(ContainerAssociation.shouldClaimVehicleOpen(false, false, true),
                "a vehicle intent with no container vehicle anywhere -> NO CLAIM");
    }

    // --- openChestedAnimal: the chested-animal sibling. A donkey/mule/llama is recognized by the player
    // looking at (or riding) an AbstractChestedHorse whose own chest size matches the menu's CHEST-slot count.
    // Like the entity sibling there is no block pos: the bind target (the entity UUID) lives in the adapter, so
    // this returns a plain bound/dropped flag and boundPos() carries only the "a menu is bound" signal. It earns
    // its own kind (not ENTITY) because the chest-only lift differs from captureBlockSlots and the stash
    // dispatches by kind. The chest size is the live size (getInventoryColumns()*3), so a 15-slot donkey and a
    // 3-slot strength-1 llama both bind; the slot-count match is the mis-bind guard.
    private static final int DONKEY_CHEST = 15; // 5 columns * 3 rows: a donkey/mule, and a max-strength llama

    /** A normal chested-animal open: at a chested animal whose chest size matches the menu's chest slots -> BIND. */
    @Test
    void chestedAnimalBindsOnTheConfidentQuad() {
        ContainerAssociation assoc = new ContainerAssociation();

        boolean bound = assoc.openChestedAnimal(true, true, DONKEY_CHEST, DONKEY_CHEST);

        assertTrue(bound, "a confident chested-animal open must BIND");
        assertTrue(assoc.boundPos().isPresent(), "the binding must persist for later stashing");
        assertEquals(ContainerAssociation.BindKind.CHESTED_ANIMAL, assoc.boundKind(),
                "the bind kind is CHESTED_ANIMAL");
    }

    /** Binding is size-agnostic across the llama strengths: a strength-1 llama has a 3-slot chest. */
    @Test
    void chestedAnimalBindsForWeakLlamaSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertTrue(assoc.openChestedAnimal(true, true, 3, 3),
                "a strength-1 llama (3 chest slots) binds when the menu has 3 chest slots");
        assertEquals(ContainerAssociation.BindKind.CHESTED_ANIMAL, assoc.boundKind());
    }

    /** Not at an animal (no entity hit, not riding one) -> DROP. */
    @Test
    void chestedAnimalDropsWhenNotAtAnimal() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openChestedAnimal(false, false, DONKEY_CHEST, DONKEY_CHEST),
                "no chested animal looked at or ridden is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** At an entity that is not a chested animal (e.g. a plain or skeleton horse) -> DROP. */
    @Test
    void chestedAnimalDropsWhenEntityIsNotChestedAnimal() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openChestedAnimal(true, false, DONKEY_CHEST, DONKEY_CHEST),
                "an entity that is not an AbstractChestedHorse must not bind -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A zero chest size (a chestless donkey or a plain horse/camel) -> DROP. */
    @Test
    void chestedAnimalDropsOnZeroChestSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openChestedAnimal(true, true, 0, 0),
                "a chestless / plain mount reports chest size 0 -> DROP (nothing to capture)");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A slot-count mismatch (the open menu's chest size disagrees with the inferred entity) -> DROP. */
    @Test
    void chestedAnimalDropsOnSlotCountMismatch() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openChestedAnimal(true, true, 3, DONKEY_CHEST),
                "a menu whose chest-slot count differs from the animal's chest size -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A dropped chested-animal open must not leave a prior binding live. */
    @Test
    void chestedAnimalDropAfterBindClearsPriorBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.openChestedAnimal(true, true, DONKEY_CHEST, DONKEY_CHEST);

        boolean bound = assoc.openChestedAnimal(true, false, DONKEY_CHEST, DONKEY_CHEST); // a new, non-animal open

        assertFalse(bound);
        assertFalse(assoc.boundPos().isPresent(),
                "a dropped chested-animal open must not leave the previous binding live");
    }

    // --- openDoubleChest: the double-chest sibling. A large chest opens a 54-slot ChestMenu over a
    // CompoundContainer of two 27-slot chest halves, which the single-block guard drops on the 54-vs-27
    // mismatch. This predicate recognizes the genuine double open and stores the two halves in MENU-SLOT order
    // so the stash can split the 54 menu slots 27/27 onto the right two block positions. The CompoundContainer
    // is always (RIGHT, LEFT), so menu slots 0..n/2 are the RIGHT-typed half: boundPos = the first/RIGHT half,
    // boundSecondaryPos = the second/LEFT half. The adapter feeds a single atRightHalf primitive, so the
    // load-bearing left/right ordering is decided here and is unit-testable both look directions.
    private static final long PARTNER_POS = 7654321L; // the other half's packed BlockPos.toLong()
    private static final int DOUBLE = 54; // the 54-slot double-chest menu = 27 (RIGHT) + 27 (LEFT)

    /** A normal double-chest open: a 54-slot menu over two 27-slot halves summing to 54 -> BIND. */
    @Test
    void doubleChestBindsOnTheConfidentTuple() {
        ContainerAssociation assoc = new ContainerAssociation();

        boolean bound = assoc.openDoubleChest(true, true, POS, PARTNER_POS, DOUBLE, DOUBLE);

        assertTrue(bound, "a confident double-chest open must BIND");
        assertTrue(assoc.boundPos().isPresent(), "the binding must persist for later stashing");
        assertEquals(ContainerAssociation.BindKind.DOUBLE_CHEST, assoc.boundKind(), "the bind kind is DOUBLE_CHEST");
    }

    /** Looking at the RIGHT half: the looked-at pos is the first half (slots 0..n/2), the partner the second. */
    @Test
    void doubleChestLookingAtRightHalfPutsLookedAtFirst() {
        ContainerAssociation assoc = new ContainerAssociation();

        assoc.openDoubleChest(true, true, POS, PARTNER_POS, DOUBLE, DOUBLE);

        assertEquals(OptionalLong.of(POS), assoc.boundPos(),
                "looking at the RIGHT half, the looked-at pos is the first half (menu slots 0..n/2)");
        assertEquals(OptionalLong.of(PARTNER_POS), assoc.boundSecondaryPos(),
                "the partner (LEFT half) is the second half (menu slots n/2..n)");
    }

    /** Looking at the LEFT half: the halves swap, so the partner (RIGHT) is the first half, not the looked-at. */
    @Test
    void doubleChestLookingAtLeftHalfSwapsTheHalves() {
        ContainerAssociation assoc = new ContainerAssociation();

        assoc.openDoubleChest(true, false, POS, PARTNER_POS, DOUBLE, DOUBLE);

        assertEquals(OptionalLong.of(PARTNER_POS), assoc.boundPos(),
                "looking at the LEFT half, the partner (RIGHT half) is the first half (menu slots 0..n/2)");
        assertEquals(OptionalLong.of(POS), assoc.boundSecondaryPos(),
                "the looked-at LEFT half is the second half (menu slots n/2..n)");
    }

    /** A zero combined size (the partner did not resolve to a chest at all) -> DROP, no empty-menu false bind. */
    @Test
    void doubleChestDropsOnZeroCombinedSize() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openDoubleChest(true, true, POS, PARTNER_POS, 0, 0),
                "a combined size of 0 -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A sum mismatch (partner missing/unloaded, so combined is 27 against a 54 menu) -> DROP. */
    @Test
    void doubleChestDropsOnSumMismatch() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openDoubleChest(true, true, POS, PARTNER_POS, DOUBLE, CHEST),
                "a 54-slot menu whose halves sum to only 27 (partner unloaded) -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** Not a block hit (an entity hit or a miss) -> DROP. */
    @Test
    void doubleChestDropsWhenNotLookingAtBlock() {
        ContainerAssociation assoc = new ContainerAssociation();

        assertFalse(assoc.openDoubleChest(false, true, POS, PARTNER_POS, DOUBLE, DOUBLE),
                "no block hit (entity/miss) is uncertain -> DROP");
        assertFalse(assoc.boundPos().isPresent());
    }

    /** A dropped double-chest open must not leave a prior binding (primary or secondary) live. */
    @Test
    void doubleChestDropAfterBindClearsPriorBinding() {
        ContainerAssociation assoc = new ContainerAssociation();
        assoc.openDoubleChest(true, true, POS, PARTNER_POS, DOUBLE, DOUBLE);

        boolean bound = assoc.openDoubleChest(true, true, POS, PARTNER_POS, DOUBLE, CHEST); // a new, dropped open

        assertFalse(bound);
        assertFalse(assoc.boundPos().isPresent(), "a dropped open must not leave the previous binding live");
        assertEquals(OptionalLong.empty(), assoc.boundSecondaryPos(),
                "a dropped open must not leave the previous secondary pos readable");
    }

    /** The secondary pos is meaningful only for a double chest: every other bind kind reports it empty. */
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

    // --- openCrafter: the crafter sibling. A CrafterMenu is exclusive to crafter blocks, so menu-plus-block
    // plus the crafting-grid size is the confident single-block match. The count compared is the menu's
    // crafting slots alone; the crafter menu's tenth, result-container slot is not the block's.

    /** A normal crafter open: looking at a crafter block whose menu is a crafter menu -> BIND. */
    @Test
    void crafterOpenBindsOnConfidentPair() {
        ContainerAssociation association = new ContainerAssociation();
        OptionalLong bound = association.openCrafter(true, 42L, true, true, CRAFTER, CRAFTER);
        assertTrue(bound.isPresent());
        assertEquals(42L, bound.getAsLong());
        assertEquals(ContainerAssociation.BindKind.CRAFTER, association.boundKind());
    }

    /** No block hit (entity hit or miss) -> DROP. */
    @Test
    void crafterOpenDropsWithoutBlockHit() {
        ContainerAssociation association = new ContainerAssociation();
        assertTrue(!association.openCrafter(false, 42L, true, true, CRAFTER, CRAFTER).isPresent());
    }

    /** The open menu is not a crafter menu -> DROP. */
    @Test
    void crafterOpenDropsOnNonCrafterMenu() {
        ContainerAssociation association = new ContainerAssociation();
        assertTrue(!association.openCrafter(true, 42L, false, true, CRAFTER, CRAFTER).isPresent());
    }

    /** The looked-at block is not a crafter -> DROP. */
    @Test
    void crafterOpenDropsOnNonCrafterBlock() {
        ContainerAssociation association = new ContainerAssociation();
        assertTrue(!association.openCrafter(true, 42L, true, false, CRAFTER, CRAFTER).isPresent());
    }

    /**
     * A crafter-typed menu whose crafting grid is not the block's nine -> DROP. The count the caller passes is the
     * crafting container's alone, so counting the result slot in is exactly the shape this rejects.
     */
    @Test
    void crafterOpenDropsWhenCraftingSlotCountDiffersFromTheBlockSize() {
        ContainerAssociation association = new ContainerAssociation();
        assertTrue(!association.openCrafter(true, 42L, true, true, CRAFTER + 1, CRAFTER).isPresent());
    }

    /** A zero-sized pair must not bind on the equality alone: the size floor is what rejects it. */
    @Test
    void crafterOpenDropsWhenNeitherSideReportsSlots() {
        ContainerAssociation association = new ContainerAssociation();
        assertTrue(!association.openCrafter(true, 42L, true, true, 0, 0).isPresent());
    }

    /** A dropped crafter open must not leave a prior binding live. */
    @Test
    void crafterDropClearsPriorBinding() {
        ContainerAssociation association = new ContainerAssociation();
        association.openCrafter(true, 42L, true, true, CRAFTER, CRAFTER);
        association.openCrafter(true, 43L, true, false, CRAFTER, CRAFTER);
        assertTrue(!association.boundPos().isPresent());
    }

    // --- openMerchant: the merchant sibling. A MerchantMenu is villager-exclusive in vanilla, and its offers
    // come from a list rather than a slotted container, so unlike every other leg there is no size to match: the
    // confidence is the menu type plus the clicked-villager identity the adapter resolves. Like the entity legs
    // there is no block pos (the bind target, the villager UUID, lives in the adapter), so boundPos() carries only
    // the "a menu is bound" signal. Owner-ruled identity-only leg with no size guard.

    /** A normal merchant open: at a villager whose open menu is a merchant menu -> BIND. */
    @Test
    void merchantBindsOnVillagerAndMerchantMenu() {
        ContainerAssociation association = new ContainerAssociation();

        boolean bound = association.openMerchant(true, true);

        assertTrue(bound, "a villager whose menu is a merchant menu must BIND");
        assertTrue(association.boundPos().isPresent(), "the binding must persist for later stashing");
        assertEquals(ContainerAssociation.BindKind.MERCHANT, association.boundKind(), "the bind kind is MERCHANT");
    }

    /** The open target is not a villager (a non-villager, drift, or a superseded intent) -> DROP. */
    @Test
    void merchantDropsWhenTargetIsNotVillager() {
        ContainerAssociation association = new ContainerAssociation();

        assertFalse(association.openMerchant(false, true),
                "a merchant menu the click tracker did not attribute to a villager is uncertain -> DROP");
        assertNotEquals(ContainerAssociation.BindKind.MERCHANT, association.boundKind());
        assertFalse(association.boundPos().isPresent());
    }

    /** The open menu is not a merchant menu -> DROP. */
    @Test
    void merchantDropsWhenMenuIsNotMerchant() {
        ContainerAssociation association = new ContainerAssociation();

        assertFalse(association.openMerchant(true, false),
                "a non-merchant menu must not bind via the merchant path -> DROP");
        assertFalse(association.boundPos().isPresent());
    }

    /** A dropped merchant open must not leave a prior binding live. */
    @Test
    void merchantDropAfterBindClearsPriorBinding() {
        ContainerAssociation association = new ContainerAssociation();
        association.openMerchant(true, true);

        boolean bound = association.openMerchant(false, true); // a new open the tracker cannot attribute

        assertFalse(bound);
        assertFalse(association.boundPos().isPresent(),
                "a dropped merchant open must not leave the previous binding live");
    }
}
