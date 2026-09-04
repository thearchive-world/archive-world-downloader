// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityMinecartChest;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the entity-id gate {@link EntityFixtures} stamps every fixture through, and the band fact behind it: at 1.10.2
 * {@code EntityList} registers unnamespaced CamelCase names, and a saved id outside that set is skipped on load, taking
 * the entity and every passenger under it. A hand-built fixture id is the one part of an entity tag no producer checks,
 * so without this a whole suite can agree on an archive this band never writes.
 */
class EntityIdRegistryTest {
    private static final String STALE = "minecraft:chest_minecart";
    private static final String LIVE = "MinecartChest";

    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
    }

    @Test
    void thisBandRegistersUnnamespacedCamelCaseNames() {
        for (String name : EntityList.getEntityNameList()) {
            assertTrue(name.indexOf(':') < 0, "registered entity name carries a namespace: " + name);
        }
        assertEquals(LIVE, EntityList.getEntityStringFromClass(EntityMinecartChest.class),
                "the chest minecart is its own registration here, not a variant of one id");
    }

    @Test
    void theChokePointsStampNamesThisBandRegisters() {
        assertEquals(LIVE, EntityFixtures.entityTag(LIVE).getString("id"));
        assertEquals("Boat", EntityFixtures.entityWithoutUuid("Boat").getString("id"));
    }

    @Test
    void theChokePointsRefuseTheNamespacedForm() {
        AssertionError fromTag = assertThrows(AssertionError.class, () -> EntityFixtures.entityTag(STALE));
        assertTrue(fromTag.getMessage().contains(STALE), fromTag.getMessage());
        assertThrows(AssertionError.class, () -> EntityFixtures.entityWithoutUuid(STALE));
    }

    @Test
    void anUnregisteredIdIsDroppedOnLoadRatherThanRead() {
        NBTTagCompound stale = new NBTTagCompound();
        stale.setString("id", STALE);
        // The saved-id read path is createEntityFromNBT, which resolves the id through NAME_TO_CLASS and, on a miss,
        // warns that it is skipping an entity with that id and returns null. AnvilChunkLoader.readChunkEntity returns
        // on that null before it recurses into Passengers, so the entity's riders go with it. The pig substitution is
        // a different function on a different path, createEntityByIDFromName, which the packet reconstruct calls and
        // no load does.
        assertNull(EntityList.createEntityFromNBT(stale, HeadlessLevel.get()),
                "a stale saved id must be dropped, not read as some other entity");
        assertNull(EntityList.NAME_TO_CLASS.get(STALE));
        assertEquals(EntityMinecartChest.class, EntityList.NAME_TO_CLASS.get(LIVE));
    }

    @Test
    void theGateReadsTheSetVanillaWritesIdsFrom() {
        Set<String> gate = new HashSet<>(EntityList.getEntityNameList());
        Set<String> written = new HashSet<>(EntityList.CLASS_TO_NAME.values());
        Set<String> gateOnly = new HashSet<>(gate);
        gateOnly.removeAll(written);
        Set<String> writtenOnly = new HashSet<>(written);
        writtenOnly.removeAll(gate);
        // getEntityNameList is NAME_TO_CLASS.keySet() less the abstract classes plus the lightning bolt, which is
        // spawned through its own global-entity packet and carries no mapping. Nothing else may differ, or the gate
        // is reading a set other than the one vanilla writes a saved id from.
        assertEquals(new HashSet<>(Arrays.asList("LightningBolt")), gateOnly);
        assertEquals(new HashSet<>(Arrays.asList("Mob", "Monster")), writtenOnly);
    }
}
