// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * The immutable prior-session coverage read by the outline classifier to mark a re-arriving container recovered rather
 * than unsaved: the block pos keys and the container-entity {@link UUID}s a prior download session captured and merged
 * back. The worker-thread coverage scan publishes a fresh instance to the main thread as one atomic reference swap, so
 * an instance is never mutated after it is built. The block keys are a primitive {@code LongSet} so the classifier's
 * hot per-tick {@link #contains} test neither boxes nor allocates; the entity ids are a flat {@code Set} because a UUID
 * is globally unique and cannot collide across dimensions, the same split {@link CapturedContainers} draws. MC-free and
 * Java-8-clean.
 */
public final class RecoveredCoverage {
    /** No prior-session coverage. */
    public static final RecoveredCoverage EMPTY = new RecoveredCoverage(LongSets.EMPTY_SET);

    /**
     * No per-position coverage but the shared player ender inventory was already saved by a prior download.
     */
    public static final RecoveredCoverage ENDER_ONLY = new RecoveredCoverage(LongSets.EMPTY_SET, Long2IntMaps.EMPTY_MAP,
            Collections.emptySet(), true);

    private final LongSet blockKeys;
    private final Long2IntMap bookshelfSavedSlots;
    private final Set<UUID> entityIds;
    private final boolean enderRecovered;

    public RecoveredCoverage(LongSet blockKeys) {
        this(blockKeys, Long2IntMaps.EMPTY_MAP, Collections.emptySet(), false);
    }

    public RecoveredCoverage(LongSet blockKeys, Long2IntMap bookshelfSavedSlots, Set<UUID> entityIds,
            boolean enderRecovered) {
        this.blockKeys = LongSets.unmodifiable(blockKeys);
        this.bookshelfSavedSlots = Long2IntMaps.unmodifiable(bookshelfSavedSlots);
        this.entityIds = Collections.unmodifiableSet(entityIds);
        this.enderRecovered = enderRecovered;
    }

    /** Whether the block at this packed {@code BlockPos.asLong()} was captured in a prior session. */
    public boolean contains(long blockKey) {
        return blockKeys.contains(blockKey);
    }

    public boolean containsEntity(UUID entityId) {
        return entityIds.contains(entityId);
    }

    public int bookshelfSavedSlots(long posKey) {
        return bookshelfSavedSlots.get(posKey);
    }

    public boolean enderRecovered() {
        return enderRecovered;
    }
}
