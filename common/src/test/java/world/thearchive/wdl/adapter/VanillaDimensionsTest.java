// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.WorldType;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The captured dimension is routed to a vanilla single-player folder by its TYPE. At 1.15.2 the dimension key IS the
 * {@link DimensionType}, so a server whose level keys are non-standard (e.g. Multiverse's
 * {@code minecraft:worlds/2b2t/2b2t_1}) still lands in {@code ./region} / {@code DIM-1} / {@code DIM1} by the vanilla
 * type it carries rather than a nested folder.
 */
class VanillaDimensionsTest {
    /** Vanilla bootstrap so the built-in {@link DimensionType} static values initialize. */
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
        assertEquals(DimensionType.THE_END, VanillaDimensions.forType(DimensionType.THE_END));
    }

    @Test
    void overworldTypeMapsToOverworld() {
        assertEquals(DimensionType.OVERWORLD, VanillaDimensions.forType(DimensionType.OVERWORLD));
    }

    @Test
    void unknownOrUnregisteredTypeFallsBackToOverworld() {
        // A null type (a holder that resolved to no value) opens as the overworld so the capture is still a loadable
        // vanilla single-player world.
        assertEquals(DimensionType.OVERWORLD, VanillaDimensions.forType(null));
    }

    @Test
    void everyRoutedDimensionReadsBackFromTheIdItWrites() {
        // The round trip is what a resume depends on: the prior level.dat records the routed dimension as a
        // plain id string, and reading it back wrong sends a write into another dimension's folder.
        for (DimensionType routed : ImmutableList.of(DimensionType.OVERWORLD, DimensionType.NETHER,
                DimensionType.THE_END)) {
            assertEquals(routed, VanillaDimensions.forId(routed.getName()),
                    routed + " must survive the id round trip");
        }
    }

    @Test
    void anIdOutsideTheRoutedThreeReadsBackAsNoDimension() {
        // Nothing this capture writes lands outside the three, so an id that does names a folder we cannot
        // route into; answering with a fallback would silently pick the wrong one.
        assertNull(VanillaDimensions.forId("examplepack:skylands"));
        assertNull(VanillaDimensions.forId("minecraft:worlds/2b2t/2b2t_1"));
        assertNull(VanillaDimensions.forId(""), "an absent Dimension key reads as the empty default");
    }

    @Test
    void defaultOverworldByTypeBlendsWhereCustomLevelKeyWouldNot() {
        // A Multiverse overworld arrives under a custom level key (minecraft:worlds/2b2t/2b2t_1) but the vanilla
        // overworld TYPE. Routing by TYPE lands it on OVERWORLD, so the DEFAULT generator blends its edge; a gate
        // keyed on the raw level key would not match minecraft:overworld and would wall instead. The call site
        // (LiveCaptureSession) is what feeds forType's routed key, not the raw key, into this predicate.
        DimensionType routedByType = VanillaDimensions.forType(DimensionType.OVERWORLD);
        assertEquals(DimensionType.OVERWORLD, routedByType);
        assertTrue(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, routedByType));
    }

    @Test
    void onlyTheDefaultGeneratorBlendsTheOverworld() {
        assertTrue(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, DimensionType.OVERWORLD));
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.FLAT, DimensionType.OVERWORLD),
                "flat writes fixed layers and discards the blender, so its marker would be inert");
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.VOID, DimensionType.OVERWORLD),
                "a void world has no terrain to blend, so its output stays byte-unchanged");
    }

    @Test
    void netherAndEndAreNeverBlended() {
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, DimensionType.NETHER));
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, DimensionType.THE_END));
    }
}
