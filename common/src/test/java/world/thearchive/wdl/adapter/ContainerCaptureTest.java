// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagShort;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

class ContainerCaptureTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    @Test
    void brewingStateRidesTheHolderWithVanillaTypes() {
        NBTTagCompound holder = new NBTTagCompound();
        ContainerCapture.putBrewingState(holder, 123, 7);
        assertEquals((short) 123, holder.getShort("BrewTime"));
        assertEquals((byte) 7, holder.getByte("Fuel"));
        // The coercing getShortOr/getByteOr above would pass on a swapped short/byte too, so pin the on-disk
        // tag type directly: vanilla persists BrewTime as a short and Fuel as a byte, and the archive must match.
        assertInstanceOf(NBTTagShort.class, holder.getTag("BrewTime"), "BrewTime must be a short tag");
        assertInstanceOf(NBTTagByte.class, holder.getTag("Fuel"), "Fuel must be a byte tag");
    }

    @Test
    void zeroStateStillWritesBothKeys() {
        NBTTagCompound holder = new NBTTagCompound();
        ContainerCapture.putBrewingState(holder, 0, 0);
        assertEquals(2, holder.getKeySet().size());
    }
}
