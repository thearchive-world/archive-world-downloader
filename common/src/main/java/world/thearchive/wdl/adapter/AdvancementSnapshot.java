// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.multiplayer.ClientAdvancements;
import org.jspecify.annotations.Nullable;

/**
 * Snapshots the client's advancement progress into an id-string keyed map. The progress map inside
 * {@link ClientAdvancements} is private; {@link ClientAdvancements#setListener} is the only public enumeration path and
 * synchronously replays every stored entry to the listener, so we install a harvesting listener, copy what it replays,
 * then clear it (restoring the no-listener state the advancements screen expects when closed). The id is taken as a
 * {@code String} so the band-renamed id type ({@code ResourceLocation} vs {@code Identifier}) never appears here.
 */
final class AdvancementSnapshot {
    private AdvancementSnapshot() {}

    static Map<String, AdvancementProgress> byId(ClientAdvancements advancements) {
        Map<String, AdvancementProgress> byId = new HashMap<>();
        advancements.setListener(new ClientAdvancements.Listener() {
            @Override
            public void onUpdateAdvancementProgress(Advancement advancement, AdvancementProgress progress) {
                byId.put(advancement.getId().toString(), progress);
            }

            @Override
            public void onSelectedTabChanged(@Nullable Advancement advancement) {}

            @Override
            public void onAddAdvancementRoot(Advancement advancement) {}

            @Override
            public void onRemoveAdvancementRoot(Advancement advancement) {}

            @Override
            public void onAddAdvancementTask(Advancement advancement) {}

            @Override
            public void onRemoveAdvancementTask(Advancement advancement) {}

            @Override
            public void onAdvancementsCleared() {}
        });
        advancements.setListener(null);
        return byId;
    }
}
