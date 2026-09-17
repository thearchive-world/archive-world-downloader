// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.multiplayer.ClientAdvancements;

/**
 * Snapshots the client's advancement progress into an id-string keyed map, copied out of
 * {@link ClientAdvancements#progress()}. The id is taken as a {@code String} so the band-renamed id type
 * ({@code ResourceLocation} vs {@code Identifier}) never appears here.
 */
final class AdvancementSnapshot {
    private AdvancementSnapshot() {}

    static Map<String, AdvancementProgress> byId(ClientAdvancements advancements) {
        Map<String, AdvancementProgress> byId = new HashMap<>();
        for (Map.Entry<AdvancementHolder, AdvancementProgress> entry : advancements.progress().entrySet()) {
            byId.put(entry.getKey().id().toString(), entry.getValue());
        }
        return byId;
    }
}
