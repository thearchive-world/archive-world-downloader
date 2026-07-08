// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * MC-free crafter slot helpers: translate the open menu's per-slot disabled flags into vanilla's sparse
 * {@code disabled_slots} form (the indices of the disabled slots, ascending), which is how the crafter block entity
 * persists them; a 9-flag mask is not the on-disk shape.
 */
public final class CrafterSlots {
    /** The crafter input grid size (3x3). */
    public static final int SLOT_COUNT = 9;

    private CrafterSlots() {}

    /** The vanilla {@code disabled_slots} int array for {@code disabledFlags}: set indices, ascending. */
    public static int[] disabledSlotIndices(boolean[] disabledFlags) {
        int count = 0;
        for (boolean flag : disabledFlags) {
            if (flag) {
                count++;
            }
        }
        int[] indices = new int[count];
        int next = 0;
        for (int i = 0; i < disabledFlags.length; i++) {
            if (disabledFlags[i]) {
                indices[next++] = i;
            }
        }
        return indices;
    }
}
