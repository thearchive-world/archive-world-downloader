// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class DownloadCountsBuilder {
    private final Set<UUID> entities = new HashSet<>();
    private final Set<String> containers = new LinkedHashSet<>();

    /** Observe one captured entity, deduped by UUID. */
    public void addEntity(UUID uuid) {
        entities.add(uuid);
    }

    public void addContainer(String containerId) {
        containers.add(containerId);
    }

    public int containerCount() {
        return containers.size();
    }

    /** The live dedup-correct entity count, read by the HUD each frame without allocating a snapshot. */
    public int entityCount() {
        return entities.size();
    }
}
