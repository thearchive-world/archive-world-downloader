// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.MerchantRecipe;
import net.minecraft.village.MerchantRecipeList;
import net.minecraft.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless guard for the merchant-offer capture transform: {@link MerchantOfferCapture#serialize} writes a
 * villager's offers to the vanilla-shaped {@code {Offers:{Recipes:[...]}}} holder through the trade list's own NBT
 * write, and {@link MerchantOfferCapture#scrubAndRemapOffers} runs each offer's {@code sell} item through the same
 * item-location scrub and map-id remap every captured item takes. Real {@link ItemStack}s carrying the tags drive the
 * round-trip, so a wrong key leaves the coordinate or the session map id and fails; no live menu or {@code World} is
 * needed.
 */
class MerchantOfferCaptureTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /**
     * A compass carrying the raw {@code LodestonePos}/{@code LodestoneDimension} keys {@link ItemLocationScrub} scrubs.
     * The scrub keeps those keys in its target set across bands even where the lodestone item itself does not exist
     * yet, so the transform is exercised by writing the keys onto a plain item's {@code tag} directly.
     */
    private static ItemStack lodestoneCompass() {
        ItemStack compass = new ItemStack(Items.COMPASS);
        BlockPos pos = new BlockPos(128, 64, -512);
        compass.setTagCompound(new NBTTagCompound());
        NBTTagCompound tag = compass.getTagCompound();
        tag.setTag("LodestonePos", NBTUtil.createPosTag(pos));
        tag.setString("LodestoneDimension", DimensionType.OVERWORLD.getName());
        tag.setBoolean("LodestoneTracked", true);
        return compass;
    }

    /** A filled-map stack carrying {@code mapId} as its item-level {@code Damage} (this band's map id). */
    private static ItemStack filledMap(int mapId) {
        return new ItemStack(Items.FILLED_MAP, 1, mapId);
    }

    private static MerchantRecipeList offering(ItemStack sell) {
        // This band's merchant offer takes a plain buy/sell ItemStack pair, no trade experience or price multiplier.
        MerchantRecipeList offers = new MerchantRecipeList();
        offers.add(new MerchantRecipe(new ItemStack(Items.EMERALD, 1), sell));
        return offers;
    }

    private static NBTTagCompound holderSelling(ItemStack sell) {
        return MerchantOfferCapture.serialize(offering(sell));
    }

    /** The {@code sell} item's own serialized compound ({@code {id, Count, Damage, tag}}), no nesting below it. */
    private static NBTTagCompound sellItem(NBTTagCompound holder) {
        return holder.getCompoundTag("Offers").getTagList("Recipes", 10).getCompoundTagAt(0)
                .getCompoundTag("sell");
    }

    private static boolean sellHasLodestoneTarget(NBTTagCompound holder) {
        NBTTagCompound tag = sellItem(holder).getCompoundTag("tag");
        return tag.hasKey("LodestonePos");
    }

    @Test
    void serializeRoundTripsOffersUnderRecipes() {
        NBTTagCompound holder = MerchantOfferCapture.serialize(offering(new ItemStack(Items.DIAMOND)));

        assertEquals(1, holder.getCompoundTag("Offers").getTagList("Recipes", 10).tagCount(),
                "one offer under Recipes");
        assertFalse(holder.hasKey("Xp"), "the capture writes no top-level trade-experience key");
    }

    @Test
    void serializeWritesNoTradeExperienceKey() {
        NBTTagCompound holder = MerchantOfferCapture.serialize(new MerchantRecipeList());

        assertFalse(holder.hasKey("Xp"), "the capture writes no top-level trade-experience key");
    }

    @Test
    void scrubBlanksLodestoneTargetOnSellItem() {
        NBTTagCompound holder = holderSelling(lodestoneCompass());
        assertTrue(sellHasLodestoneTarget(holder), "precondition: the sell compass carries a target");

        MerchantOfferCapture.scrubAndRemapOffers(holder, true, null);

        assertFalse(sellHasLodestoneTarget(holder), "the sell item's lodestone target is blanked");
    }

    @Test
    void scrubLeavesTargetWhenCoordinatesKept() {
        NBTTagCompound holder = holderSelling(lodestoneCompass());

        MerchantOfferCapture.scrubAndRemapOffers(holder, false, null);

        assertTrue(sellHasLodestoneTarget(holder), "the opt-in keeps the sell item's target");
    }

    @Test
    void remapsSellFilledMapIdThroughArchive() {
        NBTTagCompound holder = holderSelling(filledMap(7));
        MapArchive archive = new MapArchive(MapManifest.empty(), sessionId -> null, (archiveId, dataTag) -> {});

        MerchantOfferCapture.scrubAndRemapOffers(holder, false, archive);

        assertNotEquals((short) 7, sellItem(holder).getShort("Damage"),
                "the session map id was rewritten to an archive id");
    }

    @Test
    void remappingTheCapturedOffersLeavesTheLiveSellStackAlone() {
        ItemStack live = filledMap(7);
        NBTTagCompound holder = holderSelling(live);
        MapArchive archive = new MapArchive(MapManifest.empty(), sessionId -> null, (archiveId, dataTag) -> {});

        MerchantOfferCapture.scrubAndRemapOffers(holder, false, archive);

        assertNotEquals((short) 7, sellItem(holder).getShort("Damage"), "the captured holder carries the archive id");
        assertEquals(7, live.getMetadata(),
                "the live offer keeps the server's map id, so the trade still sells the map it advertises");
    }

    @Test
    void reStashingTheSameLiveOffersResolvesToOneArchiveId() {
        // The offers are re-stashed every tick the trade screen is open, each tick building a fresh holder.
        ItemStack live = filledMap(7);
        MapArchive archive = new MapArchive(MapManifest.empty(), sessionId -> null, (archiveId, dataTag) -> {});

        NBTTagCompound first = holderSelling(live);
        MerchantOfferCapture.scrubAndRemapOffers(first, false, archive);
        // Read before the second pass: a holder that still aliases the live stack would report the later id here.
        short firstId = sellItem(first).getShort("Damage");
        NBTTagCompound second = holderSelling(live);
        MerchantOfferCapture.scrubAndRemapOffers(second, false, archive);

        assertEquals(firstId, sellItem(second).getShort("Damage"),
                "a holder re-stashed on a later tick resolves to the same archive id rather than a fresh one");
    }

    @Test
    void scrubIgnoresHolderWithNoOffers() {
        NBTTagCompound holder = new NBTTagCompound();

        MerchantOfferCapture.scrubAndRemapOffers(holder, true, null);

        assertTrue(holder.isEmpty(), "a holder with no Offers is left untouched, no throw");
    }

    @Test
    void scrubSkipsRecipeWithNoSell() {
        NBTTagCompound holder = holderSelling(new ItemStack(Items.DIAMOND));
        holder.getCompoundTag("Offers").getTagList("Recipes", 10).getCompoundTagAt(0).removeTag("sell");

        MerchantOfferCapture.scrubAndRemapOffers(holder, true, null); // must not throw; reaches the per-recipe skip
    }
}
