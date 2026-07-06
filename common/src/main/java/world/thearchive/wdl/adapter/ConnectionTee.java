// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import org.slf4j.Logger;

/**
 * The shared connection packet tee: a Netty handler inserted before the connection's {@code "packet_handler"} so it
 * observes the same decoded packet stream the client does. It feeds the active {@link EntityPacketCapture} the spawn
 * and post-spawn packets it needs to reconstruct every non-player entity independent of unload. It always forwards
 * every packet unchanged in both directions, so it has zero effect on what the client receives or sends.
 *
 * <p>Anchored on {@code "packet_handler"}, which is added once per connection and never renamed or removed, so a single
 * insertion survives the login to configuration to play handler swaps (the inbound {@code "decoder"} /
 * {@code "inbound_config"} and {@code "bundler"} are torn down and recreated repeatedly across those switches,
 * {@code "packet_handler"} is not). Installed unconditionally at play-join: the capture mechanism is on by default, and
 * the tee no-ops whenever no capture is publishing an accumulator.
 *
 * <p>Runs on the Netty event-loop thread (the world state is applied later, on the main thread), so it treats each
 * packet as the source of truth at tee time and reads only the immutable, fully-materialized packet value objects. It
 * therefore publishes to the main thread rather than calling into capture state directly. Two reads are non-public and
 * reachable only through a loader-specific mechanism (a Fabric access widener, a NeoForge access transformer), so each
 * per-loader plug supplies them: the connection's channel and the relative-move packet's entity id.
 */
public abstract class ConnectionTee extends ChannelDuplexHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAME = "wdl_connection_tee";

    private static volatile boolean transferSignal;

    /** Netty side: a configuration-phase re-entry was teed; the next controller tick stops the download. */
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

    /** The connection's Netty channel; the per-loader plug widens or transforms the non-public field. */
    protected abstract Channel channel(Connection connection);

    /** A relative-move packet's entity id; the per-loader plug widens or transforms the non-public field. */
    protected abstract int entityId(ClientboundMoveEntityPacket move);

    /**
     * Insert this tee into {@code connection}'s pipeline before {@code "packet_handler"}, once. Fail-soft: any pipeline
     * surprise is logged, never thrown, so it never disrupts the connection.
     */
    protected final void installInto(Connection connection) {
        try {
            ChannelPipeline pipeline = channel(connection).pipeline();
            if (pipeline.get("packet_handler") == null || pipeline.get(NAME) != null) {
                return; // not a configured play pipeline, or already installed
            }
            pipeline.addBefore("packet_handler", NAME, this);
            LOGGER.info("connection tee installed on the play connection");
        } catch (RuntimeException e) {
            LOGGER.warn("could not install the connection tee", e);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (message instanceof ClientboundStartConfigurationPacket) {
            // A backend transfer: same dimension ids on a different server name a different world, and this
            // download does not follow across servers, so it stops like a disconnect. The stop is not only the
            // folder collision one layer out: the configuration phase rebuilds the connection's RegistryAccess
            // from the new backend, while a session holds the registries it was constructed with and encodes
            // every chunk, entity, player and level.dat through them, so following a transfer would write the
            // new server's world through the old server's registries. Signaled before the active-capture gate
            // below, because a captureEntities-off download routes nothing yet must still stop.
            ConnectionTee.signalTransfer();
        }
        EntityPacketCapture capture = EntityPacketCapture.active();
        if (capture != null && message instanceof Packet<?> packet) {
            try {
                route(packet, capture);
            } catch (RuntimeException e) {
                LOGGER.warn("connection tee inspection failed (the packet is still delivered)", e);
            }
        }
        context.fireChannelRead(message); // always forward, unchanged: the tee never consumes
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
            // A relative move's entity id has no public getter, so the capture cannot read it; pass the loader's
            // widened or transformed field. Every other entity packet exposes its id publicly.
            capture.onMove(entityId(move), move);
        } else {
            capture.accept(packet, bundleNamedIds);
        }
    }
}
