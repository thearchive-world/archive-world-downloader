// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

/**
 * A primed entity is one the accumulator does not hold, so {@code primedEncodeFailures} and {@code primedFlushDrops}
 * stay out of {@link #accounted} and {@link #unaccounted}; they join {@link #hasStructuralLoss} and
 * {@link #structuralLossCount} because either failure destroys a primed entity just as surely as a reconstructed one,
 * and the partial-download predicate must see both paths.
 */
record EntityReconciliation(long received, int reconstructedWritten, int nestedPassengers,
        int droppedUncaptured, int sinkSkips, int createDrops, int encodeFailures, int flushDrops,
        int abortDrops, int unboundDimensionDrops, int primedEncodeFailures, int primedFlushDrops) {
    public long accounted() {
        return (long) reconstructedWritten + nestedPassengers + droppedUncaptured + sinkSkips + createDrops
                + encodeFailures + flushDrops + abortDrops + unboundDimensionDrops;
    }

    public long unaccounted() {
        return received - accounted();
    }

    /**
     * Whether a structural failure lost entities. The {@link #unaccounted} churn is not a loss and is not counted here;
     * neither are the sink's refusals, which are real drops but ones vanilla makes too, so they are reported rather
     * than alarmed.
     *
     * <p>The unbound-dimension remainder joins them rather than the uncaptured drops because it is not the privacy
     * gate's doing: the finish asks that gate in the entity's OWN dimension. A held frame the gate refuses in its own
     * dimension is counted benign instead.
     */
    public boolean hasStructuralLoss() {
        return structuralLossCount() > 0;
    }

    public int structuralLossCount() {
        return createDrops + encodeFailures + flushDrops + abortDrops + unboundDimensionDrops
                + primedEncodeFailures + primedFlushDrops;
    }
}
