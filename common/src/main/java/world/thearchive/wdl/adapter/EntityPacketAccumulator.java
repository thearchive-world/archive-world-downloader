// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A {@code RemoveEntities} is deliberately not handled at all: a client removal tells you nothing about death versus
 * unload, so eviction would defeat holding entities independent of unload. Stale state is instead superseded: a fresh
 * {@link #spawn} for a reused id replaces the prior entity wholesale (a new identity, all post-spawn state cleared).
 *
 * <p>A chunk key is dimension-local (the overworld and the nether share the whole position space), so every held entity
 * also carries the dimension it was announced in. A key held for the world the player left must not match a captured
 * position in the world they entered.
 *
 * <p>State is taken one chunk at a time by {@link #dropChunk}: the main thread drains a chunk's entities and removes
 * them from here as that chunk leaves the keep-hot window, so this stays bounded as the player roams and a drained
 * entity is never re-promoted (the no-double-write property). A rider is positioned client-side at its vehicle, so the
 * server need not move it; {@link #recordPassengers} and {@link #reposition} re-home each rider to its vehicle's chunk
 * so vehicle and riders drain in one batch and the reconstruct can nest them.
 *
 * <p>Threading: the inbound tee writes (every mutator) and resolves relative moves ({@link #positionOf}) on the single
 * Netty event-loop thread, while the client main thread reads and drains
 * ({@link #chunks}/{@link #dropChunk}/{@link #heldDimensions}, plus the finish tallies {@link #spawnCount} and
 * {@link #droppedAtCapacity}). The map is concurrent, the mutable per-entity fields are volatile for cross-thread
 * visibility, and the per-entity writes for one id are all on that one Netty thread.
 */
class EntityPacketAccumulator<P, V, E> {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * There is deliberately no eviction (a client removal is ambiguous), and a stationary player drains no hot chunks,
     * so a server minting fresh ids forever (a spawn flood, or hours of farm churn) would otherwise grow the map
     * without bound.
     */
    static final int MAX_TRACKED_ENTITIES = 100_000;

    private static final int[] NO_PASSENGERS = new int[0];

    private final Map<Integer, Held<P, V, E>> byId = new ConcurrentHashMap<>();

    private volatile String streamDimension;

    private volatile long spawnCount;

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
     * Fed in packet order rather than from the client's live level, because the marker is teed before the main thread
     * applies it and the spawns that follow it on the wire are already the new world's.
     */
    public void enterDimension(String dimension) {
        this.streamDimension = dimension;
    }

    /**
     * A new id past {@link #MAX_TRACKED_ENTITIES} is skipped and counted; the fed-spawn tally still ticks so the finish
     * reconciliation stays honest about what arrived.
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

    public long droppedAtCapacity() {
        return droppedAtCapacity;
    }

    public long spawnCount() {
        return spawnCount;
    }

    public void recordData(int id, int key, V value) {
        Held<P, V, E> held = byId.get(id);
        if (held != null) {
            held.synced.put(key, value);
        }
    }

    public void recordEquipment(int id, int key, E value) {
        Held<P, V, E> held = byId.get(id);
        if (held != null) {
            held.equipment.put(key, value);
        }
    }

    public void recordPassengers(int id, int[] passengerIds) {
        Held<P, V, E> held = byId.get(id);
        if (held != null) {
            held.passengers = passengerIds.clone();
            reHomePassengers(held);
        }
    }

    public void recordLeash(int id, int holderId) {
        Held<P, V, E> held = byId.get(id);
        if (held != null) {
            held.leashHolderId = holderId;
        }
    }

    public void reposition(int id, long chunkPos, EntityPos pos) {
        Held<P, V, E> held = byId.get(id);
        if (held != null) {
            held.chunkPos = chunkPos;
            held.pos = pos;
            reHomePassengers(held);
        }
    }

    public void recordRotation(int id, float yRot, float xRot) {
        Held<P, V, E> held = byId.get(id);
        if (held != null) {
            held.pos = new EntityPos(held.pos.x(), held.pos.y(), held.pos.z(), yRot, xRot);
        }
    }

    public @Nullable EntityPos positionOf(int id) {
        Held<P, V, E> held = byId.get(id);
        return held != null ? held.pos : null;
    }

    public boolean tracks(int id) {
        return byId.containsKey(id);
    }

    public LongSet chunks(String dimension) {
        LongSet result = new LongLinkedOpenHashSet();
        for (Held<P, V, E> held : byId.values()) {
            if (dimension.equals(held.dimension)) {
                result.add(held.chunkPos);
            }
        }
        return result;
    }

    public List<PacketEntity<P, V, E>> dropChunk(String dimension, long chunkPos) {
        return drain(held -> held.chunkPos == chunkPos && dimension.equals(held.dimension));
    }

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
                List.copyOf(held.synced.values()), List.copyOf(held.equipment.values()),
                held.passengers.clone(), held.leashHolderId);
    }

    private void reHomePassengers(Held<P, V, E> vehicle) {
        if (vehicle.passengers.length == 0) {
            return;
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
            // A proxy switch to another backend server keeps the download running, and that server's entity ids can
            // collide with ones still held, so a rider held for another dimension can belong to another world;
            // re-homing it would file it under the chunk of a vehicle held for the other dimension.
            if (rider != null && vehicle.dimension.equals(rider.dimension)) {
                rider.chunkPos = vehicle.chunkPos;
                pending.addElements(pending.size(), rider.passengers);
            }
        }
    }
}
