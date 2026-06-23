// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.junit.jupiter.api.Test;

/**
 * Acceptance test for the headless registry harness.
 *
 * <p>Checks two things: (1) the {@code common} TEST classpath carries {@code net.minecraft.*} classes and the bundled
 * vanilla data (the biome JSONs), and (2) {@link TestRegistries#frozen()} reproduces vanilla's WorldLoader bootstrap
 * closely enough to populate the dynamic {@code BIOME} registry. The two assertions are the calls
 * {@link PalettedContainerFactory#create(RegistryAccess)} itself makes
 * ({@code lookupOrThrow(BIOME).getOrThrow(PLAINS)}).
 */
class RegistriesSmokeTest {
    @Test
    void buildsBiomeRegistryAndFactory() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();

        // PLAINS present in the dynamic BIOME registry, else getOrThrow throws.
        assertNotNull(registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));

        // The same factory the 1.21.11 chunk codec uses (Level.palettedContainerFactory()).
        assertNotNull(PalettedContainerFactory.create(registries));
    }
}
