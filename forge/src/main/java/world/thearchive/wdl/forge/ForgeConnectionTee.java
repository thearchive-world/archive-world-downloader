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
 * The Forge connection tee: {@link ConnectionTee}'s shared Netty plumbing plus the non-public reads it needs. The
 * connection's channel and the relative-move packet's entity id are non-public with no getter. This band has no
 * compile-time access-widening step (there is no Architectury Loom or Mojmap compile view), so unlike the Fabric
 * access widener and the higher-band NeoForge transformer, the shipped accesstransformer.cfg is a runtime-only FMLAT
 * and cannot be read as a direct field access at compile. The two fields are read reflectively instead, resolving each
 * once by its dev (MCP) name with its production (searge) name as the fallback; the position deltas have public
 * getters at this band and are read through those.
 */
final class ForgeConnectionTee extends ConnectionTee {
    private static final Field CHANNEL_FIELD =
            ReflectionHelper.findField(NetworkManager.class, "channel", "field_150746_k");
    private static final Field ENTITY_ID_FIELD =
            ReflectionHelper.findField(SPacketEntity.class, "entityId", "field_149074_a");

    private ForgeConnectionTee() {}

    /** Insert the tee into the play connection, once. */
    static void install(NetworkManager connection) {
        new ForgeConnectionTee().installInto(connection);
    }

    @Override
    protected Channel channel(NetworkManager connection) {
        try {
            return (Channel) CHANNEL_FIELD.get(connection);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("could not read the connection channel", e);
        }
    }

    @Override
    protected int entityId(SPacketEntity move) {
        try {
            return (Integer) ENTITY_ID_FIELD.get(move);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("could not read the relative-move entity id", e);
        }
    }

    // The move-delta fields are int at this band, exposed by the public getX/getY/getZ getters, and the shared
    // contract narrows them to short. An inbound relative-move packet reads each delta with PacketBuffer.readShort
    // (a sign-extended short), so the int always holds a value in short range and the narrowing is exact.
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
