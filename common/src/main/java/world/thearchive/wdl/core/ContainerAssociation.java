// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.OptionalLong;

/**
 * The container-association guard: decides which block a freshly-opened container menu belongs to. The open-screen
 * packet carries no block position, so the adapter resolves a target for the open and feeds this guard the resulting
 * primitives; the guard binds only on a high-confidence single-block match and drops every uncertain case. Mis-binding
 * is the one failure that corrupts the archive (the wrong block's items), so the rule is deliberately conservative: it
 * prefers capturing nothing to capturing the wrong block.
 *
 * <p>MC-free by construction: it names no {@code net.minecraft.*} type (CI-enforced by
 * {@code :common:checkCoreImports}) and works only on a packed {@code BlockPos} long plus slot counts and a flag, so it
 * unit-tests with hand-fed events and ports byte-identically across era-bands. The live extraction of these signals
 * (the clicked target, the menu's block-slot count, the block's container size) is MC-typed and lives in the adapter.
 * It is a tiny state machine: a confident {@link #open} binds, a close or an uncertain open unbinds, and
 * {@link #boundPos} reflects the live binding.
 *
 * <p>The {@code at*} parameters name the open's resolved target, which outside spectator is the block or entity the
 * player clicked. Nothing here reads or implies the live crosshair; that reaches this guard only through the narrowed
 * spectator branch of {@code ContainerCapture.resolveOpenTarget}. Read the resolved target as the contract everywhere
 * below: a leg that says "the clicked block" would exclude the spectator branch, which does reach these legs.
 *
 * <p>Every {@code open*} leg takes a slot count off the menu and the size of the container the open's contents are
 * lifted from, and binds only where the two agree. The count is a required parameter of each leg rather than a property
 * some legs happen to test, so a new leg, or a menu type peeled off into its own leg, cannot be added without deciding
 * what its count is compared against. Which container supplies the size is per leg and is not always the target block's
 * own: the double-chest leg sums both halves, the chested-animal leg takes the animal's chest and counts only the
 * menu's chest slots, the crafter leg takes the crafting grid and excludes the menu's result slot, and the ender leg
 * takes the player's global ender inventory, since an ender chest has no container of its own. Where the size is not
 * readable from the live world at all, the caller passes the invariant the vanilla menu itself checks on construction,
 * which is a fact about that menu type rather than a sentinel; the lectern leg is the only one that does so.
 */
public final class ContainerAssociation {
    /** Which recognition axis bound the live menu, so the stash-time dispatch need not re-derive it. */
    public enum BindKind {
        CONTAINER,
        ENDER,
        LECTERN,
        ENTITY,
        CHESTED_ANIMAL,
        DOUBLE_CHEST,
        CRAFTER
    }

    private boolean bound;
    private long boundPosKey;
    private long boundSecondaryPosKey;
    private BindKind boundKind = BindKind.CONTAINER;

    /**
     * Decide the binding for a freshly-opened, non-player container menu and remember it. Bind to {@code blockPosKey}
     * only when the resolved block's own single-block storage container has the same number of slots as the menu;
     * otherwise drop and clear any prior binding.
     *
     * <p>Why slot counts and not container identity: the client builds a container menu from its {@code MenuType} with
     * a generic {@code SimpleContainer} (it never sees the block's real {@code BlockEntity} or, for a double chest, the
     * {@code CompoundContainer}), so the only reliable client signal that the menu belongs to this one block is that
     * its block-slot count matches the block's own container size. A double chest is a 54-slot menu over a 27-slot
     * block half -> mismatch -> dropped. A block with no storage (a non-container block, or an ender chest whose
     * contents are per-player) reports {@code blockContainerSize == 0} -> dropped.
     *
     * @param atBlock            the open resolved to a block target (not an entity target, and not an unattributed
     *                           open). False for a chest minecart or boat (an entity target) and for an open no click
     *                           accounts for
     * @param blockPosKey        the packed {@code BlockPos.asLong()} of that block
     * @param menuSlotCount      the menu's block-slot count (its non-player slots)
     * @param blockContainerSize the target block's own container size, or 0 if it has no block storage
     * @return the bound pos key, or {@link OptionalLong#empty()} when the open is dropped
     */
    public OptionalLong open(boolean atBlock, long blockPosKey, int menuSlotCount, int blockContainerSize) {
        if (atBlock && blockContainerSize > 0 && menuSlotCount == blockContainerSize) {
            bound = true;
            boundPosKey = blockPosKey;
            boundKind = BindKind.CONTAINER;
            return OptionalLong.of(blockPosKey);
        }
        bound = false;
        return OptionalLong.empty();
    }

    /**
     * Decide the binding for a freshly-opened lectern menu and remember it. The lectern sibling of {@link #open}: a
     * {@code LecternMenu} is a fixed 1-slot lectern-specific menu (no double-lectern, no {@code CompoundContainer}), so
     * a lectern menu of the lectern's own size over a lectern block the open resolved to is a confident single-block
     * match. Bind to {@code blockPosKey} only on that confident triple; otherwise drop and clear any prior binding.
     *
     * @param atBlock              the open resolved to a block target (not an entity target, and not an unattributed
     *                             open)
     * @param blockPosKey          the packed {@code BlockPos.asLong()} of that block
     * @param blockIsLectern       the target block's block entity is a lectern
     * @param menuSlotCount        the menu's block-slot count (its non-player slots), 1 for a real lectern open
     * @param lecternContainerSize the lectern's own container size. A lectern block entity exposes its book access only
     *                             to the menu it builds, so the caller passes the size the vanilla lectern menu checks
     *                             its container against on construction
     * @return the bound pos key, or {@link OptionalLong#empty()} when the open is dropped
     */
    public OptionalLong openLectern(boolean atBlock, long blockPosKey, boolean blockIsLectern,
            int menuSlotCount, int lecternContainerSize) {
        if (atBlock && blockIsLectern && lecternContainerSize > 0 && menuSlotCount == lecternContainerSize) {
            bound = true;
            boundPosKey = blockPosKey;
            boundKind = BindKind.LECTERN;
            return OptionalLong.of(blockPosKey);
        }
        bound = false;
        return OptionalLong.empty();
    }

    /**
     * Decide the binding for a freshly-opened crafter menu and remember it. The crafter sibling of
     * {@link #openLectern}: a crafter menu is exclusive to crafter blocks, so menu-plus-block plus the crafting-grid
     * size is a confident single-block match. The count compared here is the menu's crafting slots alone, not its
     * non-player slots: the crafter menu carries a tenth, result-container slot, which is also why {@link #open} can
     * never bind it. Bind to {@code blockPosKey} only on that confident quad; otherwise drop and clear any prior
     * binding.
     *
     * @param atBlock               the open resolved to a block target (not an entity target, and not an unattributed
     *                              open)
     * @param blockPosKey           the packed {@code BlockPos.asLong()} of that block
     * @param menuIsCrafter         the open menu is a crafter menu
     * @param blockIsCrafter        the target block's block entity is a crafter
     * @param menuCraftingSlotCount the menu's crafting-grid slot count (the slots backed by its own crafting container,
     *                              so the result slot is excluded)
     * @param crafterContainerSize  the target crafter's own container size, or 0 if it has no block storage
     * @return the bound pos key, or {@link OptionalLong#empty()} when the open is dropped
     */
    public OptionalLong openCrafter(boolean atBlock, long blockPosKey, boolean menuIsCrafter,
            boolean blockIsCrafter, int menuCraftingSlotCount, int crafterContainerSize) {
        if (atBlock && menuIsCrafter && blockIsCrafter && crafterContainerSize > 0
                && menuCraftingSlotCount == crafterContainerSize) {
            bound = true;
            boundPosKey = blockPosKey;
            boundKind = BindKind.CRAFTER;
            return OptionalLong.of(blockPosKey);
        }
        bound = false;
        return OptionalLong.empty();
    }

    /**
     * Decide the binding for a freshly-opened ender-chest menu and remember it. The ender sibling of {@link #open}: an
     * ender chest reports {@code blockContainerSize == 0} (its block entity is not a {@code BaseContainerBlockEntity}),
     * so the size-match {@link #open} can never bind it. An ender chest and a single chest are both a chest menu, so
     * the menu type alone is ambiguous; the target block's block entity being an ender chest is the discriminator, and
     * the menu's slot count must match the player's own ender inventory. Bind to {@code blockPosKey} only on that
     * confident quad; otherwise drop and clear any prior binding. (The bound contents are the player's global ender
     * inventory, so the pos only signals which block the open resolved to; the stash merges into the player tag, not
     * this block.)
     *
     * @param atBlock            the open resolved to a block target (not an entity target, and not an unattributed
     *                           open)
     * @param blockPosKey        the packed {@code BlockPos.asLong()} of that block
     * @param menuIsChest        the open menu is a chest menu. True at the live call site; the false case exists only
     *                           so the negative is unit-testable
     * @param blockIsEnderChest  the target block's block entity is an ender chest
     * @param menuSlotCount      the menu's block-slot count (its non-player slots)
     * @param enderContainerSize the player's own ender-inventory size. The contents are lifted from the MENU, as on
     *                           every other leg: the client's ender container is never synced, so only its size is
     *                           trustworthy client-side, and that size is what the server built this menu over
     * @return the bound pos key, or {@link OptionalLong#empty()} when the open is dropped
     */
    public OptionalLong openEnderChest(boolean atBlock, long blockPosKey, boolean menuIsChest,
            boolean blockIsEnderChest, int menuSlotCount, int enderContainerSize) {
        if (atBlock && menuIsChest && blockIsEnderChest && enderContainerSize > 0
                && menuSlotCount == enderContainerSize) {
            bound = true;
            boundPosKey = blockPosKey;
            boundKind = BindKind.ENDER;
            return OptionalLong.of(blockPosKey);
        }
        bound = false;
        return OptionalLong.empty();
    }

    /**
     * Whether the container-vehicle axis claims a freshly-opened menu at all: the routing precedence the dispatch
     * consults before {@link #openEntityContainer}, kept here so the mis-bind rule is decided MC-free and unit-tested
     * from primitives. A clicked container vehicle is the axis's own target and always claims. The ridden-vehicle leg
     * exists only for the open-inventory-request flow (a chest boat opens through the vehicle and fires no use event),
     * so it claims on the POSITIVE signal that flow leaves behind, the recorded vehicle intent, and on nothing else.
     *
     * <p>The absence of a click is not that signal, and reading it as one claims every open with no provenance while a
     * player rides: an open the client cannot account for, a plugin GUI or a menu opened from a block that seeds no
     * click, is then merged into the vehicle whenever the two slot counts coincide. An open the vehicle axis does not
     * claim falls through to the block axes instead of dropping, which is why this is a routing predicate and not
     * another {@code open*} leg.
     *
     * @param targetIsContainerVehicle the open target is a container-vehicle entity
     * @param ridingContainerVehicle   the player's current vehicle is a container vehicle
     * @param vehicleIntentOpen        an open-inventory request was latched while riding a container vehicle and that
     *                                 same vehicle is still ridden. Note what this does not assert: that this menu is
     *                                 the one that request asked for. Pairing a latch with an open rests on the intent
     *                                 chain's one-open-per-action assumption, which does not hold when a seeding action
     *                                 produces no open
     * @return whether the vehicle axis claims the open; {@code false} routes it to the block axes
     */
    public static boolean shouldClaimVehicleOpen(boolean targetIsContainerVehicle, boolean ridingContainerVehicle,
            boolean vehicleIntentOpen) {
        return targetIsContainerVehicle || (ridingContainerVehicle && vehicleIntentOpen);
    }

    /**
     * Decide the binding for a freshly-opened container-vehicle menu and remember it. The entity sibling of
     * {@link #open}: a chest minecart, hopper minecart, chest boat, or chest raft is recognized by the player the open
     * resolving to a container-vehicle entity whose own container size matches the menu's block-slot count. Bind only
     * on that triple; otherwise drop and clear any prior binding. Unlike the block siblings there is no block pos: the
     * bind target (the entity UUID) lives in the adapter, so this returns a plain bound/dropped flag and
     * {@link #boundPos} carries only the "a menu is bound" signal (its long, 0, is unused for {@link BindKind#ENTITY},
     * as the ender pos is unused by the ender stash). The slot-count match is the same mis-bind guard the block path
     * uses (a hopper minecart is 5, the chest vehicles are 27).
     *
     * @param atEntity                 a bind-candidate entity is present: an entity hit, or the ridden vehicle for a
     *                                 click-less open (the adapter collapses the two)
     * @param entityIsContainerVehicle the candidate entity is a container vehicle (a {@code ContainerEntity})
     * @param menuSlotCount            the menu's block-slot count (its non-player slots)
     * @param entityContainerSize      the target vehicle's own container size, or 0 if none
     * @return whether the open bound to the entity; {@code false} when it is dropped
     */
    public boolean openEntityContainer(boolean atEntity, boolean entityIsContainerVehicle,
            int menuSlotCount, int entityContainerSize) {
        if (atEntity && entityIsContainerVehicle && entityContainerSize > 0
                && menuSlotCount == entityContainerSize) {
            bound = true;
            boundPosKey = 0L; // unused for ENTITY; the bind target (UUID) is the adapter's
            boundKind = BindKind.ENTITY;
            return true;
        }
        bound = false;
        return false;
    }

    /**
     * Decide the binding for a freshly-opened chested-animal (horse) menu and remember it. The chested-animal sibling
     * of {@link #openEntityContainer}: a donkey, mule, llama, or trader llama is recognized by the open resolving to
     * (or the player riding) an {@code AbstractChestedHorse} whose own chest size matches the menu's CHEST-slot count.
     * Bind only on that quad; otherwise drop and clear any prior binding. Like the entity sibling there is no block
     * pos: the bind target (the entity UUID) lives in the adapter, so this returns a plain bound/dropped flag and
     * {@link #boundPos} carries only the "a menu is bound" signal (its long, 0, is unused for
     * {@link BindKind#CHESTED_ANIMAL}). It earns its own kind rather than reusing {@link BindKind#ENTITY} because the
     * chest-only lift differs from the block/vehicle lift and the stash dispatches by kind. The
     * {@code menuChestSlotCount == entityChestSize} match is the same mis-bind guard the block and vehicle paths use; a
     * chestless donkey or plain horse/camel reports {@code entityChestSize == 0} and drops.
     *
     * @param atAnimal              a chested animal was identified for this menu. The caller passes a constant true:
     *                              the animal comes from the MENU itself, so neither this flag nor the next is derived
     *                              from a resolved target or a ridden vehicle
     * @param entityIsChestedAnimal that entity is a chested animal (an {@code AbstractChestedHorse})
     * @param menuChestSlotCount    the open menu's chest-slot count (its non-player slots past the saddle/body)
     * @param entityChestSize       the animal's own chest size ({@code getInventoryColumns() * 3}), or 0 if none
     * @return whether the open bound to the animal; {@code false} when it is dropped
     */
    public boolean openChestedAnimal(boolean atAnimal, boolean entityIsChestedAnimal, int menuChestSlotCount,
            int entityChestSize) {
        if (atAnimal && entityIsChestedAnimal && entityChestSize > 0 && menuChestSlotCount == entityChestSize) {
            bound = true;
            boundPosKey = 0L; // unused for CHESTED_ANIMAL; the bind target (UUID) is the adapter's
            boundKind = BindKind.CHESTED_ANIMAL;
            return true;
        }
        bound = false;
        return false;
    }

    /**
     * Decide the binding for a freshly-opened, 54-slot double-chest menu and remember it. The double-chest sibling of
     * {@link #open}: a large chest opens a 54-slot menu over a {@code CompoundContainer} of two 27-slot chest halves,
     * which the single-block {@link #open} drops on the 54-vs-27 size mismatch (it would mis-merge a 54 menu into a 27
     * block half). Bind only when the open resolved to a double-chest half, the partner resolved to a chest, and the
     * menu's block-slot count equals the SUM of both halves' container sizes (the size-match guard, summed: 54 == 27 +
     * 27); otherwise drop and clear any prior binding.
     *
     * <p>The two halves are stored in MENU-SLOT order so the stash can split the 54 menu slots 27/27 onto the right two
     * positions. The {@code CompoundContainer} is always {@code (RIGHT, LEFT)}, so menu slots {@code 0..n/2} belong to
     * the RIGHT-typed half: {@link #boundPos} is set to the first/RIGHT half and {@link #boundSecondaryPos} to the
     * second/LEFT half, independent of which half the open resolved to. The adapter passes which target half is the
     * RIGHT one via {@code atRightHalf}, so the load-bearing left/right ordering is decided here, MC-free and
     * unit-testable.
     *
     * @param atBlock               the open resolved to a block target (not an entity target, and not an unattributed
     *                              open)
     * @param atRightHalf           the target half is the RIGHT-typed chest (so it is the first half, slots
     *                              {@code 0..n/2}); false when the target half is the LEFT-typed chest
     * @param targetPosKey          the packed {@code BlockPos.asLong()} of the target half
     * @param partnerPosKey         the packed {@code BlockPos.asLong()} of the other (connected) half
     * @param menuSlotCount         the menu's block-slot count (its non-player slots), 54 for a real double open
     * @param combinedContainerSize the sum of both halves' container sizes, or less if the partner did not resolve to a
     *                              chest
     * @return whether the open bound to the double chest; {@code false} when it is dropped
     */
    public boolean openDoubleChest(boolean atBlock, boolean atRightHalf, long targetPosKey,
            long partnerPosKey, int menuSlotCount, int combinedContainerSize) {
        if (atBlock && combinedContainerSize > 0 && menuSlotCount == combinedContainerSize) {
            bound = true;
            boundPosKey = atRightHalf ? targetPosKey : partnerPosKey; // first half = RIGHT
            boundSecondaryPosKey = atRightHalf ? partnerPosKey : targetPosKey; // second half = LEFT
            boundKind = BindKind.DOUBLE_CHEST;
            return true;
        }
        bound = false;
        return false;
    }

    /**
     * The second-half block pos key the live double-chest menu is bound to (the LEFT-typed half, menu slots
     * {@code n/2..n}), or empty if none is open, it was dropped, or the binding is not a double chest. Only meaningful
     * for {@link BindKind#DOUBLE_CHEST}; {@link #boundPos} carries the first/RIGHT half.
     */
    public OptionalLong boundSecondaryPos() {
        return bound && boundKind == BindKind.DOUBLE_CHEST ? OptionalLong.of(boundSecondaryPosKey)
                : OptionalLong.empty();
    }

    /** The block pos key the currently-open menu is bound to, or empty if none is open / it was dropped. */
    public OptionalLong boundPos() {
        return bound ? OptionalLong.of(boundPosKey) : OptionalLong.empty();
    }

    /** Which axis bound the live menu (only meaningful while {@link #boundPos} is present). */
    public BindKind boundKind() {
        return boundKind;
    }

    /** The menu closed (or a different one is about to open): clear any binding. */
    public void close() {
        bound = false;
    }
}
