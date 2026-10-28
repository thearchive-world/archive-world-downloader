// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jspecify.annotations.Nullable;

final class MerchantOfferCapture {
    private MerchantOfferCapture() {}

    static CompoundTag serialize(MerchantOffers offers, int xp, boolean isVillager) {
        CompoundTag holder = new CompoundTag();
        Tag offersTag = offers.createTag();
        holder.put("Offers", offersTag);
        if (isVillager) {
            holder.putInt("Xp", xp);
        }
        return holder;
    }

    static void scrubAndRemapOffers(CompoundTag holder, boolean scrubCoordinates, @Nullable MapArchive archive) {
        CompoundTag offers = holder.get("Offers") instanceof CompoundTag ? (CompoundTag) holder.get("Offers") : null;
        ListTag recipes = offers != null && offers.get("Recipes") instanceof ListTag
                ? (ListTag) offers.get("Recipes")
                : null;
        if (offers == null || recipes == null) {
            return;
        }
        for (int i = 0; i < recipes.size(); i++) {
            CompoundTag recipe = recipes.get(i) instanceof CompoundTag ? (CompoundTag) recipes.get(i) : null;
            CompoundTag sell = recipe != null && recipe.get("sell") instanceof CompoundTag
                    ? (CompoundTag) recipe.get("sell")
                    : null;
            if (recipe == null || sell == null) {
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
