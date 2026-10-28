// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.multiplayer.ClientAdvancementManager;
import org.jspecify.annotations.Nullable;

/**
 * The progress map inside {@link ClientAdvancementManager} is private; {@link ClientAdvancementManager#setListener} is
 * the only public enumeration path and synchronously replays every stored entry to the listener.
 */
final class AdvancementSnapshot {
    private AdvancementSnapshot() {}

    static Map<String, AdvancementProgress> byId(ClientAdvancementManager advancements) {
        Map<String, AdvancementProgress> byId = new HashMap<>();
        advancements.setListener(new ClientAdvancementManager.IListener() {
            @Override
            public void onUpdateAdvancementProgress(Advancement advancement, AdvancementProgress progress) {
                byId.put(advancement.getId().toString(), progress);
            }

            @Override
            public void setSelectedTab(@Nullable Advancement advancement) {}

            @Override
            public void rootAdvancementAdded(Advancement advancement) {}

            @Override
            public void rootAdvancementRemoved(Advancement advancement) {}

            @Override
            public void nonRootAdvancementAdded(Advancement advancement) {}

            @Override
            public void nonRootAdvancementRemoved(Advancement advancement) {}

            @Override
            public void advancementsCleared() {}
        });
        advancements.setListener(null);
        return byId;
    }
}
