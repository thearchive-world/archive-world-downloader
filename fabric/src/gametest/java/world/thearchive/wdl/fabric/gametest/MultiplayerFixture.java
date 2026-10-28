// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

/**
 * It stands up an in-process dedicated server and connects the client to it over a real connection, so
 * {@code Minecraft.isLocalServer()} is false and the production {@code !isLocalServer()} activation gate holds, the
 * path the mod actually runs.
 */
@SuppressWarnings("UnstableApiUsage")
final class MultiplayerFixture implements AutoCloseable {
    private final TestDedicatedServerContext server;
    private final TestDedicatedServerConnection connection;

    private MultiplayerFixture(TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
        this.server = server;
        this.connection = connection;
    }

    static MultiplayerFixture connect(ClientGameTestContext context) {
        TestDedicatedServerContext server = context.worldBuilder().createServer();
        TestDedicatedServerConnection connection = server.connect();
        connection.waitForChunksDownload();
        context.waitFor(client -> client.player != null && client.level != null);
        server.runCommand("gamemode creative @a");
        return new MultiplayerFixture(server, connection);
    }

    static MultiplayerFixture connectWithEntityRange(ClientGameTestContext context, int broadcastPercentage) {
        Properties properties = new Properties();
        properties.setProperty("entity-broadcast-range-percentage", Integer.toString(broadcastPercentage));
        TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
        TestDedicatedServerConnection connection = server.connect();
        connection.waitForChunksDownload();
        context.waitFor(client -> client.player != null && client.level != null);
        server.runCommand("gamemode creative @a");
        return new MultiplayerFixture(server, connection);
    }

    TestServerContext server() {
        return server;
    }

    void waitForChunksDownload() {
        connection.waitForChunksDownload();
    }

    @Override
    public void close() {
        connection.close();
        server.close();
    }
}
