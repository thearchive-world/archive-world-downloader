// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
        EntityReconciliation reconciliation = new EntityReconciliation(10, 7, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(10, reconciliation.accounted());
        assertEquals(0, reconciliation.unaccounted());
        assertFalse(reconciliation.hasStructuralLoss());
    }

    @Test
    void aCreateOrFlushFailureIsStructuralEvenWhenFullyAccounted() {
        EntityReconciliation reconciliation = new EntityReconciliation(10, 4, 0, 2, 1, 1, 0, 2, 0, 0, 0, 0);

        assertEquals(10, reconciliation.accounted());
        assertEquals(0, reconciliation.unaccounted());
        assertTrue(reconciliation.hasStructuralLoss(), "createDrops and flushDrops are both non-zero");
    }

    @Test
    void aLargeChurnResidualIsNotStructuralLoss() {
        EntityReconciliation reconciliation = new EntityReconciliation(200, 50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(150, reconciliation.unaccounted());
        assertFalse(reconciliation.hasStructuralLoss());
    }

    @Test
    void aFlushLossIsStructural() {
        EntityReconciliation reconciliation = new EntityReconciliation(10, 8, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0);

        assertEquals(0, reconciliation.unaccounted());
        assertTrue(reconciliation.hasStructuralLoss());
    }

    @Test
    void aPrimedOnlyFlushLossIsStructuralButStaysOutOfThePacketArithmetic() {
        EntityReconciliation reconciliation = new EntityReconciliation(10, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3);

        assertEquals(10, reconciliation.accounted());
        assertEquals(0, reconciliation.unaccounted(), "primed losses must not disturb the packet residual");
        assertTrue(reconciliation.hasStructuralLoss(), "a primed-only flush loss is a real structural loss");
        assertEquals(3, reconciliation.structuralLossCount());
    }

    @Test
    void aPrimedOnlyEncodeFailureIsStructuralButStaysOutOfThePacketArithmetic() {
        EntityReconciliation reconciliation = new EntityReconciliation(10, 10, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0);

        assertEquals(10, reconciliation.accounted());
        assertEquals(0, reconciliation.unaccounted(), "primed losses must not disturb the packet residual");
        assertTrue(reconciliation.hasStructuralLoss(), "a primed encode failure is a real structural loss");
        assertEquals(2, reconciliation.structuralLossCount());
    }

    @Test
    void theStructuralLossCountSumsEveryDestroyedEntityAcrossBothPaths() {
        EntityReconciliation reconciliation = new EntityReconciliation(49, 4, 0, 2, 1, 3, 4, 5, 6, 9, 7, 8);

        assertEquals(42, reconciliation.structuralLossCount());
    }

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
        EntityReconciliation reconciliation = new EntityReconciliation(10, 7, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(0, reconciliation.unaccounted());
        assertFalse(reconciliation.hasStructuralLoss());
    }

    @Test
    void aSinkRefusalAloneIsNotStructural() {
        EntityReconciliation reconciliation = new EntityReconciliation(10, 9, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(10, reconciliation.accounted(), "a refused entity is still a terminal outcome of its spawn");
        assertFalse(reconciliation.hasStructuralLoss());
    }

    @Test
    void anEncodeFailureIsStructuralWhileTheRefusalBesideItIsNot() {
        EntityReconciliation reconciliation = new EntityReconciliation(10, 8, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0);

        assertEquals(10, reconciliation.accounted(), "both are terminal outcomes, so neither inflates the residual");
        assertTrue(reconciliation.hasStructuralLoss(), "the thrown encode lost an entity the sink would have written");
        assertEquals(1, reconciliation.structuralLossCount(), "and the refusal beside it is not counted as lost");
    }

    @Test
    void framesAbandonedByTheFailedDrainAreStructuralRatherThanChurn() {
        EntityReconciliation reconciliation = new EntityReconciliation(10, 6, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0);

        assertEquals(0, reconciliation.unaccounted(), "an abandoned frame is accounted, not churn");
        assertTrue(reconciliation.hasStructuralLoss());
        assertEquals(4, reconciliation.structuralLossCount());
    }

    @Test
    void overAccountingFromReloadIsNotStructuralLoss() {
        EntityReconciliation reconciliation = new EntityReconciliation(8, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(-2, reconciliation.unaccounted());
        assertFalse(reconciliation.hasStructuralLoss());
    }
}
