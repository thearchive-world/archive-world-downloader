// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SerializableUUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.adapter.impl.EntitySinkImpl;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the save-time entity-container injection: {@link EntityContainerMerge#mergeEntityStash}
 * locates a stashed container vehicle inside the captured {@code entities/} chunk tag by entity {@code "UUID"}
 * (matching the {@code "Entities"} list), sets its {@code "Items"} via {@link ContainerSink#merge}, touches no other
 * entity, then drains the merged entry. This is the part that must never mis-target, so it is proven headless with
 * hand-built entity tags (no live client, no {@code Level}).
 *
 * <p>The {@code "UUID"} decode the merge relies on is total: a missing, odd-length, or wrong-type {@code "UUID"} yields
 * a skipped entity, not a throw. That matters because the merge runs between the chunk flush and
 * {@code activeWriter.finish()}: a throw there would leave an unopenable save plus a leaked session lock.
 */
class EntityContainerStashMergeTest {
    private static RegistryAccess registries;
    private final ContainerSink containerSink = new ContainerSinkImpl();
    private final EntitySink entitySink = new EntitySinkImpl();

    private static final UUID UUID_A = new UUID(0x1111_1111_1111_1111L, 0x2222_2222_2222_2222L);
    private static final UUID UUID_B = new UUID(0x3333_3333_3333_3333L, 0x4444_4444_4444_4444L);
    private static final UUID UUID_C = new UUID(0x5555_5555_5555_5555L, 0x6666_6666_6666_6666L);

    /** A band sink whose merge blows up, for the isolation cases; its capture side is never reached. */
    private static final ContainerSink THROWING_SINK = new ContainerSink() {
        @Override
        public CompoundTag captureItems(NonNullList<ItemStack> items, RegistryAccess registries) {
            throw new AssertionError("the failure path never serializes items");
        }

        @Override
        public CompoundTag merge(CompoundTag blockEntityTag, CompoundTag capturedItemsHolder) {
            throw new IllegalStateException("a band merge blew up");
        }
    };

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    /**
     * A serialized container-vehicle tag: an {@code id}, the {@code "UUID"} via {@link SerializableUUID#CODEC}, no
     * items.
     */
    private static CompoundTag entityTag(String id, UUID uuid) {
        return EntityFixtures.entity(id, uuid);
    }

    /** Build the {@code entities/} chunk envelope via the real sink, so the merge runs against the live shape. */
    private CompoundTag entitiesChunkTag(CompoundTag... entityTags) {
        return entitySink.encodeChunk(ImmutableList.copyOf(entityTags), new ChunkPos(0, 0));
    }

    private static CompoundTag findByUuid(ListTag entities, UUID uuid) {
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag tag = entities.getCompound(i);
            if (uuid.equals(EntityMerge.readUuid(tag))) {
                return tag;
            }
        }
        throw new AssertionError("no entity with uuid " + uuid);
    }

    private CompoundTag holderWith(int slot, ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(slot, stack);
        return containerSink.captureItems(items, registries);
    }

    @Test
    void mergeEntityStashFillsTheMatchingEntityAndDrainsOnlyThatEntry() {
        CompoundTag chunkTag = entitiesChunkTag(
                entityTag("minecraft:chest_minecart", UUID_A),
                entityTag("minecraft:chest_boat", UUID_B)); // a neighbor vehicle that must stay untouched

        Map<UUID, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(UUID_A, holderWith(2, new ItemStack(Items.EMERALD, 7)));
        stash.put(UUID_C, holderWith(0, new ItemStack(Items.DIAMOND, 1))); // a vehicle in no captured chunk

        int merged = EntityContainerMerge.mergeEntityStash(containerSink, chunkTag, stash).merged();

        assertEquals(1, merged, "only the vehicle present in this chunk merges");
        assertFalse(stash.containsKey(UUID_A), "the merged entry is drained");
        assertTrue(stash.containsKey(UUID_C), "an unmatched stash entry is left undrained (the lost-items edge)");

        ListTag entities = chunkTag.getList("Entities", 10);
        CompoundTag mergedEntity = findByUuid(entities, UUID_A);
        assertEquals("minecraft:chest_minecart", mergedEntity.getString("id"),
                "the id is preserved (no clobber)");
        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(mergedEntity, back);
        assertEquals(Items.EMERALD, back.get(2).getItem(), "the chest minecart gains exactly the captured stack");
        assertEquals(7, back.get(2).getCount());

        assertFalse(findByUuid(entities, UUID_B).contains("Items"), "the neighbor vehicle is untouched");
    }

    @Test
    void mergeEntityStashReachesTheHolderNestedUnderTheVehiclesPassengers() {
        // A chested mule pushed into a plain minecart saves nested under the minecart's Passengers, never as a
        // top-level entity, and the open-time fold must still reach it or the contents the player opened are lost.
        CompoundTag chunkTag = entitiesChunkTag(
                EntityFixtures.entityCarrying(entityTag("minecraft:minecart", UUID_A),
                        entityTag("minecraft:mule", UUID_B)));

        Map<UUID, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(UUID_B, holderWith(2, new ItemStack(Items.EMERALD, 7)));

        MergeTally tally = EntityContainerMerge.mergeEntityStash(containerSink, chunkTag, stash);

        assertEquals(1, tally.merged(), "the nested chested mule gains its captured contents");
        assertFalse(stash.containsKey(UUID_B), "and its stash entry is drained");

        ListTag entities = chunkTag.getList("Entities", 10);
        CompoundTag minecart = findByUuid(entities, UUID_A);
        assertFalse(minecart.contains("Items"), "the plain minecart it was pushed under carries no contents");
        CompoundTag nestedMule = minecart.getList("Passengers", 10).getCompound(0);
        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(nestedMule, back);
        assertEquals(Items.EMERALD, back.get(2).getItem(), "the nested mule carries exactly the captured stack");
        assertEquals(7, back.get(2).getCount());
    }

    @Test
    void mergeEntityStashIsTotalOnMissingUuid() {
        CompoundTag entity = EntityFixtures.entityWithoutUuid("minecraft:chest_minecart");
        CompoundTag chunkTag = entitiesChunkTag(entity);
        Map<UUID, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(UUID_A, holderWith(0, new ItemStack(Items.DIAMOND, 1)));

        int merged = assertDoesNotThrow(
                () -> EntityContainerMerge.mergeEntityStash(containerSink, chunkTag, stash).merged());

        assertEquals(0, merged, "an entity with no UUID is skipped, not merged");
        assertTrue(stash.containsKey(UUID_A), "the stash entry is left (the unreadable entity drains nothing)");
    }

    @Test
    void mergeEntityStashIsTotalOnOddLengthUuid() {
        // 3 ints, not the 4 a UUID needs
        CompoundTag entity = EntityFixtures.entityWithShortUuid("minecraft:chest_minecart", 1, 2, 3);
        CompoundTag chunkTag = entitiesChunkTag(entity);
        Map<UUID, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(UUID_A, holderWith(0, new ItemStack(Items.DIAMOND, 1)));

        int merged = assertDoesNotThrow(
                () -> EntityContainerMerge.mergeEntityStash(containerSink, chunkTag, stash).merged());

        assertEquals(0, merged, "an odd-length UUID decodes to an error result, not a throw -> skipped");
        assertTrue(stash.containsKey(UUID_A));
    }

    @Test
    void mergeEntityStashIsTotalOnWrongTypeUuid() {
        // a string where a 4-int array is expected
        CompoundTag entity = EntityFixtures.entityWithWrongTypeUuid("minecraft:chest_minecart", "not-a-uuid");
        CompoundTag chunkTag = entitiesChunkTag(entity);
        Map<UUID, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(UUID_A, holderWith(0, new ItemStack(Items.DIAMOND, 1)));

        int merged = assertDoesNotThrow(
                () -> EntityContainerMerge.mergeEntityStash(containerSink, chunkTag, stash).merged());

        assertEquals(0, merged, "a wrong-type UUID decodes to an error result, not a throw -> skipped");
        assertTrue(stash.containsKey(UUID_A));
    }

    @Test
    void mergeEntityStashCountsThrowingMergeAsFailedButStillDrains() {
        // A band merge that throws between the chunk flush and finish() must be isolated: the vehicle's items are
        // lost, its stash entry is still drained, and the loss is counted so the caller reports a partial save
        // honestly instead of a clean one.
        CompoundTag chunkTag = entitiesChunkTag(entityTag("minecraft:chest_minecart", UUID_A));
        Map<UUID, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(UUID_A, new CompoundTag());

        MergeTally tally = EntityContainerMerge.mergeEntityStash(THROWING_SINK, chunkTag, stash);

        assertEquals(0, tally.merged(), "a throwing merge fills nothing");
        assertEquals(1, tally.failed(), "and the lost vehicle is counted for the honest partial-save report");
        assertFalse(stash.containsKey(UUID_A), "the entry is still drained before the merge is attempted");
    }

    @Test
    void refoldFlushedContainersCountsEveryCopyItRewritesAndDrainsNone() {
        // The re-fold repeats an earlier fold into every later chunk the vehicle reaches, so its holders must
        // survive the call. Counting is what says the repeat happened at all: the contents it writes are the same
        // ones the first fold wrote, so a re-fold that silently did nothing looks identical on that chunk alone.
        CompoundTag chunkTag = entitiesChunkTag(
                entityTag("minecraft:chest_minecart", UUID_A),
                entityTag("minecraft:chest_boat", UUID_B),
                entityTag("minecraft:chest_minecart", UUID_C)); // never folded, so nothing is written onto it

        Map<UUID, CompoundTag> folded = new LinkedHashMap<>();
        folded.put(UUID_A, holderWith(2, new ItemStack(Items.EMERALD, 7)));
        folded.put(UUID_B, holderWith(0, new ItemStack(Items.DIAMOND, 1)));

        MergeTally tally = EntityContainerMerge.refoldFlushedContainers(containerSink, chunkTag, folded,
                new LinkedHashMap<>());

        assertEquals(2, tally.merged(), "both vehicles this chunk holds are written again with their contents");
        assertEquals(0, tally.failed(), "and nothing is lost");
        assertTrue(folded.containsKey(UUID_A), "the holder stays for any later chunk the vehicle reaches");
        assertTrue(folded.containsKey(UUID_B));

        ListTag entities = chunkTag.getList("Entities", 10);
        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(findByUuid(entities, UUID_A), back);
        assertEquals(Items.EMERALD, back.get(2).getItem(), "this copy carries the loot rather than being empty");
        assertFalse(findByUuid(entities, UUID_C).contains("Items"), "the unfolded neighbor is untouched");
    }

    @Test
    void refoldFlushedContainersCountsThrowingMergeAsFailed() {
        // A re-fold that throws leaves this copy of the vehicle empty beside a copy that has its contents, so it
        // is a partial loss the caller has to report rather than a clean save.
        CompoundTag chunkTag = entitiesChunkTag(entityTag("minecraft:chest_minecart", UUID_A));
        Map<UUID, CompoundTag> folded = new LinkedHashMap<>();
        folded.put(UUID_A, new CompoundTag());

        MergeTally tally = EntityContainerMerge.refoldFlushedContainers(THROWING_SINK, chunkTag, folded,
                new LinkedHashMap<>());

        assertEquals(0, tally.merged(), "a throwing merge rewrites nothing");
        assertEquals(1, tally.failed(), "and the copy that saves without its contents is counted");
        assertTrue(folded.containsKey(UUID_A), "a failed re-fold still leaves the holder for the next chunk");
    }

    @Test
    void mergeEntityStashOnAnEmptyStashReturnsZeroTallyNotNull() {
        CompoundTag chunkTag = entitiesChunkTag(entityTag("minecraft:chest_minecart", UUID_A));
        MergeTally tally = EntityContainerMerge.mergeEntityStash(containerSink, chunkTag, new LinkedHashMap<>());
        assertEquals(0, tally.merged(), "an empty stash merges nothing");
        assertEquals(0, tally.failed(), "and loses nothing");
    }
}
