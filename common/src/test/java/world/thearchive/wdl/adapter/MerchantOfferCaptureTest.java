// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.testsupport.BadStacks;
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
    private static RegistryAccess registries;

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    private static ItemStack lodestoneCompass() {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponents.LODESTONE_TRACKER,
                new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD, new BlockPos(128, 64, -512))), true));
        return compass;
    }

    private static ItemStack filledMap(int mapId) {
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.MAP_ID, new MapId(mapId));
        return map;
    }

    private static MerchantOffers offering(ItemStack sell) {
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), sell, 1, 0, 0.0f));
        return offers;
    }

    private static CompoundTag holderSelling(ItemStack sell) {
        return MerchantOfferCapture.serialize(offering(sell), 0, false, registries);
    }

    private static CompoundTag sellComponents(CompoundTag holder) {
        return holder.getCompound("Offers").getList("Recipes", 10).getCompound(0)
                .getCompound("sell").getCompound("components");
    }

    private static boolean sellHasLodestoneTarget(CompoundTag holder) {
        return sellComponents(holder).getCompound("minecraft:lodestone_tracker").contains("target");
    }

    @Test
    void serializeRoundTripsOffersUnderRecipes() {
        CompoundTag holder = MerchantOfferCapture.serialize(offering(new ItemStack(Items.DIAMOND)), 42, true,
                registries);

        assertEquals(1, holder.getCompound("Offers").getList("Recipes", 10).size(),
                "one offer under Recipes");
        assertEquals(42, holder.getInt("Xp"), "the villager's experience rides alongside");
    }

    @Test
    void serializeWanderingTraderWritesNoXp() {
        CompoundTag holder = MerchantOfferCapture.serialize(new MerchantOffers(), 0, false, registries);

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

        assertNotEquals(7, sellComponents(holder).getInt("minecraft:map_id"),
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

    @Test
    void anOfferWhoseSellBookHasLevelZeroEnchantIsRepairedNotSkipped() {
        Holder<Enchantment> mending = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING);
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        book.set(DataComponents.STORED_ENCHANTMENTS, BadStacks.enchantments(registries, Map.of(mending, 0)));
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 5), book, 12, 0, 0.05f));
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), book).error().isPresent(),
                "precondition: the sell book is genuinely unsavable");

        CompoundTag holder = MerchantOfferCapture.serialize(offers, 0, true, registries);

        assertTrue(holder.contains("Offers"), "the trade is kept, not dropped");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), book).error().isPresent(),
                "the live offer's book is unchanged (only a snapshot was repaired)");
    }

    @Test
    void aCleanOfferStillSerializes() {
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 3), new ItemStack(Items.DIAMOND), 7, 0, 0.05f));

        CompoundTag holder = MerchantOfferCapture.serialize(offers, 5, true, registries);

        assertTrue(holder.contains("Offers"), "a clean offer still serializes");
        assertEquals(5, holder.getInt("Xp"), "villager trade experience is written");
    }
}
