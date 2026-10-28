// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.village.MerchantRecipeList;
import org.jspecify.annotations.Nullable;

final class MerchantOfferCapture {
    private MerchantOfferCapture() {}

    /**
     * Below 1.15 vanilla {@code ItemStack.writeToNBT} puts each offer item's live {@code tag} compound into its output,
     * so the offers are detached before they are handed on. Without that, the drain-time scrub and the remap of a map
     * nested inside a sold item write into the merchant's own stacks, and a re-stash on the next tick resolves the
     * rewritten id as a fresh one.
     */
    static NBTTagCompound serialize(MerchantRecipeList offers) {
        NBTTagCompound holder = new NBTTagCompound();
        holder.setTag("Offers", offers.getRecipiesAsTags().copy());
        return holder;
    }

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
