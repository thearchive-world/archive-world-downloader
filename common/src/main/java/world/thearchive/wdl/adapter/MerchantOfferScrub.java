// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * A merchant's real offers exist on the server and reach the client only over the open menu channel. Ask a client-side
 * merchant for its offers anyway and, unless it is an unemployed villager or a nitwit, it does not answer "none": it
 * generates a trade list, on the spot. Ordinary things ask: interacting with it, and its own entity write. On disk the
 * invention is indistinguishable from a real capture.
 *
 * <p>It also silently defeats both carry-forwards. {@link EntityMerge} and {@link RecoveredEntityContent} restore saved
 * trades only into a slot still empty, which is what stops a stale copy overwriting a fresher one; an invented list
 * fills that slot, so a genuine capture already on disk is never carried over it and is lost on the next write of that
 * chunk. Stripping therefore runs before every fold.
 */
final class MerchantOfferScrub {
    private static final String[] CLIENT_INVENTED_KEYS = { "Offers", "Career", "CareerLevel" };

    private MerchantOfferScrub() {}

    /**
     * Call before the merchant folds: they set the captured trades afterwards, so a scrub cannot cost a real capture.
     */
    static int stripInventedOffers(CompoundTag entitiesChunkTag) {
        if (!(entitiesChunkTag.get("Entities") instanceof ListTag entities)) {
            return 0;
        }
        int stripped = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (entities.get(i) instanceof CompoundTag entity) {
                stripped += stripInventedOffersFromRecord(entity);
            }
        }
        return stripped;
    }

    static int stripInventedOffersFromRecord(CompoundTag entityRecord) {
        int stripped = 0;
        for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(entityRecord).entrySet()) {
            if (strip(node.getValue())) {
                stripped++;
            }
        }
        return stripped;
    }

    private static boolean strip(CompoundTag node) {
        boolean droppedOffers = node.contains("Offers");
        for (String key : CLIENT_INVENTED_KEYS) {
            node.remove(key);
        }
        return droppedOffers;
    }
}
