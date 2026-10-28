// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

/**
 * A primed entity is one the accumulator does not hold, so {@code primedEncodeFailures} and {@code primedFlushDrops}
 * stay out of {@link #accounted} and {@link #unaccounted}; they join {@link #hasStructuralLoss} and
 * {@link #structuralLossCount} because either failure destroys a primed entity just as surely as a reconstructed one,
 * and the partial-download predicate must see both paths.
 */
final class EntityReconciliation {
    private final long received;
    private final int reconstructedWritten;
    private final int nestedPassengers;
    private final int droppedUncaptured;
    private final int sinkSkips;
    private final int createDrops;
    private final int encodeFailures;
    private final int flushDrops;
    private final int abortDrops;
    private final int unboundDimensionDrops;
    private final int primedEncodeFailures;
    private final int primedFlushDrops;

    EntityReconciliation(long received, int reconstructedWritten, int nestedPassengers, int droppedUncaptured,
            int sinkSkips, int createDrops, int encodeFailures, int flushDrops, int abortDrops,
            int unboundDimensionDrops, int primedEncodeFailures, int primedFlushDrops) {
        this.received = received;
        this.reconstructedWritten = reconstructedWritten;
        this.nestedPassengers = nestedPassengers;
        this.droppedUncaptured = droppedUncaptured;
        this.sinkSkips = sinkSkips;
        this.createDrops = createDrops;
        this.encodeFailures = encodeFailures;
        this.flushDrops = flushDrops;
        this.abortDrops = abortDrops;
        this.unboundDimensionDrops = unboundDimensionDrops;
        this.primedEncodeFailures = primedEncodeFailures;
        this.primedFlushDrops = primedFlushDrops;
    }

    long received() {
        return received;
    }

    int reconstructedWritten() {
        return reconstructedWritten;
    }

    int nestedPassengers() {
        return nestedPassengers;
    }

    int droppedUncaptured() {
        return droppedUncaptured;
    }

    int sinkSkips() {
        return sinkSkips;
    }

    int createDrops() {
        return createDrops;
    }

    int encodeFailures() {
        return encodeFailures;
    }

    int flushDrops() {
        return flushDrops;
    }

    int abortDrops() {
        return abortDrops;
    }

    int unboundDimensionDrops() {
        return unboundDimensionDrops;
    }

    int primedEncodeFailures() {
        return primedEncodeFailures;
    }

    int primedFlushDrops() {
        return primedFlushDrops;
    }

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
