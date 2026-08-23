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

/**
 * The headless guard for the merchant-offer capture transform: {@link MerchantOfferCapture#serialize} writes a villager
 * or wandering trader's offers to the vanilla-shaped {@code {Offers:{Recipes:[...]}, Xp}} holder through the vanilla
 * {@code MerchantOffers} codec, and {@link MerchantOfferCapture#scrubAndRemapOffers} runs each offer's {@code sell}
 * item through the same item-location scrub and map-id remap every captured item takes. Real {@link ItemStack}s
 * carrying the components drive the round-trip, so a wrong key leaves the coordinate or the session map id and fails;
 * no live menu or {@code Level} is needed.
 */
class MerchantOfferCaptureTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /**
     * A lodestone compass whose target is the raw {@code LodestonePos}/{@code LodestoneDimension} keys
     * {@link ItemLocationScrub} scrubs (below 1.20.5 there is no {@code LodestoneTracker} component; vanilla's own
     * {@code CompassItem.addLodestoneTags} writes exactly these three keys on the item's {@code tag}).
     */
    private static ItemStack lodestoneCompass() {
        ItemStack compass = new ItemStack(Items.COMPASS);
        GlobalPos pos = GlobalPos.of(DimensionType.OVERWORLD, new BlockPos(128, 64, -512));
        CompoundTag tag = compass.getOrCreateTag();
        tag.put("LodestonePos", NbtUtils.writeBlockPos(pos.pos()));
        tag.putString("LodestoneDimension", DimensionType.getName(pos.dimension()).toString());
        tag.putBoolean("LodestoneTracked", true);
        return compass;
    }

    /** A filled-map stack carrying {@code mapId} in its raw {@code "map"} tag (below 1.20.5, no {@code map_id}). */
    private static ItemStack filledMap(int mapId) {
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.getOrCreateTag().putInt("map", mapId);
        return map;
    }

    private static MerchantOffers offering(ItemStack sell) {
        // Below 1.20.5 there is no ItemCost; a merchant offer's buy cost is a plain ItemStack.
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemStack(Items.EMERALD, 1), sell, 1, 0, 0.0f));
        return offers;
    }

    private static CompoundTag holderSelling(ItemStack sell) {
        return MerchantOfferCapture.serialize(offering(sell), 0, false);
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
        CompoundTag holder = MerchantOfferCapture.serialize(offering(new ItemStack(Items.DIAMOND)), 42, true);

        assertEquals(1, holder.getCompound("Offers").getList("Recipes", 10).size(),
                "one offer under Recipes");
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
    void remappingTheCapturedOffersLeavesTheLiveSellStackAlone() {
        ItemStack live = filledMap(7);
        CompoundTag holder = holderSelling(live);
        MapArchive archive = new MapArchive(MapManifest.empty(), sessionId -> null, (archiveId, dataTag) -> {});

        MerchantOfferCapture.scrubAndRemapOffers(holder, false, archive);

        assertNotEquals(7, sellItemTag(holder).getInt("map"), "the captured holder carries the archive id");
        assertEquals(7, live.getOrCreateTag().getInt("map"),
                "the live offer keeps the server's map id, so the trade still sells the map it advertises");
    }

    @Test
    void reStashingTheSameLiveOffersResolvesToOneArchiveId() {
        // The offers are re-stashed every tick the trade screen is open, each tick building a fresh holder.
        ItemStack live = filledMap(7);
        MapArchive archive = new MapArchive(MapManifest.empty(), sessionId -> null, (archiveId, dataTag) -> {});

        CompoundTag first = holderSelling(live);
        MerchantOfferCapture.scrubAndRemapOffers(first, false, archive);
        // Read before the second pass: a holder that still aliases the live stack would report the later id here.
        int firstId = sellItemTag(first).getInt("map");
        CompoundTag second = holderSelling(live);
        MerchantOfferCapture.scrubAndRemapOffers(second, false, archive);

        assertEquals(firstId, sellItemTag(second).getInt("map"),
                "a holder re-stashed on a later tick resolves to the same archive id rather than a fresh one");
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
