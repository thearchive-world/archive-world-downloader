// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SpectatorCrosshairFallbackTest {
    private static final boolean BLIND_ON_BLOCKS_OBSERVES_BLOCK = false;
    private static final boolean BLIND_ON_BLOCKS_OBSERVES_ENTITY = true;
    private static final boolean BLIND_ON_ENTITIES_OBSERVES_BLOCK = true;
    private static final boolean BLIND_ON_ENTITIES_OBSERVES_ENTITY = false;

    private static final boolean SPECTATOR = true;
    private static final boolean ON_BLOCK = true;
    private static final boolean ON_ENTITY = true;
    private static final boolean OPENS = true;

    @Test
    void blockBlindLoaderTakesTheBlockLeg() {
        assertEquals(SpectatorCrosshairFallback.Axis.BLOCK,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, BLIND_ON_BLOCKS_OBSERVES_BLOCK,
                        BLIND_ON_BLOCKS_OBSERVES_ENTITY, ON_BLOCK, !ON_ENTITY, OPENS),
                "the axis this loader cannot observe is the one the crosshair must stand in for");
    }

    @Test
    void blockBlindLoaderRefusesTheEntityLeg() {
        assertEquals(SpectatorCrosshairFallback.Axis.NONE,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, BLIND_ON_BLOCKS_OBSERVES_BLOCK,
                        BLIND_ON_BLOCKS_OBSERVES_ENTITY, !ON_BLOCK, ON_ENTITY, !OPENS),
                "an observed axis has a click chain, so the crosshair is not evidence there");
    }

    @Test
    void entityBlindLoaderTakesTheEntityLeg() {
        assertEquals(SpectatorCrosshairFallback.Axis.ENTITY,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, BLIND_ON_ENTITIES_OBSERVES_BLOCK,
                        BLIND_ON_ENTITIES_OBSERVES_ENTITY, !ON_BLOCK, ON_ENTITY, !OPENS),
                "the mirror loader's blind axis is the entity one");
    }

    @Test
    void entityBlindLoaderRefusesTheBlockLeg() {
        assertEquals(SpectatorCrosshairFallback.Axis.NONE,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, BLIND_ON_ENTITIES_OBSERVES_BLOCK,
                        BLIND_ON_ENTITIES_OBSERVES_ENTITY, ON_BLOCK, !ON_ENTITY, OPENS),
                "the mirror loader observes block clicks, so its crosshair block leg is never provenance");
    }

    @Test
    void refusesTheBlockLegForProviderlessBlocks() {
        assertEquals(SpectatorCrosshairFallback.Axis.NONE,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, BLIND_ON_BLOCKS_OBSERVES_BLOCK,
                        BLIND_ON_BLOCKS_OBSERVES_ENTITY, ON_BLOCK, !ON_ENTITY, !OPENS),
                "a block with no menu provider cannot have caused a spectator's open");
    }

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

    @Test
    void refusesEveryAxisWithNoHit() {
        assertEquals(SpectatorCrosshairFallback.Axis.NONE,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, BLIND_ON_BLOCKS_OBSERVES_BLOCK,
                        BLIND_ON_BLOCKS_OBSERVES_ENTITY, !ON_BLOCK, !ON_ENTITY, !OPENS),
                "no hit result means no crosshair target to stand in for anything");
    }

    @Test
    void refusesEveryAxisWhenBothAreObserved() {
        // Both legs are exercised, which needs both hit booleans set. With only the block one set this duplicates
        // entityBlindLoaderRefusesTheBlockLeg and pins the entity leg not at all.
        assertEquals(SpectatorCrosshairFallback.Axis.NONE,
                SpectatorCrosshairFallback.axisFor(SPECTATOR, true, true, ON_BLOCK, ON_ENTITY, OPENS),
                "with both axes observed the click chain covers the gamemode and the crosshair adds only risk");
    }
}
