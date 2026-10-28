// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.WorldType;
import world.thearchive.wdl.testsupport.TestRegistries;

class VanillaDimensionsTest {
    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
    }

    @Test
    void netherTypeMapsToNether() {
        assertEquals(DimensionType.NETHER, VanillaDimensions.forType(DimensionType.NETHER));
    }

    @Test
    void endTypeMapsToEnd() {
        assertEquals(DimensionType.field_18956, VanillaDimensions.forType(DimensionType.field_18956));
    }

    @Test
    void overworldTypeMapsToOverworld() {
        assertEquals(DimensionType.field_18954, VanillaDimensions.forType(DimensionType.field_18954));
    }

    @Test
    void unknownOrUnregisteredTypeFallsBackToOverworld() {
        assertEquals(DimensionType.field_18954, VanillaDimensions.forType(null));
    }

    @Test
    void everyRoutedDimensionReadsBackFromTheIdItWrites() {
        for (DimensionType routed : ImmutableList.of(DimensionType.field_18954, DimensionType.NETHER,
                DimensionType.field_18956)) {
            assertEquals(routed, VanillaDimensions.forId(DimensionType.getName(routed).toString()),
                    routed + " must survive the id round trip");
        }
    }

    @Test
    void anIdOutsideTheRoutedThreeReadsBackAsNoDimension() {
        assertNull(VanillaDimensions.forId("examplepack:skylands"));
        assertNull(VanillaDimensions.forId("minecraft:worlds/2b2t/2b2t_1"));
        assertNull(VanillaDimensions.forId(""), "an absent Dimension key reads as the empty default");
    }

    @Test
    void defaultOverworldByTypeBlendsWhereCustomLevelKeyWouldNot() {
        DimensionType routedByType = VanillaDimensions.forType(DimensionType.field_18954);
        assertEquals(DimensionType.field_18954, routedByType);
        assertTrue(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, routedByType));
    }

    @Test
    void onlyTheDefaultGeneratorBlendsTheOverworld() {
        assertTrue(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, DimensionType.field_18954));
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.FLAT, DimensionType.field_18954),
                "flat writes fixed layers, so a blending marker would be inert");
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.VOID, DimensionType.field_18954),
                "a void world has no terrain to blend, so its output stays byte-unchanged");
    }

    @Test
    void netherAndEndAreNeverBlended() {
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, DimensionType.NETHER));
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, DimensionType.field_18956));
    }
}
