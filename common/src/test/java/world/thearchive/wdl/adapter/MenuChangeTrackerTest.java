// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The change-signal guard for {@link MenuChangeTracker}: the per-tick stash gate must fire on the first look at a menu,
 * on a slot's stack being replaced (the sync-packet and click-prediction shape), on an in-place count change (a
 * prediction's grow or shrink), and on a lectern page turn, while an unchanged menu is skipped. Real container-backed
 * {@link Slot}s drive it, the same objects the live menu exposes.
 */
class MenuChangeTrackerTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    private static List<Slot> slotsOver(SimpleContainer container) {
        List<Slot> slots = new ArrayList<>(container.getContainerSize());
        for (int i = 0; i < container.getContainerSize(); i++) {
            slots.add(new Slot(container, i, 0, 0));
        }
        return slots;
    }

    @Test
    void firstLookAlwaysFires() {
        MenuChangeTracker tracker = new MenuChangeTracker();
        List<Slot> slots = slotsOver(new SimpleContainer(3));

        assertTrue(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA),
                "the first look must stash at least once");
        assertFalse(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA), "an unchanged menu is skipped");
    }

    @Test
    void replacedStackFires() {
        MenuChangeTracker tracker = new MenuChangeTracker();
        SimpleContainer container = new SimpleContainer(3);
        List<Slot> slots = slotsOver(container);
        assertTrue(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA));

        container.setItem(1, new ItemStack(Items.DIAMOND, 3));

        assertTrue(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA), "a replaced slot stack is a change");
        assertFalse(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA));
    }

    @Test
    void inPlaceCountChangeFires() {
        MenuChangeTracker tracker = new MenuChangeTracker();
        SimpleContainer container = new SimpleContainer(3);
        ItemStack stack = new ItemStack(Items.DIAMOND, 3);
        container.setItem(0, stack);
        List<Slot> slots = slotsOver(container);
        assertTrue(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA));

        stack.setCount(5);

        assertTrue(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA), "an in-place count change is a change");
        assertFalse(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA));
    }

    @Test
    void inPlaceBundleContentMutationFires() {
        // BundleItem's click-prediction override mutates the slot-resident stack in place: same object, same
        // count, only the bundle_contents component value swapped. The gate must not read that as unchanged.
        MenuChangeTracker tracker = new MenuChangeTracker();
        SimpleContainer container = new SimpleContainer(1);
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
        container.setItem(0, bundle);
        List<Slot> slots = slotsOver(container);
        assertTrue(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA));

        bundle.set(DataComponents.BUNDLE_CONTENTS,
                new BundleContents(List.of(ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.DIAMOND)))));

        assertTrue(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA),
                "an in-place bundle contents swap is a change");
        assertFalse(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA));
    }

    @Test
    void pageTurnFires() {
        MenuChangeTracker tracker = new MenuChangeTracker();
        List<Slot> slots = slotsOver(new SimpleContainer(1));
        assertTrue(tracker.changedSince(slots, 2, MenuChangeTracker.NO_DATA));

        assertTrue(tracker.changedSince(slots, 3, MenuChangeTracker.NO_DATA), "a lectern page turn is a change");
        assertFalse(tracker.changedSince(slots, 3, MenuChangeTracker.NO_DATA));
    }

    @Test
    void resetForcesTheNextStash() {
        MenuChangeTracker tracker = new MenuChangeTracker();
        List<Slot> slots = slotsOver(new SimpleContainer(2));
        assertTrue(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA));
        assertFalse(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA));

        tracker.reset();

        assertTrue(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA),
                "a reset drops the snapshot, so the next look stashes");
    }

    @Test
    void dataOnlyChangeTriggersRestash() {
        MenuChangeTracker tracker = new MenuChangeTracker();
        List<Slot> slots = slotsOver(new SimpleContainer(1));
        assertTrue(tracker.changedSince(slots, 0, new int[] { 5, 20 }));
        assertFalse(tracker.changedSince(slots, 0, new int[] { 5, 20 }));
        assertTrue(tracker.changedSince(slots, 0, new int[] { 6, 20 }), "a data-only change re-stashes");
    }

    @Test
    void dataLengthChangeTriggersRestash() {
        MenuChangeTracker tracker = new MenuChangeTracker();
        List<Slot> slots = slotsOver(new SimpleContainer(1));
        assertTrue(tracker.changedSince(slots, 0, MenuChangeTracker.NO_DATA));
        assertTrue(tracker.changedSince(slots, 0, new int[] { 0 }), "a data-vector length change re-stashes");
    }

    @Test
    void resetForgetsData() {
        MenuChangeTracker tracker = new MenuChangeTracker();
        List<Slot> slots = slotsOver(new SimpleContainer(1));
        assertTrue(tracker.changedSince(slots, 0, new int[] { 1 }));
        tracker.reset();
        assertTrue(tracker.changedSince(slots, 0, new int[] { 1 }), "a reset forgets the last data vector");
    }
}
