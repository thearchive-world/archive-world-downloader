// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.village.MerchantRecipeList;
import org.jspecify.annotations.Nullable;

/**
 * Serializes a merchant's open-menu trades to the on-disk holder, and sanitizes each offer's sell item. A pure
 * transform over already-client-held data: the client receives the offers over the menu channel and exposes them as a
 * {@link MerchantRecipeList} (the trade list), and this drives its own {@code getRecipiesAsTags} NBT write directly, so
 * the tag it writes is the one vanilla's own {@code "Offers"} write produces and the archive loads without the mod.
 *
 * <p>{@link #serialize} builds the {@code {Offers:{Recipes:[...]}}} holder from the trade list's own NBT write. The
 * {@code "Xp"} trade-experience tag a newer band writes is omitted here: trade experience is a 1.14 addition absent at
 * 1.13.2, and this band's villager save carries no such tag. {@link #scrubAndRemapOffers} walks each offer's
 * {@code sell} item, the only full item stack an offer carries, through the item-location scrub and the map-id remap
 * every captured item takes; the {@code buy}/{@code buyB} costs are match predicates, not instance data, so they carry
 * no coordinate or map id and are left alone.
 *
 * <p>The trade list's NBT write is lenient and never rejects an offer on this band, so the per-tick caller's
 * per-villager isolation of a rejecting serialize is inert here. It is kept because the caller is band-stable and the
 * newer bands do reject through their codec.
 */
final class MerchantOfferCapture {
    private MerchantOfferCapture() {}

    /**
     * Serialize {@code offers} to the {@code "Offers"} holder. The trade list's NBT write does not reject an offer on
     * this band, so it does not throw (see the class note).
     *
     * <p>Below 1.15 vanilla {@code ItemStack.save} puts each offer item's live {@code tag} compound into its output, so
     * the offers are detached before they are handed on. Without that, the drain-time scrub and map-id remap write into
     * the merchant's own stacks, and a re-stash on the next tick resolves the rewritten id as a fresh one, burning an
     * archive id per tick and saving a trade that points at an unimaged map.
     */
    static NBTTagCompound serialize(MerchantRecipeList offers) {
        NBTTagCompound holder = new NBTTagCompound();
        holder.setTag("Offers", offers.getRecipiesAsTags().copy());
        return holder;
    }

    /**
     * Run each offer's {@code sell} item through the item-location scrub (when {@code scrubCoordinates}) and the map-id
     * remap (when {@code archive} is non-null), in place. A holder with no {@code "Offers"}/{@code "Recipes"}, or a
     * recipe with no {@code "sell"}, is skipped. Drain-time work: call once per holder before it merges.
     */
    static void scrubAndRemapOffers(NBTTagCompound holder, boolean scrubCoordinates, @Nullable MapArchive archive) {
        NBTTagCompound offers = holder.getTag("Offers") instanceof NBTTagCompound
                ? (NBTTagCompound) holder.getTag("Offers")
                : null;
        NBTTagList recipes = offers != null && offers.getTag("Recipes") instanceof NBTTagList
                ? (NBTTagList) offers.getTag("Recipes")
                : null;
        if (offers == null || recipes == null) {
            return;
        }
        for (NBTBase element : recipes) {
            NBTTagCompound recipe = element instanceof NBTTagCompound ? (NBTTagCompound) element : null;
            NBTTagCompound sell = recipe != null && recipe.getTag("sell") instanceof NBTTagCompound
                    ? (NBTTagCompound) recipe.getTag("sell")
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
