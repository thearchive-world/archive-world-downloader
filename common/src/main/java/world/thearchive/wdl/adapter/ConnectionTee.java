// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import org.slf4j.Logger;

/**
 * Runs on the Netty event-loop thread (the world state is applied later, on the main thread), so it treats each packet
 * as the source of truth at tee time and reads only the immutable, fully-materialized packet value objects. It
 * therefore publishes to the main thread rather than calling into capture state directly.
 */
public abstract class ConnectionTee extends ChannelDuplexHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAME = "wdl_connection_tee";

    private static volatile boolean transferSignal;

    static void signalTransfer() {
        transferSignal = true;
    }

    /**
     * Main side: consume the transfer signal; also cleared at capture start so a signal raised between downloads (any
     * download kind, entity capture on or off) never kills the next one's first tick.
     */
    public static boolean consumeTransferSignal() {
        if (!transferSignal) {
            return false;
        }
        transferSignal = false;
        return true;
    }

    /** Capture start: discard any signal raised while no download was running. */
    public static void clearTransferSignal() {
        transferSignal = false;
    }

    protected abstract Channel channel(Connection connection);

    protected abstract int entityId(ClientboundMoveEntityPacket move);

    /**
     * Insert this tee into {@code connection}'s pipeline before {@code "packet_handler"}, once.
     */
    protected final void installInto(Connection connection) {
        try {
            ChannelPipeline pipeline = channel(connection).pipeline();
            if (pipeline.get("packet_handler") == null || pipeline.get(NAME) != null) {
                return;
            }
            pipeline.addBefore("packet_handler", NAME, this);
            LOGGER.info("connection tee installed on the play connection");
        } catch (RuntimeException e) {
            LOGGER.warn("could not install the connection tee", e);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        EntityPacketCapture capture = EntityPacketCapture.active();
        if (capture != null && message instanceof Packet<?> packet) {
            try {
                route(packet, capture);
            } catch (RuntimeException e) {
                LOGGER.warn("connection tee inspection failed (the packet is still delivered)", e);
            }
        }
        context.fireChannelRead(message);
    }

    /**
     * The packet names the PLAYER, not the vehicle ({@code ServerboundPlayerCommandPacket} is constructed from the
     * sending entity), and this runs on the Netty event loop where no world state may be read, so it only raises a
     * signal and {@link OpenClickTracker} stamps the vehicle on the main thread.
     */
    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        if (message instanceof ServerboundPlayerCommandPacket command
                && command.getAction() == ServerboundPlayerCommandPacket.Action.OPEN_INVENTORY) {
            OpenClickTracker.signalOpenInventoryRequest();
        }
        super.write(context, message, promise);
    }

    private void route(Packet<?> packet, EntityPacketCapture capture) {
        // The inbound bundler packs a delimited run of packets into one ClientboundBundlePacket before
        // packet_handler; the per-packet split happens later, at the listener (handleBundlePacket). Entity spawn
        // pairing data is bundled, so unwrap one level or the AddEntity and friends sit inside a wrapper (bundles
        // do not nest, so one level is enough).
        if (packet instanceof ClientboundBundlePacket bundle) {
            // Pass 1: collect every id any SetPassengers sub-packet names, vehicle or passenger slot. The
            // pairing bundle of a ridden vehicle self-contains its SetPassengers, so this one set feeds
            // both the arrival commit rule and the ridden bit with no bundle-end callback or buffer.
            IntSet named = new IntOpenHashSet();
            for (Packet<?> sub : bundle.subPackets()) {
                if (sub instanceof ClientboundSetPassengersPacket passengers) {
                    named.add(passengers.getVehicle());
                    for (int passenger : passengers.getPassengers()) {
                        named.add(passenger);
                    }
                }
            }
            for (Packet<?> sub : bundle.subPackets()) {
                try {
                    routeOne(sub, capture, named);
                } catch (RuntimeException e) {
                    // Per-sub-packet isolation: one throwing sub-packet must not drop the rest of the
                    // bundle. Bundles carry the spawn pairing data, so a dropped spawn would silently lose the
                    // entity and every later packet for its id; the rest of the bundle is still routed.
                    LOGGER.warn("connection tee: a bundled sub-packet failed (rest of bundle still routed)", e);
                }
            }
        } else {
            routeOne(packet, capture, IntSets.EMPTY_SET);
        }
    }

    private void routeOne(Packet<?> packet, EntityPacketCapture capture, IntSet bundleNamedIds) {
        if (packet instanceof ClientboundMoveEntityPacket move) {
            // A relative move's entity id has no public getter, so the capture cannot read it.
            capture.onMove(entityId(move), move);
        } else {
            capture.accept(packet, bundleNamedIds);
        }
    }
}
