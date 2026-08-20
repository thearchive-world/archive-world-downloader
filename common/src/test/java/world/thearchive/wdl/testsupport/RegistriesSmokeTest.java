// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

/**
 * Acceptance test for the headless registry harness.
 *
 * <p>Checks two things: (1) the {@code common} TEST classpath carries {@code net.minecraft.*} classes and the bundled
 * vanilla data (the biome JSONs), and (2) {@link TestRegistries#bootstrap()} populates the static {@code BIOME}
 * registry the chunk codec reads. The assertions exercise that biome lookup and build a {@link LevelChunkSection} the
 * way the codec does.
 */
class RegistriesSmokeTest {
    @Test
    void buildsBiomeRegistryAndSection() {
        TestRegistries.bootstrap();

        // PLAINS present in the static BIOME registry.
        assertNotNull(Registry.BIOME.getKey(Biomes.PLAINS));

        // A section builds; below 1.18 the biome axis is per-chunk, not per-section.
        assertNotNull(new LevelChunkSection(0));
    }
}
