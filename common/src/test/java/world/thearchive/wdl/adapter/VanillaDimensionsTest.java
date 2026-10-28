// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.WorldType;
import world.thearchive.wdl.testsupport.TestRegistries;

class VanillaDimensionsTest {
    @BeforeAll
    static void bootstrap() {
        TestRegistries.frozen();
    }

    private static DimensionType type(ResourceKey<DimensionType> key) {
        return TestRegistries.frozen().registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY).getOrThrow(key);
    }

    @Test
    void netherTypeMapsToNether() {
        assertEquals(Level.NETHER, VanillaDimensions.forType(type(DimensionType.NETHER_LOCATION)));
    }

    @Test
    void endTypeMapsToEnd() {
        assertEquals(Level.END, VanillaDimensions.forType(type(DimensionType.END_LOCATION)));
    }

    @Test
    void overworldTypeMapsToOverworld() {
        assertEquals(Level.OVERWORLD, VanillaDimensions.forType(type(DimensionType.OVERWORLD_LOCATION)));
    }

    @Test
    void overworldVariantTypeMapsToOverworld() {
        assertEquals(Level.OVERWORLD, VanillaDimensions.forType(type(DimensionType.OVERWORLD_CAVES_LOCATION)));
    }

    @Test
    void unknownOrUnregisteredTypeFallsBackToOverworld() {
        assertEquals(Level.OVERWORLD, VanillaDimensions.forType(null));
    }

    @Test
    void everyRoutedDimensionReadsBackFromTheIdItWrites() {
        // The round trip is what a resume depends on: the prior level.dat records the routed dimension as a
        // plain id string, and reading it back wrong sends a write into another dimension's folder.
        for (ResourceKey<Level> routed : ImmutableList.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            assertEquals(routed, VanillaDimensions.forId(routed.location().toString()),
                    routed + " must survive the id round trip");
        }
    }

    @Test
    void anIdOutsideTheRoutedThreeReadsBackAsNoDimension() {
        // Nothing this capture writes lands outside the three, so an id that does names a folder we cannot route into;
        // answering with a fallback can silently pick the wrong one.
        assertNull(VanillaDimensions.forId("examplepack:skylands"));
        assertNull(VanillaDimensions.forId("minecraft:worlds/2b2t/2b2t_1"));
        assertNull(VanillaDimensions.forId(""), "an absent Dimension key reads as the empty default");
    }

    @Test
    void defaultOverworldByTypeBlendsWhereCustomLevelKeyWouldNot() {
        ResourceKey<Level> routedByType = VanillaDimensions.forType(type(DimensionType.OVERWORLD_LOCATION));
        assertEquals(Level.OVERWORLD, routedByType);
        assertTrue(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, routedByType));
    }

    @Test
    void onlyTheDefaultGeneratorBlendsTheOverworld() {
        assertTrue(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, Level.OVERWORLD));
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.FLAT, Level.OVERWORLD),
                "flat writes fixed layers, so a blending marker would be inert");
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.VOID, Level.OVERWORLD),
                "a void world has no terrain to blend, so its output stays byte-unchanged");
    }

    @Test
    void netherAndEndAreNeverBlended() {
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, Level.NETHER));
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, Level.END));
    }
}
