// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The deterministic guard for the report's source-MOTD read. An absent MOTD is the ordinary case on a direct connect to
 * a server that sends no status packet, so what this pins is that the report keeps a real source rather than degrading
 * to an unidentified one.
 */
class ReportSourceMotdTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void anUnpingedServerReportsAnEmptyMotd() {
        ServerData server = new ServerData("a server", "example.test", false);

        assertEquals("", LiveCaptureSession.sourceMotd(server),
                "a server the client never learned a MOTD for reports an empty one rather than throwing");
    }

    @Test
    void aKnownMotdIsReportedVerbatim() {
        ServerData server = new ServerData("a server", "example.test", false);
        server.motd = Component.literal("a friendly greeting");

        assertEquals("a friendly greeting", LiveCaptureSession.sourceMotd(server),
                "a server that supplied a MOTD reports it unchanged");
    }
}
