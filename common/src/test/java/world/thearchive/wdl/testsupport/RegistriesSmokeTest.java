// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

/**
 * Acceptance test for the headless registry harness.
 *
 * <p>Checks two things: (1) the {@code common} TEST classpath carries {@code net.minecraft.*} classes and the bundled
 * vanilla data (the biome JSONs), and (2) {@link TestRegistries#frozen()} reproduces vanilla's WorldLoader bootstrap
 * closely enough to populate the dynamic {@code BIOME} registry. The assertions exercise the biome lookup the 1.21.4
 * chunk codec relies on, {@code lookupOrThrow(BIOME).getOrThrow(PLAINS)}, and build a {@link LevelChunkSection} from
 * that registry the way the codec does.
 */
class RegistriesSmokeTest {
    @Test
    void buildsBiomeRegistryAndSection() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();

        // PLAINS present in the dynamic BIOME registry, else getOrThrow throws.
        assertNotNull(registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));

        // The biome-registry read the 1.21.4 chunk codec makes when it builds a section.
        assertNotNull(new LevelChunkSection(registries.lookupOrThrow(Registries.BIOME)));
    }
}
