// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Drops the trade data a client-side merchant invents about itself, so the only trades that reach the archive are ones
 * a player actually saw.
 *
 * <p>A merchant's real offers exist on the server and reach the client only over the open menu channel. Ask a
 * client-side merchant for its offers anyway and it does not answer "none": it assigns itself a random career and
 * generates a full trade list from its profession, on the spot. Ordinary things ask, differing by version: rendering
 * its name, interacting with it, and below 1.21.1, where vanilla added the client-side check, its own entity write. On
 * disk the invention is indistinguishable from a real capture.
 *
 * <p>It also silently defeats both carry-forwards. {@link EntityMerge} and {@link RecoveredEntityContent} restore saved
 * trades only into a slot still empty, which is what stops a stale copy overwriting a fresher one; an invented list
 * fills that slot, so a genuine capture already on disk is never carried over it and is lost on the next write of that
 * chunk. Stripping therefore runs before every fold.
 *
 * <p>The career keys go with the offers because the same invention writes them: a client merchant's career is a number
 * it made up to pick a trade table with, not anything the server told it. The profession is left alone, being genuinely
 * synced, as is the trade experience, which a client reports at its own zero and which the carry-forward already treats
 * as absent.
 */
final class MerchantOfferScrub {
    private static final String[] CLIENT_INVENTED_KEYS = { "Offers", "Career", "CareerLevel" };

    private MerchantOfferScrub() {}

    /**
     * Strip invented trade data from every node of {@code entitiesChunkTag}, at any depth of the {@code "Passengers"}
     * tree, and report how many nodes carried some. Call before the merchant folds: they set the captured trades
     * afterwards, so a scrub cannot cost a real capture.
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

    /**
     * The same strip over one entity record and its passengers, for the surfaces that write a record rather than a
     * chunk: the player's own ridden vehicle, which is held out of the entity write and so is the only copy of anything
     * riding with the player.
     */
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
