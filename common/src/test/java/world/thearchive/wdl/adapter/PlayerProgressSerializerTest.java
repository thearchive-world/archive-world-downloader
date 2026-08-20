// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.Criterion;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

class PlayerProgressSerializerTest {
    private static final int DATA_VERSION = 4189;

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap(); // boots BuiltInRegistries + the Stats statics (side effect only)
    }

    private static JsonObject parse(byte[] json) {
        return new JsonParser().parse(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void statsJsonGroupsByTypeFiltersZeroAndStampsDataVersion() {
        StatsCounter counter = new StatsCounter();
        counter.setValue(null, Stats.CUSTOM.get(Stats.PLAY_ONE_MINUTE), 1200); // base setValue ignores the player
        counter.setValue(null, Stats.BLOCK_MINED.get(Blocks.STONE), 5);
        counter.setValue(null, Stats.BLOCK_MINED.get(Blocks.DIRT), 0); // a zero must be filtered out

        byte[] bytes = PlayerProgressSerializer.statsJson(counter, DATA_VERSION);
        assertNotNull(bytes, "a populated counter yields a blob");
        JsonObject root = parse(bytes);

        assertEquals(DATA_VERSION, root.get("DataVersion").getAsInt());
        JsonObject stats = root.getAsJsonObject("stats");
        assertEquals(1200, stats.getAsJsonObject("minecraft:custom").get("minecraft:play_one_minute").getAsInt());
        JsonObject mined = stats.getAsJsonObject("minecraft:mined");
        assertEquals(5, mined.get("minecraft:stone").getAsInt());
        assertTrue(!mined.has("minecraft:dirt"), "a zero-valued stat is filtered out");
    }

    @Test
    void statsJsonReturnsNullForAnEmptyCounter() {
        assertNull(PlayerProgressSerializer.statsJson(new StatsCounter(), DATA_VERSION),
                "no stat > 0 means no reply landed, so write nothing");
    }

    @Test
    void advancementsJsonKeysByIdStringWithDoneAndRealTimestamp() {
        AdvancementProgress progress = new AdvancementProgress();
        progress.update(ImmutableMap.of("minecraft:foo", new Criterion()), new String[][] { { "minecraft:foo" } });
        progress.grantProgress("minecraft:foo");
        assertTrue(progress.isDone(), "precondition: the constructed advancement is genuinely done");

        byte[] bytes = PlayerProgressSerializer.advancementsJson(
                ImmutableMap.of("minecraft:story/root", progress), DATA_VERSION);
        JsonObject root = parse(bytes);

        assertEquals(DATA_VERSION, root.get("DataVersion").getAsInt());
        JsonObject entry = root.getAsJsonObject("minecraft:story/root");
        assertTrue(entry.get("done").getAsBoolean(), "a genuinely-done advancement serializes done=true");
        String obtained = entry.getAsJsonObject("criteria").get("minecraft:foo").getAsString();
        assertTrue(obtained.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4}"),
                "criterion timestamp is 'yyyy-MM-dd HH:mm:ss Z', not ISO-8601: " + obtained);
    }
}
