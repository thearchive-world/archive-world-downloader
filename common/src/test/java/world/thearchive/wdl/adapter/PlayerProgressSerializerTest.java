// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import net.minecraft.init.Blocks;
import net.minecraft.stats.StatBase;
import net.minecraft.stats.StatList;
import net.minecraft.stats.StatisticsManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

class PlayerProgressSerializerTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static JsonObject parse(byte[] json) {
        return new JsonParser().parse(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void statsJsonIsFlatIdToCountObjectFilteringZero() {
        StatisticsManager counter = new StatisticsManager();
        counter.increaseStat(null, StatList.PLAY_ONE_MINUTE, 1200); // base increaseStat ignores the player
        StatBase stoneMined = StatList.getBlockStats(Blocks.STONE);
        StatBase dirtMined = StatList.getBlockStats(Blocks.DIRT);
        assertNotNull(stoneMined, "vanilla registers a mine-block stat for every block");
        assertNotNull(dirtMined, "vanilla registers a mine-block stat for every block");
        counter.increaseStat(null, stoneMined, 5);
        counter.increaseStat(null, dirtMined, 0);

        byte[] bytes = PlayerProgressSerializer.statsJson(counter);
        assertNotNull(bytes, "a populated counter yields a blob");
        JsonObject stats = parse(bytes);

        assertEquals(1200, stats.get(StatList.PLAY_ONE_MINUTE.statId).getAsInt());
        assertEquals(5, stats.get(stoneMined.statId).getAsInt());
        assertTrue(!stats.has(dirtMined.statId), "a zero-valued stat is filtered out");
    }

    @Test
    void statsJsonReturnsNullForAnEmptyCounter() {
        assertNull(PlayerProgressSerializer.statsJson(new StatisticsManager()),
                "no stat > 0 means no reply landed, so write nothing");
    }
}
