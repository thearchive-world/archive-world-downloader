// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jspecify.annotations.Nullable;

final class MerchantOfferCapture {
    private MerchantOfferCapture() {}

    static CompoundTag serialize(MerchantOffers offers, int xp, boolean isVillager, HolderLookup.Provider registries) {
        CompoundTag holder = new CompoundTag();
        Tag offersTag = offers.createTag();
        holder.put("Offers", offersTag);
        if (isVillager) {
            holder.putInt("Xp", xp);
        }
        return holder;
    }

    static void scrubAndRemapOffers(CompoundTag holder, boolean scrubCoordinates, @Nullable MapArchive archive) {
        if (!(holder.get("Offers") instanceof CompoundTag offers)
                || !(offers.get("Recipes") instanceof ListTag recipes)) {
            return;
        }
        for (int i = 0; i < recipes.size(); i++) {
            if (!(recipes.get(i) instanceof CompoundTag recipe)
                    || !(recipe.get("sell") instanceof CompoundTag sell)) {
                continue;
            }
            if (scrubCoordinates) {
                ItemLocationScrub.scrubItem(sell);
            }
            if (archive != null) {
                archive.remapItem(sell);
            }
        }
    }
}
