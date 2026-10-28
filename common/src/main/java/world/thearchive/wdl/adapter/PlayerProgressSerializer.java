// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.Registry;
import net.minecraft.stats.StatType;
import net.minecraft.stats.StatsCounter;
import org.jspecify.annotations.Nullable;

final class PlayerProgressSerializer {
    private static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(AdvancementProgress.class, new AdvancementProgress.Serializer())
            .create();

    private PlayerProgressSerializer() {}

    static byte[] advancementsJson(Map<String, AdvancementProgress> progressById, int dataVersion) {
        JsonObject root = new JsonObject();
        progressById.forEach((id, progress) -> root.add(id, gson.toJsonTree(progress)));
        root.addProperty("DataVersion", dataVersion);
        return gson.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns {@code null} when nothing enumerates, so the caller writes no file rather than an empty one.
     */
    static byte @Nullable [] statsJson(StatsCounter counter, int dataVersion) {
        JsonObject stats = new JsonObject();
        boolean any = false;
        for (StatType<?> type : Registry.STAT_TYPE) {
            any |= collectStatType(type, counter, stats);
        }
        if (!any) {
            return null;
        }
        JsonObject root = new JsonObject();
        root.add("stats", stats);
        root.addProperty("DataVersion", dataVersion);
        return gson.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    // getKey returns non-null for elements obtained by iterating the same registry
    @SuppressWarnings("NullAway")
    private static <T> boolean collectStatType(StatType<T> type, StatsCounter counter, JsonObject stats) {
        String typeId = Registry.STAT_TYPE.getKey(type).toString();
        Registry<T> registry = type.getRegistry();
        boolean any = false;
        for (T value : registry) {
            // getValue(type, value) is contains()-guarded, so it never force-creates a Stat in the shared
            // StatType map (which a concurrent stats-reply decode on the network thread also mutates).
            int count = counter.getValue(type, value);
            if (count > 0) {
                JsonObject typeObject = stats.getAsJsonObject(typeId);
                if (typeObject == null) {
                    typeObject = new JsonObject();
                    stats.add(typeId, typeObject);
                }
                typeObject.addProperty(registry.getKey(value).toString(), count);
                any = true;
            }
        }
        return any;
    }
}
