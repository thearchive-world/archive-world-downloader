// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Accumulates the live entity and container counts with their exact dedup rules, so the report's numbers provably
 * follow them and are testable headless: entities dedup by UUID, containers by a caller-supplied logical id (a double
 * chest is fed under one id, so it counts as one). The counts are read directly through {@link #entityCount()} /
 * {@link #containerCount()}, including by the HUD each frame. The chunk total and its per-dimension breakdown come from
 * the capture engine's already-deduped position sets, so they are not accumulated here.
 */
public final class DownloadCountsBuilder {
    private final Set<UUID> entities = new HashSet<>();
    private final Set<String> containers = new LinkedHashSet<>();

    /** Observe one captured entity, deduped by UUID. */
    public void addEntity(UUID uuid) {
        entities.add(uuid);
    }

    /** Observe one container whose rich/opened state was captured; the caller feeds a double chest as one id. */
    public void addContainer(String containerId) {
        containers.add(containerId);
    }

    /** The live dedup-correct container count, read by the HUD each frame without allocating a snapshot. */
    public int containerCount() {
        return containers.size();
    }

    /** The live dedup-correct entity count, read by the HUD each frame without allocating a snapshot. */
    public int entityCount() {
        return entities.size();
    }
}
