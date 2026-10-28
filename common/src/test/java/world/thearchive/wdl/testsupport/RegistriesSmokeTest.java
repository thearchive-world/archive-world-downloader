// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

class RegistriesSmokeTest {
    @Test
    void buildsBiomeRegistryAndSection() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();

        assertNotNull(registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));

        assertNotNull(new LevelChunkSection(0, registries.registryOrThrow(Registries.BIOME)));
    }
}
