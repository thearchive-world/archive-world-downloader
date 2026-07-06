// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SendRangeSamplerTest {
    private final AtomicLong clock = new AtomicLong();
    private final SendRangeSampler sampler = new SendRangeSampler(clock::get, false);

    private void expireStartWindow() {
        clock.addAndGet(SendRangeSampler.WINDOW_NANOS + 1);
    }

    // --- registration and the bits ---

    @Test
    void removalOfNeverMovedNeverRiddenIdSamplesItsHaircutDistance() {
        expireStartWindow();
        sampler.registerSeed(7, 60.0, 80.0); // off-axis anchor AND off-axis player: z = 0 anywhere
        // leaves MATH mutants alive (the dz subtraction needs a nonzero playerZ to be observable)
        assertEquals(100, sampler.removalSample(7, 0.0, -20.0)); // floor(sqrt(60^2 + 100^2)) - 16 = 116 - 16
    }

    @Test
    void removalDropsTheEntryEitherWay() {
        expireStartWindow();
        sampler.registerSeed(7, 100.0, 0.0);
        sampler.removalSample(7, 0.0, 0.0);
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
    }

    @Test
    void aRiddenIdNeverSamplesOnRemoval() {
        expireStartWindow();
        sampler.registerSeed(7, 100.0, 0.0);
        sampler.markRidden(7);
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
    }

    @Test
    void aMovedIdNeverSamplesOnRemoval() {
        expireStartWindow();
        sampler.registerSeed(7, 100.0, 0.0);
        sampler.markMovedRelative(7, true);
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
    }

    @Test
    void zeroDeltaRelativesNeverTripMovedKnownOrUnknown() {
        expireStartWindow();
        sampler.registerSeed(7, 100.0, 0.0);
        sampler.markMovedRelative(7, false);
        assertEquals(84, sampler.removalSample(7, 0.0, 0.0));
        sampler.markMovedRelative(9, false); // unknown id: must NOT create an entry
        sampler.registerSeed(9, 100.0, 0.0);
        assertEquals(84, sampler.removalSample(9, 0.0, 0.0));
    }

    @Test
    void samePositionAbsolutesDoNotTripMovedButFarOnesDo() {
        expireStartWindow();
        sampler.registerSeed(7, 100.0, 0.0);
        sampler.markMovedAbsolute(7, 100.4, 0.0); // within the 1-block epsilon
        assertEquals(84, sampler.removalSample(7, 0.0, 0.0));
        sampler.registerSeed(8, 100.0, 0.0);
        sampler.markMovedAbsolute(8, 130.0, 0.0);
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(8, 0.0, 0.0));
    }

    @Test
    void aFarAbsoluteMarksTheEntryMovedRatherThanDroppingIt() {
        expireStartWindow();
        sampler.registerSeed(7, 100.0, 0.0);
        sampler.markMovedAbsolute(7, 130.0, 0.0);
        // a markMovedAbsolute that dropped the entry instead would let this re-seed re-create it
        // bits-clear and sample 84; the moved bit must survive the putIfAbsent-shaped merge
        sampler.registerSeed(7, 100.0, 0.0);
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
    }

    @Test
    void relativeFlaggedTeleportsTripMoved() {
        expireStartWindow();
        sampler.registerSeed(7, 100.0, 0.0);
        sampler.markMovedRelativeTeleport(7);
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
    }

    // --- pre-registration bits-only entries ---

    @Test
    void aPreRegistrationSetPassengersLeavesItsMarkThroughLaterRegistration() {
        expireStartWindow();
        sampler.markRidden(7); // bits-only entry, no position
        sampler.markMovedAbsolute(7, 55.0, 0.0); // an absolute on a positionless entry: covered, stays marked
        sampler.registerSeed(7, 100.0, 0.0); // merge fills the position, must not clear the bits
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
    }

    @Test
    void theSeedFillPreservesTheEntryTheRiddenArrivalWouldOtherwiseLaunder() {
        expireStartWindow();
        sampler.markRidden(7);
        sampler.registerSeed(7, 100.0, 0.0); // must fill the anchor IN the ridden entry, not replace it
        sampler.registerArrival(7, 100.0, 0.0);
        // a registerSeed that dropped the entry would let the arrival re-create it bits-clear and sample 84
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
    }

    @Test
    void aPreRegistrationAbsoluteMarksMovedThroughLaterRegistration() {
        expireStartWindow();
        sampler.markMovedAbsolute(7, 55.0, 0.0);
        sampler.registerSeed(7, 100.0, 0.0);
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
    }

    @Test
    void aPreRegistrationNonzeroRelativeMarksMovedThroughLaterRegistration() {
        expireStartWindow();
        sampler.markMovedRelative(7, true);
        sampler.registerSeed(7, 100.0, 0.0);
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
    }

    // --- registration merge shapes ---

    @Test
    void seedRegistrationIsPutIfAbsentShaped() {
        expireStartWindow();
        sampler.registerArrival(7, 100.0, 0.0);
        sampler.registerSeed(7, 40.0, 0.0); // must NOT overwrite the arrival anchor
        assertEquals(84, sampler.removalSample(7, 0.0, 0.0));
    }

    @Test
    void arrivalRegistrationOverwritesThePositionButNeverBits() {
        expireStartWindow();
        sampler.registerSeed(7, 40.0, 0.0);
        sampler.markRidden(7);
        sampler.registerArrival(7, 100.0, 0.0); // fresh codec base; bit must survive
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
        sampler.registerArrival(8, 40.0, 0.0);
        sampler.registerArrival(8, 100.0, 0.0);
        assertEquals(84, sampler.removalSample(8, 0.0, 0.0));
    }

    // --- haircut arithmetic ---

    @Test
    void nonpositiveAfterHaircutSkipsButRegistrationStands() {
        expireStartWindow();
        sampler.registerSeed(7, 10.0, 0.0); // 10 - 16 < 0
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.seedSample(7, 0.0, 0.0));
        // the entry survives (registration ungated by the nonpositive skip): once the player is far,
        // the removal samples floor(sqrt(110^2)) - 16 = 94
        assertEquals(94, sampler.removalSample(7, -100.0, 0.0));
    }

    @Test
    void exactHaircutBoundaryIsNonpositive() {
        expireStartWindow();
        sampler.registerSeed(7, 16.0, 0.0); // 16 - 16 == 0: skipped, not fed
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.seedSample(7, 0.0, 0.0));
        // removal's own copy of the boundary
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
    }

    @Test
    void seedSampleIsGatedOnBothBits() {
        expireStartWindow();
        sampler.registerSeed(7, 100.0, 0.0);
        sampler.markRidden(7);
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.seedSample(7, 0.0, 0.0));
        sampler.registerSeed(8, 100.0, 0.0);
        sampler.markMovedRelative(8, true);
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.seedSample(8, 0.0, 0.0));
    }

    @Test
    void arrivalOverwriteNeverClearsTheMovedBit() {
        expireStartWindow();
        sampler.registerSeed(7, 40.0, 0.0);
        sampler.markMovedRelative(7, true);
        sampler.registerArrival(7, 100.0, 0.0);
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
    }

    @Test
    void exactEpsilonAbsoluteIsInert() {
        expireStartWindow();
        sampler.registerSeed(7, 60.0, 80.0); // off-axis anchor: kills the compare's z-term mutants
        sampler.markMovedAbsolute(7, 60.0, 81.0); // exactly the 1-block epsilon: not moved
        assertEquals(84, sampler.removalSample(7, 0.0, 0.0));
        sampler.registerSeed(8, 60.0, 80.0);
        sampler.markMovedAbsolute(8, 60.0, 82.0); // past the epsilon on the z axis: moved
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(8, 0.0, 0.0));
    }

    @Test
    void registrationContinuesWhileSuppressed() {
        // the start window is still armed: registration must not be gated on suppression
        sampler.registerSeed(7, 100.0, 0.0);
        sampler.registerArrival(8, 100.0, 0.0);
        expireStartWindow();
        assertEquals(84, sampler.seedSample(7, 0.0, 0.0));
        assertEquals(84, sampler.seedSample(8, 0.0, 0.0));
    }

    @Test
    void seedSampleKeepsTheEntryAndTakesTheHaircut() {
        expireStartWindow();
        sampler.registerSeed(7, 100.0, 0.0);
        assertEquals(84, sampler.seedSample(7, 0.0, 0.0));
        assertEquals(84, sampler.seedSample(7, 0.0, 0.0)); // idempotent, entry kept
    }

    @Test
    void arrivalSampleTakesNoHaircut() {
        expireStartWindow();
        assertEquals(100, sampler.arrivalSample(0.0, 0.0, 100.0, 0.0));
    }

    // --- anomaly window ---

    @Test
    void theStartWindowSuppressesAllThreeSampleKinds() {
        sampler.registerSeed(7, 100.0, 0.0);
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.arrivalSample(0.0, 0.0, 100.0, 0.0));
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.seedSample(7, 0.0, 0.0));
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
        // the suppressed removal still dropped the entry: a post-window retry finds nothing
        expireStartWindow();
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
    }

    @Test
    void aFastTickReArmsThePendingSweep() {
        expireStartWindow();
        sampler.sweepComplete(sampler.sweepBeginGeneration()); // consume the start arming
        sampler.gateArmTick(SendRangeSampler.FAST_TICK_BLOCKS + 0.1, false);
        expireStartWindow();
        assertTrue(sampler.sweepBeginGeneration() != 0); // the fast tick owed a sweep, uniformly with packets
    }

    @Test
    void exactBoundariesAreInert() {
        expireStartWindow();
        sampler.gateArmTick(SendRangeSampler.FAST_TICK_BLOCKS, false); // exactly 0.8: not a fast tick
        assertFalse(sampler.suppressed());
        sampler.onAnomalyPacket();
        clock.addAndGet(SendRangeSampler.WINDOW_NANOS); // clock == deadline: expired (strict less-than)
        assertFalse(sampler.suppressed());
    }

    @Test
    void theWindowExpiresOnTheClockAndReArmsInFull() {
        expireStartWindow();
        assertFalse(sampler.suppressed());
        sampler.onAnomalyPacket();
        assertTrue(sampler.suppressed());
        clock.addAndGet(SendRangeSampler.WINDOW_NANOS - 1);
        assertTrue(sampler.suppressed());
        sampler.onAnomalyPacket(); // re-arm in full
        clock.addAndGet(SendRangeSampler.WINDOW_NANOS - 1);
        assertTrue(sampler.suppressed());
        clock.addAndGet(2);
        assertFalse(sampler.suppressed());
    }

    @Test
    void aFastTickArmsTheWindow() {
        expireStartWindow();
        sampler.gateArmTick(SendRangeSampler.FAST_TICK_BLOCKS + 0.1, false);
        assertTrue(sampler.suppressed());
        sampler.gateArmTick(0.0, false);
        assertTrue(sampler.suppressed()); // slow tick does not release early
    }

    @Test
    void respawnClearsTheBookAndArms() {
        expireStartWindow();
        sampler.registerSeed(7, 100.0, 0.0);
        sampler.onRespawn();
        assertTrue(sampler.suppressed());
        expireStartWindow();
        assertEquals(SendRangeSampler.NO_SAMPLE, sampler.removalSample(7, 0.0, 0.0));
    }

    // --- camera hold ---

    @Test
    void bornDetachedHoldsAllFeedsUntilTheAppliedStateAttaches() {
        SendRangeSampler detached = new SendRangeSampler(clock::get, true);
        clock.addAndGet(SendRangeSampler.WINDOW_NANOS + 1);
        assertTrue(detached.suppressed());
        detached.gateArmTick(0.0, false); // applied state now attached
        detached.gateArmTick(0.0, false); // full tick boundary observed
        assertFalse(detached.suppressed());
    }

    @Test
    void aTeedSetCameraHoldsUntilConsumedAcrossTheFullAttachedBoundary() {
        expireStartWindow();
        sampler.gateArmTick(0.0, false);
        sampler.onSetCamera();
        assertTrue(sampler.suppressed());
        sampler.gateArmTick(0.0, false); // first attached observation
        sampler.gateArmTick(0.0, false); // full boundary: consume
        assertFalse(sampler.suppressed());
    }

    @Test
    void aFreshLatchDuringConsumeIsNotEaten() {
        expireStartWindow();
        sampler.gateArmTick(0.0, false);
        sampler.onSetCamera();
        sampler.gateArmTick(0.0, false);
        sampler.onSetCamera(); // second detach queued between observations
        sampler.gateArmTick(0.0, false); // consumes only the FIRST latch generation
        assertTrue(sampler.suppressed());
    }

    @Test
    void appliedDetachedStateHoldsRegardlessOfTheLatch() {
        expireStartWindow();
        sampler.gateArmTick(0.0, true);
        assertTrue(sampler.suppressed());
    }

    // --- sweep generations ---

    @Test
    void aSweepIsDueOncePerArmingAndOnlyWhenQuiet() {
        assertEquals(0, sampler.sweepBeginGeneration()); // start window still armed
        expireStartWindow();
        int generation = sampler.sweepBeginGeneration();
        assertTrue(generation != 0);
        sampler.sweepComplete(generation);
        assertEquals(0, sampler.sweepBeginGeneration()); // consumed
    }

    @Test
    void aReArmDuringTheSweepIsNotEaten() {
        expireStartWindow();
        int generation = sampler.sweepBeginGeneration();
        sampler.onAnomalyPacket(); // re-arm mid-sweep
        sampler.sweepComplete(generation);
        expireStartWindow();
        assertTrue(sampler.sweepBeginGeneration() != 0); // the fresh arming still owes a sweep
    }

    @Test
    void plausibleMaxBlocksScalesTheRenderDistanceWithTwoChunkFloor() {
        assertEquals(160, SendRangeSampler.plausibleMaxBlocks(10));
        assertEquals(48, SendRangeSampler.plausibleMaxBlocks(3)); // above the floor: the distance itself scales
        assertEquals(32, SendRangeSampler.plausibleMaxBlocks(2));
        assertEquals(32, SendRangeSampler.plausibleMaxBlocks(0)); // floored, never a zero ceiling
    }

    @Test
    void sweepIdsListsOnlyBitsClearAnchoredEntries() {
        expireStartWindow();
        sampler.registerSeed(1, 50.0, 0.0);
        sampler.registerSeed(2, 60.0, 0.0);
        sampler.markRidden(2);
        sampler.markRidden(3); // bits-only, no anchor
        int[] ids = sampler.sweepIds();
        assertEquals(1, ids.length);
        assertEquals(1, ids[0]);
    }
}
