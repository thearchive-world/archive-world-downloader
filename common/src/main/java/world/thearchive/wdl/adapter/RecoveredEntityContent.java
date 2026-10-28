// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
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
    private final Map<UUID, NBTTagCompound> holders = new ConcurrentHashMap<>();

    /**
     * Shares {@link EntityMerge#hasCapturedContent} with the outline's recovered set, so exactly the records the
     * outline calls recovered are the ones that can be carried forward.
     */
    void record(NBTTagCompound onDiskEntityChunkTag) {
        // Reading a top-level Entities here banks nothing, with no error anywhere and the carry-forward simply
        // never firing. The apply side is deliberately not symmetric: it takes the flat envelope the fold passes.
        NBTBase rawLevel = onDiskEntityChunkTag.getTag("Level");
        if (!(rawLevel instanceof NBTTagCompound)) {
            return;
        }
        NBTBase rawEntities = ((NBTTagCompound) rawLevel).getTag("Entities");
        if (!(rawEntities instanceof NBTTagList)) {
            return;
        }
        NBTTagList entities = (NBTTagList) rawEntities;
        for (NBTBase rawEntity : NbtElements.of(entities)) {
            if (!(rawEntity instanceof NBTTagCompound)) {
                continue;
            }
            NBTTagCompound entity = (NBTTagCompound) rawEntity;
            for (Map.Entry<UUID, NBTTagCompound> node : EntityTreeWalk.byUuid(entity).entrySet()) {
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
    int applyTo(NBTTagCompound freshEntitiesChunkTag) {
        NBTBase rawEntities = freshEntitiesChunkTag.getTag("Entities");
        if (holders.isEmpty() || !(rawEntities instanceof NBTTagList)) {
            return 0;
        }
        NBTTagList entities = (NBTTagList) rawEntities;
        int carried = 0;
        for (NBTBase rawEntity : NbtElements.of(entities)) {
            if (!(rawEntity instanceof NBTTagCompound)) {
                continue;
            }
            NBTTagCompound entity = (NBTTagCompound) rawEntity;
            for (Map.Entry<UUID, NBTTagCompound> node : EntityTreeWalk.byUuid(entity).entrySet()) {
                NBTTagCompound holder = holders.get(node.getKey());
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
    NBTTagCompound holderFor(UUID uuid) {
        return holders.get(uuid);
    }

    private static NBTTagCompound carryKeysOf(NBTTagCompound node) {
        NBTTagCompound holder = new NBTTagCompound();
        NBTBase rawItems = node.getTag("Items");
        if (rawItems instanceof NBTTagList) {
            NBTTagList items = (NBTTagList) rawItems;
            holder.setTag("Items", items.copy());
        }
        NBTBase rawOffers = node.getTag("Offers");
        if (rawOffers instanceof NBTTagCompound) {
            NBTTagCompound offers = (NBTTagCompound) rawOffers;
            holder.setTag("Offers", offers.copy());
        }
        NBTBase rawExperience = node.getTag("Xp");
        if (rawExperience instanceof NBTTagInt) {
            NBTTagInt experience = (NBTTagInt) rawExperience;
            holder.setTag("Xp", experience.copy());
        }
        return holder;
    }

    private static boolean carryInto(NBTTagCompound holder, NBTTagCompound node) {
        boolean carried = NbtMerge.carryList(holder, node, "Items");
        carried |= NbtMerge.carryCompound(holder, node, "Offers", null);
        // Xp only rides along with offers: where the version writes Xp, a villager the client never traded with
        // serializes it at the client zero, so carrying it alone would restore a level for a merchant whose trades did
        // not come back.
        if (carried) {
            carried |= NbtMerge.carryValue(holder, node, "Xp", new NBTTagInt(0));
        }
        return carried;
    }
}
