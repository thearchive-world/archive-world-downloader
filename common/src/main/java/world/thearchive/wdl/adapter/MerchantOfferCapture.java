// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jspecify.annotations.Nullable;

/**
 * Serializes a merchant's open-menu trades to the on-disk holder, and sanitizes each offer's sell item. A pure
 * transform over already-client-held data: the client receives the offers over the menu channel and exposes them as a
 * {@code MerchantOffers}, and this drives the vanilla {@code MerchantOffers.createTag} directly, so the tag it writes
 * is the one vanilla's own {@code "Offers"} write produces and the archive loads without the mod.
 *
 * <p>{@link #serialize} builds the {@code {Offers:{Recipes:[...]}, Xp}} holder: the offers through
 * {@code MerchantOffers.createTag} (the pre-component NBT write), and the trade experience only for a villager (a
 * wandering trader has none). {@link #scrubAndRemapOffers} walks each offer's {@code sell} item, the only full item
 * stack an offer carries, through the item-location scrub and the map-id remap every captured item takes; the
 * {@code buy}/{@code buyB} costs are match predicates, not instance data, so they carry no coordinate or map id and are
 * left alone.
 *
 * <p>Below the component era {@code MerchantOffers.createTag} is a lenient NBT write that never rejects an offer, so
 * the per-tick caller's per-villager isolation of a rejecting serialize is inert on this band. It is kept because the
 * caller is band-stable and the newer bands do reject through their codec.
 */
final class MerchantOfferCapture {
    private MerchantOfferCapture() {}

    /**
     * Serialize {@code offers} to the {@code "Offers"} holder, adding {@code "Xp"} only when {@code isVillager}. The
     * pre-component NBT write does not reject an offer on this band, so it does not throw (see the class note).
     */
    static CompoundTag serialize(MerchantOffers offers, int xp, boolean isVillager, HolderLookup.Provider registries) {
        CompoundTag holder = new CompoundTag();
        Tag offersTag = offers.createTag();
        holder.put("Offers", offersTag);
        if (isVillager) {
            holder.putInt("Xp", xp);
        }
        return holder;
    }

    /**
     * Run each offer's {@code sell} item through the item-location scrub (when {@code scrubCoordinates}) and the map-id
     * remap (when {@code archive} is non-null), in place. A holder with no {@code "Offers"}/{@code "Recipes"}, or a
     * recipe with no {@code "sell"}, is skipped. Drain-time work: call once per holder before it merges.
     */
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
