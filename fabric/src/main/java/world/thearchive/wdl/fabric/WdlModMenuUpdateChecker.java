// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import com.terraformersmc.modmenu.api.UpdateChannel;
import com.terraformersmc.modmenu.api.UpdateChecker;
import com.terraformersmc.modmenu.api.UpdateInfo;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.update.UpdateAvailable;
import world.thearchive.wdl.update.UpdateCheck;

/**
 * Feeds the mod's launch-scoped update result to ModMenu's mod-list badge. ModMenu calls this once at client init on
 * its own worker thread and caches the result for the session (it does not re-query on opening the Mods list), so this
 * blocks on {@link UpdateCheck#awaitSettled(long)} rather than returning an early null. Reusing the launch check means
 * no extra network request. Returns null (no badge) when no newer release is held.
 */
final class WdlModMenuUpdateChecker implements UpdateChecker {
    private static final long AWAIT_MILLIS = 10_000L;

    @Override
    public @Nullable UpdateInfo checkForUpdates() {
        UpdateCheck check = Wdl.updateCheck();
        check.awaitSettled(AWAIT_MILLIS);
        Optional<UpdateAvailable> available = check.available();
        if (!available.isPresent()) {
            return null;
        }
        UpdateAvailable update = available.get();
        return new UpdateInfo() {
            @Override
            public boolean isUpdateAvailable() {
                return true;
            }

            @Override
            public Component getUpdateMessage() {
                return Component.translatable("wdl.screen.downloads.update_available",
                        update.runningDisplay(), update.latestDisplay());
            }

            @Override
            public String getDownloadLink() {
                return UpdateCheck.MODRINTH_PAGE_URL;
            }

            @Override
            public UpdateChannel getUpdateChannel() {
                return UpdateChannel.RELEASE;
            }
        };
    }
}
