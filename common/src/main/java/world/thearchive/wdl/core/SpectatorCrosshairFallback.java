// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * Which axis, if any, a spectator's crosshair may stand in for on an open no click accounts for. The last attribution
 * rule in the mod that reads the live crosshair at all, and the only one whose answer depends on which loader the build
 * ships with.
 *
 * <p>MC-free by construction, the way {@link ContainerAssociation} is: it names no {@code net.minecraft.*} type and
 * works on booleans the adapter extracts from the live hit result, the gamemode, the block, and the loader bridge. That
 * matters more here than elsewhere, because the automated capture tier runs on ONE loader and this rule is the one
 * place the two loaders take different branches. Deciding it from primitives is what lets both loader configurations be
 * pinned headlessly instead of one being reasoned about.
 *
 * <p>The rule has three parts, each of which exists to stop a different wrong-target write:
 *
 * <p>Only a spectator reaches it at all. Everyone else has a click chain, and where the player LOOKS is not evidence of
 * what opened; a menu bound to a drifted crosshair writes its contents into whatever the view happens to rest on once
 * the slot counts coincide.
 *
 * <p>Only the axis the loader cannot observe is offered. A spectator's right-click is observable on exactly one of the
 * two axes per loader, and the loaders differ on which, so on the axis a loader DOES observe every open reaching this
 * fallback is one no click accounts for, where the crosshair is a guess rather than provenance. The blind axis is
 * different: there the click was real and simply never surfaced, so the crosshair is the only provenance that exists
 * and the open-time drift is accepted to have any capture at all.
 *
 * <p>Only a block a spectator could actually have opened is offered. The server's spectator branch opens a menu solely
 * from the block state's own menu provider, skipping the block's use handler, so a block with no provider cannot be the
 * source of a spectator's open however squarely the crosshair sits on it. The ender chest is exactly that case and is
 * the expensive one, since its bind merges into the player tag rather than into a chunk.
 */
public final class SpectatorCrosshairFallback {
    /** Which crosshair axis may seed the open, or {@link #NONE} when the crosshair is not evidence at all. */
    public enum Axis {
        NONE,
        BLOCK,
        ENTITY
    }

    private SpectatorCrosshairFallback() {}

    /**
     * Decide the axis for one unattributed open. Returns {@link Axis#NONE} for every case the crosshair must not seed,
     * which the caller treats as an open that binds nothing.
     *
     * @param spectator                 the local player is in spectator mode
     * @param loaderObservesBlockClick  this loader's use hook fires for a spectator's block right-click
     * @param loaderObservesEntityClick this loader's use hook fires for a spectator's entity right-click
     * @param crosshairOnBlock          the live hit result is a block hit
     * @param crosshairOnEntity         the live hit result is an entity hit
     * @param blockOpensForSpectator    that block's state reports a menu provider, vanilla's own rule for what a
     *                                  spectator's click can open. Meaningless unless {@code spectator} and
     *                                  {@code crosshairOnBlock} are both set, and callers are expected to withhold it
     *                                  otherwise rather than compute it: reading it costs a mod-overridable block
     *                                  lookup that no other case needs
     * @return the axis the caller may read the crosshair on
     */
    public static Axis axisFor(boolean spectator, boolean loaderObservesBlockClick,
            boolean loaderObservesEntityClick, boolean crosshairOnBlock, boolean crosshairOnEntity,
            boolean blockOpensForSpectator) {
        if (!spectator) {
            return Axis.NONE;
        }
        if (!loaderObservesBlockClick && crosshairOnBlock && blockOpensForSpectator) {
            return Axis.BLOCK;
        }
        if (!loaderObservesEntityClick && crosshairOnEntity) {
            return Axis.ENTITY;
        }
        return Axis.NONE;
    }
}
