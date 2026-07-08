// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A read-only view of the session's captured-set, read by the outline classifier to decide which loaded containers
 * still carry a rim. It mirrors {@link CaptureCounts} as an encapsulation boundary across the
 * {@link CaptureController.Session} seam: MC-free (primitive fastutil collections, a {@code Set}, a {@code boolean})
 * and Java-8-clean. The members are the identities a container can match: a block pos key, a borne-entity {@link UUID},
 * the single global ender flag (one ender capture clears every ender chest's rim), a chiseled-bookshelf per-slot
 * captured mask, and the recorded block-entity type per captured pos. The block keys are a primitive {@code LongSet} so
 * the classifier's hot per-tick {@link #containsBlock} test over every loaded container neither boxes its key nor
 * allocates.
 *
 * <p>Construct one per read, as {@link CaptureController.Session#capturedContainers()} does, and do not cache it. The
 * collection members wrap the session's live sets unmodifiable rather than copying them (read and capture share the one
 * client thread), so they track later mutation, but the {@code boolean} ender flag is taken by value at construction. A
 * held instance would see the collections move on while the ender flag stays frozen; a fresh view each read keeps every
 * member consistent, for the cost of a few short-lived wrappers.
 */
public final class CapturedContainers {
    /** No container captured this session: every membership query is false. */
    public static final CapturedContainers EMPTY = new CapturedContainers(LongSets.EMPTY_SET,
            Collections.emptySet(), false);

    private final LongSet blockKeys;
    private final Set<UUID> entityIds;
    private final boolean enderCaptured;
    private final Long2IntMap bookshelfCapturedSlots;
    private final Long2ObjectMap<String> blockTypes;

    public CapturedContainers(LongSet blockKeys, Set<UUID> entityIds, boolean enderCaptured) {
        this(blockKeys, entityIds, enderCaptured, Long2IntMaps.EMPTY_MAP, Long2ObjectMaps.emptyMap());
    }

    public CapturedContainers(LongSet blockKeys, Set<UUID> entityIds, boolean enderCaptured,
            Long2IntMap bookshelfCapturedSlots, Long2ObjectMap<String> blockTypes) {
        this.blockKeys = LongSets.unmodifiable(blockKeys);
        this.entityIds = Collections.unmodifiableSet(entityIds);
        this.enderCaptured = enderCaptured;
        this.bookshelfCapturedSlots = Long2IntMaps.unmodifiable(bookshelfCapturedSlots);
        this.blockTypes = Long2ObjectMaps.unmodifiable(blockTypes);
    }

    /** Whether the block at this packed {@code BlockPos.asLong()} had its contents captured this session. */
    public boolean containsBlock(long blockKey) {
        return blockKeys.contains(blockKey);
    }

    /** Whether the borne-container entity with this id had its contents captured this session. */
    public boolean containsEntity(UUID entityId) {
        return entityIds.contains(entityId);
    }

    /** Whether any ender chest was opened this session (the shared ender inventory is captured once). */
    public boolean enderCaptured() {
        return enderCaptured;
    }

    /** The mask of slots captured this session for the chiseled bookshelf at this pos (bit n = slot n), 0 if none. */
    public int bookshelfCapturedSlots(long posKey) {
        return bookshelfCapturedSlots.get(posKey);
    }

    /**
     * The block-entity registry id recorded when the container at this packed pos was captured, or {@code null} if none
     * is recorded. The outline compares it against the live block-entity type to detect a same-position block
     * replacement (Gate 2): a mismatch means the captured container is gone and its rim returns.
     */
    @Nullable
    String capturedBlockType(long posKey) {
        return blockTypes.get(posKey);
    }
}
