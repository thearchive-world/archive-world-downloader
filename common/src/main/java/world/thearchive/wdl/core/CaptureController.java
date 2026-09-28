// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The capture orchestrator: a small state machine driving {@code IDLE -> RECORDING -> SAVING -> IDLE}. The MC-typed
 * work (snapshotting loaded chunks, writing the region files and level.dat) lives behind the {@link Session} seam,
 * whose implementation the loader/adapter layer supplies.
 *
 * <p>Threading: the client main thread drives {@link #tick()}, and the controller holds no locks. Apart from its
 * volatile fields and the three overlay stores, which are safe from any thread, it is written for that one thread and
 * marshals nothing itself: {@link #onDisconnect} runs on whatever thread delivers the disconnect, and the stop, the
 * finish and any re-poll the finish makes inline run there with it.
 *
 * <p>The save is asynchronous: {@link Session#finish()} returns without waiting for the background write, and the
 * controller stays {@link CaptureState#SAVING} until the write reports done, then returns to {@link CaptureState#IDLE}.
 * A second capture cannot start until then. {@link #tick()} polls {@link Session#isSaveComplete()}, but the tick is not
 * the only route out of {@link CaptureState#SAVING}: the session also re-polls this controller when its write completes
 * (see {@link Session#finish()}).
 */
public final class CaptureController {
    /**
     * The window the frozen counts and elapsed timer stay readable after a save completes, comfortably past the longest
     * configurable HUD done linger plus its fade, so the overlay never reads a blanked snapshot mid-linger.
     */
    private static final long DONE_LINGER_HOLD_MILLIS = 60_000L;

    /** The MC-typed capture work. */
    public interface Session {
        /** Snapshot any newly-eligible loaded chunks (called once per client tick while recording). */
        void captureTick();

        /**
         * Begin the asynchronous save (write level.dat, drain and close the region storage, report the saved world).
         * Returns without waiting for the write.
         *
         * <p>An implementation must arrange for the controller to be re-polled once the background write completes, and
         * may not rely on the controller's per-tick poll alone: the tick can stay suspended for arbitrarily long while
         * the rest of the client keeps running, which would strand the save in {@link CaptureState#SAVING} forever.
         * That re-poll may run synchronously inside this call (a finish with nothing to write does exactly that), so it
         * must be idempotent against the polls that follow. A re-poll for a write that completes off the client main
         * thread must be marshaled to it (see the threading note above).
         */
        void finish();

        /**
         * Polled while saving, on the tick and again on the session's own completion re-poll: {@code true} once the
         * background write has finished (success or failure). The session surfaces its own outcome message on
         * completion.
         */
        boolean isSaveComplete();

        /**
         * Hold the background writer off the loader's registries, and wait, for a bounded time, for any read already
         * under way to finish. A loader can rebuild those registries in place at either edge of a connection, and a
         * write encoded across a rebuild loses its blocks, so this is taken on a connection edge. Default no-op,
         * because a session with no background write of its own has nothing to hold.
         */
        default void holdWriterEncoding() {}

        /** Let the background writer read the loader's registries again, once the rebuild is over. */
        default void releaseWriterEncoding() {}

        /** Live progress so far. */
        CaptureCounts counts();

        /**
         * The live captured-set: which containers had their contents captured this session. Same-thread read, no
         * snapshot.
         */
        CapturedContainers capturedContainers();

        /**
         * The prior-session recovered coverage the resume scan has published. Empty on a fresh download; published by
         * the writer thread as one atomic reference swap, read here.
         */
        RecoveredCoverage recoveredCoverage();

        /**
         * Answered from the config this session was constructed with, never by re-reading the live one. Read once, at
         * start.
         */
        CaptureToggles latchedToggles();

        /** The current finalization phase while the background save drains. */
        SaveStage saveStage();

        /** The current finalization phase's fraction in {@code [0, 1]}. */
        float saveProgress();
    }

    private static final long[] NO_OVERLAY_CHUNKS = new long[0];

    private final LongSupplier clockMillis;

    // Volatile because a map overlay may read it off the client thread while the client thread writes it: a stale IDLE
    // would hide that overlay for a whole download with nothing to correct it.
    private volatile CaptureState state = CaptureState.IDLE;
    private @Nullable Session session;

    /**
     * The session whose writer is held off the loader's registries, released by the first tick that finds no finish
     * under way.
     */
    private volatile @Nullable Session encodeHeld;

    /** Set while {@link #stop()} is inside {@link Session#finish()}; that call's own poll must not release the hold. */
    private volatile boolean finishing;

    // The running session's latched capture toggles, republished here because the session reference itself is not
    // volatile, and an overlay may read these off the client thread. Null exactly when no session is set.
    private volatile @Nullable CaptureToggles latchedToggles;

    // The three overlay stores are owned here, on the singleton that outlives individual sessions, so an overlay
    // reading off the client thread sees a stably published reference. The live session writes to them.
    private final SavedChunkIndex savedChunks = new SavedChunkIndex();
    private final CoveredChunkIndex coveredChunks = new CoveredChunkIndex();
    private final SendRangeEstimator sendRange = new SendRangeEstimator();

    // The backend-transfer stop signal, polled first each recording tick so that once it is raised no tick rebinds the
    // re-entered world as a portal trip or recomputes at a stale radius.
    private BooleanSupplier transferStopPoll = () -> false;

    private CaptureCounts frozenCounts = CaptureCounts.EMPTY;
    private long startMillis;
    private long frozenElapsedMillis;
    private long saveCompletedMillis;
    private boolean hasCompletedSave;

    public CaptureController() {
        this(System::currentTimeMillis);
    }

    CaptureController(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    public CaptureState state() {
        return state;
    }

    /**
     * Capture counts: live while recording, the stop-time frozen snapshot through saving, the final totals through the
     * done-linger hold, {@link CaptureCounts#EMPTY} when idle and past it.
     */
    public CaptureCounts counts() {
        if (state == CaptureState.RECORDING && session != null) {
            return session.counts();
        }
        if (state == CaptureState.SAVING || isWithinDoneHold()) {
            return frozenCounts;
        }
        return CaptureCounts.EMPTY;
    }

    /**
     * The live captured-set while recording, {@link CapturedContainers#EMPTY} otherwise. Unlike the counts it is not
     * held through saving.
     */
    public CapturedContainers capturedContainers() {
        if (state == CaptureState.RECORDING && session != null) {
            return session.capturedContainers();
        }
        return CapturedContainers.EMPTY;
    }

    /**
     * The capture toggles the on-screen aids may draw under: the per-axis conjunction of {@code live} and what the
     * running download latched at start, or {@code live} alone when nothing is running, since then no latched set
     * exists and the settings are the only answer there is. The conjunction is what keeps a mid-download edit from
     * stranding a marker: switching an axis on cannot draw for an axis this download is not capturing, and switching
     * one off hides its markers from the next read on.
     */
    public CaptureToggles aidToggles(WdlConfig live) {
        CaptureToggles liveToggles = CaptureToggles.from(live);
        CaptureToggles latched = latchedToggles;
        return latched == null ? liveToggles : liveToggles.and(latched);
    }

    /**
     * The saved chunk-position longs for a dimension, snapshotted thread-safely so the coverage overlay may read them
     * off the client thread. Holds only the running download's own coverage, emptied once its save completes, and empty
     * while {@code renderCoverageOverlay} is off: that toggle is read live, on each call, so switching it needs no
     * restart, and the first read after switching it back on returns the whole recorded index (the index keeps filling
     * regardless). The id is the live client's key for the dimension.
     */
    public long[] overlaySavedChunks(WdlConfig live, String dimensionId) {
        if (!live.renderCoverageOverlay()) {
            return NO_OVERLAY_CHUNKS;
        }
        return savedChunks.snapshot(dimensionId);
    }

    /**
     * The covered chunk-position longs for a dimension. They are not limited to saved chunks, so a reader intersects
     * them with {@link #overlaySavedChunks}: a saved chunk found here is covered, and any other saved chunk is suspect.
     * Gated on the same live {@code renderCoverageOverlay} toggle and read the same thread-safe way as
     * {@link #overlaySavedChunks}; each is its own independent synchronized snapshot, so a chunk may transiently appear
     * in one and not the other for a single refresh. When {@link #aidToggles} reports entity capture off, the read is
     * empty; this is checked first and short-circuits the cold-start mirror below, which only applies while entity
     * capture is on. Until the send range has been measured for the dimension the covered read mirrors the saved set,
     * so the overlay draws single-tone rather than flashing a spurious suspect boundary at a not-yet-known range.
     */
    public long[] overlayCoveredChunks(WdlConfig live, String dimensionId) {
        if (!live.renderCoverageOverlay()) {
            return NO_OVERLAY_CHUNKS;
        }
        if (!aidToggles(live).captureEntities()) {
            return NO_OVERLAY_CHUNKS;
        }
        if (!sendRange.isCalibrated(dimensionId)) {
            return savedChunks.snapshot(dimensionId);
        }
        return coveredChunks.snapshot(dimensionId);
    }

    public SavedChunkIndex savedChunks() {
        return savedChunks;
    }

    public CoveredChunkIndex coveredChunks() {
        return coveredChunks;
    }

    public SendRangeEstimator sendRange() {
        return sendRange;
    }

    /** The prior-session recovered coverage while recording, {@link RecoveredCoverage#EMPTY} otherwise. */
    public RecoveredCoverage recoveredCoverage() {
        if (state == CaptureState.RECORDING && session != null) {
            return session.recoveredCoverage();
        }
        return RecoveredCoverage.EMPTY;
    }

    /**
     * The capture's elapsed wall-clock time: counting up while recording, frozen at its stop-time value through saving
     * and the done-linger hold, 0 when idle and past it.
     */
    public long elapsedMillis() {
        if (state == CaptureState.RECORDING) {
            return Math.max(0L, clockMillis.getAsLong() - startMillis);
        }
        if (state == CaptureState.SAVING || isWithinDoneHold()) {
            return frozenElapsedMillis;
        }
        return 0L;
    }

    /** The finalization phase while saving; {@link SaveStage#NONE} otherwise. */
    public SaveStage saveStage() {
        return state == CaptureState.SAVING && session != null ? session.saveStage() : SaveStage.NONE;
    }

    /** The finalization phase's fraction while saving; 0 otherwise. */
    public float saveProgress() {
        return state == CaptureState.SAVING && session != null ? session.saveProgress() : 0.0f;
    }

    /**
     * Milliseconds since the last save completed, while the controller is idle within the done-linger hold;
     * {@linkplain OptionalLong#empty() empty} while recording or saving, before any save has completed, or once past
     * the hold.
     */
    public OptionalLong doneElapsedMillis() {
        if (!isWithinDoneHold()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(clockMillis.getAsLong() - saveCompletedMillis);
    }

    /**
     * Wire the backend-transfer stop signal, polled at the top of each recording tick; a true read stops the download.
     * The default never fires.
     */
    public void setTransferStopPoll(BooleanSupplier transferStopPoll) {
        this.transferStopPoll = transferStopPoll;
    }

    /** Begin recording with a fresh session (no-op unless idle). */
    public void start(Supplier<Session> sessionFactory) {
        if (state == CaptureState.IDLE) {
            savedChunks.clear();
            coveredChunks.clear();
            sendRange.clear();
            session = sessionFactory.get();
            latchedToggles = session.latchedToggles();
            state = CaptureState.RECORDING;
            startMillis = clockMillis.getAsLong();
            frozenCounts = CaptureCounts.EMPTY;
            frozenElapsedMillis = 0L;
            hasCompletedSave = false;
        }
    }

    /**
     * The controller's poll: capture eligible chunks while recording, or (while saving) poll the background write and
     * return to idle once it has completed, stamping the done-linger start. Called once per client tick and also as the
     * session's completion-poke target, so it can run off the tick and re-entrantly from inside {@link #stop()}; the
     * null-session and state guards keep it idempotent.
     */
    public void tick() {
        // Ahead of every guard below, including the null-session one: the session that was held may already have been
        // dropped, and the writer it owns still has to be let go. Where the disconnect arrives inside the client's own
        // teardown, a tick cannot begin until that teardown has returned, which is what makes this the safe side of the
        // rebuild; a re-entrant tick from inside the finish is not, so it is excluded rather than allowed to release
        // early.
        Session held = encodeHeld;
        if (held != null && !finishing) {
            encodeHeld = null;
            held.releaseWriterEncoding();
        }
        if (session == null) {
            return;
        }
        if (state == CaptureState.RECORDING && transferStopPoll.getAsBoolean()) {
            stop();
            return;
        }
        if (state == CaptureState.RECORDING) {
            session.captureTick();
        } else if (state == CaptureState.SAVING && session.isSaveComplete()) {
            // The save drain can grow the counts past the stop-time freeze, so the done linger must show the final
            // totals, not the stop figures.
            frozenCounts = session.counts();
            session = null;
            latchedToggles = null;
            state = CaptureState.IDLE;
            saveCompletedMillis = clockMillis.getAsLong();
            hasCompletedSave = true;
            // Cleared here rather than at stop, because the finish drain can enqueue the resume overlay seed on
            // the writer and that task would otherwise land after a stop-time clear and leave the prior
            // download's on-disk coverage in the indexes for the next stale read to draw.
            savedChunks.clear();
            coveredChunks.clear();
            sendRange.clear();
        }
    }

    /**
     * Stop recording and finish saving the world to disk (no-op unless recording); the write drains asynchronously.
     *
     * <p>{@link Session#finish()} may re-poll this controller synchronously, re-entering {@link #tick()} from inside
     * this call: on a finish that completes at once, the state is already {@link CaptureState#IDLE} and the session
     * already null by the time the body resumes below it. Nothing after the finish call may assume either is unchanged.
     */
    public void stop() {
        if (state == CaptureState.RECORDING) {
            finishing = true;
            Session active = Objects.requireNonNull(session, "session is set whenever state is RECORDING");
            // Snapshot the counts and elapsed time before finish() tears the live session down.
            frozenCounts = active.counts();
            frozenElapsedMillis = Math.max(0L, clockMillis.getAsLong() - startMillis);
            state = CaptureState.SAVING;
            try {
                active.finish(); // does not wait for the write, but may already have re-entered tick() and reached IDLE
            } finally {
                finishing = false;
            }
        }
    }

    /**
     * Hold the writer, then flush. The hold comes first and is taken while saving as well as while recording, because
     * the save the disconnect has to protect is often one already running: stopping a download and then leaving the
     * server is a routine order, and a stop that already moved the state out of recording makes the flush below a no-op
     * while leaving a full drain to encode straight through the registry rebuild a loader can run on the way out.
     */
    public void onDisconnect() {
        holdWriterEncoding();
        stop();
    }

    /** Joining can rebuild the registries too, so the hold is taken for a save still draining from the last server. */
    public void onServerJoin() {
        holdWriterEncoding();
    }

    private void holdWriterEncoding() {
        Session active = session;
        if (active != null && encodeHeld == null) {
            encodeHeld = active;
            active.holdWriterEncoding();
        }
    }

    /**
     * Flip an idle controller into the session-less {@link CaptureState#RESTORING} state, so no capture can start while
     * a restore worker runs. Main-thread only, so the check and the flip are one uninterrupted step. Returns false and
     * changes nothing when the controller is not idle: a running capture, a draining save, or another restore is never
     * disturbed.
     */
    public boolean tryBeginRestoring() {
        if (state != CaptureState.IDLE) {
            return false;
        }
        state = CaptureState.RESTORING;
        return true;
    }

    /** Return the controller from {@link CaptureState#RESTORING} to idle; a no-op in any other state. */
    public void endRestoring() {
        if (state == CaptureState.RESTORING) {
            state = CaptureState.IDLE;
        }
    }

    private boolean isWithinDoneHold() {
        return state == CaptureState.IDLE && hasCompletedSave
                && clockMillis.getAsLong() - saveCompletedMillis <= DONE_LINGER_HOLD_MILLIS;
    }
}
