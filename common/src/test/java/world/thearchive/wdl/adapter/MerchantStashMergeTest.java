// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.village.class_1144;
import net.minecraft.village.class_1145;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless guard for the save-time villager-trade fold: {@link EntityContainerMerge#mergeMerchantStash} locates a
 * stashed villager inside the captured {@code entities/} chunk tag by {@code "UUID"}, sets its {@code "Offers"} and
 * (for a villager) {@code "Xp"} from the holder, then drains the merged entry; {@code refoldFlushedMerchants}
 * re-applies an already-folded holder to another chunk copy so a wandering trader that crossed entity-chunks carries
 * its trades to each. The merchant sibling of {@link EntityContainerStashMergeTest}, but the fold is a plain tag copy
 * with no sink and no per-node failure isolation, so these cases assert the payload and the drain rather than a
 * lost-item tally.
 */
class MerchantStashMergeTest {
    private static final UUID UUID_A = new UUID(0x1111_1111_1111_1111L, 0x2222_2222_2222_2222L);
    private static final UUID UUID_B = new UUID(0x3333_3333_3333_3333L, 0x4444_4444_4444_4444L);

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static CompoundTag villager(UUID uuid) {
        return EntityFixtures.entity("minecraft:villager", uuid);
    }

    private static CompoundTag wanderingTrader(UUID uuid) {
        return EntityFixtures.entity("minecraft:wandering_trader", uuid);
    }

    private static CompoundTag entitiesChunk(CompoundTag... entities) {
        return EntityFixtures.entityChunkTagWith(entities);
    }

    /** The Recipes offers holder vanilla's own trade-list NBT write produces, one offer selling the item. */
    private static CompoundTag offersWith(String sellId) {
        // This band's merchant offer takes a plain buy/sell ItemStack pair, no trade experience or price multiplier.
        class_1145 offers = new class_1145();
        offers.add(new class_1144(new ItemStack(Items.EMERALD, 1), ItemFixtures.stack(sellId)));
        return offers.method_3557();
    }

    private static CompoundTag merchantHolder(CompoundTag offers, @Nullable Integer xp) {
        CompoundTag holder = new CompoundTag();
        holder.put("Offers", offers);
        if (xp != null) {
            holder.putInt("Xp", xp);
        }
        return holder;
    }

    private static CompoundTag node(CompoundTag chunk, UUID uuid) {
        ListTag entities = chunk.getList("Entities", 10);
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag found = EntityTreeWalk.byUuid(entities.getCompound(i)).get(uuid);
            if (found != null) {
                return found;
            }
        }
        throw new AssertionError("no node " + uuid);
    }

    private static String firstSellId(CompoundTag entity) {
        return entity.getCompound("Offers").getList("Recipes", 10).getCompound(0)
                .getCompound("sell").getString("id");
    }

    @Test
    void mergeMerchantStashSetsOffersAndXpOnMatchingNode() {
        CompoundTag chunk = entitiesChunk(villager(UUID_A), villager(UUID_B));
        Map<UUID, CompoundTag> stash = new HashMap<>();
        stash.put(UUID_A, merchantHolder(offersWith("minecraft:emerald"), 12));

        MergeTally tally = EntityContainerMerge.mergeMerchantStash(chunk, stash);

        assertEquals(1, tally.merged(), "only the villager present in this chunk folds");
        assertEquals(0, tally.failed(), "a plain-copy fold never fails");
        assertEquals(12, node(chunk, UUID_A).getInt("Xp"), "the experience folds");
        assertEquals("minecraft:emerald", firstSellId(node(chunk, UUID_A)), "the trades fold");
        assertFalse(node(chunk, UUID_B).contains("Offers"), "the neighbor villager is untouched");
        assertTrue(stash.isEmpty(), "the merged entry is drained");
    }

    @Test
    void mergeMerchantStashWanderingTraderHolderHasNoXp() {
        CompoundTag chunk = entitiesChunk(wanderingTrader(UUID_A));
        Map<UUID, CompoundTag> stash = new HashMap<>();
        stash.put(UUID_A, merchantHolder(offersWith("minecraft:emerald"), null)); // no Xp for a wandering trader

        EntityContainerMerge.mergeMerchantStash(chunk, stash);

        assertTrue(node(chunk, UUID_A).get("Offers") instanceof CompoundTag, "the trades fold");
        assertFalse(node(chunk, UUID_A).contains("Xp"), "a wandering trader gets no experience");
    }

    @Test
    void mergeMerchantStashOnAnEmptyStashFoldsNothing() {
        CompoundTag chunk = entitiesChunk(villager(UUID_A));

        MergeTally tally = EntityContainerMerge.mergeMerchantStash(chunk, new HashMap<>());

        assertEquals(0, tally.merged(), "an empty stash folds nothing");
        assertEquals(0, tally.failed());
        assertFalse(node(chunk, UUID_A).contains("Offers"), "the villager is untouched");
    }

    @Test
    void refoldFlushedMerchantsReappliesToAnotherChunkCopy() {
        CompoundTag chunkB = entitiesChunk(villager(UUID_A)); // the same trader, in another entity-chunk
        Map<UUID, CompoundTag> folded = new LinkedHashMap<>();
        folded.put(UUID_A, merchantHolder(offersWith("minecraft:emerald"), 3)); // already folded in chunk A

        MergeTally tally = EntityContainerMerge.refoldFlushedMerchants(chunkB, folded, new HashMap<>());

        assertEquals(1, tally.merged(), "the second copy is written again with the trades");
        assertEquals("minecraft:emerald", firstSellId(node(chunkB, UUID_A)), "both copies carry the trades");
        assertTrue(folded.containsKey(UUID_A), "the refold does not drain the folded holder");
    }

    @Test
    void refoldFlushedMerchantsSkipsUuidWithFresherStashedCapture() {
        CompoundTag chunkB = entitiesChunk(villager(UUID_A));
        Map<UUID, CompoundTag> folded = new LinkedHashMap<>();
        folded.put(UUID_A, merchantHolder(offersWith("minecraft:emerald"), 3));
        Map<UUID, CompoundTag> stash = new HashMap<>();
        stash.put(UUID_A, merchantHolder(offersWith("minecraft:diamond"), 9)); // reopened; its own fold runs next

        EntityContainerMerge.refoldFlushedMerchants(chunkB, folded, stash);

        assertFalse(node(chunkB, UUID_A).contains("Offers"), "the stale fold is skipped, not applied");
    }

    @Test
    void refoldFlushedMerchantsOnEmptyFoldedFoldsNothing() {
        CompoundTag chunkB = entitiesChunk(villager(UUID_A));

        MergeTally tally = EntityContainerMerge.refoldFlushedMerchants(chunkB, new LinkedHashMap<>(), new HashMap<>());

        assertEquals(0, tally.merged(), "nothing folded yet, nothing to re-apply");
        assertEquals(0, tally.failed());
        assertFalse(node(chunkB, UUID_A).contains("Offers"));
    }
}
