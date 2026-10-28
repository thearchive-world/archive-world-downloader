// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

import java.net.URI;
import java.util.Map;

interface Transport {
    Result fetch(URI uri, Map<String, String> headers);

    record Result(int status, String body) {
        public static final Result FAILURE = new Result(-1, "");
    }
}
