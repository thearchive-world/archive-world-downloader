// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

import world.thearchive.wdl.adapter.ConnectionTee;

/**
 * The Forge connection tee: {@link ConnectionTee}'s shared Netty plumbing plus the two non-public reads it
 * needs, made reachable by the Forge access transformer (the connection's channel and the relative-move
 * packet's entity id).
 */
final class ForgeConnectionTee extends ConnectionTee {
    private ForgeConnectionTee() {}

    /** Insert the tee into the play connection, once. Called from the play-join hook. */
    static void install(Connection connection) {
        new ForgeConnectionTee().installInto(connection);
    }

    @Override
    protected Channel channel(Connection connection) {
        return connection.channel; // access-transformed: net.minecraft.network.Connection channel
    }

    @Override
    protected int entityId(ClientboundMoveEntityPacket move) {
        return move.entityId; // access-transformed
    }
}
