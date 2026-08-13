// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.MountMenuReader;

/**
 * The Fabric mount-menu read: {@link MountMenuReader}'s seam plus the one non-public read it needs, made reachable by
 * the Fabric access widener (the horse-inventory menu's horse).
 */
final class FabricMountMenuReader extends MountMenuReader {
    @Override
    protected @Nullable Entity mount(AbstractContainerMenu menu) {
        return menu instanceof HorseInventoryMenu mountMenu ? mountMenu.horse : null; // widened
    }
}
