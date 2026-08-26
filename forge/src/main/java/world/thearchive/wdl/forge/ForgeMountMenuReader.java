// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import java.lang.reflect.Field;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerHorseInventory;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.MountMenuReader;

/**
 * The Forge mount-menu read: {@link MountMenuReader}'s seam plus the one non-public read it needs. The
 * horse-inventory menu's horse is private with no getter, and this band has no compile-time access-widening step, so
 * the shipped accesstransformer.cfg is a runtime-only FMLAT rather than a compile-time widening; the field is read
 * reflectively instead, resolved once by its dev (MCP) name with its production (searge) name as the fallback.
 */
final class ForgeMountMenuReader extends MountMenuReader {
    private static final Field HORSE_FIELD =
            ReflectionHelper.findField(ContainerHorseInventory.class, "horse", "field_111242_f");

    @Override
    protected @Nullable Entity mount(Container menu) {
        if (!(menu instanceof ContainerHorseInventory)) {
            return null;
        }
        try {
            return (Entity) HORSE_FIELD.get(menu);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("could not read the horse-inventory mount", e);
        }
    }
}
