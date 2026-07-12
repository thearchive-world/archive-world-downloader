// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
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
            public void onUpdateAdvancementProgress(AdvancementNode node, AdvancementProgress progress) {
                byId.put(node.holder().id().toString(), progress);
            }

            @Override
            public void onSelectedTabChanged(@Nullable AdvancementHolder holder) {}

            @Override
            public void onAddAdvancementRoot(AdvancementNode node) {}

            @Override
            public void onRemoveAdvancementRoot(AdvancementNode node) {}

            @Override
            public void onAddAdvancementTask(AdvancementNode node) {}

            @Override
            public void onRemoveAdvancementTask(AdvancementNode node) {}

            @Override
            public void onAdvancementsCleared() {}
        });
        advancements.setListener(null);
        return byId;
    }
}
