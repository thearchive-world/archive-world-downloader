// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

import java.net.URI;
import java.util.Map;

/**
 * The injectable HTTP seam of the update check: one GET, one reply, and never a throw to the caller, so every network
 * failure mode is headlessly testable through a fake.
 */
interface Transport {
    Result fetch(URI uri, Map<String, String> headers);

    /** An HTTP reply, or {@link #FAILURE} when the call threw instead of replying. */
    record Result(int status, String body) {
        public static final Result FAILURE = new Result(-1, "");
    }
}
