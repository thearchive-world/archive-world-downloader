// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquippedItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.SendRangeSampler;

/**
 * The production specialization of {@link EntityPacketAccumulator}, binding the MC packet types and mapping each
 * inbound entity packet to the generic state the main thread reconstructs from: {@code AddEntity} as the spawn payload,
 * {@code SynchedEntityData.DataValue} as the synced-value payload, and the equipment slot/stack pair as the equipment
 * payload. The reconstruct applies each post-spawn packet the way the client handlers do, so this only has to decode
 * each packet to the same state the client would hold.
 *
 * <p>Every accessor used here is public MC API, so this compiles in {@code common} against the un-widened vanilla jar.
 * The one exception is {@code ClientboundMoveEntityPacket}'s entity id, which is {@code protected}; the per-loader tee,
 * where the access widener / transformer applies, reads it and calls {@link #onMove} with it. {@code RemoveEntities} is
 * handled only as a range sample and book cleanup; it never evicts from the accumulator (a client removal is
 * {@code RemovalReason.DISCARDED} and tells nothing about death versus unload, so reconstruction keeps every tracked
 * entity).
 *
 * <p>{@code Respawn} and {@code Login} are the two inbound packets that announce the world the stream has moved to (the
 * only two that assign the client a level; a configuration re-entry clears it to none), so both are routed to the
 * accumulator's dimension marker and to the sampler, whose id book the rebuilt level invalidates either way. Reading
 * the client's live level here instead would be wrong by a tick: the tee sees the packet on the Netty thread before the
 * main thread applies it, so the entity spawns that follow it on the wire would be stamped with the world the player
 * has left. The marker is advanced first in either branch, because it is the only per-packet state whose loss misfiles
 * data, where the sampler's loses at worst one invalidation.
 *
 * <p>It also carries the connection-scoped publication point. The per-loader inbound tee is installed once per
 * connection and has no reference to the per-download session, so the running capture publishes its accumulator here
 * for the tee to feed. A process singleton because at most one capture runs at a time
 * ({@link world.thearchive.wdl.core.CaptureController}); {@code null} when none is running, so the tee no-ops and the
 * accumulator never grows outside a capture.
 */
final class EntityPacketCapture
        extends
        EntityPacketAccumulator<ClientboundAddEntityPacket, SynchedEntityData.DataItem<?>, EquipmentEntry> {
    private static volatile @Nullable EntityPacketCapture active;

    /**
     * The three static decoration types the covered overlay reports on. A decoration always qualifies for range
     * sampling. This band's {@code EntityType} carries no tracking range (that lives on the server tracker below 1.14,
     * not on the type), so the range-10 co-sampling of non-decoration types the newer bands do cannot be reproduced
     * here; the estimator anchors on decorations alone, which are the stable markers it trusts most, and the
     * mount/display exclusion list that guarded the co-sample is dropped with it.
     */
    private static final Set<EntityType<?>> DECORATION_TYPES = ImmutableSet.of(
            EntityType.ITEM_FRAME, EntityType.PAINTING, EntityType.ARMOR_STAND);

    // Spawn-object type ids from the vanilla client handleAddEntity switch at this band; the minecart id defers its
    // subtype to the packet data, the item frame id is the one this class classifies directly for the frame diagnostic.
    private static final int SPAWN_OBJECT_MINECART = 10;
    private static final int SPAWN_OBJECT_ITEM_FRAME = 71;

    /**
     * Diagnostic only (gated by {@code dumpReceivedFrames}, default off): the {@code (blockX blockY blockZ facing)} key
     * of every item-frame spawn packet received, deduped, matching the key a saved frame's {@code block_pos} and
     * {@code Facing} yield. Dumped at finish so a missing frame can be checked against what the client actually
     * received (received-but-not-saved would be a capture bug; never-received is the server not sending it). Empty, and
     * never populated, when the diagnostic is off.
     */
    private final Set<String> receivedFrames = ConcurrentHashMap.newKeySet();

    /** Whether to populate {@link #receivedFrames} (the {@code dumpReceivedFrames} diagnostic config knob). */
    private final boolean dumpReceivedFrames;

    /** The live send-range estimator fed each received decoration or range-10 distance from the local player. */
    private final SendRangeEstimator sendRange;

    private final SendRangeSampler sampler;

    EntityPacketCapture(boolean dumpReceivedFrames, SendRangeEstimator sendRange, SendRangeSampler sampler,
            String dimensionId) {
        super(dimensionId);
        this.dumpReceivedFrames = dumpReceivedFrames;
        this.sendRange = sendRange;
        this.sampler = sampler;
    }

    /** The sampler, for the session's main-thread gate-arm, seed, and sweep calls. */
    SendRangeSampler sampler() {
        return sampler;
    }

    static void activate(EntityPacketCapture capture) {
        active = capture;
    }

    static void deactivate(EntityPacketCapture capture) {
        if (active == capture) {
            active = null;
        }
    }

    /** The connection-scoped publication point the inbound tee resolves. */
    public static @Nullable EntityPacketCapture active() {
        return active;
    }

    /**
     * Route one inbound packet (already unbundled by the tee) to the accumulator and the send-range sampler. Handles
     * every entity packet whose entity id is public API; {@code ClientboundMoveEntityPacket} is fed via {@link #onMove}
     * instead, because its id is not. {@code bundleNamedIds} carries every id a same-bundle {@code SetPassengers}
     * names, vehicle or passenger; empty outside a bundle.
     */
    public void accept(Packet<?> packet, IntSet bundleNamedIds) {
        if (packet instanceof ClientboundAddEntityPacket) {
            ClientboundAddEntityPacket add = (ClientboundAddEntityPacket) packet;
            onAdd(add, bundleNamedIds);
        } else if (packet instanceof ClientboundRemoveEntitiesPacket) {
            ClientboundRemoveEntitiesPacket remove = (ClientboundRemoveEntitiesPacket) packet;
            onRemove(remove);
        } else if (packet instanceof ClientboundPlayerPositionPacket) {
            sampler.onAnomalyPacket();
        } else if (packet instanceof ClientboundRespawnPacket) {
            ClientboundRespawnPacket respawn = (ClientboundRespawnPacket) packet;
            enterDimension(DimensionType.getName(respawn.getDimension()).toString());
            sampler.onRespawn();
        } else if (packet instanceof ClientboundLoginPacket) {
            ClientboundLoginPacket login = (ClientboundLoginPacket) packet;
            enterDimension(DimensionType.getName(login.getDimension()).toString());
            sampler.onRespawn();
        } else if (packet instanceof ClientboundSetCameraPacket) {
            sampler.onSetCamera();
        } else if (packet instanceof ClientboundSetEntityDataPacket) {
            ClientboundSetEntityDataPacket synced = (ClientboundSetEntityDataPacket) packet;
            onSetData(synced);
        } else if (packet instanceof ClientboundSetEquippedItemPacket) {
            ClientboundSetEquippedItemPacket equip = (ClientboundSetEquippedItemPacket) packet;
            onSetEquipment(equip);
        } else if (packet instanceof ClientboundSetPassengersPacket) {
            ClientboundSetPassengersPacket passengers = (ClientboundSetPassengersPacket) packet;
            onSetPassengers(passengers);
        } else if (packet instanceof ClientboundSetEntityLinkPacket) {
            ClientboundSetEntityLinkPacket link = (ClientboundSetEntityLinkPacket) packet;
            onSetLink(link);
        } else if (packet instanceof ClientboundTeleportEntityPacket) {
            ClientboundTeleportEntityPacket teleport = (ClientboundTeleportEntityPacket) packet;
            onTeleport(teleport);
        }
    }

    private void onAdd(ClientboundAddEntityPacket add, IntSet bundleNamedIds) {
        observeSendRange(add, bundleNamedIds);
        // This band's spawn is split by packet: ClientboundAddEntityPacket carries objects only (never a player),
        // so the newer bands' PLAYER skip has nothing to fire on here.
        EntityPos pos = new EntityPos(add.getX(), add.getY(), add.getZ(),
                decodeAngle((byte) add.getyRot()), decodeAngle((byte) add.getxRot()));
        spawn(add.getId(), add.getUUID(), chunkKey(add.getX(), add.getZ()), pos, add);
        if (dumpReceivedFrames
                && add.method_7626() == SPAWN_OBJECT_ITEM_FRAME) {
            // Diagnostic key for the received-frame diff: block_pos = floor(spawn pos), Facing = the data int
            // (the frame's get3DDataValue, which is also what it saves). floor recovers the block whether the
            // packet carries the block pos or the entity pos (the offset is in [0,1)).
            receivedFrames.add(Mth.floor(add.getX()) + " " + Mth.floor(add.getY()) + " " + Mth.floor(add.getZ())
                    + " " + add.getData());
        }
    }

    /**
     * Whether this type feeds the range estimator. At this band only decorations qualify (see
     * {@link #DECORATION_TYPES}).
     */
    static boolean qualifies(EntityType<?> type) {
        return DECORATION_TYPES.contains(type);
    }

    /**
     * Feed 1, arrivals: register always (a spawn is the fresh codec base and overwrites the anchor; bits survive),
     * sample unless the suppression predicate or the same-flush commit rule blocks it. The commit rule drops an arrival
     * any same-bundle SetPassengers names anywhere: a ridden vehicle pairs at its passenger's boosted range, the plugin
     * chair pattern, so its arrival distance over-claims; the one collected set also feeds the ridden bit (a
     * passenger-named qualifying arrival is thereby suppressed although its own pairing is unboosted, a deliberate
     * one-set concession, under-claim). Reads the player and dimension from the client singleton at netty time; the
     * render-distance bound caps what a stale read can commit.
     */
    private void observeSendRange(ClientboundAddEntityPacket add, IntSet bundleNamedIds) {
        EntityType<?> type = objectEntityType(add.method_7626());
        if (type == null || !qualifies(type)) {
            return; // only decorations qualify at this band; an unmapped object type never does
        }
        int id = add.getId();
        sampler.registerArrival(id, add.getX(), add.getZ());
        if (bundleNamedIds.contains(id)) {
            sampler.markRidden(id);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        MultiPlayerLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        int distanceBlocks = sampler.arrivalSample(player.x, player.z, add.getX(), add.getZ());
        if (distanceBlocks == SendRangeSampler.NO_SAMPLE) {
            return;
        }
        int plausibleMaxBlocks = SendRangeSampler.plausibleMaxBlocks(minecraft.options.renderDistance);
        if (distanceBlocks > plausibleMaxBlocks) {
            return;
        }
        sendRange.observe(DimensionType.getName(level.getDimension().getType()).toString(), distanceBlocks);
    }

    /**
     * Feed 2, the seed at chunk prime: register and sample a loaded entity on the main thread. The live passenger-chain
     * check gates SAMPLING AND REGISTRATION both (a pre-start mount is invisible to the packet stream, so a frozen
     * boarding position must never enter the book) but never the prime loop's buffering. The anchor is the position
     * codec base, the packet-visible position, not the lerped render position: a lerped read would bake a permanent
     * offset into the moved-bit compare.
     */
    void primeSeed(Entity entity, double playerX, double playerZ, int plausibleMaxBlocks, String dimensionId) {
        if (!qualifies(entity.getType())) {
            return; // players are range 32 and never qualify
        }
        if (entity.isPassenger() || entity.isVehicle()) {
            return;
        }
        Vec3 base = new Vec3(entity.x, entity.y, entity.z);
        int id = entity.getId();
        sampler.registerSeed(id, base.x, base.z);
        int distanceBlocks = sampler.seedSample(id, playerX, playerZ);
        if (distanceBlocks != SendRangeSampler.NO_SAMPLE && distanceBlocks <= plausibleMaxBlocks) {
            sendRange.observe(dimensionId, distanceBlocks);
        }
    }

    private void onRemove(ClientboundRemoveEntitiesPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        MultiPlayerLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        int plausibleMaxBlocks = SendRangeSampler.plausibleMaxBlocks(minecraft.options.renderDistance);
        String dimensionId = DimensionType.getName(level.getDimension().getType()).toString();
        int[] ids = packet.getEntityIds();
        for (int i = 0; i < ids.length; i++) {
            int distanceBlocks = sampler.removalSample(ids[i], player.x, player.z);
            if (distanceBlocks != SendRangeSampler.NO_SAMPLE && distanceBlocks <= plausibleMaxBlocks) {
                sendRange.observe(dimensionId, distanceBlocks);
            }
        }
    }

    /** Diagnostic: the deduped received item-frame keys, dumped at finish for the missing-vs-received diff. */
    public Set<String> receivedFrames() {
        return receivedFrames;
    }

    private void onSetData(ClientboundSetEntityDataPacket packet) {
        if (tracks(packet.getId())) {
            List<SynchedEntityData.DataItem<?>> items = packet.getUnpackedData();
            if (items != null) {
                for (SynchedEntityData.DataItem<?> value : items) {
                    recordData(packet.getId(), value.getAccessor().getId(), value);
                }
            }
        }
    }

    private void onSetEquipment(ClientboundSetEquippedItemPacket packet) {
        if (tracks(packet.getEntity())) {
            // 1.15.2 sends one slot per packet, not the 1.16 slot/stack list.
            EquipmentSlot slot = packet.getSlot();
            recordEquipment(packet.getEntity(), slot.ordinal(), new EquipmentEntry(slot, packet.getItem()));
        }
    }

    private void onSetPassengers(ClientboundSetPassengersPacket packet) {
        // The ridden bit is fed unconditionally: the existing gate below tracks only packet-spawned
        // vehicles, and a seeded vehicle would otherwise dodge the mark.
        sampler.markRidden(packet.getVehicle());
        for (int passenger : packet.getPassengers()) {
            sampler.markRidden(passenger);
        }
        if (tracks(packet.getVehicle())) {
            recordPassengers(packet.getVehicle(), packet.getPassengers());
        }
    }

    private void onSetLink(ClientboundSetEntityLinkPacket packet) {
        if (tracks(packet.getSourceId())) {
            recordLeash(packet.getSourceId(), packet.getDestId());
        }
    }

    private void onTeleport(ClientboundTeleportEntityPacket packet) {
        int id = packet.getId();
        double x = packet.getX();
        double y = packet.getY();
        double z = packet.getZ();
        sampler.markMovedAbsolute(id, x, z);
        EntityPos current = positionOf(id);
        if (current != null) {
            reposition(id, chunkKey(x, z),
                    new EntityPos(x, y, z, decodeAngle(packet.getyRot()), decodeAngle(packet.getxRot())));
        }
    }

    /**
     * Apply a relative move ({@code ClientboundMoveEntityPacket}, the common walk update) keyed by the entity id the
     * tee read from the packet's non-public field. A relative move is a short delta in 1/4096 of a block off the last
     * sent base, so it is resolved against the current accumulated position (vanilla's VecDeltaCodec).
     *
     * <p>Vanilla sends a rider rotation but never a position, so a rider's held coordinates stay frozen at where it
     * boarded; a chunk home derived from them would revert the re-home onto its vehicle and split the pair across two
     * drain batches. A rotation-only update therefore leaves the home alone.
     */
    public void onMove(int id, short xa, short ya, short za, ClientboundMoveEntityPacket move) {
        // This band has no ClientboundMoveEntityPacket.hasPosition; the Pos and PosRot subclasses carry a position.
        // The Pos and PosRot position-bearing subclasses are intermediary-named at this band (class_2028 is Pos,
        // class_2029 is PosRot); a plain relative move (class_2030, Rot only) carries no position.
        boolean hasPosition = move instanceof ClientboundMoveEntityPacket.class_2028
                || move instanceof ClientboundMoveEntityPacket.class_2029;
        sampler.markMovedRelative(id, hasPosition && (xa != 0 || ya != 0 || za != 0));
        EntityPos current = positionOf(id);
        if (current == null) {
            return;
        }
        float yRot = move.hasRotation() ? decodeAngle(move.getyRot()) : current.yRot();
        float xRot = move.hasRotation() ? decodeAngle(move.getxRot()) : current.xRot();
        if (!hasPosition) {
            recordRotation(id, yRot, xRot);
            return;
        }
        double x = decodeAxis(current.x(), xa);
        double y = decodeAxis(current.y(), ya);
        double z = decodeAxis(current.z(), za);
        reposition(id, chunkKey(x, z), new EntityPos(x, y, z, yRot, xRot));
    }

    private static long chunkKey(double x, double z) {
        return ChunkPos.asLong(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    private static double decodeAxis(double base, short delta) {
        return delta == 0 ? base : (Math.round(base * 4096.0) + delta) / 4096.0;
    }

    /** Decode a wire-byte rotation (256 units per full turn) to degrees, as vanilla unpacks it. */
    private static float decodeAngle(byte packed) {
        return packed * 360 / 256.0F;
    }

    /**
     * The {@link EntityType} a spawn-object packet's object-type id names, or null for an id this band does not spawn
     * as an object. Mirrors the vanilla client handleAddEntity resolution; the minecart id defers its subtype to the
     * packet data, so a bare minecart stands in here for the decoration classification, and {@link #createSpawnObject}
     * resolves the real subtype when it reconstructs.
     */
    static @Nullable EntityType<?> objectEntityType(int spawnObjectType) {
        switch (spawnObjectType) {
            case 1:
                return EntityType.BOAT;
            case 2:
                return EntityType.ITEM;
            case 3:
                return EntityType.AREA_EFFECT_CLOUD;
            case SPAWN_OBJECT_MINECART:
                return EntityType.MINECART;
            case 50:
                return EntityType.TNT;
            case 51:
                return EntityType.END_CRYSTAL;
            case 60:
                return EntityType.ARROW;
            case 61:
                return EntityType.SNOWBALL;
            case 62:
                return EntityType.EGG;
            case 63:
                return EntityType.FIREBALL;
            case 64:
                return EntityType.SMALL_FIREBALL;
            case 65:
                return EntityType.ENDER_PEARL;
            case 66:
                return EntityType.WITHER_SKULL;
            case 67:
                return EntityType.SHULKER_BULLET;
            case 68:
                return EntityType.LLAMA_SPIT;
            case 70:
                return EntityType.FALLING_BLOCK;
            case SPAWN_OBJECT_ITEM_FRAME:
                return EntityType.ITEM_FRAME;
            case 72:
                return EntityType.EYE_OF_ENDER;
            case 73:
                return EntityType.POTION;
            case 75:
                return EntityType.EXPERIENCE_BOTTLE;
            case 76:
                return EntityType.FIREWORK_ROCKET;
            case 77:
                return EntityType.LEASH_KNOT;
            case 78:
                return EntityType.ARMOR_STAND;
            case 79:
                return EntityType.EVOKER_FANGS;
            case 90:
                return EntityType.FISHING_BOBBER;
            case 91:
                return EntityType.SPECTRAL_ARROW;
            case 93:
                return EntityType.DRAGON_FIREBALL;
            case 94:
                return EntityType.TRIDENT;
            default:
                return null;
        }
    }

    /**
     * Reconstruct the object a spawn-object packet describes, the int-type-to-entity resolution the vanilla client
     * handleAddEntity applies. The minecart subtype is resolved from the packet data through
     * AbstractMinecart.createMinecart; every other object is created bare from its {@link EntityType}, exactly as the
     * newer bands' unified reconstruct did, so the caller's own snap-to-position, synced-value, and equipment
     * application fill it in. Null for an id this band does not spawn as an object, or a type with no factory.
     */
    static @Nullable Entity createSpawnObject(ClientboundAddEntityPacket add, Level level) {
        int spawnObjectType = add.method_7626();
        if (spawnObjectType == SPAWN_OBJECT_MINECART) {
            return AbstractMinecart.createMinecart(level, add.getX(), add.getY(), add.getZ(),
                    AbstractMinecart.Type.method_11173(add.getData()));
        }
        EntityType<?> type = objectEntityType(spawnObjectType);
        return type == null ? null : type.create(level);
    }
}
