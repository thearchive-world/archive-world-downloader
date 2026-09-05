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
 * The deterministic guard for the report's source-MOTD read. The read sits inside the world-open step, whose failure is
 * permanent for the session, so an absent MOTD losing the whole download is what this pins against.
 */
class ReportSourceMotdTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void anUnpingedServerReportsAnEmptyMotd() {
        ServerData server = new ServerData("a server", "example.test", ServerData.Type.OTHER);

        assertEquals("", LiveCaptureSession.sourceMotd(server),
                "a server the client never learned a MOTD for reports an empty one rather than throwing");
    }

    @Test
    void aKnownMotdIsReportedVerbatim() {
        ServerData server = new ServerData("a server", "example.test", ServerData.Type.OTHER);
        server.motd = Component.literal("a friendly greeting");

        assertEquals("a friendly greeting", LiveCaptureSession.sourceMotd(server),
                "a server that supplied a MOTD reports it unchanged");
    }
}
