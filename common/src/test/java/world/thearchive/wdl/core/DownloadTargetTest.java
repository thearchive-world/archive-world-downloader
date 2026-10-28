// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DownloadTargetTest {
    @Test
    void threeArgumentConstructorDefaultsToScreenOrigin() {
        DownloadTarget target = new DownloadTarget("base", "base", DownloadMode.NEW);

        assertEquals(DownloadTarget.Origin.SCREEN, target.origin());
    }

    @Test
    void withOriginReTagsTheCopyAndKeepsTheResolvedFields() {
        DownloadTarget screen = new DownloadTarget("base", null, DownloadMode.RESUME);
        DownloadTarget flow = screen.withOrigin(DownloadTarget.Origin.FLOW_AUTO);

        assertEquals(DownloadTarget.Origin.FLOW_AUTO, flow.origin());
        assertEquals("base", flow.folderName());
        assertNull(flow.worldName());
        assertEquals(DownloadMode.RESUME, flow.mode());
        assertEquals(DownloadTarget.Origin.SCREEN, screen.origin(), "the wither copies, never mutates");
    }

    @Test
    void refusalChannelIsTheToastOnlyForTheScreen() {
        assertTrue(DownloadTarget.refusalUsesToast(DownloadTarget.Origin.SCREEN));
        assertFalse(DownloadTarget.refusalUsesToast(DownloadTarget.Origin.FLOW_DELIBERATE));
        assertFalse(DownloadTarget.refusalUsesToast(DownloadTarget.Origin.FLOW_AUTO));
    }
}
