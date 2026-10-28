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

final class PlayerProgressSerializer {
    private static final Gson gson = new Gson();

    private PlayerProgressSerializer() {}

    /**
     * Returns {@code null} when nothing enumerates, so the caller writes no file rather than an empty one.
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
