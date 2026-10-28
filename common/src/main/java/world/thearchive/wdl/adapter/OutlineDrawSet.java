// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * It is rebuilt on the client tick and read on the render thread, which in vanilla is the same client thread, so it
 * carries no concurrency machinery.
 */
public final class OutlineDrawSet {
    private final Long2ObjectMap<List<OutlineRim>> sections = new Long2ObjectOpenHashMap<>();

    /**
     * A bucket already empty here got no rim last tick: drop it, which bounds the map to the clamp plus one tick of lag
     * rather than letting it accumulate empty buckets along the camera's path.
     */
    void clear() {
        ObjectIterator<Long2ObjectMap.Entry<List<OutlineRim>>> entries = sections.long2ObjectEntrySet().iterator();
        while (entries.hasNext()) {
            List<OutlineRim> rims = entries.next().getValue();
            if (rims.isEmpty()) {
                entries.remove();
            } else {
                rims.clear();
            }
        }
    }

    void add(long sectionKey, OutlineRim rim) {
        sections.computeIfAbsent(sectionKey, key -> new ArrayList<>()).add(rim);
    }

    /** The sections keyed by their packed section position. */
    public Long2ObjectMap<List<OutlineRim>> sections() {
        return sections;
    }
}
