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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless guard for the resume entity read-merge: {@link EntityMerge} carries forward a container vehicle's (or
 * chested animal's) on-disk {@code "Items"} into a freshly re-captured entity-chunk, matched by {@code "UUID"},
 * preferring non-empty, so a parked storage minecart the prior session opened is not wiped by a fly-through that never
 * re-opened it. The UUID-keyed sibling of {@link ChunkMerge}.
 */
class EntityMergeTest {
    private static final UUID UUID_A = new UUID(0x1111_1111_1111_1111L, 0x2222_2222_2222_2222L);
    private static final UUID UUID_B = new UUID(0x3333_3333_3333_3333L, 0x4444_4444_4444_4444L);

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static NBTTagCompound vehicle(UUID uuid, String... itemIds) {
        return EntityFixtures.containerVehicle("minecraft:chest_minecart", uuid, itemIds);
    }

    private static NBTTagCompound entities(NBTTagCompound... vehicles) {
        return EntityFixtures.entityChunkTagWith(vehicles);
    }

    private static int itemCount(NBTTagCompound chunk, UUID uuid) {
        NBTTagList list = (NBTTagList) chunk.getTag("Entities");
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = (NBTTagCompound) list.get(i);
            if (matches(tag, uuid)) {
                return tag.getTag("Items") instanceof NBTTagList ? ((NBTTagList) tag.getTag("Items")).tagCount() : 0;
            }
        }
        throw new AssertionError("no entity " + uuid);
    }

    private static int nestedItemCount(NBTTagCompound chunk, UUID rootUuid, UUID nestedUuid) {
        NBTTagList list = (NBTTagList) chunk.getTag("Entities");
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound root = (NBTTagCompound) list.get(i);
            if (!matches(root, rootUuid) || !(root.getTag("Passengers") instanceof NBTTagList)) {
                continue;
            }
            NBTTagList passengers = (NBTTagList) root.getTag("Passengers");
            for (int j = 0; j < passengers.tagCount(); j++) {
                NBTTagCompound passenger = (NBTTagCompound) passengers.get(j);
                if (matches(passenger, nestedUuid)) {
                    return passenger.getTag("Items") instanceof NBTTagList
                            ? ((NBTTagList) passenger.getTag("Items")).tagCount()
                            : 0;
                }
            }
        }
        throw new AssertionError("no entity " + nestedUuid + " nested under " + rootUuid);
    }

    private static boolean matches(NBTTagCompound tag, UUID uuid) {
        return tag.hasUniqueId("UUID") && uuid.equals(tag.getUniqueId("UUID"));
    }

    /**
     * A villager entity node carrying the trade experience vanilla writes unconditionally (so a client-reconstructed
     * node carries the client zero here), and the offers holder only when {@code offers} is present.
     */
    private static NBTTagCompound villager(UUID uuid, @Nullable NBTTagCompound offers, int xp) {
        NBTTagCompound tag = EntityFixtures.entity("minecraft:villager", uuid);
        tag.setInteger("Xp", xp);
        if (offers != null) {
            tag.setTag("Offers", offers);
        }
        return tag;
    }

    /** The Recipes offers holder vanilla's own trade-list NBT write produces, one offer selling the item. */
    private static NBTTagCompound offersWith(String sellId) {
        // This band's merchant offer takes a plain buy/sell ItemStack pair, no trade experience or price multiplier.
        MerchantRecipeList offers = new MerchantRecipeList();
        offers.add(new MerchantRecipe(new ItemStack(Items.EMERALD, 1), ItemFixtures.stack(sellId)));
        return offers.getRecipiesAsTags();
    }

    private static NBTTagCompound entitiesChunk(NBTTagCompound... villagers) {
        return EntityFixtures.entityChunkTagWith(villagers);
    }

    private static NBTTagCompound firstEntity(NBTTagCompound chunk) {
        return chunk.getTagList("Entities", 10).getCompoundTagAt(0);
    }

    private static String firstSellId(NBTTagCompound entity) {
        return entity.getCompoundTag("Offers").getTagList("Recipes", 10).getCompoundTagAt(0)
                .getCompoundTag("sell").getString("id");
    }

    @Test
    void mergeCarriesOffersForwardWhenFreshVillagerHasNone() {
        NBTTagCompound disk = entitiesChunk(villager(UUID_A, offersWith("minecraft:emerald"), 27));
        NBTTagCompound fresh = entitiesChunk(villager(UUID_A, null, 0)); // reconstructed: no Offers, Xp:0

        int carried = EntityMerge.merge(disk, fresh);

        NBTTagCompound merged = firstEntity(fresh);
        assertEquals(1, carried, "the villager received a carry-forward");
        assertTrue(merged.getTag("Offers") instanceof NBTTagCompound, "the trades carried, not lost");
        assertEquals("minecraft:emerald", firstSellId(merged), "the sold item carried");
        assertEquals(27, merged.getInteger("Xp"), "the experience carried, not the fresh zero");
    }

    @Test
    void mergeFreshNonEmptyOffersWins() {
        NBTTagCompound disk = entitiesChunk(villager(UUID_A, offersWith("minecraft:emerald"), 5));
        NBTTagCompound fresh = entitiesChunk(villager(UUID_A, offersWith("minecraft:diamond"), 9));

        EntityMerge.merge(disk, fresh);

        assertEquals("minecraft:diamond", firstSellId(firstEntity(fresh)), "the re-opened capture stands");
        assertEquals(9, firstEntity(fresh).getInteger("Xp"), "the fresher experience stands");
    }

    @Test
    void hasCapturedContentTrueForNonEmptyOffers() {
        assertTrue(EntityMerge.hasCapturedContent(villager(UUID_A, offersWith("minecraft:emerald"), 1)),
                "a villager with trades is captured content");
        assertFalse(EntityMerge.hasCapturedContent(villager(UUID_A, null, 0)),
                "a reconstructed villager with no trades is not");
        assertFalse(EntityMerge.hasCapturedContent(villager(UUID_A, new NBTTagCompound(), 0)),
                "an offers holder with no recipes is not content");
    }

    @Test
    void hasCapturedContentRecognizesFilledContainerEntity() {
        assertTrue(EntityMerge.hasCapturedContent(vehicle(UUID_A, "minecraft:diamond")), "a filled vehicle is content");
    }

    @Test
    void hasCapturedContentRejectsEmptyContainerEntity() {
        assertFalse(EntityMerge.hasCapturedContent(vehicle(UUID_A)), "an empty re-walked vehicle is not content");
    }

    @Test
    void aParkedVehicleCarriesForwardItsItems() {
        NBTTagCompound onDisk = entities(vehicle(UUID_A, "minecraft:diamond", "minecraft:gold_ingot"));
        NBTTagCompound fresh = entities(vehicle(UUID_A)); // re-captured, never re-opened: empty

        int mergeBacks = EntityMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks);
        assertEquals(2, itemCount(fresh, UUID_A), "the prior contents survive");
    }

    @Test
    void aNestedChestedAnimalCarriesForwardItsItems() {
        // A chested mule pushed into a minecart saves nested under the minecart's Passengers, and on a resume
        // the re-captured mule spawns with an empty chest, so its prior contents must carry from the nested node.
        NBTTagCompound onDisk = entities(EntityFixtures.entityCarrying(vehicle(UUID_A),
                EntityFixtures.containerVehicle("minecraft:mule", UUID_B, "minecraft:diamond",
                        "minecraft:gold_ingot")));
        NBTTagCompound fresh = entities(EntityFixtures.entityCarrying(vehicle(UUID_A),
                EntityFixtures.containerVehicle("minecraft:mule", UUID_B)));

        int mergeBacks = EntityMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "the nested mule's contents carry forward");
        assertEquals(2, nestedItemCount(fresh, UUID_A, UUID_B), "the nested mule keeps the prior contents");
    }

    @Test
    void aReOpenedVehicleKeepsFreshItems() {
        NBTTagCompound onDisk = entities(vehicle(UUID_A, "minecraft:dirt"));
        NBTTagCompound fresh = entities(vehicle(UUID_A, "minecraft:diamond", "minecraft:emerald"));

        assertEquals(0, EntityMerge.merge(onDisk, fresh));
        assertEquals(2, itemCount(fresh, UUID_A), "the fresher capture wins");
    }

    @Test
    void onlyTheMatchingUuidCarriesForward() {
        NBTTagCompound onDisk = entities(vehicle(UUID_A, "minecraft:diamond"), vehicle(UUID_B, "minecraft:gold_ingot"));
        NBTTagCompound fresh = entities(vehicle(UUID_A), vehicle(UUID_B, "minecraft:emerald"));

        assertEquals(1, EntityMerge.merge(onDisk, fresh));
        assertEquals(1, itemCount(fresh, UUID_A), "the empty one carried back");
        assertEquals(1, itemCount(fresh, UUID_B), "the rich one kept its fresh emerald");
    }

    @Test
    void aPartialReflushKeepsThePriorEntities() {
        // A chunk re-flushed with a partial entity set (the rest re-streamed late, or on a revisit)
        // must not overwrite the entities a prior flush already wrote. Union by UUID so both survive; the bug
        // was that only "Items" carried forward, so the fresh (partial) write replaced the on-disk full set.
        NBTTagCompound onDisk = entities(vehicle(UUID_A, "minecraft:diamond")); // a prior flush saved A
        NBTTagCompound fresh = entities(vehicle(UUID_B)); // later partial flush of same chunk; A not in this batch

        EntityMerge.merge(onDisk, fresh);

        NBTTagList merged = (NBTTagList) fresh.getTag("Entities");
        assertEquals(2, merged.tagCount(), "A is carried forward, not overwritten by the partial re-flush");
        assertEquals(1, itemCount(fresh, UUID_A), "A survives with its items");
        assertEquals(0, itemCount(fresh, UUID_B), "B is the fresh capture");
    }

    @Test
    void anEntityAbsentFromTheFreshCaptureIsCarriedForward() {
        // Union always: a re-flush (or a resume) carries forward an entity absent from the fresh capture
        // rather than dropping it. Accepted ghosting (over-capture > under-capture), UUID-deduped so never
        // duplicated. The within-session-only alternative (resume strictly current) is rejected.
        NBTTagCompound onDisk = entities(vehicle(UUID_A, "minecraft:diamond"));
        NBTTagCompound fresh = entities(); // A not in the fresh capture

        assertEquals(1, EntityMerge.merge(onDisk, fresh), "A is carried forward");
        assertEquals(1, ((NBTTagList) fresh.getTag("Entities")).tagCount(), "carried, not dropped");
        assertEquals(1, itemCount(fresh, UUID_A), "with its items");
    }

    @Test
    void anEmptyOnBothSidesStaysEmpty() {
        NBTTagCompound onDisk = entities(vehicle(UUID_A));
        NBTTagCompound fresh = entities(vehicle(UUID_A));

        assertEquals(0, EntityMerge.merge(onDisk, fresh));
        NBTTagList list = fresh.getTag("Entities") instanceof NBTTagList ? (NBTTagList) fresh.getTag("Entities") : null;
        assertFalse(list != null
                && ((NBTTagCompound) list.get(0)).getTag("Items") instanceof NBTTagList
                && !((NBTTagList) ((NBTTagCompound) list.get(0)).getTag("Items")).hasNoTags());
    }

    @Test
    void aNullUuidOnDiskEntityIsCarriedForwardNotDropped() {
        // An on-disk entity whose "UUID" is absent or malformed cannot be UUID-deduped, but the union's
        // stated contract is to carry forward every on-disk entity the fresh capture lacks (over-capture beats
        // under-capture). We always write a valid UUID, so the trigger is corruption or a foreign-written save;
        // dropping it would silently lose the user's existing world data. Carry it forward, the same as a keyed one.
        NBTTagCompound noUuid = EntityFixtures.entityWithoutUuid("minecraft:chest_minecart");
        NBTTagCompound onDisk = entities(noUuid);
        NBTTagCompound fresh = entities(vehicle(UUID_A));

        assertEquals(1, EntityMerge.merge(onDisk, fresh), "the unkeyed on-disk entity is carried forward");
        assertEquals(2, ((NBTTagList) fresh.getTag("Entities")).tagCount(), "carried, not dropped");
    }

    @Test
    void anAbsentEntitiesListOnEitherSideChangesNothing() {
        // The early-return guard covers an ABSENT "Entities" key, not just a present-but-empty list. A chunk
        // with no list fresh (defensive) or none on disk (a first write) has nothing to union; merge is a no-op
        // and never throws. Production always hands a fresh tag carrying an "Entities" list.
        NBTTagCompound onDiskWithEntities = entities(vehicle(UUID_A, "minecraft:diamond"));
        NBTTagCompound freshNoList = new NBTTagCompound(); // no "Entities" key at all
        assertEquals(0, EntityMerge.merge(onDiskWithEntities, freshNoList), "fresh has no list: nothing to merge into");

        NBTTagCompound onDiskNoList = new NBTTagCompound();
        NBTTagCompound freshWithEntities = entities(vehicle(UUID_B));
        assertEquals(0, EntityMerge.merge(onDiskNoList, freshWithEntities), "disk has no list: nothing to carry");
        assertEquals(1, ((NBTTagList) freshWithEntities.getTag("Entities")).tagCount(),
                "the fresh capture is untouched");
    }
}
