// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import java.util.UUID;

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
