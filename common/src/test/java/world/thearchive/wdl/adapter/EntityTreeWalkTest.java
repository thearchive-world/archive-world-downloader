// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.EntityFixtures;

/**
 * The node index every per-node question about a mount record is asked through. The index must reach a node at any
 * depth, hand back the node that lives inside the tree rather than a copy of it (a caller writes contents onto it), and
 * decode identity through the {@code "UUID"} codec rather than by tag equality, since a tag we did not produce can hold
 * an unreadable one and must not become a key.
 */
class EntityTreeWalkTest {
    private static final UUID CARRIER = UUID.fromString("6b1d5f2c-9a30-4e11-b8c7-5d0e3a71f402");
    private static final UUID MOUNT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void everyNodeOfTheTreeIsIndexedByItsOwnUuid() {
        CompoundTag mount = EntityFixtures.entity("minecraft:mule", MOUNT);
        CompoundTag tree = EntityFixtures.entityCarrying(EntityFixtures.entity("minecraft:minecart", CARRIER), mount);

        Map<UUID, CompoundTag> nodes = EntityTreeWalk.byUuid(tree);

        assertEquals(2, nodes.size(), "the record is a set of entities, not one entity");
        assertSame(tree, nodes.get(CARRIER), "the root is a node of its own record");
        assertSame(mount, nodes.get(MOUNT),
                "and a passenger is indexed as the node inside the tree, so writing to it writes into the record");
    }

    @Test
    void aNodeWithNoReadableUuidIsNotIndexedAndDoesNotHideItsPassengers() {
        CompoundTag mount = EntityFixtures.entity("minecraft:mule", MOUNT);
        CompoundTag tree = EntityFixtures.entityCarrying(
                EntityFixtures.entityWithShortUuid("minecraft:minecart", 1, 2), mount);

        Map<UUID, CompoundTag> nodes = EntityTreeWalk.byUuid(tree);

        assertEquals(1, nodes.size(), "a tag whose UUID does not decode is not one we wrote, so it keys nothing");
        assertFalse(nodes.containsValue(tree), "the unreadable root is absent");
        assertTrue(nodes.containsKey(MOUNT), "and the walk still descends past it to the node that does decode");
    }
}
