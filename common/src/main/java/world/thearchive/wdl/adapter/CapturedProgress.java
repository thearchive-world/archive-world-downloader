// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The immutable finish-snapshot of the local player's progress surfaces, assembled on the client main thread in
 * {@code finish()} and read by the writer thread when it writes the {@code stats/} file. The blob is already-rendered
 * detached JSON bytes, so the record crosses the thread boundary safely (the live {@code StatisticsManager} keeps
 * mutating on the connection while the save runs). A null blob skips the file: {@code statsJson} is null when
 * {@code captureStatistics} is off or no stats reply has landed.
 *
 * <p>There is no advancement blob at this band. Advancements are a 1.12 addition and this band has achievements
 * instead, whose counts already ride the statistics surface, so the progress snapshot carries one file rather than two.
 *
 * @param playerUuid the local player's UUID, the {@code <uuid>.json} key for the file
 * @param statsJson  the {@code stats/<uuid>.json} bytes, or null to skip that file
 */
final class CapturedProgress {
    private final UUID playerUuid;
    private final byte @Nullable [] statsJson;

    CapturedProgress(UUID playerUuid, byte @Nullable [] statsJson) {
        this.playerUuid = playerUuid;
        this.statsJson = statsJson;
    }

    UUID playerUuid() {
        return playerUuid;
    }

    byte @Nullable [] statsJson() {
        return statsJson;
    }
}
