// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * The outline's cull structure: a sparse map from {@code SectionPos.asLong()} to the rims that fall in that section,
 * holding only sections that contain a rim. It is rebuilt on the client tick and read on the render thread, which in
 * vanilla is the same client thread, so it carries no concurrency machinery. The per-frame render iterates the
 * populated sections, frustum-rejects whole sections, and emits only the survivors' rims, so per-frame cost scales with
 * what is on screen, not the total tracked set.
 */
public final class OutlineDrawSet {
    private final Long2ObjectMap<List<OutlineRim>> sections = new Long2ObjectOpenHashMap<>();

    /**
     * Empty every section's rims before the tick rebuilds membership, retaining each bucket and its grown backing array
     * so the rebuild refills it without reallocating (the draw-set is rebuilt every tick). A bucket already empty here
     * got no rim last tick, so its section has left the clamp: drop it, which bounds the map to the clamp plus one tick
     * of lag rather than letting it accumulate empty buckets along the camera's path.
     */
    void clear() {
        ObjectIterator<Long2ObjectMap.Entry<List<OutlineRim>>> entries = Long2ObjectMaps.fastIterator(sections);
        while (entries.hasNext()) {
            List<OutlineRim> rims = entries.next().getValue();
            if (rims.isEmpty()) {
                entries.remove();
            } else {
                rims.clear();
            }
        }
    }

    /** Add {@code rim} to its section bucket, creating the bucket on first use. */
    void add(long sectionKey, OutlineRim rim) {
        sections.computeIfAbsent(sectionKey, key -> new ArrayList<>()).add(rim);
    }

    /** The populated sections keyed by {@code SectionPos.asLong()}, for the per-frame cull to iterate. */
    public Long2ObjectMap<List<OutlineRim>> sections() {
        return sections;
    }
}
