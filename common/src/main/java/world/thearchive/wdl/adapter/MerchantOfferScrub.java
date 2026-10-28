// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * A merchant's real offers exist on the server and reach the client only over the open menu channel. Ask a client-side
 * merchant for its offers anyway and, unless it is a nitwit, it does not answer "none": it assigns itself a random
 * career and generates a trade list from its profession, on the spot. Interacting with it asks, and so does reading its
 * display name when it carries no custom name. Its entity write saves whatever list it holds, so on disk the invention
 * is indistinguishable from a real capture.
 *
 * <p>It also silently defeats both carry-forwards. {@link EntityMerge} and {@link RecoveredEntityContent} restore saved
 * trades only into a slot still empty, which is what stops a stale copy overwriting a fresher one; an invented list
 * fills that slot, so a genuine capture already on disk is never carried over it and is lost on the next write of that
 * chunk. Stripping therefore runs before every fold.
 *
 * <p>The career keys go with the offers because the same invention writes them: a client merchant's career is a number
 * it made up to pick a trade table with, not anything the server told it.
 */
final class MerchantOfferScrub {
    private static final String[] CLIENT_INVENTED_KEYS = { "Offers", "Career", "CareerLevel" };

    private MerchantOfferScrub() {}

    /**
     * Call before the merchant folds: they set the captured trades afterwards, so a scrub cannot cost a real capture.
     */
    static int stripInventedOffers(NBTTagCompound entitiesChunkTag) {
        NBTBase rawEntities = entitiesChunkTag.getTag("Entities");
        if (!(rawEntities instanceof NBTTagList)) {
            return 0;
        }
        NBTTagList entities = (NBTTagList) rawEntities;
        int stripped = 0;
        for (NBTBase rawEntity : entities) {
            if (rawEntity instanceof NBTTagCompound) {
                NBTTagCompound entity = (NBTTagCompound) rawEntity;
                stripped += stripInventedOffersFromRecord(entity);
            }
        }
        return stripped;
    }

    static int stripInventedOffersFromRecord(NBTTagCompound entityRecord) {
        int stripped = 0;
        for (Map.Entry<UUID, NBTTagCompound> node : EntityTreeWalk.byUuid(entityRecord).entrySet()) {
            if (strip(node.getValue())) {
                stripped++;
            }
        }
        return stripped;
    }

    /**
     * Returns whether an invented TRADE LIST was dropped, not whether any key was. The career keys go unconditionally
     * on the versions that have them, so counting those would report every villager the download saw and say nothing
     * about invention.
     */
    private static boolean strip(NBTTagCompound node) {
        boolean droppedOffers = node.hasKey("Offers");
        for (String key : CLIENT_INVENTED_KEYS) {
            node.removeTag(key);
        }
        return droppedOffers;
    }
}
