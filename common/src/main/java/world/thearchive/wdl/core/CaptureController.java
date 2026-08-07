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
 * The version-agnostic capture orchestrator: a small state machine driving {@code IDLE -> RECORDING -> SAVING -> IDLE}.
 * It imports no {@code net.minecraft.*} type (CI-enforced): the MC-typed work (snapshotting loaded chunks, writing the
 * region files and level.dat) lives behind the {@link Session} seam, whose implementation the loader/adapter layer
 * supplies.
 *
 * <p>Threading: {@link #tick()} runs on the client main thread, and so does the whole per-chunk snapshot inside
 * {@code Session.captureTick()}; only immutable tags cross to the async IO worker. This controller holds no locks and
 * must only ever be touched from that one thread.
 *
 * <p>The save is asynchronous: {@link Session#finish()} only <em>begins</em> the background write and returns at once
 * (so the keybind/command never freezes), and the controller stays {@link CaptureState#SAVING} until the write reports
 * done, then returns to {@link CaptureState#IDLE}. A second capture cannot start until then. {@link #tick()} polls
 * {@link Session#isSaveComplete()} on the main thread, but the tick is not the only route out of
 * {@link CaptureState#SAVING}: the session also re-polls this controller when its write completes (see
 * {@link Session#finish()}).
 *
 * <p>For the HUD the controller carries three time-aware reads past the live session's teardown: the counts freeze at
 * {@code /wdl stop} and hold through the save and a post-save done linger; the elapsed timer freezes the same way; and
 * {@link #doneElapsedMillis()} reports time since the save completed so the overlay can draw the done frame and fade it
 * out.
 */
public final class CaptureController {
    /**
     * The window the frozen counts and elapsed timer stay readable after a save completes, comfortably past the longest
     * configurable HUD done linger plus its fade, so the overlay never reads a blanked snapshot mid-linger. The overlay
     * applies its own (shorter) configured linger to decide when to stop drawing.
     */
    private static final long DONE_LINGER_HOLD_MILLIS = 60_000L;

    /** The MC-typed capture work, abstracted so the controller stays MC-free. */
    public interface Session {
        /** Snapshot any newly-eligible loaded chunks (called once per client tick while recording). */
        void captureTick();

        /**
         * Begin the asynchronous save (write level.dat, drain and close the region storage, report the saved world).
         * Returns immediately.
         *
         * <p>An implementation must arrange for the controller to be re-polled once the background write completes, and
         * may not rely on the controller's per-tick poll alone: the tick can stay suspended for arbitrarily long while
         * the rest of the client keeps running, which would strand the save in {@link CaptureState#SAVING} forever.
         * That re-poll may run synchronously inside this call (a finish with nothing to write does exactly that), so it
         * must be idempotent against the polls that follow. It must also reach the controller on the one thread every
         * other call to it uses (see the threading note above), so a write that completes off that thread marshals the
         * re-poll back to it.
         */
        void finish();

        /**
         * Polled on the client main thread while saving, on the tick and again on the session's own completion re-poll:
         * {@code true} once the background write has finished (success or failure). The session surfaces its own
         * outcome message on completion.
         */
        boolean isSaveComplete();

        /** Live progress so far, as MC-free counts (read for the status command and HUD). */
        CaptureCounts counts();

        /**
         * The live captured-set: which loaded containers had their contents captured this session, read by the
         * unsaved-container outline to decide which still carry a rim. Same-thread read, no snapshot.
         */
        CapturedContainers capturedContainers();

        /**
         * The prior-session recovered coverage the resume scan has published, read by the outline so a re-arriving
         * prior-captured container settles to the recovered hue rather than unsaved. Empty on a fresh download;
         * published by the writer thread as one atomic reference swap, read here.
         */
        RecoveredCoverage recoveredCoverage();

        /**
         * Answered from the config this session was constructed with, never by re-reading the live one. Read once, at
         * start.
         */
        CaptureToggles latchedToggles();

        /** The current finalization phase while the background save drains, for the HUD bar. */
        SaveStage saveStage();

        /** The current finalization phase's fraction in {@code [0, 1]}; 0 unless a save is draining. */
        float saveProgress();
    }

    private static final long[] NO_OVERLAY_CHUNKS = new long[0];

    private final LongSupplier clockMillis;

    // Volatile because a map mod's off-thread overlay supplier gates on this through Wdl.state() while the
    // client thread writes it. A stale RECORDING is harmless, the indexes only ever hold the current download's
    // own coverage and are cleared once its save completes, so the worst it can redraw is what that download was
    // already drawing; a stale IDLE would hide the overlay for a whole download with nothing to correct it.
    private volatile CaptureState state = CaptureState.IDLE;
    private @Nullable Session session;

    // The running session's latched capture toggles, republished here because the session reference itself is
    // client-thread-only while the overlay reads run off-thread. Null exactly when no session is set.
    private volatile @Nullable CaptureToggles latchedToggles;

    // The three overlay stores are owned here, on the singleton that outlives individual sessions, so the
    // off-render-thread overlay reads a stably published reference. The live session writes to them.
    private final SavedChunkIndex savedChunks = new SavedChunkIndex();
    private final CoveredChunkIndex coveredChunks = new CoveredChunkIndex();
    private final SendRangeEstimator sendRange = new SendRangeEstimator();

    // The backend-transfer stop signal, polled first each recording tick so no tick ever rebinds the re-entered
    // world as a portal trip or recomputes at a stale radius. A field with a never-fire default rather than a
    // constructor parameter: the adapter layer wires the real poll at startup, and the controller stays MC-free.
    private BooleanSupplier transferStopPoll = () -> false;

    // The frozen counts held through SAVING and the done linger (the live session is gone during the
    // save). Armed at stop() and re-armed with the written totals once the save completes, read by counts()
    // while not recording, cleared at the next start() or once the controller has been idle past the hold
    // window.
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
     * Capture counts for the status command and HUD: live while recording, the stop-time frozen snapshot through
     * saving, the written totals through the done linger, {@link CaptureCounts#EMPTY} when idle and past the linger.
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
     * held through saving: the unsaved-container rims are a live to-do list that stops the moment the capture ends and
     * the drain begins, so the outline reads an empty set once idle.
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
     * one off still hides its markers at once.
     */
    public CaptureToggles aidToggles(WdlConfig live) {
        CaptureToggles liveToggles = CaptureToggles.from(live);
        CaptureToggles latched = latchedToggles;
        return latched == null ? liveToggles : liveToggles.and(latched);
    }

    /**
     * The saved chunk-position longs for a dimension, snapshotted thread-safely for the coverage overlay, which reads
     * off the render thread. Holds only the running download's own coverage, emptied once its save completes, and empty
     * while {@code renderCoverageOverlay} is off: that toggle is read live, on the overlay's own read path, so
     * switching it off hides the highlight without a restart and switching it back on shows the recorded index at once
     * (the index keeps filling regardless). The id is the live client key, for instance
     * {@code level.dimension().identifier().toString()}.
     */
    public long[] overlaySavedChunks(WdlConfig live, String dimensionId) {
        if (!live.renderCoverageOverlay()) {
            return NO_OVERLAY_CHUNKS;
        }
        return savedChunks.snapshot(dimensionId);
    }

    /**
     * The covered chunk-position longs for a dimension: the saved chunks the recording path brought within entity send
     * range, which the two-tone overlay draws in the covered hue while the rest of the saved set draws the suspect hue.
     * Gated on the same live {@code renderCoverageOverlay} toggle and read the same thread-safe way as
     * {@link #overlaySavedChunks}; each is its own independent synchronized snapshot, so a chunk may transiently appear
     * in one and not the other for a single refresh. When {@link #aidToggles} reports entity capture off this download
     * is adding no entity, so nothing draws covered; this is checked first and short-circuits the cold-start mirror
     * below, which only applies while entity capture is on. Until the send range has been measured for the dimension
     * the covered read mirrors the saved set, so the overlay draws single-tone rather than flashing a spurious suspect
     * boundary at a not-yet-known range.
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

    /** The saved-chunk-position index for the coverage overlay; snapshotted off-thread by the supplier. */
    public SavedChunkIndex savedChunks() {
        return savedChunks;
    }

    /** The covered-chunk-position index for the two-tone overlay; snapshotted off-thread by the supplier. */
    public CoveredChunkIndex coveredChunks() {
        return coveredChunks;
    }

    /**
     * The estimator holds one running max per dimension, fed by the sampler-gated feeds.
     */
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
     * The capture's elapsed wall-clock time: counting up while recording, frozen at the {@code /wdl stop} duration
     * through saving and the done linger, 0 when idle and past the linger.
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

    /** The finalization phase while saving; {@link SaveStage#NONE} otherwise (no bar during capture or idle). */
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
     * the hold. The overlay reads this to draw and fade the done frame.
     */
    public OptionalLong doneElapsedMillis() {
        if (!isWithinDoneHold()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(clockMillis.getAsLong() - saveCompletedMillis);
    }

    /**
     * Wire the backend-transfer stop signal, polled at the top of each recording tick; a true read stops the download
     * exactly like a disconnect. Wired once at startup by the adapter layer; the default never fires.
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
            hasCompletedSave = false; // a fresh capture clears any lingering done frame
        }
    }

    /**
     * The controller's poll (main thread): capture eligible chunks while recording, or (while saving) poll the
     * background write and return to idle once it has completed, stamping the done-linger start. Called once per client
     * tick and also as the session's completion-poke target, so it can run off the tick and re-entrantly from inside
     * {@link #stop()}; the null-session and state guards keep it idempotent.
     */
    public void tick() {
        if (session == null) {
            return;
        }
        if (state == CaptureState.RECORDING && transferStopPoll.getAsBoolean()) {
            stop(); // a backend transfer behaves exactly like a disconnect, and the capture does not follow it across
            return;
        }
        if (state == CaptureState.RECORDING) {
            session.captureTick();
        } else if (state == CaptureState.SAVING && session.isSaveComplete()) {
            // The save drain grows the counts past the stop-time freeze (chunk-prime entity snapshots and
            // promoted packet frames), so the done linger must show the written totals, not the stop figures.
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
     * Begin saving the world to disk (no-op unless recording); the write drains asynchronously.
     *
     * <p>{@link Session#finish()} may re-poll this controller synchronously, re-entering {@link #tick()} from inside
     * this call: on a finish that completes at once, the state is already {@link CaptureState#IDLE} and the session
     * already null by the time the body resumes below it. Nothing after the finish call may assume either is unchanged.
     */
    public void stop() {
        if (state == CaptureState.RECORDING) {
            Session active = Objects.requireNonNull(session, "session is set whenever state is RECORDING");
            // Snapshot the counts and elapsed time before finish() tears the live session down, so the HUD
            // keeps showing the stop-time figures through the save and the done linger.
            frozenCounts = active.counts();
            frozenElapsedMillis = Math.max(0L, clockMillis.getAsLong() - startMillis);
            state = CaptureState.SAVING;
            active.finish(); // returns at once, but may already have re-entered tick() and reached IDLE
        }
    }

    /** Auto-flush when the player disconnects mid-recording. */
    public void onDisconnect() {
        stop();
    }

    /**
     * Flip an idle controller into the session-less {@link CaptureState#RESTORING} state, so no capture can start while
     * a restore worker runs. Main-thread only, like every controller call, so the check and the flip are one
     * uninterrupted step. Returns false and changes nothing when the controller is not idle: a running capture, a
     * draining save, or another restore is never disturbed.
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
