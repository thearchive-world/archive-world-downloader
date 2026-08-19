// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.bobby;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.platform.PlatformBridge;

/**
 * Rejects Bobby's locally cached chunks from capture. Bobby serves cached {@code FakeChunk} objects through
 * {@code ClientChunkCache.getChunk}, which is exactly where terrain capture and the container-outline scan read; this
 * filter identifies them by class so those reads can treat a cached chunk as an unsent one.
 *
 * <p>Null-object: {@link #INACTIVE} matches nothing, so callers never null-check. Holds no compile-time Bobby
 * reference, the only mention is the class-name string, resolved reflectively.
 */
public final class BobbyChunkFilter {
    private static final Logger LOGGER = LogManager.getLogger(BobbyChunkFilter.class);

    private static final String BOBBY_MOD_ID = "bobby";

    // Version-coupled on purpose: a Bobby rename makes forName throw -> INACTIVE + WARN (detection, not
    // prevention). A class-identity match is the least-false-positive option.
    private static final String FAKE_CHUNK_CLASS = "de.johni0702.minecraft.bobby.FakeChunk";

    /** Shared no-op used when Bobby is absent or its class will not resolve. */
    public static final BobbyChunkFilter INACTIVE = new BobbyChunkFilter(null);

    private final @Nullable Class<?> fakeChunkClass;

    private BobbyChunkFilter(@Nullable Class<?> fakeChunkClass) {
        this.fakeChunkClass = fakeChunkClass;
    }

    /** Resolve the shared filter once at startup; logs one line describing the outcome. */
    public static BobbyChunkFilter resolve(PlatformBridge bridge) {
        return resolve(bridge.isModLoaded(BOBBY_MOD_ID), FAKE_CHUNK_CLASS, BobbyChunkFilter.class.getClassLoader());
    }

    // Package-private seam: lets the unit test drive all three outcomes through the real Class.forName without a
    // full PlatformBridge stub. initialize=false so no static initializer runs on resolution.
    static BobbyChunkFilter resolve(boolean bobbyPresent, String className, ClassLoader loader) {
        if (!bobbyPresent) {
            return INACTIVE;
        }
        try {
            Class<?> resolved = Class.forName(className, false, loader);
            LOGGER.info("Bobby detected; world downloads exclude its locally cached chunks and save only "
                    + "server-sent chunks");
            return new BobbyChunkFilter(resolved);
        } catch (ClassNotFoundException e) {
            LOGGER.warn("Bobby is present but its cached-chunk class ({}) could not be resolved; its locally "
                    + "cached chunks may be saved into downloads", className);
            return INACTIVE;
        }
    }

    /** True when {@code chunk} is a Bobby cached chunk. Always false for {@link #INACTIVE}. */
    public boolean isBobbyChunk(Object chunk) {
        return fakeChunkClass != null && fakeChunkClass.isInstance(chunk);
    }
}
