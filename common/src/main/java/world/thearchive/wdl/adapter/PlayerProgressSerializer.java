// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.stats.StatBase;
import net.minecraft.stats.StatList;
import net.minecraft.stats.StatisticsManager;
import org.jspecify.annotations.Nullable;

/**
 * Pure JSON builders for the player's progress surfaces: data in, detached JSON bytes out, no {@code Minecraft} or live
 * client state, so the logic is headless-testable. The advancement value shape is vanilla's own
 * {@code AdvancementProgress.Serializer} Gson adapter; the stats shape is hand-built (the vanilla serializer,
 * {@code StatisticsManagerServer.dumpJson}, is server-only). Neither surface carries a {@code DataVersion} stamp at
 * this era; that is a 1.13+ addition. The advancement id is keyed as a plain {@code String} so the band-renamed id type
 * never appears here.
 */
final class PlayerProgressSerializer {
    private static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(AdvancementProgress.class, new AdvancementProgress.Serializer())
            .create();

    private PlayerProgressSerializer() {}

    /** The {@code advancements/<uuid>.json} bytes: id-string keyed progress, this era's own flat on-disk shape. */
    static byte[] advancementsJson(Map<String, AdvancementProgress> progressById) {
        JsonObject root = new JsonObject();
        progressById.forEach((id, progress) -> root.add(id, gson.toJsonTree(progress)));
        return gson.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The {@code stats/<uuid>.json} bytes: a flat {@code {"stat.<id>": <count>}} object, no namespace nesting (that
     * grouping is the 1.13+ {@code Registry.STAT_TYPE} shape), enumerated over {@link StatList#ALL_STATS}, the flat
     * list every {@code StatBase} joins at class load (captures modded stats too, the same way vanilla's own dispatch
     * does), keeping only counts {@code > 0}. Returns {@code null} when nothing enumerates (an empty result means no
     * stats reply landed, so the caller writes no file rather than an empty one).
     */
    static byte @Nullable [] statsJson(StatisticsManager counter) {
        JsonObject stats = new JsonObject();
        boolean any = false;
        for (StatBase stat : StatList.ALL_STATS) {
            int count = counter.readStat(stat);
            if (count > 0) {
                stats.addProperty(stat.statId, count);
                any = true;
            }
        }
        return any ? gson.toJson(stats).getBytes(StandardCharsets.UTF_8) : null;
    }
}
