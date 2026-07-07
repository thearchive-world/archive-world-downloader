// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The spectator crosshair fallback, unit-tested MC-free for BOTH loader configurations. That is the whole reason the
 * decision lives in core: the automated capture tier runs on one loader, so the other loader's branch would otherwise
 * ship on reasoning alone, and this rule is the only attribution decision in the mod whose answer depends on which
 * loader built the jar.
 *
 * <p>The two configurations are named after what they assert rather than after the loaders, so a third loader or a
 * changed upstream hook re-uses them by picking the matching observation pair instead of by editing a loader name. The
 * observation pairs themselves are read from each loader's source and declared on its bridge; what is pinned here is
 * what the mod does with them.
 */
class SpectatorCrosshairFallbackTest {
    // Observes the entity axis and not the block axis, so only the block leg carries provenance (Fabric).
    private static final boolean BLIND_ON_BLOCKS_OBSERVES_BLOCK = false;
    private static final boolean BLIND_ON_BLOCKS_OBSERVES_ENTITY = true;
    // The mirror image: observes the block axis and not the entity axis (NeoForge).
    private static final boolean BLIND_ON_ENTITIES_OBSERVES_BLOCK = true;
    private static final boolean BLIND_ON_ENTITIES_OBSERVES_ENTITY = false;

    private static final boolean SPECTATOR = true;
    private static final boolean ON_BLOCK = true;
    private static final boolean ON_ENTITY = true;
    private static final boolean OPENS = true;

    /** A loader blind on blocks reads the crosshair's block: the click happened and nothing surfaced it. */
    @Test
    void blockBlindLoaderTakesTheBlockLeg() {
        assertEquals(SpectatorCrosshairFallback.Axis.BLOCK,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, BLIND_ON_BLOCKS_OBSERVES_BLOCK,
                        BLIND_ON_BLOCKS_OBSERVES_ENTITY, ON_BLOCK, !ON_ENTITY, OPENS),
                "the axis this loader cannot observe is the one the crosshair must stand in for");
    }

    /**
     * The same loader refuses the crosshair's entity, because it DOES observe a spectator's entity click. Any open
     * reaching the fallback on an observed axis is one no click accounts for, so the crosshair there is a guess that
     * writes a container's contents onto whatever the view rests on once the slot counts coincide.
     */
    @Test
    void blockBlindLoaderRefusesTheEntityLeg() {
        assertEquals(SpectatorCrosshairFallback.Axis.NONE,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, BLIND_ON_BLOCKS_OBSERVES_BLOCK,
                        BLIND_ON_BLOCKS_OBSERVES_ENTITY, !ON_BLOCK, ON_ENTITY, !OPENS),
                "an observed axis has a click chain, so the crosshair is not evidence there");
    }

    /** The mirror loader takes the entity leg, the axis it is blind on. */
    @Test
    void entityBlindLoaderTakesTheEntityLeg() {
        assertEquals(SpectatorCrosshairFallback.Axis.ENTITY,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, BLIND_ON_ENTITIES_OBSERVES_BLOCK,
                        BLIND_ON_ENTITIES_OBSERVES_ENTITY, !ON_BLOCK, ON_ENTITY, !OPENS),
                "the mirror loader's blind axis is the entity one");
    }

    /** And refuses the block leg, which it observes. This is the branch no capture tier runs today. */
    @Test
    void entityBlindLoaderRefusesTheBlockLeg() {
        assertEquals(SpectatorCrosshairFallback.Axis.NONE,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, BLIND_ON_ENTITIES_OBSERVES_BLOCK,
                        BLIND_ON_ENTITIES_OBSERVES_ENTITY, ON_BLOCK, !ON_ENTITY, OPENS),
                "the mirror loader observes block clicks, so its crosshair block leg is never provenance");
    }

    /**
     * A block with no menu provider is refused even on the blind axis. The server's spectator branch opens a menu
     * solely from the provider, so such a block cannot have caused any open a spectator made; an ender chest is exactly
     * that case, and its bind merges into the player tag rather than into a chunk.
     */
    @Test
    void refusesTheBlockLegForProviderlessBlocks() {
        assertEquals(SpectatorCrosshairFallback.Axis.NONE,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, BLIND_ON_BLOCKS_OBSERVES_BLOCK,
                        BLIND_ON_BLOCKS_OBSERVES_ENTITY, ON_BLOCK, !ON_ENTITY, !OPENS),
                "a block with no menu provider cannot have caused a spectator's open");
    }

    /** Outside spectator the crosshair is never consulted, whatever the loader observes or the view rests on. */
    @Test
    void refusesEveryAxisOutsideSpectator() {
        assertEquals(SpectatorCrosshairFallback.Axis.NONE,
                SpectatorCrosshairFallback.axisFor(!SPECTATOR, BLIND_ON_BLOCKS_OBSERVES_BLOCK,
                        BLIND_ON_BLOCKS_OBSERVES_ENTITY, ON_BLOCK, !ON_ENTITY, OPENS),
                "a non-spectator has a click chain, so where they look is not evidence of what opened");
        assertEquals(SpectatorCrosshairFallback.Axis.NONE,
                SpectatorCrosshairFallback.axisFor(!SPECTATOR, BLIND_ON_ENTITIES_OBSERVES_BLOCK,
                        BLIND_ON_ENTITIES_OBSERVES_ENTITY, !ON_BLOCK, ON_ENTITY, !OPENS),
                "and the same holds on the mirror loader's blind axis");
    }

    /** A crosshair resting on nothing seeds nothing, which is the open-into-empty-air case. */
    @Test
    void refusesEveryAxisWithNoHit() {
        assertEquals(SpectatorCrosshairFallback.Axis.NONE,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, BLIND_ON_BLOCKS_OBSERVES_BLOCK,
                        BLIND_ON_BLOCKS_OBSERVES_ENTITY, !ON_BLOCK, !ON_ENTITY, !OPENS),
                "no hit result means no crosshair target to stand in for anything");
    }

    /**
     * A loader that observed BOTH axes would take neither leg. Neither shipped loader is in this state, and the case is
     * pinned so that a loader gaining the missing hook cannot silently leave a leg guessing.
     */
    @Test
    void refusesEveryAxisWhenBothAreObserved() {
        // Both legs are exercised, which needs both hit booleans set. With only the block one set this
        // duplicates entityBlindLoaderRefusesTheBlockLeg and pins the entity leg not at all, which is the
        // half that would silently start guessing if a loader gained the hook it currently lacks.
        assertEquals(SpectatorCrosshairFallback.Axis.NONE,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, true, true, ON_BLOCK, ON_ENTITY, OPENS),
                "with both axes observed the click chain covers the gamemode and the crosshair adds only risk");
    }
}
