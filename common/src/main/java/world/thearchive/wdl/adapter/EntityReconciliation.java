// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

/**
 * The finish-time accounting that ties every spawn packet the entity packet path received to a terminal outcome: a
 * written root, a nested passenger, or a counted drop (uncaptured, sink-refused, create-fail, encode-fail, flush-loss,
 * abandoned by a failed finish drain, or held for a dimension other than the one the download ended bound to). What
 * remains, {@link #unaccounted}, is the received-minus-accounted residual, and it is not a loss signal:
 * {@code received} counts every spawn packet, so a fly-past that re-announces entities on reload and supersedes others
 * by server id-reuse drives the residual to a large, workload-dependent baseline. Tightening it to a real loss count
 * would need an unbounded set of every UUID seen, which the weak-hardware target rules out, so the residual is reported
 * as a transparency figure, not alarmed on.
 *
 * <p>The tripwire is instead {@link #hasStructuralLoss}: a whole entity-chunk lost to a flush throw or null, a typed
 * entity that could not be created, a single entity whose encode threw, the frames an aborted finish drain left held in
 * a captured chunk, or the frames the download ended holding for a dimension other than the one it ended bound to, on
 * terrain that dimension had captured. All five are near-zero in a healthy capture and genuinely destructive when not.
 * Two no-tag outcomes are deliberately kept out of it. The sink's own refusals, where a non-serializable type, a
 * passenger saved nested, a removed entity, and a player-only vehicle are the non-saves vanilla makes too, so they are
 * counted and visible but never alarmed, and the prime's unresolvable-leash case saves unleashed rather than dropping
 * at all. And the uncaptured drops, which are the privacy gate doing its job rather than a failure.
 *
 * <p>The arithmetic fields are the packet (reconstruct) path's, the only path with a {@code received} spawn count to
 * reconcile against. A primed entity has no spawn packet, so {@code primedEncodeFailures} and {@code primedFlushDrops}
 * stay out of {@link #accounted} and {@link #unaccounted}; they join {@link #hasStructuralLoss} and
 * {@link #structuralLossCount} because either failure destroys a primed entity just as surely as a reconstructed one,
 * and the partial-download predicate must see both paths.
 *
 * @param received              the spawn packets fed in ({@code EntityPacketAccumulator.spawnCount})
 * @param reconstructedWritten  reconstructed root entities that reached the writer
 * @param nestedPassengers      reconstructed entities saved nested in a vehicle, so not a written root
 * @param droppedUncaptured     entities dropped at finish because their chunk's terrain was never captured, in the
 *                              dimension the download was bound to and against that dimension's own captured positions,
 *                              which is the only comparison that makes the drop a gate refusal rather than a misread
 *                              key
 * @param sinkSkips             reconstructed entities the sink refused; one of vanilla's own non-saves, not a loss
 * @param createDrops           reconstructed entities dropped because the typed entity could not be created
 * @param encodeFailures        reconstructed entities lost because {@code entity.save} threw or the envelope came back
 *                              without them
 * @param flushDrops            reconstructed entities lost when a whole entity-chunk threw or nulled out during its
 *                              flush
 * @param abortDrops            entities an aborted finish drain left held in a captured chunk, so nothing wrote them
 * @param unboundDimensionDrops entities received for a dimension other than the one the download was bound to when it
 *                              ended, whose own dimension had captured the terrain under them, so the gate would have
 *                              allowed the write and nothing performed it
 * @param primedEncodeFailures  primed entities lost to a throwing or malformed encode; outside the packet arithmetic
 * @param primedFlushDrops      primed entities lost to a flush failure; likewise outside the packet arithmetic
 */
record EntityReconciliation(long received, int reconstructedWritten, int nestedPassengers,
        int droppedUncaptured, int sinkSkips, int createDrops, int encodeFailures, int flushDrops,
        int abortDrops, int unboundDimensionDrops, int primedEncodeFailures, int primedFlushDrops) {
    /** The received spawns explained by a terminal outcome: a written root, a nested passenger, or a counted drop. */
    public long accounted() {
        return (long) reconstructedWritten + nestedPassengers + droppedUncaptured + sinkSkips + createDrops
                + encodeFailures + flushDrops + abortDrops + unboundDimensionDrops;
    }

    /** Received minus accounted: the reload / id-reuse churn figure (workload-dependent, not a loss), reported only. */
    public long unaccounted() {
        return received - accounted();
    }

    /**
     * Whether a structural failure lost entities: an entity-chunk flush loss, a typed-entity create failure, a throwing
     * or malformed single encode, an aborted finish drain, or a remainder held for a dimension other than the one the
     * download ended bound to, all near-zero in a healthy capture. The {@link #unaccounted} churn is not a loss and is
     * not counted here; neither are the sink's refusals, which are real drops but ones vanilla makes too, so they are
     * reported rather than alarmed.
     *
     * <p>The unbound-dimension remainder joins them rather than the uncaptured drops because it is not the privacy
     * gate's doing: the finish asks that gate in the entity's OWN dimension, and what lands here is what the gate would
     * have allowed, or what it could not be asked about because the dimension resolves to no folder this download
     * writes. A held frame the gate refuses in its own dimension is counted benign instead. So this term is entities
     * that were received, were writable, and were not written.
     */
    public boolean hasStructuralLoss() {
        return structuralLossCount() > 0;
    }

    /** Every entity a structural failure destroyed, both paths: the figure the partial-download predicate sums. */
    public int structuralLossCount() {
        return createDrops + encodeFailures + flushDrops + abortDrops + unboundDimensionDrops
                + primedEncodeFailures + primedFlushDrops;
    }
}
