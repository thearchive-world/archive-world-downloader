// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.WdlConfig;

/**
 * The launch-scoped orchestrator: opt-out before any request, at-most-once dispatch off the calling thread, one
 * published result, and the chat latch and banner dismissal semantics.
 */
class UpdateCheckTest {
    private static final String OLDER_BODY = UpdateFixtures.WELL_FORMED_BODY.replace("3.9.5+26.1-fabric", "0.9.0");

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            tasks.forEach(Runnable::run);
        }
    }

    private static WdlConfig config(boolean checkForUpdates) {
        Properties properties = new Properties();
        properties.setProperty("checkForUpdates", Boolean.toString(checkForUpdates));
        return WdlConfig.parse(properties);
    }

    private static UpdateCheck checkedAgainst(String body) {
        UpdateCheck check = new UpdateCheck();
        check.dispatch(UpdateFixtures.INFO, config(true),
                new UpdateFixtures.RecordingTransport(new Transport.Result(200, body)), Runnable::run);
        return check;
    }

    @Test
    void optOutMakesZeroRequests() {
        UpdateFixtures.RecordingTransport transport = new UpdateFixtures.RecordingTransport(
                new Transport.Result(200, UpdateFixtures.WELL_FORMED_BODY));
        UpdateCheck check = new UpdateCheck();

        check.dispatch(UpdateFixtures.INFO, config(false), transport, Runnable::run);

        assertEquals(0, transport.calls, "opt-out is honored before any request");
        assertFalse(check.available().isPresent());
    }

    @Test
    void dispatchFiresAtMostOncePerLaunch() {
        UpdateFixtures.RecordingTransport transport = new UpdateFixtures.RecordingTransport(
                new Transport.Result(200, UpdateFixtures.WELL_FORMED_BODY));
        UpdateCheck check = new UpdateCheck();

        check.dispatch(UpdateFixtures.INFO, config(true), transport, Runnable::run);
        check.dispatch(UpdateFixtures.INFO, config(true), transport, Runnable::run);

        assertEquals(1, transport.calls, "a double init dispatches one request");
    }

    @Test
    void dispatchRunsTheCheckOnTheWorker() {
        UpdateFixtures.RecordingTransport transport = new UpdateFixtures.RecordingTransport(
                new Transport.Result(200, UpdateFixtures.WELL_FORMED_BODY));
        RecordingExecutor worker = new RecordingExecutor();
        UpdateCheck check = new UpdateCheck();

        check.dispatch(UpdateFixtures.INFO, config(true), transport, worker);

        assertEquals(0, transport.calls, "dispatch itself never touches the network");
        worker.runAll();
        assertEquals(1, transport.calls);
        assertTrue(check.available().isPresent());
    }

    @Test
    void publishesBothVersionsDisplayCleaned() {
        Optional<UpdateAvailable> available = checkedAgainst(UpdateFixtures.WELL_FORMED_BODY).available();

        assertTrue(available.isPresent());
        assertEquals("1.0.0-SNAPSHOT", available.get().runningDisplay(),
                "the running version shows without its build tail");
        assertEquals("3.9.5", available.get().latestDisplay(), "the latest version shows without its build tail");
    }

    @Test
    void noNewerReleaseHoldsEmpty() {
        assertFalse(checkedAgainst(OLDER_BODY).available().isPresent());
    }

    @Test
    void aTransportFailureHoldsEmpty() {
        UpdateCheck check = new UpdateCheck();

        check.dispatch(UpdateFixtures.INFO, config(true),
                new UpdateFixtures.RecordingTransport(Transport.Result.FAILURE), Runnable::run);

        assertFalse(check.available().isPresent(), "a failed check reads as up to date, never an error");
    }

    @Test
    void chatPendingConsumesExactlyOnce() {
        UpdateCheck check = checkedAgainst(UpdateFixtures.WELL_FORMED_BODY);

        assertTrue(check.consumeChatPending(true));
        assertFalse(check.consumeChatPending(true), "the chat line fires at most once per launch");
    }

    @Test
    void aChatToggleSuppressionDoesNotBurnTheLatch() {
        UpdateCheck check = checkedAgainst(UpdateFixtures.WELL_FORMED_BODY);

        assertFalse(check.consumeChatPending(false), "showChatMessages off suppresses the line");
        assertTrue(check.consumeChatPending(true), "a later join with the toggle on still delivers");
    }

    @Test
    void chatIsNotPendingBeforeTheResultArrives() {
        UpdateFixtures.RecordingTransport transport = new UpdateFixtures.RecordingTransport(
                new Transport.Result(200, UpdateFixtures.WELL_FORMED_BODY));
        RecordingExecutor worker = new RecordingExecutor();
        UpdateCheck check = new UpdateCheck();
        check.dispatch(UpdateFixtures.INFO, config(true), transport, worker);

        assertFalse(check.consumeChatPending(true), "a join before the result lands delivers nothing yet");
        worker.runAll();
        assertTrue(check.consumeChatPending(true), "the first join after the result delivers");
    }

    @Test
    void chatIsNotPendingWithoutAnUpdate() {
        assertFalse(checkedAgainst(OLDER_BODY).consumeChatPending(true));
    }

    @Test
    void bannerShowsUntilDismissedAndStaysDismissed() {
        UpdateCheck check = checkedAgainst(UpdateFixtures.WELL_FORMED_BODY);

        assertTrue(check.bannerVisible());
        check.dismissBanner();
        assertFalse(check.bannerVisible(), "dismissal is launch-scoped, surviving screen re-opens");
        assertTrue(check.available().isPresent(), "dismissing the banner does not clear the held result");
    }

    @Test
    void bannerIgnoresTheChatToggleAndTheChatLatch() {
        UpdateCheck check = checkedAgainst(UpdateFixtures.WELL_FORMED_BODY);

        assertTrue(check.consumeChatPending(true));
        assertTrue(check.bannerVisible(), "the banner is independent of the chat surface");
    }

    @Test
    void bannerHiddenBeforeAnyResult() {
        assertFalse(new UpdateCheck().bannerVisible());
    }

    @Test
    void awaitSettledReturnsTrueOnceTheWorkerCompletes() {
        UpdateCheck check = new UpdateCheck();
        check.dispatch(UpdateFixtures.INFO, config(true),
                new UpdateFixtures.RecordingTransport(new Transport.Result(200, UpdateFixtures.WELL_FORMED_BODY)),
                Runnable::run);
        assertTrue(check.awaitSettled(1_000));
    }

    @Test
    void awaitSettledReturnsImmediatelyWhenCheckDisabled() {
        UpdateCheck check = new UpdateCheck();
        check.dispatch(UpdateFixtures.INFO, config(false),
                new UpdateFixtures.RecordingTransport(new Transport.Result(200, UpdateFixtures.WELL_FORMED_BODY)),
                Runnable::run);
        assertTrue(check.awaitSettled(1_000));
        assertFalse(check.available().isPresent());
    }

    @Test
    void awaitSettledTimesOutWhenDispatchNeverRan() {
        UpdateCheck check = new UpdateCheck();
        assertFalse(check.awaitSettled(50));
        assertFalse(check.available().isPresent());
    }
}
