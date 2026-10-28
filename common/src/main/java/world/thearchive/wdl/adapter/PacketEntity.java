// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import java.util.UUID;

record PacketEntity<P, V, E>(int id, UUID uuid, long chunkPos, EntityPos pos, P spawn, List<V> synced,
        List<E> equipment, int[] passengers, int leashHolderId) {}
