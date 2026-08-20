// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

import world.thearchive.wdl.adapter.ConnectionTee;

/**
 * The Forge connection tee: {@link ConnectionTee}'s shared Netty plumbing plus the non-public reads it needs. The
 * connection's channel and the relative-move packet's entity id are non-public with no getter, so the Forge access
 * transformer widens them; the position deltas have public getters at this band and are read through those.
 */
final class ForgeConnectionTee extends ConnectionTee {
    private ForgeConnectionTee() {}

    /** Insert the tee into the play connection, once. */
    static void install(Connection connection) {
        new ForgeConnectionTee().installInto(connection);
    }

    @Override
    protected Channel channel(Connection connection) {
        return connection.channel; // access-transformed: net.minecraft.network.Connection channel
    }

    @Override
    protected int entityId(ClientboundMoveEntityPacket move) {
        return move.entityId; // access-transformed: no public getter for the entity id at this band
    }

    // The move-delta fields are int at this band, exposed by public getters, and the shared contract narrows them to
    // short. An inbound relative-move packet reads each delta with FriendlyByteBuf.readShort (a sign-extended short),
    // so the int always holds a value in short range and the narrowing is exact.
    @Override
    protected short moveDeltaX(ClientboundMoveEntityPacket move) {
        return (short) move.getXa();
    }

    @Override
    protected short moveDeltaY(ClientboundMoveEntityPacket move) {
        return (short) move.getYa();
    }

    @Override
    protected short moveDeltaZ(ClientboundMoveEntityPacket move) {
        return (short) move.getZa();
    }
}
