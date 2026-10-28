// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import org.jspecify.annotations.Nullable;

/**
 * A container vehicle's {@code "Items"} and a villager's trade {@code "Offers"} reach the client only through the open
 * menu, so every serialize after that menu closes is empty, and the entity read-merge ({@link EntityMerge#merge})
 * repairs that only inside the one chunk it is writing. Within a session a refold covers an entity that moved, out of a
 * retained in-memory holder; that holder does not survive a session boundary, so without this bank an entity that
 * changed chunk between two downloads would be written again, empty, under the same UUID in a second chunk, and vanilla
 * keeps whichever chunk loads first.
 *
 * <p>Threading. Filled on the writer thread from that thread's own read-only resume scan, and read both there (the
 * entity write) and on another thread (the player's ridden vehicle). Hence the concurrent map.
 */
final class RecoveredEntityContent {
    private final Map<UUID, CompoundTag> holders = new ConcurrentHashMap<>();

    /**
     * Shares {@link EntityMerge#hasCapturedContent} with the outline's recovered set, so exactly the records the
     * outline calls recovered are the ones that can be carried forward.
     */
    void record(CompoundTag onDiskEntityChunkTag) {
        if (!(onDiskEntityChunkTag.get("Entities") instanceof ListTag entities)) {
            return;
        }
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag entity)) {
                continue;
            }
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(entity).entrySet()) {
                if (EntityMerge.hasCapturedContent(node.getValue())) {
                    holders.putIfAbsent(node.getKey(), carryKeysOf(node.getValue()));
                }
            }
        }
    }

    /**
     * Call after the chunk's own read-merge, never before: running it first would fill the keys that merge exists to
     * carry and silence it.
     */
    int applyTo(CompoundTag freshEntitiesChunkTag) {
        if (holders.isEmpty() || !(freshEntitiesChunkTag.get("Entities") instanceof ListTag entities)) {
            return 0;
        }
        int carried = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag entity)) {
                continue;
            }
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(entity).entrySet()) {
                CompoundTag holder = holders.get(node.getKey());
                if (holder != null && carryInto(holder, node.getValue())) {
                    carried++;
                }
            }
        }
        return carried;
    }

    /**
     * The bank keeps the tag it returns, so a caller folds from it through {@link NbtMerge}, which copies, and never
     * through a sink that would alias the list.
     */
    @Nullable
    CompoundTag holderFor(UUID uuid) {
        return holders.get(uuid);
    }

    private static CompoundTag carryKeysOf(CompoundTag node) {
        CompoundTag holder = new CompoundTag();
        if (node.get("Items") instanceof ListTag items) {
            holder.put("Items", items.copy());
        }
        if (node.get("Offers") instanceof CompoundTag offers) {
            holder.put("Offers", offers.copy());
        }
        if (node.get("Xp") instanceof IntTag experience) {
            holder.put("Xp", experience.copy());
        }
        return holder;
    }

    private static boolean carryInto(CompoundTag holder, CompoundTag node) {
        boolean carried = NbtMerge.carryList(holder, node, "Items");
        carried |= NbtMerge.carryCompound(holder, node, "Offers", null);
        // Xp only rides along with offers: where the version writes Xp, a villager the client never traded with
        // serializes it at the client zero, so carrying it alone would restore a level for a merchant whose trades did
        // not come back.
        if (carried) {
            carried |= NbtMerge.carryValue(holder, node, "Xp", IntTag.valueOf(0));
        }
        return carried;
    }
}
