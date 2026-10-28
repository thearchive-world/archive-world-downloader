// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.OptionalLong;

/**
 * The container-association guard: decides which block or entity a freshly-opened container menu belongs to. The
 * open-screen packet carries no block position, so the adapter resolves a target for the open and feeds this guard the
 * resulting primitives; the guard binds only on a high-confidence match and drops every uncertain case. A mis-bind
 * archives one container's items as another's, so the rule is deliberately conservative: it prefers capturing nothing
 * to capturing the wrong container.
 *
 * <p>It is a tiny state machine: a confident {@link #open} binds, a close or an uncertain open unbinds, and
 * {@link #boundPos} reflects the live binding.
 *
 * <p>Every {@code open*} leg but the merchant's takes a slot count off the menu and a container size, and binds only
 * where the two agree. The count is a required parameter of each such leg rather than a property some legs happen to
 * test, so a new leg, or a menu type peeled off into its own leg, cannot be added without deciding what its count is
 * compared against. Which container supplies the size is per leg and is not always the target block's own: the
 * double-chest leg sums both halves, the chested-animal leg takes the animal's chest and counts only the menu's chest
 * slots, the crafter leg takes the crafting grid and excludes the menu's result slot, and the ender leg takes the
 * player's global ender inventory, since an ender chest has no container of its own. The merchant leg is the sole
 * exception to the size-count rule: {@link #openMerchant} has no size to match, since a merchant's offers come from a
 * list rather than a slotted container, so it discriminates only on the menu type and the entity the open resolved to.
 */
public final class ContainerAssociation {
    /** Which recognition axis bound the live menu. */
    public enum BindKind {
        CONTAINER,
        ENDER,
        LECTERN,
        ENTITY,
        CHESTED_ANIMAL,
        DOUBLE_CHEST,
        CRAFTER,
        MERCHANT
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
     * @param atBlock            the open resolved to a block target (not an entity target, and not an unattributed
     *                           open)
     * @param blockPosKey        the packed position of that block
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
     * Decide the binding for a freshly-opened lectern menu and remember it. Bind to {@code blockPosKey} only when the
     * open resolved to a lectern block and the menu's slot count equals the lectern's container size; otherwise drop
     * and clear any prior binding.
     *
     * @param atBlock              the open resolved to a block target (not an entity target, and not an unattributed
     *                             open)
     * @param blockPosKey          the packed position of that block
     * @param blockIsLectern       the target block's block entity is a lectern
     * @param menuSlotCount        the menu's block-slot count (its non-player slots)
     * @param lecternContainerSize the lectern's own container size
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
     * Decide the binding for a freshly-opened crafter menu and remember it. The count compared here is the menu's
     * crafting slots alone, not its non-player slots: the crafter menu carries a tenth, result-container slot, which is
     * also why {@link #open} can never bind it. Bind to {@code blockPosKey} only when the menu is a crafter menu, the
     * open resolved to a crafter block, and the crafting-slot count equals the crafter's container size; otherwise drop
     * and clear any prior binding.
     *
     * @param atBlock               the open resolved to a block target (not an entity target, and not an unattributed
     *                              open)
     * @param blockPosKey           the packed position of that block
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
     * ender chest reports {@code blockContainerSize == 0} (its block entity holds no container), so the size-match
     * {@link #open} can never bind it. An ender chest and a single chest are both a chest menu, so the menu type alone
     * is ambiguous; the target block's block entity being an ender chest is the discriminator, and the menu's slot
     * count must match the player's own ender inventory. Bind to {@code blockPosKey} only on that confident quad;
     * otherwise drop and clear any prior binding. (The bound contents are the player's global ender inventory, so the
     * pos only signals which block the open resolved to; the stash merges into the player tag, not this block.)
     *
     * @param atBlock            the open resolved to a block target (not an entity target, and not an unattributed
     *                           open)
     * @param blockPosKey        the packed position of that block
     * @param menuIsChest        the open menu is a chest menu. True at the live call site; the false case exists only
     *                           so the negative is unit-testable
     * @param blockIsEnderChest  the target block's block entity is an ender chest
     * @param menuSlotCount      the menu's block-slot count (its non-player slots)
     * @param enderContainerSize the player's own ender-inventory size. The contents are lifted from the MENU: the
     *                           client's ender container is never synced, so only its size is trustworthy client-side
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
     * Whether the container-vehicle axis claims a freshly-opened menu at all. A targeted container vehicle is the
     * axis's own target and always claims. The ridden-vehicle leg claims only on the positive signal an open-inventory
     * request leaves behind, the recorded vehicle intent, and on nothing else.
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
     *                                 the one that request asked for
     * @return whether the vehicle axis claims the open; {@code false} routes it to the block axes
     */
    public static boolean shouldClaimVehicleOpen(boolean targetIsContainerVehicle, boolean ridingContainerVehicle,
            boolean vehicleIntentOpen) {
        return targetIsContainerVehicle || (ridingContainerVehicle && vehicleIntentOpen);
    }

    /**
     * Decide the binding for a freshly-opened container-vehicle menu and remember it. The entity sibling of
     * {@link #open}: a container vehicle's menu is recognized by a container-vehicle entity, the one the open resolved
     * to or else the ridden one, whose own container size matches the menu's block-slot count. Bind only on that
     * triple; otherwise drop and clear any prior binding. Unlike the block siblings there is no block pos: the bind
     * target (the entity UUID) lives in the adapter, so this returns a plain bound/dropped flag.
     *
     * @param atEntity                 a bind-candidate entity is present: an entity hit, or the ridden vehicle for a
     *                                 click-less open (the adapter collapses the two)
     * @param entityIsContainerVehicle the candidate entity is a container vehicle
     * @param menuSlotCount            the menu's block-slot count (its non-player slots)
     * @param entityContainerSize      the target vehicle's own container size, or 0 if none
     * @return whether the open bound to the entity; {@code false} when it is dropped
     */
    public boolean openEntityContainer(boolean atEntity, boolean entityIsContainerVehicle,
            int menuSlotCount, int entityContainerSize) {
        if (atEntity && entityIsContainerVehicle && entityContainerSize > 0
                && menuSlotCount == entityContainerSize) {
            bound = true;
            boundPosKey = 0L;
            boundKind = BindKind.ENTITY;
            return true;
        }
        bound = false;
        return false;
    }

    /**
     * Decide the binding for a freshly-opened chested-animal menu and remember it. The chested-animal sibling of
     * {@link #openEntityContainer}: bind only when a chested animal was identified for the menu and its own chest size
     * matches the menu's chest-slot count; otherwise drop and clear any prior binding. Like the entity sibling there is
     * no block pos: the bind target (the entity UUID) lives in the adapter, so this returns a plain bound/dropped flag.
     * It earns its own kind rather than reusing {@link BindKind#ENTITY} because the chest-only lift differs from the
     * block/vehicle lift and the stash dispatches by kind.
     *
     * @param atAnimal              a chested animal was identified for this menu. The caller passes a constant true:
     *                              the animal comes from the MENU itself, so neither this flag nor the next is derived
     *                              from a resolved target or a ridden vehicle
     * @param entityIsChestedAnimal that entity is a chested animal
     * @param menuChestSlotCount    the open menu's chest-slot count
     * @param entityChestSize       the animal's own chest size, or 0 if none
     * @return whether the open bound to the animal; {@code false} when it is dropped
     */
    public boolean openChestedAnimal(boolean atAnimal, boolean entityIsChestedAnimal, int menuChestSlotCount,
            int entityChestSize) {
        if (atAnimal && entityIsChestedAnimal && entityChestSize > 0 && menuChestSlotCount == entityChestSize) {
            bound = true;
            boundPosKey = 0L;
            boundKind = BindKind.CHESTED_ANIMAL;
            return true;
        }
        bound = false;
        return false;
    }

    /**
     * Decide the binding for a freshly-opened merchant menu and remember it. An identity-discriminated leg with no size
     * guard: a merchant menu's offers come from a list, not a slotted container, so unlike every other leg there is no
     * size to match. The confidence comes instead from the menu type plus the merchant entity the open resolved to.
     * {@code menuIsMerchant} is true at the one call site; it is a parameter so the negative stays unit-testable here.
     * Like the entity legs there is no block pos: the bind target (the merchant's UUID) lives in the adapter.
     *
     * @param atVillager     the open resolved to a merchant entity target
     * @param menuIsMerchant the open menu is a merchant menu
     * @return whether the open bound to the merchant; {@code false} when it is dropped
     */
    public boolean openMerchant(boolean atVillager, boolean menuIsMerchant) {
        if (atVillager && menuIsMerchant) {
            bound = true;
            boundPosKey = 0L;
            boundKind = BindKind.MERCHANT;
            return true;
        }
        bound = false;
        return false;
    }

    /**
     * Decide the binding for a freshly-opened, 54-slot double-chest menu and remember it. The double-chest sibling of
     * {@link #open}: a large chest opens a 54-slot menu over its two 27-slot halves, which the single-block
     * {@link #open} drops on the 54-vs-27 size mismatch (it would mis-merge a 54 menu into a 27 block half). Bind only
     * when the open resolved to a double-chest half, the partner resolved to a chest, and the menu's block-slot count
     * equals the SUM of both halves' container sizes (the size-match guard, summed: 54 == 27 + 27); otherwise drop and
     * clear any prior binding.
     *
     * <p>The two halves are stored in MENU-SLOT order so the stash can split the 54 menu slots 27/27 onto the right two
     * positions: {@link #boundPos} is set to the half holding menu slots {@code 0..n/2} and {@link #boundSecondaryPos}
     * to the other half, independent of which half the open resolved to.
     *
     * @param atBlock               the open resolved to a block target (not an entity target, and not an unattributed
     *                              open)
     * @param atRightHalf           the target half holds menu slots {@code 0..n/2}; false when it holds the other half
     * @param targetPosKey          the packed position of the target half
     * @param partnerPosKey         the packed position of the other (connected) half
     * @param menuSlotCount         the menu's block-slot count (its non-player slots), 54 for a real double open
     * @param combinedContainerSize the sum of both halves' container sizes, or less if the partner did not resolve to a
     *                              chest
     * @return whether the open bound to the double chest; {@code false} when it is dropped
     */
    public boolean openDoubleChest(boolean atBlock, boolean atRightHalf, long targetPosKey,
            long partnerPosKey, int menuSlotCount, int combinedContainerSize) {
        if (atBlock && combinedContainerSize > 0 && menuSlotCount == combinedContainerSize) {
            bound = true;
            boundPosKey = atRightHalf ? targetPosKey : partnerPosKey;
            boundSecondaryPosKey = atRightHalf ? partnerPosKey : targetPosKey;
            boundKind = BindKind.DOUBLE_CHEST;
            return true;
        }
        bound = false;
        return false;
    }

    /**
     * The second-half block pos key the live double-chest menu is bound to (menu slots {@code n/2..n}), or empty if
     * none is open, it was dropped, or the binding is not a double chest. {@link #boundPos} carries the first half.
     */
    public OptionalLong boundSecondaryPos() {
        return bound && boundKind == BindKind.DOUBLE_CHEST ? OptionalLong.of(boundSecondaryPosKey)
                : OptionalLong.empty();
    }

    /** The pos key the live menu is bound to (0 for an entity bind), or empty if none is bound. */
    public OptionalLong boundPos() {
        return bound ? OptionalLong.of(boundPosKey) : OptionalLong.empty();
    }

    /** Which axis bound the live menu (only meaningful while {@link #boundPos} is present). */
    public BindKind boundKind() {
        return boundKind;
    }

    /** Clear any binding. */
    public void close() {
        bound = false;
    }
}
