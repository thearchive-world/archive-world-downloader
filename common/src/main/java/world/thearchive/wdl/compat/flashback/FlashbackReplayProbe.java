// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.flashback;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Identifies Flashback's replay server, so the activation gate can admit that one local-server case while still
 * refusing a genuine singleplayer or LAN-hosted world. Flashback plays a replay through a real {@code IntegratedServer}
 * subclass installed in {@code Minecraft.getSingleplayerServer()}, so the test is class identity on that object: no
 * vanilla path can produce it, and this is the same test Flashback runs on itself.
 *
 * <p>Detects playback, not installation: Flashback being loaded while an ordinary singleplayer world is open leaves a
 * plain {@code IntegratedServer} there, which does not match.
 *
 * <p>Null-object: {@link #INACTIVE} matches nothing, so callers never null-check. Holds no compile-time Flashback
 * reference, the only mention is the class-name string, resolved reflectively.
 */
public final class FlashbackReplayProbe {
    private static final Logger LOGGER = LogManager.getLogger(FlashbackReplayProbe.class);

    /** Flashback's mod id, for the caller's loader mod-list query. */
    public static final String MOD_ID = "flashback";

    // Version-coupled on purpose: a Flashback rename makes forName throw -> INACTIVE + WARN, which refuses
    // capture rather than mis-capturing (detection, not prevention). Class identity is the
    // least-false-positive option.
    private static final String REPLAY_SERVER_CLASS = "com.moulberry.flashback.playback.ReplayServer";

    /** Shared no-op used when Flashback is absent or its class will not resolve. */
    public static final FlashbackReplayProbe INACTIVE = new FlashbackReplayProbe(null);

    private final @Nullable Class<?> replayServerClass;

    private FlashbackReplayProbe(@Nullable Class<?> replayServerClass) {
        this.replayServerClass = replayServerClass;
    }

    /** Resolve the probe once; logs one line describing the outcome. */
    public static FlashbackReplayProbe resolve(boolean flashbackPresent) {
        return resolve(flashbackPresent, REPLAY_SERVER_CLASS, FlashbackReplayProbe.class.getClassLoader());
    }

    // Package-private seam: lets the unit test drive all three outcomes through the real Class.forName without
    // a PlatformBridge stub. initialize=false so no static initializer runs on resolution. LinkageError is
    // caught alongside ClassNotFoundException because resolution is deferred to first use, where a Flashback
    // built for another MC band surfaces as NoClassDefFoundError, an Error that would otherwise escape.
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
     * for null, which is what the gate sees during client shutdown.
     */
    public boolean isReplayServer(@Nullable Object singleplayerServer) {
        return replayServerClass != null && replayServerClass.isInstance(singleplayerServer);
    }
}
