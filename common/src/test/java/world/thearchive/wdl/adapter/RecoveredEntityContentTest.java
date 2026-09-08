// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.NonNullList;
import net.minecraft.village.MerchantRecipe;
import net.minecraft.village.MerchantRecipeList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The carry rules of {@link RecoveredEntityContent} in isolation, against the counts and the per-key precedence its
 * on-disk siblings in {@link EntityResumeContentCarryTest} cannot see: those assert the bytes a resume leaves in the
 * save, which is the same either way for a node that gained a key it already had.
 *
 * <p>The precedence cases are the load-bearing ones. The bank is filled from a prior download and applied to a chunk
 * whose own read-merge has already run, so anything it finds already present is at least as fresh as what it holds, and
 * overwriting would be the repair destroying newer trades than it restores.
 */
class RecoveredEntityContentTest {
    private static final UUID VILLAGER = UUID.fromString("2f6c0f4a-1e88-4d3b-9a52-7c0b6e4d1a93");
    private static final UUID BOAT = UUID.fromString("8d1a44b7-5c02-4e69-b0f1-3a9e77c25d40");

    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    @Test
    void aBankedMerchantsTradesAndExperienceReachAnEmptyCopy() {
        RecoveredEntityContent content = new RecoveredEntityContent();
        content.record(scannedChunkOf(villagerWith(offersSelling("minecraft:emerald"), 12)));
        NBTTagCompound fresh = chunkOf(EntityFixtures.entity("minecraft:villager", VILLAGER));

        assertEquals(1, content.applyTo(fresh), "the one node that gained its trades is the one counted");

        assertEquals("minecraft:emerald", firstSellId(node(fresh, VILLAGER)));
        assertEquals(12, intOr(node(fresh, VILLAGER), "Xp", -1), "and the level that went with them");
    }

    @Test
    void aCopyThatAlreadyCarriesTradesKeepsItsOwn() {
        RecoveredEntityContent content = new RecoveredEntityContent();
        content.record(scannedChunkOf(villagerWith(offersSelling("minecraft:emerald"), 12)));
        NBTTagCompound fresh = chunkOf(villagerWith(offersSelling("minecraft:diamond"), 3));

        assertEquals(0, content.applyTo(fresh), "nothing was missing, so nothing is carried and nothing counted");

        assertEquals("minecraft:diamond", firstSellId(node(fresh, VILLAGER)),
                "the copy in hand is at least as fresh as the bank, so a banked one must never displace it");
        assertEquals(3, intOr(node(fresh, VILLAGER), "Xp", -1), "including its level");
    }

    @Test
    void experienceIsNotCarriedOnItsOwn() {
        RecoveredEntityContent content = new RecoveredEntityContent();
        content.record(scannedChunkOf(villagerWith(offersSelling("minecraft:emerald"), 12)));
        // Trades already present and no level: restoring a prior level here would claim a rank for a merchant
        // whose current trades this download did capture.
        NBTTagCompound fresh = chunkOf(villagerWith(offersSelling("minecraft:diamond"), null));

        assertEquals(0, content.applyTo(fresh));

        assertFalse(node(fresh, VILLAGER).hasKey("Xp"), "no level rides in behind trades that did not carry");
    }

    @Test
    void aBankedVehiclesContentsReachAnEmptyCopy() {
        RecoveredEntityContent content = new RecoveredEntityContent();
        content.record(scannedChunkOf(boatWith(itemsOf("minecraft:diamond"))));
        NBTTagCompound fresh = chunkOf(EntityFixtures.entity("minecraft:chest_boat", BOAT));

        assertEquals(1, content.applyTo(fresh));

        assertTrue(EntityMerge.hasCapturedContent(node(fresh, BOAT)));
    }

    @Test
    void everyNodeThatGainsContentIsCounted() {
        RecoveredEntityContent content = new RecoveredEntityContent();
        content.record(scannedChunkOf(villagerWith(offersSelling("minecraft:emerald"), 12),
                boatWith(itemsOf("minecraft:diamond"))));
        NBTTagCompound fresh = chunkOf(EntityFixtures.entity("minecraft:villager", VILLAGER),
                EntityFixtures.entity("minecraft:chest_boat", BOAT));

        assertEquals(2, content.applyTo(fresh), "the count is per node carried, not per chunk touched");
    }

    @Test
    void aRecordWithNothingCapturedIsNotBanked() {
        RecoveredEntityContent content = new RecoveredEntityContent();
        content.record(scannedChunkOf(EntityFixtures.entity("minecraft:villager", VILLAGER)));

        assertNull(content.holderFor(VILLAGER), "a villager nobody traded with has nothing to carry forward");
        assertEquals(0, content.applyTo(chunkOf(EntityFixtures.entity("minecraft:villager", VILLAGER))));
    }

    @Test
    void anEmptyBankCarriesNothing() {
        assertEquals(0, new RecoveredEntityContent()
                .applyTo(chunkOf(EntityFixtures.entity("minecraft:villager", VILLAGER))));
    }

    @Test
    void aChunkTagWithNoEntityListIsIgnoredBothWays() {
        RecoveredEntityContent content = new RecoveredEntityContent();
        content.record(new NBTTagCompound());

        assertNull(content.holderFor(VILLAGER));
        assertEquals(0, content.applyTo(new NBTTagCompound()));
    }

    @Test
    void theBankedHolderIsDetachedFromTheScannedChunk() {
        NBTTagCompound scanned = chunkOf(villagerWith(offersSelling("minecraft:emerald"), 12));
        RecoveredEntityContent content = new RecoveredEntityContent();
        content.record(wrapAsRegionChunk(scanned));

        // The scan hands the same tag on to the outline's own consumer, which is free to mutate it.
        node(scanned, VILLAGER).removeTag("Offers");

        NBTTagCompound fresh = chunkOf(EntityFixtures.entity("minecraft:villager", VILLAGER));
        assertEquals(1, content.applyTo(fresh), "the bank holds its own copy, not a view of the scanned chunk");
        assertEquals("minecraft:emerald", firstSellId(node(fresh, VILLAGER)));
    }

    @Test
    void theBankedContentsAreDetachedFromTheScannedChunkToo() {
        NBTTagCompound scanned = chunkOf(boatWith(itemsOf("minecraft:diamond")));
        RecoveredEntityContent content = new RecoveredEntityContent();
        content.record(wrapAsRegionChunk(scanned));

        NBTTagList scannedItems = node(scanned, BOAT).getTagList("Items", 10);
        while (scannedItems.tagCount() > 0) {
            scannedItems.removeTag(0);
        }

        NBTTagCompound fresh = chunkOf(EntityFixtures.entity("minecraft:chest_boat", BOAT));
        assertEquals(1, content.applyTo(fresh), "the items are copied on the way in, not held as a view");
        assertTrue(EntityMerge.hasCapturedContent(node(fresh, BOAT)));
    }

    @Test
    void anEntityBankedTwiceKeepsWhatTheFirstScanFound() {
        RecoveredEntityContent content = new RecoveredEntityContent();
        content.record(scannedChunkOf(villagerWith(offersSelling("minecraft:emerald"), 12)));
        content.record(scannedChunkOf(villagerWith(offersSelling("minecraft:diamond"), 3)));

        NBTTagCompound fresh = chunkOf(EntityFixtures.entity("minecraft:villager", VILLAGER));
        content.applyTo(fresh);

        assertEquals("minecraft:emerald", firstSellId(node(fresh, VILLAGER)),
                "first scan wins, stated as the design decision and otherwise undetectable; either copy beats "
                        + "empty, so what matters is that the choice is fixed rather than which one it is");
    }

    private static NBTTagCompound chunkOf(NBTTagCompound... entities) {
        return EntityFixtures.entityChunkTagWith(entities);
    }

    private static NBTTagCompound villagerWith(NBTTagCompound offers, Integer xp) {
        NBTTagCompound villager = EntityFixtures.entity("minecraft:villager", VILLAGER);
        villager.setTag("Offers", offers);
        if (xp != null) {
            villager.setInteger("Xp", xp);
        }
        return villager;
    }

    private NBTTagCompound boatWith(NBTTagList items) {
        NBTTagCompound boat = EntityFixtures.entity("minecraft:chest_boat", BOAT);
        boat.setTag("Items", items);
        return boat;
    }

    /** Built through the sink the capture itself uses, so the entries carry the Slot key vanilla always writes. */
    private NBTTagList itemsOf(String itemId) {
        NonNullList<ItemStack> stacks = NonNullList.withSize(27, ItemStack.EMPTY);
        stacks.set(0, ItemFixtures.stack(itemId));
        return sink.captureItems(stacks).getTagList("Items", 10);
    }

    private static NBTTagCompound offersSelling(String sellId) {
        MerchantRecipeList offers = new MerchantRecipeList();
        offers.add(new MerchantRecipe(new ItemStack(Items.EMERALD, 1), ItemFixtures.stack(sellId)));
        return offers.getRecipiesAsTags();
    }

    /**
     * The shape the resume scan hands the bank: a region chunk, whose entities sit under {@code Level.Entities}.
     * {@link #chunkOf} keeps the flat envelope the fold passes to {@code applyTo}. A test using one shape for both
     * would pass while production banked nothing.
     */
    private static NBTTagCompound scannedChunkOf(NBTTagCompound... entities) {
        return wrapAsRegionChunk(chunkOf(entities));
    }

    private static NBTTagCompound wrapAsRegionChunk(NBTTagCompound flatChunk) {
        NBTTagCompound level = new NBTTagCompound();
        level.setTag("Entities", flatChunk.getTagList("Entities", 10));
        NBTTagCompound host = new NBTTagCompound();
        host.setTag("Level", level);
        return host;
    }

    private static NBTTagCompound node(NBTTagCompound chunk, UUID uuid) {
        NBTTagList entities = chunk.getTagList("Entities", 10);
        for (int i = 0; i < entities.tagCount(); i++) {
            NBTTagCompound found = EntityTreeWalk.byUuid(entities.getCompoundTagAt(i)).get(uuid);
            if (found != null) {
                return found;
            }
        }
        throw new AssertionError("no node " + uuid);
    }

    private static String firstSellId(NBTTagCompound entity) {
        return entity.getCompoundTag("Offers").getTagList("Recipes", 10).getCompoundTagAt(0)
                .getCompoundTag("sell").getString("id");
    }

    /** {@code getIntOr} is a later-band accessor; below it an absent key must not read back as a real zero. */
    private static int intOr(NBTTagCompound tag, String key, int fallback) {
        return tag.hasKey(key) ? tag.getInteger(key) : fallback;
    }
}
