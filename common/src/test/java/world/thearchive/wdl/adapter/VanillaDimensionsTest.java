// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.WorldType;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The captured dimension is routed to a vanilla single-player folder by its TYPE, not its level key, so a server whose
 * level keys are non-standard (e.g. Multiverse's {@code minecraft:worlds/2b2t/2b2t_1}) still lands in {@code ./region}
 * / {@code DIM-1} / {@code DIM1} rather than a nested {@code dimensions/<ns>/<path>} folder.
 */
class VanillaDimensionsTest {
    /** Vanilla bootstrap so the {@code Level} / {@code BuiltinDimensionTypes} static keys initialize. */
    @BeforeAll
    static void bootstrap() {
        TestRegistries.frozen();
    }

    /** The built-in dimension type value for a key; forType classifies the value, not the key. */
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
        // The caves variant is a distinct dimension type but carries the overworld effects id, so it routes to the
        // overworld: forType classifies by effects, since the 1.18.2 client's type holder carries no registry key.
        assertEquals(Level.OVERWORLD, VanillaDimensions.forType(type(DimensionType.OVERWORLD_CAVES_LOCATION)));
    }

    @Test
    void unknownOrUnregisteredTypeFallsBackToOverworld() {
        // A null type (a holder that resolved to no value) opens as the overworld so the capture is still a loadable
        // vanilla single-player world.
        assertEquals(Level.OVERWORLD, VanillaDimensions.forType(null));
    }

    @Test
    void everyRoutedDimensionReadsBackFromTheIdItWrites() {
        // The round trip is what a resume depends on: the prior level.dat records the routed dimension as a
        // plain id string, and reading it back wrong sends a write into another dimension's folder.
        for (ResourceKey<Level> routed : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            assertEquals(routed, VanillaDimensions.forId(routed.location().toString()),
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
        ResourceKey<Level> routedByType = VanillaDimensions.forType(type(DimensionType.OVERWORLD_LOCATION));
        assertEquals(Level.OVERWORLD, routedByType);
        assertTrue(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, routedByType));
    }

    @Test
    void onlyTheDefaultGeneratorBlendsTheOverworld() {
        assertTrue(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, Level.OVERWORLD));
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.FLAT, Level.OVERWORLD),
                "flat writes fixed layers and discards the blender, so its marker would be inert");
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.VOID, Level.OVERWORLD),
                "a void world has no terrain to blend, so its output stays byte-unchanged");
    }

    @Test
    void netherAndEndAreNeverBlended() {
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, Level.NETHER));
        assertFalse(VanillaDimensions.shouldSynthesizeBlending(WorldType.DEFAULT, Level.END));
    }
}
