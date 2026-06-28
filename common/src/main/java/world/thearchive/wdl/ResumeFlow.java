// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import java.time.LocalDate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.browse.TargetResolver;
import world.thearchive.wdl.platform.PlatformBridge;

/**
 * The download-start flow behind the toggle keybind: it turns a start name into a {@link DownloadTarget} and hands that
 * to Wdl's terminal start action. Constructed once by {@link Wdl#initialize}, which supplies the bridge it asks about
 * the world, a config supplier read fresh per decision so a hand-edit applies to the next download, and the start
 * action itself.
 */
final class ResumeFlow {
    private final PlatformBridge bridge;
    private final Supplier<WdlConfig> configSupplier;
    private final Consumer<DownloadTarget> startDownload;

    ResumeFlow(PlatformBridge bridge, Supplier<WdlConfig> configSupplier,
            Consumer<DownloadTarget> startDownload) {
        this.bridge = bridge;
        this.configSupplier = configSupplier;
        this.startDownload = startDownload;
    }

    /**
     * The quick-start flow behind the keybind, whose caller passes a concrete default name and whether the start is a
     * deliberate player act, the split that decides the origin tag: the name starts a NEW folder, dated per
     * {@code appendDateSuffix}.
     */
    void begin(String defaultName, boolean deliberate) {
        DownloadTarget target = classifyNewStart(defaultName, deliberate);
        if (target == null) {
            return;
        }
        startDownload.accept(target);
    }

    /**
     * Guard, then resolve {@code name} into a NEW target tagged with the origin its caller reports, or null when the
     * guard rejected it: a world the client is not connected to as a server has nothing to download.
     */
    private @Nullable DownloadTarget classifyNewStart(String name, boolean deliberate) {
        if (!bridge.isRemoteWorld()) {
            return null;
        }
        DownloadTarget target = TargetResolver.resolveNew(name, LocalDate.now(),
                configSupplier.get().appendDateSuffix());
        return target.withOrigin(deliberate ? DownloadTarget.Origin.FLOW_DELIBERATE : DownloadTarget.Origin.FLOW_AUTO);
    }
}
