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
 * The interaction-captured content a previous download already saved, keyed by entity UUID, so a resumed download can
 * carry it onto a copy of the same entity filed under a different entity-chunk.
 *
 * <p>Why it has to exist. A container vehicle's {@code "Items"} and a villager's trade {@code "Offers"} reach the
 * client only through the open menu, so every serialize after that menu closes is empty, and the entity read-merge
 * ({@link EntityMerge#merge}) repairs that only inside the one chunk file it is writing. Within a session
 * {@link EntityContainerMerge#refoldFlushedContainers} covers an entity that moved, out of a retained in-memory holder;
 * that holder does not survive a session boundary, so an entity that changed entity-chunk between two downloads is
 * written again, empty, under the same UUID in a second chunk file, and vanilla keeps whichever chunk loads first. This
 * is the cross-session half of the same repair, sourced from the save rather than from memory.
 *
 * <p>Threading. Filled on the writer thread from that thread's own read-only resume scan, and read both there (the
 * entity write) and on the client thread (the player's ridden vehicle, whose contents reach disk through the player
 * record and never through an entity write). Hence the concurrent map. The copy on the way in is a lifetime measure and
 * not a threading one: it detaches the banked holder from a scanned chunk the scan is about to hand on.
 *
 * <p>Precedence, which is the whole safety argument. {@link #applyTo} runs after the per-chunk read-merge and carries a
 * key only where the value is still absent or empty, reusing {@link NbtMerge}'s own carry semantics. So a value the
 * chunk being written already holds, whether from this session's capture or from that chunk's own prior record, always
 * wins over a value recovered from elsewhere, and a stale copy can never overwrite a fresher one. The one arbitrary
 * choice left is which prior record supplies a UUID that appears in two scanned chunks, resolved first-scan-wins, which
 * is unimportant precisely because it can only ever fill a hole.
 *
 * <p>Coverage limit worth knowing before trusting it: the resume scan reads only chunks the resumed download brings
 * into its capture buffer, so a prior record in a chunk the player never re-approaches is never learned. An entity that
 * wandered a few chunks is covered; one that relocated across the world is not.
 */
final class RecoveredEntityContent {
    private final Map<UUID, CompoundTag> holders = new ConcurrentHashMap<>();

    /**
     * Writer thread: bank the interaction-captured content of every node in a prior on-disk entity chunk, at any depth
     * of the {@code "Passengers"} tree. Shares {@link EntityMerge#hasCapturedContent} with the outline's recovered set,
     * so exactly the records the outline calls recovered are the ones that can be carried forward.
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
        for (int i = 0; i < entities.size(); i++) {
            Tag rawEntity = entities.get(i);
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
     * Writer thread: carry banked content onto every node of {@code freshEntitiesChunkTag} whose value is still absent
     * or empty, and report how many nodes gained one. Call after the chunk's own read-merge, never before: running it
     * first would fill the keys that merge exists to carry and silence it.
     */
    int applyTo(CompoundTag freshEntitiesChunkTag) {
        Tag rawEntities = freshEntitiesChunkTag.get("Entities");
        if (holders.isEmpty() || !(rawEntities instanceof ListTag)) {
            return 0;
        }
        ListTag entities = (ListTag) rawEntities;
        int carried = 0;
        for (int i = 0; i < entities.size(); i++) {
            Tag rawEntity = entities.get(i);
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
     * Client thread: the banked content for {@code uuid}, or null. The bank keeps the tag it returns, so a caller folds
     * from it through {@link NbtMerge}, which copies, and never through a sink that would alias the list.
     */
    @Nullable
    CompoundTag holderFor(UUID uuid) {
        return holders.get(uuid);
    }

    /**
     * The keys a client capture cannot re-derive once the menu closes. Copied out of the scanned chunk so the banked
     * holder is not a live reference into a tag the scan is about to drop.
     */
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

    /** The same three carries {@link EntityMerge#merge} performs for a same-chunk prior, so the two agree by reuse. */
    private static boolean carryInto(CompoundTag holder, CompoundTag node) {
        boolean carried = NbtMerge.carryList(holder, node, "Items");
        carried |= NbtMerge.carryCompound(holder, node, "Offers", null);
        // Xp only rides along with offers: a villager the client never traded with serializes Xp at the client zero,
        // so carrying it alone would restore a level for a merchant whose trades did not come back.
        if (carried) {
            carried |= NbtMerge.carryValue(holder, node, "Xp", new IntTag(0));
        }
        return carried;
    }
}
