// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import net.minecraft.stats.StatBase;
import net.minecraft.stats.StatList;
import net.minecraft.stats.StatisticsManager;
import org.jspecify.annotations.Nullable;

/**
 * Pure JSON builder for the player's statistics surface: data in, detached JSON bytes out, no {@code Minecraft} or live
 * client state, so the logic is headless-testable. The shape is hand-built (the vanilla serializer,
 * {@code StatisticsManagerServer.dumpJson}, is server-only). It carries no {@code DataVersion} stamp at this era; that
 * is a 1.13+ addition. There is no advancement builder beside it: advancements are a 1.12 addition and this band's
 * achievements ride the statistics surface as ordinary {@code achievement.*} keys.
 */
final class PlayerProgressSerializer {
    private static final Gson gson = new Gson();

    private PlayerProgressSerializer() {}

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
