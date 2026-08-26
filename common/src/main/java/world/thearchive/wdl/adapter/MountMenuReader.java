// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.entity.Entity;
import net.minecraft.inventory.Container;
import org.jspecify.annotations.Nullable;

/**
 * The shared read of the mount a mount-inventory menu was opened for. The client builds that menu from the mount-screen
 * packet's entity id, so the menu itself names the animal the SERVER opened it for, and the open-container bind reads
 * it here instead of inferring the animal from the crosshair or the ridden vehicle. The menu holds it in a non-public
 * field with no getter, reachable only through a loader-specific mechanism (a Fabric access widener, a NeoForge access
 * transformer).
 *
 * <p>Mirrors {@link ConnectionTee}: the MC-typed accessor is declared abstract here, in common, which is compiled
 * without either loader's widening, and each per-loader plug ships the concrete read. Published statically because the
 * bind that consumes it holds no loader reference, and installed once from the loader entry point, before any capture
 * can run. The parameter is the plain {@link Container} rather than the mount menu type, so each plug names the menu
 * class its own band ships.
 */
public abstract class MountMenuReader {
    // An install this read cannot see costs every chested-animal capture, silently and with no log line.
    private static volatile @Nullable MountMenuReader installed;

    /** Publish this loader's reader; called once from the loader entry point. */
    public static void install(MountMenuReader reader) {
        installed = reader;
    }

    /**
     * The mount {@code menu} names, or {@code null} when it is not a mount-inventory menu.
     *
     * <p>Null is also what an uninstalled reader returns, and that case does not drop the open: it demotes the mount
     * menu to the block axis, where the slot-count match is the only thing left between it and a block bind, and a
     * strength-one chested llama's menu is five non-player slots, exactly a hopper's and a brewing stand's. No vanilla
     * action reaches that coincidence today, because opening a llama's inventory begins with a click on the llama that
     * records an entity intent, and the click-less routes are limited to donkeys and mules at seventeen slots. Treat
     * the uninstalled state as a real hazard rather than a safe no-op if anything ever makes a block target and a
     * click-less mount open coexist.
     */
    static @Nullable Entity mountOf(Container menu) {
        MountMenuReader reader = installed;
        return reader != null ? reader.mount(menu) : null;
    }

    /** The menu's mount; the per-loader plug widens or transforms the non-public field. */
    protected abstract @Nullable Entity mount(Container menu);
}
