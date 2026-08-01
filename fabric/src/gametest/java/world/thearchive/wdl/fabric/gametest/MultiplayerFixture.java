// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestClientWorldContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

/**
 * The shared dedicated-server fixture every capture axis runs against. It stands up an in-process dedicated server and
 * connects the client to it over a real connection, so {@code
 * Minecraft.isLocalServer()} is false and the production {@code !isLocalServer()} activation gate holds, the path the
 * mod actually runs. A singleplayer integrated server connects over an in-VM memory connection
 * ({@code isLocalServer() == true}) and so could only be reached by skipping that gate; the world downloader's
 * correctness target is a remote server, and the inbound entity Netty tee taps a real channel only here.
 *
 * <p>An {@link AutoCloseable} handle for a try-with-resources: {@link #close()} disconnects the client and then stops
 * the server (in that order). World setup is driven through {@link #server()} as server console commands the client
 * receives over the wire, the same shape the capture observes in production. The player is put in creative so the
 * open-drive interactions and command setup are unrestricted; the capture is gamemode-blind.
 */
@SuppressWarnings("UnstableApiUsage")
final class MultiplayerFixture implements AutoCloseable {
    private final TestDedicatedServerContext server;
    private final TestServerConnection connection;

    private MultiplayerFixture(TestDedicatedServerContext server, TestServerConnection connection) {
        this.server = server;
        this.connection = connection;
    }

    /**
     * Start the dedicated server, connect the client over a real connection, and wait until the world has downloaded
     * and the player has spawned. The harness writes a flat, consistent fixture world and a server.properties with
     * {@code online-mode=false} (localhost authentication) and {@code max-players=1}, so the scenario is deterministic
     * and isolated.
     */
    static MultiplayerFixture connect(ClientGameTestContext context) {
        TestDedicatedServerContext server = context.worldBuilder().createServer();
        TestServerConnection connection = server.connect();
        connection.getClientWorld().waitForChunksDownload();
        context.waitFor(client -> client.player != null && client.level != null);
        server.runCommand("gamemode creative @a");
        return new MultiplayerFixture(server, connection);
    }

    /**
     * As {@link #connect}, but writes {@code entity-broadcast-range-percentage} into the fixture's server.properties so
     * a test can drive the real, server-scaled entity send range instead of the unscaled default.
     */
    static MultiplayerFixture connectWithEntityRange(ClientGameTestContext context, int broadcastPercentage) {
        Properties properties = new Properties();
        properties.setProperty("entity-broadcast-range-percentage", Integer.toString(broadcastPercentage));
        TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
        TestServerConnection connection = server.connect();
        connection.getClientWorld().waitForChunksDownload();
        context.waitFor(client -> client.player != null && client.level != null);
        server.runCommand("gamemode creative @a");
        return new MultiplayerFixture(server, connection);
    }

    /** The server handle for console commands and server-thread actions (setblock, summon, item replace). */
    TestServerContext server() {
        return server;
    }

    /** The client world handle, for re-waiting on chunk download after the player moves to a new area. */
    TestClientWorldContext clientWorld() {
        return connection.getClientWorld();
    }

    @Override
    public void close() {
        connection.close(); // disconnect the client first
        server.close(); // then stop the dedicated server
    }
}
