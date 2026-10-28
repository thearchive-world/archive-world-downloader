// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.village.class_1145;
import org.jspecify.annotations.Nullable;

final class MerchantOfferCapture {
    private MerchantOfferCapture() {}

    /**
     * Below 1.15 vanilla {@code ItemStack.save} puts each offer item's live {@code tag} compound into its output, so
     * the offers are detached before they are handed on. Without that, the drain-time scrub and map-id remap write into
     * the merchant's own stacks, and a re-stash on the next tick resolves the rewritten id as a fresh one.
     */
    static CompoundTag serialize(class_1145 offers) {
        CompoundTag holder = new CompoundTag();
        holder.put("Offers", offers.method_3557().copy());
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
        for (Tag element : recipes) {
            CompoundTag recipe = element instanceof CompoundTag ? (CompoundTag) element : null;
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
