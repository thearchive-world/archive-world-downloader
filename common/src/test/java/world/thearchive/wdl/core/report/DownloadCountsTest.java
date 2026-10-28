// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DownloadCountsTest {
    private static final UUID UUID_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID UUID_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Test
    void entitiesDedupByUuid() {
        DownloadCountsBuilder builder = new DownloadCountsBuilder();
        builder.addEntity(UUID_A);
        builder.addEntity(UUID_A);
        builder.addEntity(UUID_B);

        assertEquals(2, builder.entityCount());
    }

    @Test
    void containersCountDoubleChestAsOne() {
        DownloadCountsBuilder builder = new DownloadCountsBuilder();
        builder.addContainer("double:10,64,10");
        builder.addContainer("double:10,64,10");
        builder.addContainer("single:20,64,20");

        assertEquals(2, builder.containerCount());
    }

    @Test
    void liveAccessorsReadTheDedupCorrectRunningCount() {
        DownloadCountsBuilder builder = new DownloadCountsBuilder();
        builder.addContainer("double:10,64,10");
        builder.addContainer("double:10,64,10");
        builder.addContainer("single:20,64,20");
        builder.addEntity(UUID_A);
        builder.addEntity(UUID_A);
        builder.addEntity(UUID_B);

        assertEquals(2, builder.containerCount(), "live container count is dedup-correct, no snapshot needed");
        assertEquals(2, builder.entityCount(), "live entity count is dedup-correct, no snapshot needed");
    }

    @Test
    void emptyBuilderReadsZero() {
        DownloadCountsBuilder builder = new DownloadCountsBuilder();

        assertEquals(0, builder.entityCount());
        assertEquals(0, builder.containerCount());
    }
}
