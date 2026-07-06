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
record PacketEntity<P, V, E>(int id, UUID uuid, long chunkPos, EntityPos pos, P spawn, List<V> synced,
        List<E> equipment, int[] passengers, int leashHolderId) {}
