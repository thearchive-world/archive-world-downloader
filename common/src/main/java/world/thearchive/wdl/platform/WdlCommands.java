// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import java.util.function.Consumer;

/**
 * Loader-agnostic {@code /wdl} actions. Each loader builds its own Brigadier tree (the command-source type is
 * loader-specific) dispatching to these callbacks on the client main thread, so the bridge stays free of both
 * {@code net.minecraft.*} and Brigadier types. A plain final class (not a record) so the platform SPI stays portable to
 * the Java-8 bands.
 */
public final class WdlCommands {
    private final Runnable start;
    private final Consumer<String> startNamed;
    private final Runnable stop;
    private final Runnable status;
    private final Runnable config;
    private final Consumer<String> resume;
    private final Runnable openDownloads;

    public WdlCommands(Runnable start, Consumer<String> startNamed, Runnable stop, Runnable status,
            Runnable config, Consumer<String> resume, Runnable openDownloads) {
        this.start = start;
        this.startNamed = startNamed;
        this.stop = stop;
        this.status = status;
        this.config = config;
        this.resume = resume;
        this.openDownloads = openDownloads;
    }

    public Runnable start() {
        return start;
    }

    /** {@code /wdl start <name>}: start a NEW download of that name, refusing an unusable or existing name. */
    public Consumer<String> startNamed() {
        return startNamed;
    }

    /** {@code /wdl downloads}: open the download screen (start / resume / browse). */
    public Runnable openDownloads() {
        return openDownloads;
    }

    /** {@code /wdl resume <name>}: validate a wdl-managed folder, then confirm-then-resume into it. */
    public Consumer<String> resume() {
        return resume;
    }

    public Runnable stop() {
        return stop;
    }

    public Runnable status() {
        return status;
    }

    public Runnable config() {
        return config;
    }
}
