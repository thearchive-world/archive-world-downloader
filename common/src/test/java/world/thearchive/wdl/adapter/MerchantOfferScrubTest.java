// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
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
        TestRegistries.frozen();
    }

    @Test
    void inventedTradesAreDroppedFromTheEntity() {
        CompoundTag villager = EntityFixtures.entity("minecraft:villager", VILLAGER);
        villager.put("Offers", offers());
        CompoundTag chunk = EntityFixtures.entityChunkTagWith(villager);

        assertEquals(1, MerchantOfferScrub.stripInventedOffers(chunk), "the one node carrying them is counted");

        assertFalse(node(chunk, VILLAGER).contains("Offers"));
    }

    @Test
    void theInventedCareerGoesWithTheTradesItWasMadeUpToPick() {
        CompoundTag villager = EntityFixtures.entity("minecraft:villager", VILLAGER);
        villager.put("Offers", offers());
        villager.putInt("Career", 4);
        villager.putInt("CareerLevel", 1);
        CompoundTag chunk = EntityFixtures.entityChunkTagWith(villager);

        MerchantOfferScrub.stripInventedOffers(chunk);

        assertFalse(node(chunk, VILLAGER).contains("Career"));
        assertFalse(node(chunk, VILLAGER).contains("CareerLevel"));
    }

    @Test
    void theSyncedProfessionSurvives() {
        CompoundTag villager = EntityFixtures.entity("minecraft:villager", VILLAGER);
        villager.put("Offers", offers());
        villager.putInt("Profession", 2); // the server tells the client this one, so it is real
        CompoundTag chunk = EntityFixtures.entityChunkTagWith(villager);

        MerchantOfferScrub.stripInventedOffers(chunk);

        assertEquals(2, intOr(node(chunk, VILLAGER), "Profession", -1),
                "stripping the invention must not take the identity the archive needs to render the villager");
    }

    @Test
    void aMerchantRidingSomethingIsReached() {
        CompoundTag rider = EntityFixtures.entity("minecraft:villager", RIDER);
        rider.put("Offers", offers());
        CompoundTag chunk = EntityFixtures
                .entityChunkTagWith(EntityFixtures.entityCarrying(
                        EntityFixtures.entity("minecraft:boat", VILLAGER), rider));

        assertEquals(1, MerchantOfferScrub.stripInventedOffers(chunk));

        assertFalse(node(chunk, RIDER).contains("Offers"), "a passenger's invented trades are the same invention");
    }

    @Test
    void everyCarryingNodeIsCounted() {
        CompoundTag first = EntityFixtures.entity("minecraft:villager", VILLAGER);
        first.put("Offers", offers());
        CompoundTag second = EntityFixtures.entity("minecraft:wandering_trader", RIDER);
        second.put("Offers", offers());

        assertEquals(2, MerchantOfferScrub.stripInventedOffers(EntityFixtures.entityChunkTagWith(first, second)));
    }

    @Test
    void anEntityCarryingNoneIsUntouchedAndUncounted() {
        CompoundTag chunk = EntityFixtures
                .entityChunkTagWith(EntityFixtures.entity("minecraft:villager", VILLAGER));

        assertEquals(0, MerchantOfferScrub.stripInventedOffers(chunk));

        assertTrue(node(chunk, VILLAGER).contains("UUID"), "and the entity itself is still there");
    }

    @Test
    void aChunkTagWithNoEntityListIsIgnored() {
        assertEquals(0, MerchantOfferScrub.stripInventedOffers(new CompoundTag()));
    }

    @Test
    void aBareRecordIsScrubbedWithoutBeingWrappedInChunkShape() {
        CompoundTag rider = EntityFixtures.entity("minecraft:villager", RIDER);
        rider.put("Offers", offers());
        CompoundTag record = EntityFixtures.entityCarrying(
                EntityFixtures.entity("minecraft:boat", VILLAGER), rider);

        assertEquals(1, MerchantOfferScrub.stripInventedOffersFromRecord(record),
                "the player's ridden vehicle is a bare record, never a chunk, and is the only copy of what rides "
                        + "with the player");

        assertFalse(EntityTreeWalk.byUuid(record).get(RIDER).contains("Offers"));
    }

    @Test
    void aCareerWithNoTradeListIsDroppedButNotCounted() {
        CompoundTag villager = EntityFixtures.entity("minecraft:villager", VILLAGER);
        villager.putInt("Career", 4); // the versions that have this key write it for every villager, always
        CompoundTag chunk = EntityFixtures.entityChunkTagWith(villager);

        assertEquals(0, MerchantOfferScrub.stripInventedOffers(chunk),
                "counting these would report every villager the download saw and say nothing about invention");

        assertFalse(node(chunk, VILLAGER).contains("Career"), "it is still dropped, being equally made up");
    }

    private static CompoundTag offers() {
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), ItemFixtures.stack("minecraft:diamond"),
                1, 0, 0.0f));
        return (CompoundTag) MerchantOffers.CODEC
                .encodeStart(RegistryOps.create(NbtOps.INSTANCE, TestRegistries.frozen()), offers).getOrThrow();
    }

    private static CompoundTag node(CompoundTag chunk, UUID uuid) {
        ListTag entities = chunk.getList("Entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag found = EntityTreeWalk.byUuid(entities.getCompound(i)).get(uuid);
            if (found != null) {
                return found;
            }
        }
        throw new AssertionError("no node " + uuid);
    }

    /** {@code getIntOr} is a later-band accessor; below it an absent key must not read back as a real zero. */
    private static int intOr(CompoundTag tag, String key, int fallback) {
        return tag.contains(key) ? tag.getInt(key) : fallback;
    }
}
