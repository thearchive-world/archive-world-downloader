// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.testsupport.TestRegistries;

class MerchantOfferCaptureTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static ItemStack lodestoneCompass() {
        ItemStack compass = new ItemStack(Items.COMPASS);
        GlobalPos pos = GlobalPos.of(DimensionType.OVERWORLD, new BlockPos(128, 64, -512));
        CompoundTag tag = compass.getOrCreateTag();
        tag.put("LodestonePos", NbtUtils.writeBlockPos(pos.pos()));
        tag.putString("LodestoneDimension", DimensionType.getName(pos.dimension()).toString());
        tag.putBoolean("LodestoneTracked", true);
        return compass;
    }

    private static ItemStack filledMap(int mapId) {
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.getOrCreateTag().putInt("map", mapId);
        return map;
    }

    private static MerchantOffers offering(ItemStack sell) {
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemStack(Items.EMERALD, 1), sell, 1, 0, 0.0f));
        return offers;
    }

    private static CompoundTag holderSelling(ItemStack sell) {
        return MerchantOfferCapture.serialize(offering(sell), 0, false);
    }

    private static CompoundTag sellItemTag(CompoundTag holder) {
        return holder.getCompound("Offers").getList("Recipes", 10).getCompound(0)
                .getCompound("sell").getCompound("tag");
    }

    private static boolean sellHasLodestoneTarget(CompoundTag holder) {
        return sellItemTag(holder).contains("LodestonePos");
    }

    @Test
    void serializeRoundTripsOffersUnderRecipes() {
        CompoundTag holder = MerchantOfferCapture.serialize(offering(new ItemStack(Items.DIAMOND)), 42, true);

        assertEquals(1, holder.getCompound("Offers").getList("Recipes", 10).size(), "one offer under Recipes");
        assertEquals(42, holder.getInt("Xp"), "the villager's experience rides alongside");
    }

    @Test
    void serializeWanderingTraderWritesNoXp() {
        CompoundTag holder = MerchantOfferCapture.serialize(new MerchantOffers(), 0, false);

        assertFalse(holder.contains("Xp"), "a wandering trader gets no experience key");
    }

    @Test
    void scrubBlanksLodestoneTargetOnSellItem() {
        CompoundTag holder = holderSelling(lodestoneCompass());
        assertTrue(sellHasLodestoneTarget(holder), "precondition: the sell compass carries LodestonePos");

        MerchantOfferCapture.scrubAndRemapOffers(holder, true, null);

        assertFalse(sellHasLodestoneTarget(holder), "the sell item's LodestonePos is blanked");
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

        assertNotEquals(7, sellItemTag(holder).getInt("map"), "the session map id was rewritten to an archive id");
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

        MerchantOfferCapture.scrubAndRemapOffers(holder, true, null);
    }
}
