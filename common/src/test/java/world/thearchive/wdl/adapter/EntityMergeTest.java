// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.EntityFixtures;
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
        TestRegistries.frozen();
    }

    private static CompoundTag vehicle(UUID uuid, String... itemIds) {
        return EntityFixtures.containerVehicle("minecraft:chest_minecart", uuid, itemIds);
    }

    private static CompoundTag entities(CompoundTag... vehicles) {
        return EntityFixtures.entityChunkTagWith(vehicles);
    }

    private static int itemCount(CompoundTag chunk, UUID uuid) {
        ListTag list = (ListTag) chunk.get("Entities");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = (CompoundTag) list.get(i);
            if (UUIDUtil.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag.get("UUID")).result()
                    .map(uuid::equals).orElse(false)) {
                return tag.get("Items") instanceof ListTag items ? items.size() : 0;
            }
        }
        throw new AssertionError("no entity " + uuid);
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
        CompoundTag onDisk = entities(vehicle(UUID_A, "minecraft:diamond", "minecraft:gold_ingot"));
        CompoundTag fresh = entities(vehicle(UUID_A)); // re-captured, never re-opened: empty

        int mergeBacks = EntityMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks);
        assertEquals(2, itemCount(fresh, UUID_A), "the prior contents survive");
    }

    @Test
    void aReOpenedVehicleKeepsFreshItems() {
        CompoundTag onDisk = entities(vehicle(UUID_A, "minecraft:dirt"));
        CompoundTag fresh = entities(vehicle(UUID_A, "minecraft:diamond", "minecraft:emerald"));

        assertEquals(0, EntityMerge.merge(onDisk, fresh));
        assertEquals(2, itemCount(fresh, UUID_A), "the fresher capture wins");
    }

    @Test
    void onlyTheMatchingUuidCarriesForward() {
        CompoundTag onDisk = entities(vehicle(UUID_A, "minecraft:diamond"), vehicle(UUID_B, "minecraft:gold_ingot"));
        CompoundTag fresh = entities(vehicle(UUID_A), vehicle(UUID_B, "minecraft:emerald"));

        assertEquals(1, EntityMerge.merge(onDisk, fresh));
        assertEquals(1, itemCount(fresh, UUID_A), "the empty one carried back");
        assertEquals(1, itemCount(fresh, UUID_B), "the rich one kept its fresh emerald");
    }

    @Test
    void aPartialReflushKeepsThePriorEntities() {
        // A chunk re-flushed with a partial entity set (the rest re-streamed late, or on a revisit)
        // must not overwrite the entities a prior flush already wrote. Union by UUID so both survive; the bug
        // was that only "Items" carried forward, so the fresh (partial) write replaced the on-disk full set.
        CompoundTag onDisk = entities(vehicle(UUID_A, "minecraft:diamond")); // a prior flush saved A
        CompoundTag fresh = entities(vehicle(UUID_B)); // a later partial flush of the same chunk; A not in this batch

        EntityMerge.merge(onDisk, fresh);

        ListTag merged = (ListTag) fresh.get("Entities");
        assertEquals(2, merged.size(), "A is carried forward, not overwritten by the partial re-flush");
        assertEquals(1, itemCount(fresh, UUID_A), "A survives with its items");
        assertEquals(0, itemCount(fresh, UUID_B), "B is the fresh capture");
    }

    @Test
    void anEntityAbsentFromTheFreshCaptureIsCarriedForward() {
        // Union always: a re-flush (or a resume) carries forward an entity absent from the fresh capture
        // rather than dropping it. Accepted ghosting (over-capture > under-capture), UUID-deduped so never
        // duplicated. The within-session-only alternative (resume strictly current) is rejected.
        CompoundTag onDisk = entities(vehicle(UUID_A, "minecraft:diamond"));
        CompoundTag fresh = entities(); // A not in the fresh capture

        assertEquals(1, EntityMerge.merge(onDisk, fresh), "A is carried forward");
        assertEquals(1, ((ListTag) fresh.get("Entities")).size(), "carried, not dropped");
        assertEquals(1, itemCount(fresh, UUID_A), "with its items");
    }

    @Test
    void anEmptyOnBothSidesStaysEmpty() {
        CompoundTag onDisk = entities(vehicle(UUID_A));
        CompoundTag fresh = entities(vehicle(UUID_A));

        assertEquals(0, EntityMerge.merge(onDisk, fresh));
        assertFalse(fresh.get("Entities") instanceof ListTag list
                && ((CompoundTag) list.get(0)).get("Items") instanceof ListTag items && !items.isEmpty());
    }

    @Test
    void aNullUuidOnDiskEntityIsCarriedForwardNotDropped() {
        // An on-disk entity whose "UUID" is absent or malformed cannot be UUID-deduped, but the union's
        // stated contract is to carry forward every on-disk entity the fresh capture lacks (over-capture beats
        // under-capture). We always write a valid UUID, so the trigger is corruption or a foreign-written save;
        // dropping it would silently lose the user's existing world data. Carry it forward, the same as a keyed one.
        CompoundTag noUuid = EntityFixtures.entityWithoutUuid("minecraft:chest_minecart");
        CompoundTag onDisk = entities(noUuid);
        CompoundTag fresh = entities(vehicle(UUID_A));

        assertEquals(1, EntityMerge.merge(onDisk, fresh), "the unkeyed on-disk entity is carried forward");
        assertEquals(2, ((ListTag) fresh.get("Entities")).size(), "carried, not dropped");
    }

    @Test
    void anAbsentEntitiesListOnEitherSideChangesNothing() {
        // The early-return guard covers an ABSENT "Entities" key, not just a present-but-empty list. A chunk
        // with no list fresh (defensive) or none on disk (a first write) has nothing to union; merge is a no-op
        // and never throws. Production always hands a fresh tag carrying an "Entities" list.
        CompoundTag onDiskWithEntities = entities(vehicle(UUID_A, "minecraft:diamond"));
        CompoundTag freshNoList = new CompoundTag(); // no "Entities" key at all
        assertEquals(0, EntityMerge.merge(onDiskWithEntities, freshNoList), "fresh has no list: nothing to merge into");

        CompoundTag onDiskNoList = new CompoundTag();
        CompoundTag freshWithEntities = entities(vehicle(UUID_B));
        assertEquals(0, EntityMerge.merge(onDiskNoList, freshWithEntities), "disk has no list: nothing to carry");
        assertEquals(1, ((ListTag) freshWithEntities.get("Entities")).size(), "the fresh capture is untouched");
    }
}
