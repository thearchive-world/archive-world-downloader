// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The immutable finish-snapshot of the local player's progress surfaces, assembled on the client main thread in
 * {@code finish()} and read by the writer thread when it writes the {@code stats/} and {@code advancements/} files.
 * Both blobs are already-rendered detached JSON bytes, so the record crosses the thread boundary safely (the live
 * {@code ClientAdvancements}/{@code StatsCounter} keep mutating on the connection while the save runs). A null blob
 * skips that file. {@code advancementsJson} is null when {@code captureAdvancements} is off or its serialization fails
 * (per-surface fail-soft); {@code statsJson} is null when {@code captureStatistics} is off or no stats reply has
 * landed.
 *
 * @param playerUuid       the local player's UUID, the {@code <uuid>.json} key for both files
 * @param advancementsJson the {@code advancements/<uuid>.json} bytes, or null to skip that file
 * @param statsJson        the {@code stats/<uuid>.json} bytes, or null to skip that file
 */
record CapturedProgress(UUID playerUuid, byte @Nullable [] advancementsJson, byte @Nullable [] statsJson) {}
