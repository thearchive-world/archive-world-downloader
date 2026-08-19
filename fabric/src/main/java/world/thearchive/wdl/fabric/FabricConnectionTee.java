// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

import world.thearchive.wdl.adapter.ConnectionTee;

/**
 * The Fabric connection tee: {@link ConnectionTee}'s shared Netty plumbing plus the two non-public reads it needs, made
 * reachable by the Fabric access widener (the connection's channel and the relative-move packet's entity id).
 */
final class FabricConnectionTee extends ConnectionTee {
    private FabricConnectionTee() {}

    /** Insert the tee into the play connection, once. Called from the play-join hook. */
    static void install(Connection connection) {
        new FabricConnectionTee().installInto(connection);
    }

    @Override
    protected Channel channel(Connection connection) {
        return connection.channel; // widened in the access widener
    }

    @Override
    protected int entityId(ClientboundMoveEntityPacket move) {
        return move.entityId; // widened in the access widener
    }

    @Override
    protected short moveDeltaX(ClientboundMoveEntityPacket move) {
        return move.xa; // widened in the access widener
    }

    @Override
    protected short moveDeltaY(ClientboundMoveEntityPacket move) {
        return move.ya; // widened in the access widener
    }

    @Override
    protected short moveDeltaZ(ClientboundMoveEntityPacket move) {
        return move.za; // widened in the access widener
    }
}
