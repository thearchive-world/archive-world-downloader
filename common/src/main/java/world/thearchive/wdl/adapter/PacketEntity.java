// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import java.util.UUID;

/**
 * One entity drained from {@link EntityPacketAccumulator}: the inputs the main thread reconstructs and saves it from.
 * Beyond the spawn and synced state item frames need, a general entity carries its post-spawn packets: its final
 * {@link EntityPos position}, equipment (one value per slot), passenger int ids, and the leash holder int id (0 when
 * unleashed). Passengers and the leash holder stay int ids here, the packet-layer key, and are resolved to the referent
 * entity at reconstruct. Generic over the spawn payload {@code P}, the synced-value payload {@code V}, and the
 * equipment payload {@code E} so it stays MC-free and headless-testable; the production specialization binds the MC
 * packet types.
 */
final class PacketEntity<P, V, E> {
    private final int id;
    private final UUID uuid;
    private final long chunkPos;
    private final EntityPos pos;
    private final P spawn;
    private final List<V> synced;
    private final List<E> equipment;
    private final int[] passengers;
    private final int leashHolderId;

    PacketEntity(int id, UUID uuid, long chunkPos, EntityPos pos, P spawn, List<V> synced, List<E> equipment,
            int[] passengers, int leashHolderId) {
        this.id = id;
        this.uuid = uuid;
        this.chunkPos = chunkPos;
        this.pos = pos;
        this.spawn = spawn;
        this.synced = synced;
        this.equipment = equipment;
        this.passengers = passengers;
        this.leashHolderId = leashHolderId;
    }

    int id() {
        return id;
    }

    UUID uuid() {
        return uuid;
    }

    long chunkPos() {
        return chunkPos;
    }

    EntityPos pos() {
        return pos;
    }

    P spawn() {
        return spawn;
    }

    List<V> synced() {
        return synced;
    }

    List<E> equipment() {
        return equipment;
    }

    int[] passengers() {
        return passengers;
    }

    int leashHolderId() {
        return leashHolderId;
    }
}
