// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.update.LatestRelease;
import world.thearchive.wdl.core.update.Release;
import world.thearchive.wdl.core.update.SemVer;

/**
 * The launch-scoped update check: dispatched once after config load, run on an injected worker, and held as one
 * published result both surfaces read. The worker's single volatile write of the held result is the only cross-thread
 * hop (the write happens-before every later read, and the value is immutable); the chat latch and the banner dismissal
 * are read and written on the client main thread only, so they stay plain fields.
 */
public final class UpdateCheck {
    public static final String MODRINTH_PAGE_URL = "https://modrinth.com/mod/wdl";
    public static final String CURSEFORGE_PAGE_URL = "https://www.curseforge.com/minecraft/mc-mods/wdl";

    private final AtomicBoolean started = new AtomicBoolean();
    private final CountDownLatch settled = new CountDownLatch(1);
    private volatile Optional<UpdateAvailable> held = Optional.empty();
    private boolean chatDelivered;
    private boolean dismissed;

    /**
     * Run the check once for this launch: the opt-out is honored before any request (off means zero requests), a repeat
     * dispatch is a no-op, and the fetch, filter, and compare run entirely on {@code worker}. Every failure inside the
     * check leaves the held result empty, which reads as up to date.
     */
    public void dispatch(RuntimeInfo info, WdlConfig config, Transport transport, Executor worker) {
        if (!config.checkForUpdates()) {
            settled.countDown();
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            try {
                List<Release> rows = ReleaseIndex.fetch(info, transport);
                Optional<String> newer = LatestRelease.newerThan(info.loaderId(), info.mcVersion(), info.running(),
                        rows);
                held = newer.map(latest -> new UpdateAvailable(SemVer.display(info.runningRaw()),
                        SemVer.display(latest)));
            } finally {
                settled.countDown();
            }
        });
    }

    /** The held outcome: both versions display-cleaned when an update exists, empty otherwise or not yet. */
    public Optional<UpdateAvailable> available() {
        return held;
    }

    /**
     * Block up to {@code timeoutMillis} for the launch check to settle (the worker finished, or the check was
     * disabled). Returns true if it settled, false on timeout or interruption; {@link #available()} then holds the
     * final result. A launch that never dispatched at all leaves the latch untouched and this returns false at the
     * timeout. Callable off the client thread (the ModMenu update worker).
     */
    public boolean awaitSettled(long timeoutMillis) {
        try {
            return settled.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Whether the join now being handled should deliver the chat line: true at most once per launch, and only when a
     * result is held and {@code showChatMessages} allows chat. A suppressed or too-early join leaves the latch intact,
     * so a later join still delivers. Client main thread only.
     */
    public boolean consumeChatPending(boolean showChatMessages) {
        if (!showChatMessages || chatDelivered || !held.isPresent()) {
            return false;
        }
        chatDelivered = true;
        return true;
    }

    /** Whether the downloads screen should show the banner: a result is held and not dismissed. */
    public boolean bannerVisible() {
        return held.isPresent() && !dismissed;
    }

    /** Hide the banner for the rest of this launch; the held result itself stays readable. */
    public void dismissBanner() {
        dismissed = true;
    }
}
