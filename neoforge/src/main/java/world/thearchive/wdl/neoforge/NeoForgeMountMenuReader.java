// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.neoforge;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.MountMenuReader;

/**
 * The NeoForge mount-menu read: {@link MountMenuReader}'s seam plus the one non-public read it needs, made reachable by
 * the NeoForge access transformer (the horse-inventory menu's horse).
 */
final class NeoForgeMountMenuReader extends MountMenuReader {
    @Override
    protected @Nullable Entity mount(AbstractContainerMenu menu) {
        // access-transformed: net.minecraft.world.inventory.HorseInventoryMenu horse
        return menu instanceof HorseInventoryMenu mountMenu ? mountMenu.horse : null;
    }
}
