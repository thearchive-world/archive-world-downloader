// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import io.netty.channel.Channel;
import java.lang.reflect.Field;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketEntity;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import world.thearchive.wdl.adapter.ConnectionTee;

/**
 * The entity id field is read reflectively, resolving once by its dev (MCP) name with its production (searge) name.
 */
final class ForgeConnectionTee extends ConnectionTee {
    private static final Field ENTITY_ID_FIELD =
            ReflectionHelper.findField(SPacketEntity.class, "entityId", "field_149074_a");

    private ForgeConnectionTee() {}

    static void install(NetworkManager connection) {
        new ForgeConnectionTee().installInto(connection);
    }

    @Override
    protected Channel channel(NetworkManager connection) {
        return connection.channel();
    }

    @Override
    protected int entityId(SPacketEntity move) {
        try {
            return (Integer) ENTITY_ID_FIELD.get(move);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("could not read the relative-move entity id", e);
        }
    }

    // An inbound relative-move packet reads each delta with PacketBuffer.readShort (a sign-extended short), so the int
    // always holds a value in short range and the narrowing is exact.
    @Override
    protected short moveDeltaX(SPacketEntity move) {
        return (short) move.getX();
    }

    @Override
    protected short moveDeltaY(SPacketEntity move) {
        return (short) move.getY();
    }

    @Override
    protected short moveDeltaZ(SPacketEntity move) {
        return (short) move.getZ();
    }
}
