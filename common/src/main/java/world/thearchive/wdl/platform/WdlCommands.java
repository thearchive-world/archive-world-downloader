// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import java.util.function.Consumer;

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

    public Consumer<String> startNamed() {
        return startNamed;
    }

    public Runnable openDownloads() {
        return openDownloads;
    }

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
