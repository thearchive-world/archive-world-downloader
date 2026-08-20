// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.village.class_1144;
import net.minecraft.village.class_1145;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless guard for the merchant-offer capture transform: {@link MerchantOfferCapture#serialize} writes a
 * villager's offers to the vanilla-shaped {@code {Offers:{Recipes:[...]}}} holder through the trade list's own NBT
 * write, and {@link MerchantOfferCapture#scrubAndRemapOffers} runs each offer's {@code sell} item through the same
 * item-location scrub and map-id remap every captured item takes. This band writes no {@code "Xp"} trade-experience
 * key: trade experience is a 1.14 addition, absent here. Real {@link ItemStack}s carrying the tags drive the
 * round-trip, so a wrong key leaves the coordinate or the session map id and fails; no live menu or {@code Level} is
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
        CompoundTag tag = compass.getOrCreateTag();
        tag.put("LodestonePos", NbtUtils.writeBlockPos(pos));
        tag.putString("LodestoneDimension", DimensionType.getName(DimensionType.field_18954).toString());
        tag.putBoolean("LodestoneTracked", true);
        return compass;
    }

    /** A filled-map stack carrying {@code mapId} in its raw {@code "map"} tag (below 1.20.5, no {@code map_id}). */
    private static ItemStack filledMap(int mapId) {
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.getOrCreateTag().putInt("map", mapId);
        return map;
    }

    private static class_1145 offering(ItemStack sell) {
        // This band's merchant offer takes a plain buy/sell ItemStack pair, no trade experience or price multiplier.
        class_1145 offers = new class_1145();
        offers.add(new class_1144(new ItemStack(Items.EMERALD, 1), sell));
        return offers;
    }

    private static CompoundTag holderSelling(ItemStack sell) {
        return MerchantOfferCapture.serialize(offering(sell));
    }

    /** The {@code sell} item's pre-component {@code tag} compound (below 1.20.5, no {@code components} map). */
    private static CompoundTag sellItemTag(CompoundTag holder) {
        return holder.getCompound("Offers").getList("Recipes", 10).getCompound(0)
                .getCompound("sell").getCompound("tag");
    }

    private static boolean sellHasLodestoneTarget(CompoundTag holder) {
        return sellItemTag(holder).contains("LodestonePos");
    }

    @Test
    void serializeRoundTripsOffersUnderRecipes() {
        CompoundTag holder = MerchantOfferCapture.serialize(offering(new ItemStack(Items.DIAMOND)));

        assertEquals(1, holder.getCompound("Offers").getList("Recipes", 10).size(),
                "one offer under Recipes");
        assertFalse(holder.contains("Xp"),
                "no trade-experience key: trade experience is a 1.14 addition, absent at this band");
    }

    @Test
    void serializeWritesNoTradeExperienceKey() {
        CompoundTag holder = MerchantOfferCapture.serialize(new class_1145());

        assertFalse(holder.contains("Xp"), "the capture writes no trade-experience key at this band");
    }

    @Test
    void scrubBlanksLodestoneTargetOnSellItem() {
        CompoundTag holder = holderSelling(lodestoneCompass());
        assertTrue(sellHasLodestoneTarget(holder), "precondition: the sell compass carries a target");

        MerchantOfferCapture.scrubAndRemapOffers(holder, true, null);

        assertFalse(sellHasLodestoneTarget(holder), "the sell item's lodestone target is blanked");
    }

    @Test
    void scrubLeavesTargetWhenCoordinatesKept() {
        CompoundTag holder = holderSelling(lodestoneCompass());

        MerchantOfferCapture.scrubAndRemapOffers(holder, false, null);

        assertTrue(sellHasLodestoneTarget(holder), "the opt-in keeps the sell item's target");
    }

    @Test
    void remapsSellFilledMapIdThroughArchive() {
        CompoundTag holder = holderSelling(filledMap(7));
        MapArchive archive = new MapArchive(MapManifest.empty(), sessionId -> null, (archiveId, dataTag) -> {});

        MerchantOfferCapture.scrubAndRemapOffers(holder, false, archive);

        assertNotEquals(7, sellItemTag(holder).getInt("map"),
                "the session map id was rewritten to an archive id");
    }

    @Test
    void scrubIgnoresHolderWithNoOffers() {
        CompoundTag holder = new CompoundTag();

        MerchantOfferCapture.scrubAndRemapOffers(holder, true, null);

        assertTrue(holder.isEmpty(), "a holder with no Offers is left untouched, no throw");
    }

    @Test
    void scrubSkipsRecipeWithNoSell() {
        CompoundTag holder = holderSelling(new ItemStack(Items.DIAMOND));
        holder.getCompound("Offers").getList("Recipes", 10).getCompound(0).remove("sell");

        MerchantOfferCapture.scrubAndRemapOffers(holder, true, null); // must not throw; reaches the per-recipe skip
    }
}
