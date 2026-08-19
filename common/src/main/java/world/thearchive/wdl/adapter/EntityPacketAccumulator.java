// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The inbound entity packet state accumulator: holds each entity's synced packet state independent of whether the
 * client still has it loaded, so an entity flown past fast and unloaded can still be written. It holds the post-spawn
 * packets for every non-player type: position (so a moving entity re-homes to the chunk it ends in), equipment (one
 * value per slot), passengers, and the leash holder.
 *
 * <p>Keyed by the integer entity id, the packet-layer key every post-spawn packet carries, and bridged to the UUID, the
 * save-layer key the {@link EntityBuffer} and the report count on ({@code AddEntity} carries both). A
 * {@code RemoveEntities} is deliberately not handled at all: a client removal is {@code RemovalReason.DISCARDED} and
 * tells you nothing about death versus unload, so eviction would defeat holding entities independent of unload. Stale
 * state is instead superseded the only correct way, a fresh {@link #spawn} for a reused id replaces the prior entity
 * wholesale (a new identity, all post-spawn state cleared). The capture one layer up samples {@code RemoveEntities} for
 * the range estimator and drops its book entry, but the accumulator itself must never evict: reconstruction owns every
 * tracked entity until finish.
 *
 * <p>A chunk key is dimension-local (the overworld and the nether share the whole position space), so every held entity
 * also carries the dimension it was announced in, taken from {@link #enterDimension}, which the in-stream dimension
 * marker advances in packet order. Every read is scoped to one dimension for that reason: a key held for the world the
 * player left must not match a captured position in the world they entered, and the connection outlives a dimension
 * change while this holds state across it. The dimension is fixed at spawn and never re-homed, because a server-side
 * world change reaches the client as a removal and a fresh spawn, not as a move. Identified by the server's own level
 * key as a string, the identity the other per-dimension packet-side stores ({@code SendRangeEstimator},
 * {@code CoveredChunkIndex}) already use.
 *
 * <p>State is taken one chunk at a time by {@link #dropChunk}: the main thread drains a chunk's entities and removes
 * them from here as that chunk leaves the keep-hot window, so this stays bounded as the player roams and a drained
 * entity is never re-promoted (the no-double-write property). A rider is positioned client-side at its vehicle, so the
 * server need not move it; {@link #recordPassengers} and {@link #reposition} re-home each rider to its vehicle's chunk
 * so vehicle and riders drain in one batch and the reconstruct can nest them. Generic over the spawn payload {@code P},
 * the synced-value payload {@code V}, and the equipment payload {@code E} so the bookkeeping is MC-free and
 * headless-testable; {@link EntityPacketCapture} binds the MC packet types and adds the connection-scoped publication.
 *
 * <p>Threading: the inbound tee writes (every mutator) and resolves relative moves ({@link #positionOf}) on the single
 * Netty event-loop thread, while the client main thread reads and drains
 * ({@link #chunks}/{@link #dropChunk}/{@link #heldDimensions}, plus the finish tallies {@link #spawnCount} and
 * {@link #droppedAtCapacity}). The map is concurrent, the mutable per-entity fields are volatile for cross-thread
 * visibility, and the per-entity writes for one id are all on that one Netty thread, so the only cross-thread race is a
 * drain observing a concurrent update, which the accepted-ghosting trade already covers.
 */
class EntityPacketAccumulator<P, V, E> {
    private static final Logger LOGGER = LogManager.getLogger(EntityPacketAccumulator.class);

    /**
     * The tracked-entity ceiling. There is deliberately no eviction (a client removal is ambiguous), and a stationary
     * player drains no hot chunks, so a server minting fresh ids forever (a spawn flood, or hours of farm churn) would
     * otherwise grow the map without bound. Far above any legitimate tracked population near one player; at the ceiling
     * a new id is skipped and counted, a reused id still replaces.
     */
    static final int MAX_TRACKED_ENTITIES = 100_000;

    private static final int[] NO_PASSENGERS = new int[0];

    private final Map<Integer, Held<P, V, E>> byId = new ConcurrentHashMap<>();

    /**
     * The dimension every later {@link #spawn} is announced in. Written and read on the single Netty thread only, by
     * the in-stream dimension marker and by {@link #spawn}; named apart from the drains' own dimension parameter
     * because confusing the stream's current world with the world a caller is asking about is the defect this keying
     * exists to prevent. Volatile is belt only, since nothing off that thread reads it: what the main thread reads is
     * the per-entity stamp, which is final and published by the map.
     */
    private volatile String streamDimension;

    /**
     * Total {@link #spawn} calls this session, the count of spawn packets the tee fed in, for the finish diagnostic
     * that reconciles received against written (it does not deduplicate a reused id or a reload). Written only on the
     * single Netty thread; volatile for the main-thread read at finish.
     */
    private volatile long spawnCount;

    /** Spawns skipped at {@link #MAX_TRACKED_ENTITIES}; zero in any legitimate capture. */
    private volatile long droppedAtCapacity;

    private static final class Held<P, V, E> {
        private final UUID uuid;
        private final String dimension;
        private final P spawn;
        private final Map<Integer, V> synced = new ConcurrentHashMap<>();
        private final Map<Integer, E> equipment = new ConcurrentHashMap<>();
        private volatile long chunkPos;
        private volatile EntityPos pos;
        private volatile int[] passengers = NO_PASSENGERS;
        private volatile int leashHolderId;

        private Held(UUID uuid, String dimension, long chunkPos, EntityPos pos, P spawn) {
            this.uuid = uuid;
            this.dimension = dimension;
            this.chunkPos = chunkPos;
            this.pos = pos;
            this.spawn = spawn;
        }
    }

    EntityPacketAccumulator(String dimension) {
        this.streamDimension = dimension;
    }

    /**
     * Bind the dimension every later {@link #spawn} belongs to, from the in-stream marker that announces one. Fed in
     * packet order rather than from the client's live level, because the marker is teed before the main thread applies
     * it and the spawns that follow it on the wire are already the new world's. Re-announcing the dimension the stream
     * is already in (a death respawn inside one dimension, where a death in the nether with an overworld respawn point
     * names a different world and is a genuine change) leaves every held entity where it is.
     */
    public void enterDimension(String dimension) {
        this.streamDimension = dimension;
    }

    /**
     * Record (or wholesale replace, for a reused id) the entity at {@code id}, clearing all post-spawn state, under the
     * dimension the stream is currently in. A new id past {@link #MAX_TRACKED_ENTITIES} is skipped and counted; the
     * fed-spawn tally still ticks so the finish reconciliation stays honest about what arrived. The check-then-act on
     * the map is safe because the inbound tee is the only spawner and the main thread's dropChunk only removes, so a
     * concurrent drain can only shrink the size read here.
     */
    public void spawn(int id, UUID uuid, long chunkPos, EntityPos pos, P spawn) {
        spawnCount++;
        if (byId.size() >= MAX_TRACKED_ENTITIES && !byId.containsKey(id)) {
            if (droppedAtCapacity == 0) {
                LOGGER.info("entity tracking ceiling reached: {} entities already held, skipping new ids until "
                        + "chunks drain; a legitimate capture never reaches this", MAX_TRACKED_ENTITIES);
            }
            droppedAtCapacity++;
            return;
        }
        byId.put(id, new Held<>(uuid, streamDimension, chunkPos, pos, spawn));
    }

    /** Spawns skipped at the tracking ceiling this session; zero in any legitimate capture. */
    public long droppedAtCapacity() {
        return droppedAtCapacity;
    }

    /** Total spawn packets fed in this session (the finish diagnostic's received tally). */
    public long spawnCount() {
        return spawnCount;
    }

    /** Merge one synced value, keyed by its accessor {@code key} so the latest wins; ignored if id is unspawned. */
    public void recordData(int id, int key, V value) {
        Held<P, V, E> held = byId.get(id);
        if (held != null) {
            held.synced.put(key, value);
        }
    }

    /** Merge one equipment value, keyed by its slot {@code key} so the latest wins; ignored if id is unspawned. */
    public void recordEquipment(int id, int key, E value) {
        Held<P, V, E> held = byId.get(id);
        if (held != null) {
            held.equipment.put(key, value);
        }
    }

    /**
     * Replace the passenger int ids of {@code id} wholesale (the client ejects then re-seats), and re-home each tracked
     * rider to this vehicle's chunk so they drain together. Ignored if {@code id} is unspawned.
     */
    public void recordPassengers(int id, int[] passengerIds) {
        Held<P, V, E> held = byId.get(id);
        if (held != null) {
            held.passengers = passengerIds.clone();
            reHomePassengers(held);
        }
    }

    /** Record the leash holder int id of {@code id} (0 unleashes); ignored if id is unspawned. */
    public void recordLeash(int id, int holderId) {
        Held<P, V, E> held = byId.get(id);
        if (held != null) {
            held.leashHolderId = holderId;
        }
    }

    /**
     * Move {@code id} to an absolute position and the chunk it now sits in, carrying its riders along. Ignored if
     * {@code id} is unspawned. The MC specialization resolves relative moves against {@link #positionOf} before calling
     * this.
     */
    public void reposition(int id, long chunkPos, EntityPos pos) {
        Held<P, V, E> held = byId.get(id);
        if (held != null) {
            held.chunkPos = chunkPos;
            held.pos = pos;
            reHomePassengers(held);
        }
    }

    /**
     * Replace the pose of {@code id} in place, leaving its chunk home and its riders alone; ignored if {@code id} is
     * unspawned.
     */
    public void recordRotation(int id, float yRot, float xRot) {
        Held<P, V, E> held = byId.get(id);
        if (held != null) {
            held.pos = new EntityPos(held.pos.x(), held.pos.y(), held.pos.z(), yRot, xRot);
        }
    }

    /** The current position of {@code id}, for resolving a relative move; null if unspawned. */
    public @Nullable EntityPos positionOf(int id) {
        Held<P, V, E> held = byId.get(id);
        return held != null ? held.pos : null;
    }

    /** Whether {@code id} has an accumulated entry, so the tee can skip merging an untracked entity's data. */
    public boolean tracks(int id) {
        return byId.containsKey(id);
    }

    /**
     * The chunks of {@code dimension} currently holding accumulated entities (a snapshot, safe to iterate while
     * draining). Another dimension's chunks are absent: its position space is a different one, so a key from it answers
     * no question about this one.
     */
    public LongSet chunks(String dimension) {
        LongSet result = new LongLinkedOpenHashSet();
        for (Held<P, V, E> held : byId.values()) {
            if (dimension.equals(held.dimension)) {
                result.add(held.chunkPos);
            }
        }
        return result;
    }

    /** Remove and return every accumulated entity of {@code dimension} in {@code chunkPos}, to reconstruct. */
    public List<PacketEntity<P, V, E>> dropChunk(String dimension, long chunkPos) {
        return drain(held -> held.chunkPos == chunkPos && dimension.equals(held.dimension));
    }

    /**
     * The dimensions entities are currently held for. Lets a caller settle a held remainder one dimension at a time,
     * which is the only way to ask a question whose answer is per dimension, such as whether that dimension's terrain
     * was captured where the entity stands.
     */
    public Set<String> heldDimensions() {
        Set<String> result = new LinkedHashSet<>();
        for (Held<P, V, E> held : byId.values()) {
            result.add(held.dimension);
        }
        return result;
    }

    private List<PacketEntity<P, V, E>> drain(Predicate<Held<P, V, E>> take) {
        List<PacketEntity<P, V, E>> drained = new ArrayList<>();
        Iterator<Map.Entry<Integer, Held<P, V, E>>> entries = byId.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, Held<P, V, E>> entry = entries.next();
            if (take.test(entry.getValue())) {
                drained.add(snapshot(entry.getKey(), entry.getValue()));
                entries.remove();
            }
        }
        return drained;
    }

    private static <P, V, E> PacketEntity<P, V, E> snapshot(int id, Held<P, V, E> held) {
        return new PacketEntity<>(id, held.uuid, held.chunkPos, held.pos, held.spawn,
                ImmutableList.copyOf(held.synced.values()), ImmutableList.copyOf(held.equipment.values()),
                held.passengers.clone(), held.leashHolderId);
    }

    private void reHomePassengers(Held<P, V, E> vehicle) {
        if (vehicle.passengers.length == 0) {
            return; // the loop tolerates an empty list, but the two collections are allocated before it
        }
        IntSet visited = new IntOpenHashSet();
        IntArrayList pending = new IntArrayList(vehicle.passengers);
        while (!pending.isEmpty()) {
            int riderId = pending.removeInt(pending.size() - 1);
            // A malformed stream can seat a vehicle inside its own rider; visiting each id once ends that walk.
            if (!visited.add(riderId)) {
                continue;
            }
            Held<P, V, E> rider = byId.get(riderId);
            // A rider held for another dimension is a recycled id whose entity the stream has already
            // replaced elsewhere; re-homing it would move an entity of one world into another world's chunk.
            if (rider != null && vehicle.dimension.equals(rider.dimension)) {
                rider.chunkPos = vehicle.chunkPos;
                pending.addElements(pending.size(), rider.passengers);
            }
        }
    }
}
