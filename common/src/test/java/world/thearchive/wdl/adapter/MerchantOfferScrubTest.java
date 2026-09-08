// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.village.MerchantRecipe;
import net.minecraft.village.MerchantRecipeList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The guard against a client-side merchant's own account of its trades reaching the archive. Asked for its offers, a
 * client merchant does not answer "none": it assigns itself a random career and generates a trade list from its
 * profession. Several ordinary things ask, differing by version, and on disk the result is indistinguishable from a
 * real capture. It also blocks the genuine trades already saved from being carried over it, both carry-forwards filling
 * only an empty slot.
 *
 * <p>The fixtures build that shape directly rather than provoking it, because a band whose vanilla guards the
 * client-side write cannot produce it locally. The keys are the seam, so the cases hold on every band.
 */
class MerchantOfferScrubTest {
    private static final UUID VILLAGER = UUID.fromString("2f6c0f4a-1e88-4d3b-9a52-7c0b6e4d1a93");
    private static final UUID RIDER = UUID.fromString("8d1a44b7-5c02-4e69-b0f1-3a9e77c25d40");

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    @Test
    void inventedTradesAreDroppedFromTheEntity() {
        NBTTagCompound villager = EntityFixtures.entity("minecraft:villager", VILLAGER);
        villager.setTag("Offers", offers());
        NBTTagCompound chunk = EntityFixtures.entityChunkTagWith(villager);

        assertEquals(1, MerchantOfferScrub.stripInventedOffers(chunk), "the one node carrying them is counted");

        assertFalse(node(chunk, VILLAGER).hasKey("Offers"));
    }

    @Test
    void theInventedCareerGoesWithTheTradesItWasMadeUpToPick() {
        NBTTagCompound villager = EntityFixtures.entity("minecraft:villager", VILLAGER);
        villager.setTag("Offers", offers());
        villager.setInteger("Career", 4);
        villager.setInteger("CareerLevel", 1);
        NBTTagCompound chunk = EntityFixtures.entityChunkTagWith(villager);

        MerchantOfferScrub.stripInventedOffers(chunk);

        assertFalse(node(chunk, VILLAGER).hasKey("Career"));
        assertFalse(node(chunk, VILLAGER).hasKey("CareerLevel"));
    }

    @Test
    void theSyncedProfessionSurvives() {
        NBTTagCompound villager = EntityFixtures.entity("minecraft:villager", VILLAGER);
        villager.setTag("Offers", offers());
        villager.setInteger("Profession", 2); // the server tells the client this one, so it is real
        NBTTagCompound chunk = EntityFixtures.entityChunkTagWith(villager);

        MerchantOfferScrub.stripInventedOffers(chunk);

        assertEquals(2, intOr(node(chunk, VILLAGER), "Profession", -1),
                "stripping the invention must not take the identity the archive needs to render the villager");
    }

    @Test
    void aMerchantRidingSomethingIsReached() {
        NBTTagCompound rider = EntityFixtures.entity("minecraft:villager", RIDER);
        rider.setTag("Offers", offers());
        NBTTagCompound chunk = EntityFixtures
                .entityChunkTagWith(EntityFixtures.entityCarrying(
                        EntityFixtures.entity("minecraft:boat", VILLAGER), rider));

        assertEquals(1, MerchantOfferScrub.stripInventedOffers(chunk));

        assertFalse(node(chunk, RIDER).hasKey("Offers"), "a passenger's invented trades are the same invention");
    }

    @Test
    void everyCarryingNodeIsCounted() {
        NBTTagCompound first = EntityFixtures.entity("minecraft:villager", VILLAGER);
        first.setTag("Offers", offers());
        NBTTagCompound second = EntityFixtures.entity("minecraft:wandering_trader", RIDER);
        second.setTag("Offers", offers());

        assertEquals(2, MerchantOfferScrub.stripInventedOffers(EntityFixtures.entityChunkTagWith(first, second)));
    }

    @Test
    void anEntityCarryingNoneIsUntouchedAndUncounted() {
        NBTTagCompound chunk = EntityFixtures
                .entityChunkTagWith(EntityFixtures.entity("minecraft:villager", VILLAGER));

        assertEquals(0, MerchantOfferScrub.stripInventedOffers(chunk));

        assertTrue(node(chunk, VILLAGER).hasKey("UUIDMost"), "and the entity itself is still there");
    }

    @Test
    void aChunkTagWithNoEntityListIsIgnored() {
        assertEquals(0, MerchantOfferScrub.stripInventedOffers(new NBTTagCompound()));
    }

    @Test
    void aBareRecordIsScrubbedWithoutBeingWrappedInChunkShape() {
        NBTTagCompound rider = EntityFixtures.entity("minecraft:villager", RIDER);
        rider.setTag("Offers", offers());
        NBTTagCompound record = EntityFixtures.entityCarrying(
                EntityFixtures.entity("minecraft:boat", VILLAGER), rider);

        assertEquals(1, MerchantOfferScrub.stripInventedOffersFromRecord(record),
                "the player's ridden vehicle is a bare record, never a chunk, and is the only copy of what rides "
                        + "with the player");

        assertFalse(EntityTreeWalk.byUuid(record).get(RIDER).hasKey("Offers"));
    }

    @Test
    void aCareerWithNoTradeListIsDroppedButNotCounted() {
        NBTTagCompound villager = EntityFixtures.entity("minecraft:villager", VILLAGER);
        villager.setInteger("Career", 4); // the versions that have this key write it for every villager, always
        NBTTagCompound chunk = EntityFixtures.entityChunkTagWith(villager);

        assertEquals(0, MerchantOfferScrub.stripInventedOffers(chunk),
                "counting these would report every villager the download saw and say nothing about invention");

        assertFalse(node(chunk, VILLAGER).hasKey("Career"), "it is still dropped, being equally made up");
    }

    private static NBTTagCompound offers() {
        MerchantRecipeList offers = new MerchantRecipeList();
        offers.add(new MerchantRecipe(new ItemStack(Items.EMERALD, 1), ItemFixtures.stack("minecraft:diamond")));
        return offers.getRecipiesAsTags();
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

    /** {@code getIntOr} is a later-band accessor; below it an absent key must not read back as a real zero. */
    private static int intOr(NBTTagCompound tag, String key, int fallback) {
        return tag.hasKey(key) ? tag.getInteger(key) : fallback;
    }
}
