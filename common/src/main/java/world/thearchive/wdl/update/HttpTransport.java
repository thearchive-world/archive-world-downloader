// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The production {@link Transport}: short timeouts, no redirects, a size-capped body, and every exception mapped to the
 * failure sentinel so a dead network can never surface past the seam.
 */
public final class HttpTransport implements Transport {
    /** The connect and read timeout in milliseconds; a stalled endpoint fails rather than hanging the check thread. */
    private static final int TIMEOUT_MS = 3000;
    /** The update check reads a short version list; a body past this is a hostile endpoint, not a real reply. */
    private static final int MAX_BODY_BYTES = 1 << 20;

    @Override
    public Result fetch(URI uri, Map<String, String> headers) {
        HttpURLConnection connection = null;
        try {
            URL url = uri.toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            int status = connection.getResponseCode();
            return new Result(status, readBody(connection, status));
        } catch (IOException | RuntimeException e) {
            return Result.FAILURE;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Reads the reply body as UTF-8, capped at {@link #MAX_BODY_BYTES}. The error stream is read on a failure status so
     * a non-2xx body is not lost to the status split; a missing stream reads as an empty body.
     */
    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            return readCapped(in, MAX_BODY_BYTES);
        }
    }

    /**
     * Reads {@code in} into a UTF-8 string, stopping and throwing once more than {@code capacity} bytes have been read,
     * so the total is bounded across reads rather than trusting any single one; the read stops at the cap instead of
     * draining the rest of the stream.
     */
    static String readCapped(InputStream in, int capacity) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > capacity) {
                throw new IOException("update-check body exceeds " + capacity + " bytes");
            }
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
