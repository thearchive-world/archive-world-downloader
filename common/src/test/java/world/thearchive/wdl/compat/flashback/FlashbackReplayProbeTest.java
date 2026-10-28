// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.flashback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moulberry.flashback.playback.LocalServer;
import com.moulberry.flashback.playback.ReplayServer;
import org.junit.jupiter.api.Test;

class FlashbackReplayProbeTest {
    private static final String REAL = "com.moulberry.flashback.playback.ReplayServer";
    private static final String ABSENT = "com.moulberry.flashback.playback.NoSuchClass";
    private static final ClassLoader loader = FlashbackReplayProbeTest.class.getClassLoader();

    @Test
    void flashbackAbsentReturnsInactiveMatchesNothing() {
        FlashbackReplayProbe probe = FlashbackReplayProbe.resolve(false, REAL, loader);
        assertSame(FlashbackReplayProbe.INACTIVE, probe);
        assertFalse(probe.isReplayServer(new ReplayServer()));
        assertFalse(probe.isReplayServer(new Object()));
    }

    @Test
    void flashbackPresentClassResolvesMatchesOnlyThatClass() {
        FlashbackReplayProbe probe = FlashbackReplayProbe.resolve(true, REAL, loader);
        assertTrue(probe.isReplayServer(new ReplayServer()));
        assertFalse(probe.isReplayServer(new Object()));
        assertFalse(probe.isReplayServer("not a server"));
    }

    @Test
    void flashbackPresentClassUnresolvableReturnsInactive() {
        FlashbackReplayProbe probe = FlashbackReplayProbe.resolve(true, ABSENT, loader);
        assertSame(FlashbackReplayProbe.INACTIVE, probe);
        assertFalse(probe.isReplayServer(new ReplayServer()));
    }

    // The stub models the real subclass relation, so this fails if the isInstance test is ever inverted to
    // isAssignableFrom or widened to the supertype.
    @Test
    void aPlainLocalServerAndNullServerNeverMatch() {
        FlashbackReplayProbe probe = FlashbackReplayProbe.resolve(true, REAL, loader);
        assertFalse(probe.isReplayServer(new LocalServer()));
        assertFalse(probe.isReplayServer(null));
        assertTrue(probe.isReplayServer(new ReplayServer()), "the subclass must still match");
    }

    // Drives the shipped class name rather than this file's own literal, so the constant is covered too. The other
    // tests all go through the package-private seam, which leaves the production constant free to be widened to a
    // supertype (making the gate true in genuine singleplayer with Flashback loaded) with the suite still green.
    @Test
    void theShippedClassNameMatchesOnlyTheReplayServer() {
        FlashbackReplayProbe probe = FlashbackReplayProbe.resolve(true);
        assertTrue(probe.isReplayServer(new ReplayServer()));
        assertFalse(probe.isReplayServer(new LocalServer()));
    }
}
