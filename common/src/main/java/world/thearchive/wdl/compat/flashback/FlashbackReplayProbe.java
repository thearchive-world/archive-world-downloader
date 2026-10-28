// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.flashback;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects playback, not installation: Flashback being loaded while an ordinary singleplayer world is open leaves a
 * plain {@code IntegratedServer} there, which does not match.
 */
public final class FlashbackReplayProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger(FlashbackReplayProbe.class);

    /** Flashback's mod id. */
    public static final String MOD_ID = "flashback";

    // Version-coupled on purpose: a Flashback rename makes forName throw -> INACTIVE + WARN, which refuses
    // capture rather than mis-capturing (detection, not prevention). Class identity is the
    // least-false-positive option.
    private static final String REPLAY_SERVER_CLASS = "com.moulberry.flashback.playback.ReplayServer";

    public static final FlashbackReplayProbe INACTIVE = new FlashbackReplayProbe(null);

    private final @Nullable Class<?> replayServerClass;

    private FlashbackReplayProbe(@Nullable Class<?> replayServerClass) {
        this.replayServerClass = replayServerClass;
    }

    /** Resolve the probe once; logs at most one line describing the outcome. */
    public static FlashbackReplayProbe resolve(boolean flashbackPresent) {
        return resolve(flashbackPresent, REPLAY_SERVER_CLASS, FlashbackReplayProbe.class.getClassLoader());
    }

    // LinkageError is caught alongside ClassNotFoundException because a Flashback built for another MC band can surface
    // as NoClassDefFoundError, an Error that would otherwise escape.
    static FlashbackReplayProbe resolve(boolean flashbackPresent, String className, ClassLoader loader) {
        if (!flashbackPresent) {
            return INACTIVE;
        }
        try {
            Class<?> resolved = Class.forName(className, false, loader);
            LOGGER.info("Flashback detected; world downloads are permitted during replay playback");
            return new FlashbackReplayProbe(resolved);
        } catch (ClassNotFoundException | LinkageError e) {
            LOGGER.warn("Flashback is present but its replay-server class ({}) could not be resolved; "
                    + "downloads during replay playback stay refused", className);
            return INACTIVE;
        }
    }

    /**
     * True when {@code singleplayerServer} is Flashback's replay server. Always false for {@link #INACTIVE}, and false
     * for null.
     */
    public boolean isReplayServer(@Nullable Object singleplayerServer) {
        return replayServerClass != null && replayServerClass.isInstance(singleplayerServer);
    }
}
