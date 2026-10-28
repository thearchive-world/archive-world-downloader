// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractMountInventoryMenu;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.MountMenuReader;

final class FabricMountMenuReader extends MountMenuReader {
    @Override
    protected @Nullable Entity mount(AbstractContainerMenu menu) {
        return menu instanceof AbstractMountInventoryMenu mountMenu ? mountMenu.mount : null;
    }
}
