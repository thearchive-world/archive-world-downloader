// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

import java.net.URI;
import java.util.Map;

interface Transport {
    Result fetch(URI uri, Map<String, String> headers);

    final class Result {
        public static final Result FAILURE = new Result(-1, "");

        private final int status;
        private final String body;

        Result(int status, String body) {
            this.status = status;
            this.body = body;
        }

        int status() {
            return status;
        }

        String body() {
            return body;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Result)) {
                return false;
            }
            Result other = (Result) o;
            return status == other.status && body.equals(other.body);
        }

        @Override
        public int hashCode() {
            return 31 * Integer.hashCode(status) + body.hashCode();
        }
    }
}
