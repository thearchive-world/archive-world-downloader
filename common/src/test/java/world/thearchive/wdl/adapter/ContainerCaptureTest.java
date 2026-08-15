// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ShortTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

class ContainerCaptureTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void brewingStateRidesTheHolderWithVanillaTypes() {
        CompoundTag holder = new CompoundTag();
        ContainerCapture.putBrewingState(holder, 123, 7);
        assertEquals((short) 123, holder.getShort("BrewTime"));
        assertEquals((byte) 7, holder.getByte("Fuel"));
        // The coercing getShortOr/getByteOr above would pass on a swapped short/byte too, so pin the on-disk
        // tag type directly: vanilla persists BrewTime as a short and Fuel as a byte, and the archive must match.
        assertInstanceOf(ShortTag.class, holder.get("BrewTime"), "BrewTime must be a short tag");
        assertInstanceOf(ByteTag.class, holder.get("Fuel"), "Fuel must be a byte tag");
    }

    @Test
    void zeroStateStillWritesBothKeys() {
        CompoundTag holder = new CompoundTag();
        ContainerCapture.putBrewingState(holder, 0, 0);
        assertEquals(2, holder.getAllKeys().size());
    }
}
