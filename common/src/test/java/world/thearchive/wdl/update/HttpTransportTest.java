// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The production transport's failure mapping and its bounded-body guard. */
class HttpTransportTest {
    @Test
    void mapsConnectionFailuresToTheFailureSentinel() {
        // Port 1 on loopback refuses immediately, so the transport sees a connect failure with no network.
        Transport.Result result = new HttpTransport().fetch(URI.create("http://127.0.0.1:1/"), ImmutableMap.of());

        assertEquals(Transport.Result.FAILURE, result);
    }

    @Test
    void aBodyWithinTheCapIsReturnedVerbatim() throws IOException {
        assertEquals("hello", HttpTransport.readCapped(
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), 16));
    }

    @Test
    void anOversizedBodyFailsInsteadOfBuffering() {
        assertThrows(IOException.class, () -> HttpTransport.readCapped(
                new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5 }), 4));
    }

    @Test
    void anOversizedBodyStopsReadingInsteadOfDraining() {
        ChunkedStream stream = new ChunkedStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 2);
        assertThrows(IOException.class, () -> HttpTransport.readCapped(stream, 4));
        assertTrue(stream.remaining() > 0, "overflow stops pulling further bytes rather than draining the source");
    }

    @Test
    void bytesAreSummedAcrossReadsNotCheckedPerRead() {
        // Three-byte reads each stay under the six-byte cap, but their running total crosses it.
        assertThrows(IOException.class, () -> HttpTransport.readCapped(
                new ChunkedStream(new byte[] { 1, 2, 3, 4, 5, 6, 7 }, 3), 6));
    }

    /** An input stream that hands back at most {@code chunk} bytes per read, so cross-read summing is exercised. */
    private static final class ChunkedStream extends InputStream {
        private final byte[] data;
        private final int chunk;
        private int pos;

        ChunkedStream(byte[] data, int chunk) {
            this.data = data;
            this.chunk = chunk;
        }

        int remaining() {
            return this.data.length - this.pos;
        }

        @Override
        public int read() {
            return this.pos < this.data.length ? this.data[this.pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            if (this.pos >= this.data.length) {
                return -1;
            }
            int count = Math.min(Math.min(length, this.chunk), this.data.length - this.pos);
            System.arraycopy(this.data, this.pos, destination, offset, count);
            this.pos += count;
            return count;
        }
    }
}
