// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package de.johni0702.minecraft.bobby;

/**
 * Bare stand-in at Bobby's real class name so BobbyChunkFilter's Class.forName resolution and isInstance check can be
 * exercised without a dependency on the Bobby mod. Only the fully-qualified name and instance identity matter here; the
 * real Bobby FakeChunk extends LevelChunk, which is irrelevant to the filter.
 */
public final class FakeChunk {
    public FakeChunk() {}
}
