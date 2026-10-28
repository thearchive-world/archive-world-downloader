// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
        // Reading a top-level Entities here banks nothing, with no error anywhere and the carry-forward simply
        // never firing. The apply side is deliberately not symmetric: it takes the flat envelope the fold passes.
        Tag rawLevel = onDiskEntityChunkTag.get("Level");
        if (!(rawLevel instanceof CompoundTag)) {
            return;
        }
        Tag rawEntities = ((CompoundTag) rawLevel).get("Entities");
        if (!(rawEntities instanceof ListTag)) {
            return;
        }
        ListTag entities = (ListTag) rawEntities;
        for (Tag rawEntity : entities) {
            if (!(rawEntity instanceof CompoundTag)) {
                continue;
            }
            CompoundTag entity = (CompoundTag) rawEntity;
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
        Tag rawEntities = freshEntitiesChunkTag.get("Entities");
        if (holders.isEmpty() || !(rawEntities instanceof ListTag)) {
            return 0;
        }
        ListTag entities = (ListTag) rawEntities;
        int carried = 0;
        for (Tag rawEntity : entities) {
            if (!(rawEntity instanceof CompoundTag)) {
                continue;
            }
            CompoundTag entity = (CompoundTag) rawEntity;
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
        Tag rawItems = node.get("Items");
        if (rawItems instanceof ListTag) {
            holder.put("Items", ((ListTag) rawItems).copy());
        }
        Tag rawOffers = node.get("Offers");
        if (rawOffers instanceof CompoundTag) {
            holder.put("Offers", ((CompoundTag) rawOffers).copy());
        }
        Tag rawXp = node.get("Xp");
        if (rawXp instanceof IntTag) {
            holder.put("Xp", ((IntTag) rawXp).copy());
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
            carried |= NbtMerge.carryValue(holder, node, "Xp", new IntTag(0));
        }
        return carried;
    }
}
