// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package de.johni0702.minecraft.bobby;

/**
 * Bare stand-in at Bobby's real class name so {@code BobbyChunkFilter}'s {@code Class.forName} resolution and
 * {@code isInstance} check can be exercised without a dependency on the Bobby mod. Only the fully-qualified name and
 * instance identity matter here; the real Bobby {@code FakeChunk} extends {@code LevelChunk}, which is irrelevant to
 * the filter.
 */
public final class FakeChunk {
    public FakeChunk() {}
}
