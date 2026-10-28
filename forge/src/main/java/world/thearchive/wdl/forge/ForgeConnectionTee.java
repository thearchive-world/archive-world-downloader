// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

import world.thearchive.wdl.adapter.ConnectionTee;

final class ForgeConnectionTee extends ConnectionTee {
    private ForgeConnectionTee() {}

    static void install(Connection connection) {
        new ForgeConnectionTee().installInto(connection);
    }

    @Override
    protected Channel channel(Connection connection) {
        return connection.channel;
    }

    @Override
    protected int entityId(ClientboundMoveEntityPacket move) {
        return move.entityId;
    }
}
