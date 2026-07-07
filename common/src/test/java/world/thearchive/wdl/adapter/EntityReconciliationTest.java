// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The headless guard for the finish reconciliation arithmetic. Every spawn packet on the packet path is accounted to a
 * terminal outcome, but the received-minus-accounted residual carries a large reload / reused-id churn baseline (a
 * fly-past re-announces and supersedes thousands of transient entities), so the residual is a transparency figure, NOT
 * a loss tripwire. The tripwire is the structural drops: an entity-chunk flush loss, a typed-entity create failure, a
 * throwing or malformed single encode, and the frames an aborted finish drain left held, all near-zero in a healthy
 * capture. Two no-tag outcomes are held out of it: the sink's own refusal, which is a non-save vanilla makes too, and
 * the uncaptured drop, which is the privacy gate doing its job. Pure integer accounting, MC-free.
 *
 * <p>The twelve components are positional and all but one are the same type, so each test below fills only the ones it
 * is about and every other stays zero, and each names in its message which component it is claiming about. The order is
 * received, written, nested, uncaptured, sink-refused, create, encode, flush, abort, unbound-dimension, then the two
 * primed-side losses.
 */
class EntityReconciliationTest {
    @Test
    void aFullyWrittenCaptureReconcilesWithNoStructuralLoss() {
        EntityReconciliation reconciliation = new EntityReconciliation(10, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(10, reconciliation.accounted());
        assertEquals(0, reconciliation.unaccounted());
        assertFalse(reconciliation.hasStructuralLoss());
    }

    @Test
    void nestedPassengersAccountForTheReceivedVersusWrittenGap() {
        // Each rider gets its own AddEntity (received++), but a wired passenger saves nested in its vehicle's tag
        // and is not a written root. Subtracting them closes the arithmetic gap; with no create/flush failure
        // there is no structural loss.
        EntityReconciliation reconciliation = new EntityReconciliation(10, 7, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(10, reconciliation.accounted());
        assertEquals(0, reconciliation.unaccounted());
        assertFalse(reconciliation.hasStructuralLoss());
    }

    @Test
    void aCreateOrFlushFailureIsStructuralEvenWhenFullyAccounted() {
        // Uncaptured drops plus sink refusals are accounted but benign; a create failure or a whole-chunk flush
        // loss is structural and flags even when the arithmetic reconciles to zero.
        EntityReconciliation reconciliation = new EntityReconciliation(10, 4, 0, 2, 1, 1, 0, 2, 0, 0, 0, 0);

        assertEquals(10, reconciliation.accounted());
        assertEquals(0, reconciliation.unaccounted());
        assertTrue(reconciliation.hasStructuralLoss(), "createDrops and flushDrops are both non-zero");
    }

    @Test
    void aLargeChurnResidualIsNotStructuralLoss() {
        // The false positive the structural-drop tripwire avoids: 200 received, 50 written, the other 150 are
        // reload / id-reuse churn (a frame diff proved zero frame loss). With no create/flush failure that residual
        // is not a structural loss, so the tripwire stays silent however large the churn.
        EntityReconciliation reconciliation = new EntityReconciliation(200, 50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(150, reconciliation.unaccounted());
        assertFalse(reconciliation.hasStructuralLoss());
    }

    @Test
    void aFlushLossIsStructural() {
        // A whole entity-chunk lost during flush is the most data-destructive exit and must trip,
        // however small the arithmetic residual.
        EntityReconciliation reconciliation = new EntityReconciliation(10, 8, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0);

        assertEquals(0, reconciliation.unaccounted());
        assertTrue(reconciliation.hasStructuralLoss());
    }

    @Test
    void aPrimedOnlyFlushLossIsStructuralButStaysOutOfThePacketArithmetic() {
        // A flush loss on a chunk holding only primed entities destroys them just as surely as a
        // reconstructed-side loss, so it must trip the structural predicate and be counted, even though a
        // primed entity has no spawn packet and therefore never enters the received/accounted arithmetic.
        EntityReconciliation reconciliation = new EntityReconciliation(10, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3);

        assertEquals(10, reconciliation.accounted());
        assertEquals(0, reconciliation.unaccounted(), "primed losses must not disturb the packet residual");
        assertTrue(reconciliation.hasStructuralLoss(), "a primed-only flush loss is a real structural loss");
        assertEquals(3, reconciliation.structuralLossCount());
    }

    @Test
    void aPrimedOnlyEncodeFailureIsStructuralButStaysOutOfThePacketArithmetic() {
        // The encode sibling of the flush case above: a primed entity whose encode threw is destroyed, so it
        // trips the predicate, while still having no spawn packet to reconcile against.
        EntityReconciliation reconciliation = new EntityReconciliation(10, 10, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0);

        assertEquals(10, reconciliation.accounted());
        assertEquals(0, reconciliation.unaccounted(), "primed losses must not disturb the packet residual");
        assertTrue(reconciliation.hasStructuralLoss(), "a primed encode failure is a real structural loss");
        assertEquals(2, reconciliation.structuralLossCount());
    }

    @Test
    void theStructuralLossCountSumsEveryDestroyedEntityAcrossBothPaths() {
        // The partial-download predicate consumes this single figure, so it must cover create failures, encode
        // failures, both flush-loss sources, an aborted drain and the unbound-dimension remainder; the benign
        // uncaptured drops and sink refusals stay out. Every component gets a distinct value so a term summed
        // twice or omitted changes the total.
        EntityReconciliation reconciliation = new EntityReconciliation(49, 4, 0, 2, 1, 3, 4, 5, 6, 9, 7, 8);

        assertEquals(42, reconciliation.structuralLossCount());
    }

    /**
     * The term that separates a drop the download decided from one it could not: an entity received for a dimension
     * other than the one it ended bound to, standing on terrain that dimension DID capture, is not the privacy gate
     * refusing it, so it must not read like the uncaptured drops beside it, whose whole meaning is that the gate
     * refused. Charged as loss because the gate in its own dimension would have allowed the write and nothing performed
     * it.
     */
    @Test
    void framesHeldForAnUnboundDimensionAreStructuralRatherThanPrivacyGateDrops() {
        EntityReconciliation unbound = new EntityReconciliation(10, 8, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0);
        EntityReconciliation uncaptured = new EntityReconciliation(10, 8, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(0, unbound.unaccounted(), "a held remainder is accounted, not churn");
        assertTrue(unbound.hasStructuralLoss(), "and it is loss: it was received and nothing wrote it");
        assertEquals(2, unbound.structuralLossCount());
        assertFalse(uncaptured.hasStructuralLoss(),
                "while the same count of gate refusals stays benign, which is the distinction");
    }

    @Test
    void aCreateFailureIsStructural() {
        EntityReconciliation reconciliation = new EntityReconciliation(10, 9, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0);

        assertTrue(reconciliation.hasStructuralLoss());
    }

    @Test
    void uncapturedDropsAloneAreNotStructural() {
        // Entities dropped at finish because their chunk's terrain was never captured are the documented benign
        // outcome of partial coverage; the tripwire must stay silent on them.
        EntityReconciliation reconciliation = new EntityReconciliation(10, 7, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(0, reconciliation.unaccounted());
        assertFalse(reconciliation.hasStructuralLoss());
    }

    @Test
    void aSinkRefusalAloneIsNotStructural() {
        // The sink refusing an entity is one of vanilla's own non-saves (a passenger saved nested, a removed
        // entity, a player-only vehicle, a non-serializable type), so it is accounted, reported, and never
        // alarmed. This is the half the encode-failure case below is split from.
        EntityReconciliation reconciliation = new EntityReconciliation(10, 9, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(10, reconciliation.accounted(), "a refused entity is still a terminal outcome of its spawn");
        assertFalse(reconciliation.hasStructuralLoss());
    }

    @Test
    void anEncodeFailureIsStructuralWhileTheRefusalBesideItIsNot() {
        // The split the two counters exist for: the same reconciliation carries one entity the sink declined and
        // one whose encode threw, and only the second may reach the verdict. A predicate reading the pair as one
        // figure would either alarm on the refusal or stay silent on the loss.
        EntityReconciliation reconciliation = new EntityReconciliation(10, 8, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0);

        assertEquals(10, reconciliation.accounted(), "both are terminal outcomes, so neither inflates the residual");
        assertTrue(reconciliation.hasStructuralLoss(), "the thrown encode lost an entity the sink would have written");
        assertEquals(1, reconciliation.structuralLossCount(), "and the refusal beside it is not counted as lost");
    }

    @Test
    void framesAbandonedByTheFailedDrainAreStructuralRatherThanChurn() {
        // The case the residual's own label denies: a finish drain that threw leaves received frames that
        // nothing will ever write. Left uncounted they would inflate the unaccounted figure the reconciliation
        // reports as reload churn, so the one residual that IS loss would read as the one thing it is not.
        EntityReconciliation reconciliation = new EntityReconciliation(10, 6, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0);

        assertEquals(0, reconciliation.unaccounted(), "an abandoned frame is accounted, not churn");
        assertTrue(reconciliation.hasStructuralLoss());
        assertEquals(4, reconciliation.structuralLossCount());
    }

    @Test
    void overAccountingFromReloadIsNotStructuralLoss() {
        // A reload or a cross-chunk duplicate can write an entity more than its single received spawn (accepted
        // ghosting), so accounted can exceed received and the residual go negative. Over-capture is never a loss.
        EntityReconciliation reconciliation = new EntityReconciliation(8, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(-2, reconciliation.unaccounted());
        assertFalse(reconciliation.hasStructuralLoss());
    }
}
