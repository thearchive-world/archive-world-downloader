// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

final class CapturedProgress {
    private final UUID playerUuid;
    private final byte @Nullable [] advancementsJson;
    private final byte @Nullable [] statsJson;

    CapturedProgress(UUID playerUuid, byte @Nullable [] advancementsJson, byte @Nullable [] statsJson) {
        this.playerUuid = playerUuid;
        this.advancementsJson = advancementsJson;
        this.statsJson = statsJson;
    }

    UUID playerUuid() {
        return playerUuid;
    }

    byte @Nullable [] advancementsJson() {
        return advancementsJson;
    }

    byte @Nullable [] statsJson() {
        return statsJson;
    }
}
