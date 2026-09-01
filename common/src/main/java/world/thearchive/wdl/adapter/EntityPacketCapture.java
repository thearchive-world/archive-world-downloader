// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLeashKnot;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityPainting;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.network.Packet;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.network.play.server.SPacketCamera;
import net.minecraft.network.play.server.SPacketDestroyEntities;
import net.minecraft.network.play.server.SPacketEntity;
import net.minecraft.network.play.server.SPacketEntityAttach;
import net.minecraft.network.play.server.SPacketEntityEquipment;
import net.minecraft.network.play.server.SPacketEntityMetadata;
import net.minecraft.network.play.server.SPacketEntityTeleport;
import net.minecraft.network.play.server.SPacketJoinGame;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.network.play.server.SPacketRespawn;
import net.minecraft.network.play.server.SPacketSetPassengers;
import net.minecraft.network.play.server.SPacketSpawnExperienceOrb;
import net.minecraft.network.play.server.SPacketSpawnMob;
import net.minecraft.network.play.server.SPacketSpawnObject;
import net.minecraft.network.play.server.SPacketSpawnPainting;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.SendRangeSampler;

/**
 * The production specialization of {@link EntityPacketAccumulator}, binding the MC packet types and mapping each
 * inbound entity packet to the generic state the main thread reconstructs from. This band splits a non-player spawn
 * across several packets ({@code SPacketSpawnObject} for objects, {@code SPacketSpawnMob} for living entities,
 * {@code SPacketSpawnPainting}, and {@code SPacketSpawnExperienceOrb}), so the spawn payload is the erased
 * {@code Packet} and {@link #createSpawnEntity} dispatches on the concrete type at reconstruct; the newer bands fold
 * every one of these into a single add-entity packet. The lightning bolt ({@code SPacketSpawnGlobalEntity}, present at
 * this band too) is deliberately left unread: its entity type is not serializable, so the unified bands never save it
 * either, and reading it would only add sink refusals. {@code EntityDataManager.DataEntry} is the synced-value payload
 * and the equipment slot/stack pair the equipment payload. The reconstruct applies each post-spawn packet the way the
 * client handlers do, so this only has to decode each packet to the same state the client would hold.
 *
 * <p>There is no {@code EntityType} type-object before 1.13, so discrimination and reconstruction run on the classic
 * model: the spawn-object type id and {@code EntityList} (its registry names and {@code createEntityByID} /
 * {@code createEntityByIDFromName} factories) rather than an {@code EntityType} enum and {@code EntityType.create}.
 *
 * <p>Every accessor used here is public MC API, so this compiles in {@code common} against the un-widened vanilla jar.
 * The one exception is {@code SPacketEntity}'s entity id, which is not public; the per-loader tee, where the access
 * widener / transformer applies, reads it and calls {@link #onMove} with it. {@code SPacketDestroyEntities} is handled
 * only as a range sample and book cleanup; it never evicts from the accumulator, so reconstruction keeps every tracked
 * entity.
 *
 * <p>{@code SPacketRespawn} and {@code SPacketJoinGame} are the two inbound packets that announce the world the stream
 * has moved to, so both are routed to the accumulator's dimension marker and to the sampler, whose id book the rebuilt
 * level invalidates either way. Reading the client's live level here instead would be wrong by a tick: the tee sees the
 * packet on the Netty thread before the main thread applies it, so the entity spawns that follow it on the wire would
 * be stamped with the world the player has left. The marker is advanced first in either branch, because it is the only
 * per-packet state whose loss misfiles data, where the sampler's loses at worst one invalidation.
 *
 * <p>It also carries the connection-scoped publication point. The per-loader inbound tee is installed once per
 * connection and has no reference to the per-download session, so the running capture publishes its accumulator here
 * for the tee to feed. A process singleton because at most one capture runs at a time
 * ({@link world.thearchive.wdl.core.CaptureController}); {@code null} when none is running, so the tee no-ops and the
 * accumulator never grows outside a capture.
 */
final class EntityPacketCapture
        extends
        EntityPacketAccumulator<Packet<?>, EntityDataManager.DataEntry<?>, EquipmentEntry> {
    private static volatile @Nullable EntityPacketCapture active;

    // Spawn-object type ids from the vanilla client handleSpawnObject switch at this band; the minecart id defers its
    // subtype to the packet data, the item frame and armor stand ids are the decoration objects the range sampler
    // co-anchors on, and the leash knot is a positioned hanging entity like the item frame.
    private static final int SPAWN_OBJECT_MINECART = 10;
    private static final int SPAWN_OBJECT_ITEM_FRAME = 71;
    private static final int SPAWN_OBJECT_LEASH_KNOT = 77;
    private static final int SPAWN_OBJECT_ARMOR_STAND = 78;

    /**
     * Diagnostic only (gated by {@code dumpReceivedFrames}, default off): the {@code (blockX blockY blockZ facing)} key
     * of every item-frame spawn packet received, deduped, matching the key a saved frame's {@code block_pos} and
     * {@code Facing} yield. Dumped at finish so a missing frame can be checked against what the client actually
     * received. Empty, and never populated, when the diagnostic is off.
     */
    private final Set<String> receivedFrames = ConcurrentHashMap.newKeySet();

    /** Whether to populate {@link #receivedFrames} (the {@code dumpReceivedFrames} diagnostic config knob). */
    private final boolean dumpReceivedFrames;

    /** The live send-range estimator fed each received decoration distance from the local player. */
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
     * every entity packet whose entity id is public API; {@code SPacketEntity} is fed via {@link #onMove} instead,
     * because its id is not. {@code bundleNamedIds} carries every id a same-bundle passenger set names, vehicle or
     * passenger; empty outside a bundle.
     */
    public void accept(Packet<?> packet, IntSet bundleNamedIds) {
        if (packet instanceof SPacketSpawnObject) {
            SPacketSpawnObject add = (SPacketSpawnObject) packet;
            onAdd(add, bundleNamedIds);
        } else if (packet instanceof SPacketSpawnMob) {
            SPacketSpawnMob mob = (SPacketSpawnMob) packet;
            onAddMob(mob);
        } else if (packet instanceof SPacketSpawnPainting) {
            SPacketSpawnPainting painting = (SPacketSpawnPainting) packet;
            onAddPainting(painting);
        } else if (packet instanceof SPacketSpawnExperienceOrb) {
            SPacketSpawnExperienceOrb orb = (SPacketSpawnExperienceOrb) packet;
            onAddExperienceOrb(orb);
        } else if (packet instanceof SPacketDestroyEntities) {
            SPacketDestroyEntities remove = (SPacketDestroyEntities) packet;
            onRemove(remove);
        } else if (packet instanceof SPacketPlayerPosLook) {
            sampler.onAnomalyPacket();
        } else if (packet instanceof SPacketRespawn) {
            SPacketRespawn respawn = (SPacketRespawn) packet;
            enterDimension(DimensionType.getById(respawn.getDimensionID()).getName());
            sampler.onRespawn();
        } else if (packet instanceof SPacketJoinGame) {
            SPacketJoinGame login = (SPacketJoinGame) packet;
            enterDimension(DimensionType.getById(login.getDimension()).getName());
            sampler.onRespawn();
        } else if (packet instanceof SPacketCamera) {
            sampler.onSetCamera();
        } else if (packet instanceof SPacketEntityMetadata) {
            SPacketEntityMetadata synced = (SPacketEntityMetadata) packet;
            onSetData(synced);
        } else if (packet instanceof SPacketEntityEquipment) {
            SPacketEntityEquipment equip = (SPacketEntityEquipment) packet;
            onSetEquipment(equip);
        } else if (packet instanceof SPacketSetPassengers) {
            SPacketSetPassengers passengers = (SPacketSetPassengers) packet;
            onSetPassengers(passengers);
        } else if (packet instanceof SPacketEntityAttach) {
            SPacketEntityAttach link = (SPacketEntityAttach) packet;
            onSetLink(link);
        } else if (packet instanceof SPacketEntityTeleport) {
            SPacketEntityTeleport teleport = (SPacketEntityTeleport) packet;
            onTeleport(teleport);
        }
    }

    private void onAdd(SPacketSpawnObject add, IntSet bundleNamedIds) {
        observeSendRange(add, bundleNamedIds);
        // This band's spawn is split by packet: SPacketSpawnObject carries objects only (never a player), so the
        // newer bands' PLAYER skip has nothing to fire on here.
        EntityPos pos = new EntityPos(add.getX(), add.getY(), add.getZ(),
                decodeAngle((byte) add.getYaw()), decodeAngle((byte) add.getPitch()));
        spawn(add.getEntityID(), add.getUniqueId(), chunkKey(add.getX(), add.getZ()), pos, add);
        if (dumpReceivedFrames && add.getType() == SPAWN_OBJECT_ITEM_FRAME) {
            // Diagnostic key for the received-frame diff: block_pos = floor(spawn pos), Facing = the data int (the
            // frame's facing index, which is also what it saves). floor recovers the block whether the packet carries
            // the block pos or the entity pos (the offset is in [0,1)).
            receivedFrames.add(MathHelper.floor(add.getX()) + " " + MathHelper.floor(add.getY()) + " "
                    + MathHelper.floor(add.getZ()) + " " + add.getData());
        }
    }

    /**
     * Ingest a living-entity spawn ({@code SPacketSpawnMob}, this band's separate mob spawn, folded into a single
     * add-entity packet at 1.19). The spawn carries the synched data inline, so those values are seeded here the way
     * the client applies them at spawn; later {@code SPacketEntityMetadata} packets merge over them.
     */
    private void onAddMob(SPacketSpawnMob mob) {
        EntityPos pos = new EntityPos(mob.getX(), mob.getY(), mob.getZ(),
                decodeAngle(mob.getYaw()), decodeAngle(mob.getPitch()));
        spawn(mob.getEntityID(), mob.getUniqueId(), chunkKey(mob.getX(), mob.getZ()), pos, mob);
        List<EntityDataManager.DataEntry<?>> items = mob.getDataManagerEntries();
        if (items != null) {
            for (EntityDataManager.DataEntry<?> value : items) {
                recordData(mob.getEntityID(), value.getKey().getId(), value);
            }
        }
    }

    /**
     * Ingest a painting spawn ({@code SPacketSpawnPainting}, separate until 1.19). A painting is a hanging entity fixed
     * by its block pos and facing, so the accumulated position is that block, which the reconstruct rebuilds it on.
     */
    private void onAddPainting(SPacketSpawnPainting painting) {
        BlockPos block = painting.getPosition();
        EntityPos pos = new EntityPos(block.getX(), block.getY(), block.getZ(), 0.0F, 0.0F);
        spawn(painting.getEntityID(), painting.getUniqueId(), chunkKey(block.getX(), block.getZ()), pos, painting);
    }

    /**
     * Ingest an experience-orb spawn ({@code SPacketSpawnExperienceOrb}, separate until 1.21.5). The packet carries no
     * UUID, so a synthetic id-derived one stands in as the accumulator's identity key; orbs are transient and merge.
     */
    private void onAddExperienceOrb(SPacketSpawnExperienceOrb orb) {
        EntityPos pos = new EntityPos(orb.getX(), orb.getY(), orb.getZ(), 0.0F, 0.0F);
        spawn(orb.getEntityID(), syntheticUuid(orb.getEntityID()), chunkKey(orb.getX(), orb.getZ()), pos, orb);
    }

    /** A stable id-derived UUID for a spawn packet that carries none (the experience orb). */
    private static UUID syntheticUuid(int id) {
        return new UUID(0L, id & 0xFFFFFFFFL);
    }

    /** Whether a spawn-object type id is a decoration the range estimator anchors on (item frame or armor stand). */
    private static boolean isDecorationObject(int spawnObjectType) {
        return spawnObjectType == SPAWN_OBJECT_ITEM_FRAME || spawnObjectType == SPAWN_OBJECT_ARMOR_STAND;
    }

    /** Whether a live entity is a decoration (item frame, painting, armor stand), the primed-seed range anchor set. */
    private static boolean isDecoration(Entity entity) {
        return entity instanceof EntityItemFrame || entity instanceof EntityPainting
                || entity instanceof EntityArmorStand;
    }

    /**
     * Feed 1, arrivals: register always (a spawn is the fresh codec base and overwrites the anchor; bits survive),
     * sample unless the suppression predicate or the same-flush commit rule blocks it. The commit rule drops an arrival
     * any same-bundle passenger set names anywhere: a ridden vehicle pairs at its passenger's boosted range, the plugin
     * chair pattern, so its arrival distance over-claims; the one collected set also feeds the ridden bit (a
     * passenger-named qualifying arrival is thereby suppressed although its own pairing is unboosted, a deliberate
     * one-set concession, under-claim). Reads the player and dimension from the client singleton at netty time; the
     * render-distance bound caps what a stale read can commit.
     */
    private void observeSendRange(SPacketSpawnObject add, IntSet bundleNamedIds) {
        if (!isDecorationObject(add.getType())) {
            return; // only decorations qualify at this band; every other object type never does
        }
        int id = add.getEntityID();
        sampler.registerArrival(id, add.getX(), add.getZ());
        if (bundleNamedIds.contains(id)) {
            sampler.markRidden(id);
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.player;
        WorldClient level = minecraft.world;
        if (player == null || level == null) {
            return;
        }
        int distanceBlocks = sampler.arrivalSample(player.posX, player.posZ, add.getX(), add.getZ());
        if (distanceBlocks == SendRangeSampler.NO_SAMPLE) {
            return;
        }
        int plausibleMaxBlocks = SendRangeSampler.plausibleMaxBlocks(minecraft.gameSettings.renderDistanceChunks);
        if (distanceBlocks > plausibleMaxBlocks) {
            return;
        }
        sendRange.observe(level.provider.getDimensionType().getName(), distanceBlocks);
    }

    /**
     * Feed 2, the seed at chunk prime: register and sample a loaded entity on the main thread. The live passenger-chain
     * check gates SAMPLING AND REGISTRATION both (a pre-start mount is invisible to the packet stream, so a frozen
     * boarding position must never enter the book) but never the prime loop's buffering. The anchor is the position
     * codec base, the packet-visible position, not the lerped render position: a lerped read would bake a permanent
     * offset into the moved-bit compare.
     */
    void primeSeed(Entity entity, double playerX, double playerZ, int plausibleMaxBlocks, String dimensionId) {
        if (!isDecoration(entity)) {
            return; // players and mobs are not range anchors at this band
        }
        if (entity.isRiding() || entity.isBeingRidden()) {
            return;
        }
        Vec3d base = new Vec3d(entity.posX, entity.posY, entity.posZ);
        int id = entity.getEntityId();
        sampler.registerSeed(id, base.x, base.z);
        int distanceBlocks = sampler.seedSample(id, playerX, playerZ);
        if (distanceBlocks != SendRangeSampler.NO_SAMPLE && distanceBlocks <= plausibleMaxBlocks) {
            sendRange.observe(dimensionId, distanceBlocks);
        }
    }

    private void onRemove(SPacketDestroyEntities packet) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.player;
        WorldClient level = minecraft.world;
        if (player == null || level == null) {
            return;
        }
        int plausibleMaxBlocks = SendRangeSampler.plausibleMaxBlocks(minecraft.gameSettings.renderDistanceChunks);
        String dimensionId = level.provider.getDimensionType().getName();
        int[] ids = packet.getEntityIDs();
        for (int i = 0; i < ids.length; i++) {
            int distanceBlocks = sampler.removalSample(ids[i], player.posX, player.posZ);
            if (distanceBlocks != SendRangeSampler.NO_SAMPLE && distanceBlocks <= plausibleMaxBlocks) {
                sendRange.observe(dimensionId, distanceBlocks);
            }
        }
    }

    /** Diagnostic: the deduped received item-frame keys, dumped at finish for the missing-vs-received diff. */
    public Set<String> receivedFrames() {
        return receivedFrames;
    }

    private void onSetData(SPacketEntityMetadata packet) {
        if (tracks(packet.getEntityId())) {
            List<EntityDataManager.DataEntry<?>> items = packet.getDataManagerEntries();
            if (items != null) {
                for (EntityDataManager.DataEntry<?> value : items) {
                    recordData(packet.getEntityId(), value.getKey().getId(), value);
                }
            }
        }
    }

    private void onSetEquipment(SPacketEntityEquipment packet) {
        if (tracks(packet.getEntityID())) {
            // 1.12.2 sends one slot per packet, not the 1.16 slot/stack list.
            EntityEquipmentSlot slot = packet.getEquipmentSlot();
            recordEquipment(packet.getEntityID(), slot.ordinal(), new EquipmentEntry(slot, packet.getItemStack()));
        }
    }

    private void onSetPassengers(SPacketSetPassengers packet) {
        // The ridden bit is fed unconditionally: the existing gate below tracks only packet-spawned
        // vehicles, and a seeded vehicle would otherwise dodge the mark.
        sampler.markRidden(packet.getEntityId());
        for (int passenger : packet.getPassengerIds()) {
            sampler.markRidden(passenger);
        }
        if (tracks(packet.getEntityId())) {
            recordPassengers(packet.getEntityId(), packet.getPassengerIds());
        }
    }

    private void onSetLink(SPacketEntityAttach packet) {
        if (tracks(packet.getEntityId())) {
            recordLeash(packet.getEntityId(), packet.getVehicleEntityId());
        }
    }

    private void onTeleport(SPacketEntityTeleport packet) {
        int id = packet.getEntityId();
        double x = packet.getX();
        double y = packet.getY();
        double z = packet.getZ();
        sampler.markMovedAbsolute(id, x, z);
        EntityPos current = positionOf(id);
        if (current != null) {
            reposition(id, chunkKey(x, z),
                    new EntityPos(x, y, z, decodeAngle(packet.getYaw()), decodeAngle(packet.getPitch())));
        }
    }

    /**
     * Apply a relative move ({@code SPacketEntity}, the common walk update) keyed by the entity id the tee read from
     * the packet's non-public field. A relative move is a short delta in 1/4096 of a block off the last sent base, so
     * it is resolved against the current accumulated position.
     *
     * <p>Vanilla sends a rider rotation but never a position, so a rider's held coordinates stay frozen at where it
     * boarded; a chunk home derived from them would revert the re-home onto its vehicle and split the pair across two
     * drain batches. A rotation-only update therefore leaves the home alone.
     */
    public void onMove(int id, short xa, short ya, short za, SPacketEntity move) {
        // This band has no hasPosition/hasRotation accessor; the nested SPacketEntity subclasses are the discriminant.
        // S15PacketEntityRelMove (Pos) and S17PacketEntityLookMove (PosRot) carry a position; S16PacketEntityLook
        // (Rot only) and the plain SPacketEntity carry none. S16 and S17 carry a rotation.
        boolean hasPosition = move instanceof SPacketEntity.S15PacketEntityRelMove
                || move instanceof SPacketEntity.S17PacketEntityLookMove;
        boolean hasRotation = move instanceof SPacketEntity.S16PacketEntityLook
                || move instanceof SPacketEntity.S17PacketEntityLookMove;
        sampler.markMovedRelative(id, hasPosition && (xa != 0 || ya != 0 || za != 0));
        EntityPos current = positionOf(id);
        if (current == null) {
            return;
        }
        float yRot = hasRotation ? decodeAngle(move.getYaw()) : current.yRot();
        float xRot = hasRotation ? decodeAngle(move.getPitch()) : current.xRot();
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
        return ChunkPos.asLong(MathHelper.floor(x) >> 4, MathHelper.floor(z) >> 4);
    }

    private static double decodeAxis(double base, short delta) {
        return delta == 0 ? base : (Math.round(base * 4096.0) + delta) / 4096.0;
    }

    /** Decode a wire-byte rotation (256 units per full turn) to degrees, as vanilla unpacks it. */
    private static float decodeAngle(byte packed) {
        return packed * 360 / 256.0F;
    }

    /**
     * The {@link EntityList} registry name a spawn-object packet's object-type id names, or null for an id this band
     * does not spawn as an object (the fishing bobber has no registry entry, the trident does not exist below 1.13).
     * Mirrors the vanilla client handleSpawnObject resolution; the minecart id defers its subtype to the packet data,
     * so the caller resolves that ahead of this default resolution.
     */
    static @Nullable ResourceLocation objectEntityName(int spawnObjectType) {
        switch (spawnObjectType) {
            case 1:
                return new ResourceLocation("boat");
            case 2:
                return new ResourceLocation("item");
            case 3:
                return new ResourceLocation("area_effect_cloud");
            case SPAWN_OBJECT_MINECART:
                return new ResourceLocation("minecart");
            case 50:
                return new ResourceLocation("tnt");
            case 51:
                return new ResourceLocation("ender_crystal");
            case 60:
                return new ResourceLocation("arrow");
            case 61:
                return new ResourceLocation("snowball");
            case 62:
                return new ResourceLocation("egg");
            case 63:
                return new ResourceLocation("fireball");
            case 64:
                return new ResourceLocation("small_fireball");
            case 65:
                return new ResourceLocation("ender_pearl");
            case 66:
                return new ResourceLocation("wither_skull");
            case 67:
                return new ResourceLocation("shulker_bullet");
            case 68:
                return new ResourceLocation("llama_spit");
            case 70:
                return new ResourceLocation("falling_block");
            case SPAWN_OBJECT_ITEM_FRAME:
                return new ResourceLocation("item_frame");
            case 72:
                return new ResourceLocation("eye_of_ender_signal");
            case 73:
                return new ResourceLocation("potion");
            case 75:
                return new ResourceLocation("xp_bottle");
            case 76:
                return new ResourceLocation("fireworks_rocket");
            case SPAWN_OBJECT_LEASH_KNOT:
                return new ResourceLocation("leash_knot");
            case SPAWN_OBJECT_ARMOR_STAND:
                return new ResourceLocation("armor_stand");
            case 79:
                return new ResourceLocation("evocation_fangs");
            case 91:
                return new ResourceLocation("spectral_arrow");
            case 93:
                return new ResourceLocation("dragon_fireball");
            default:
                return null;
        }
    }

    /**
     * Reconstruct the object a spawn-object packet describes, the int-type-to-entity resolution the vanilla client
     * handleSpawnObject applies. The minecart subtype is resolved from the packet data through
     * {@code EntityMinecart.create}; the item frame and leash knot are hanging entities built through their positioned
     * constructors, which set the block pos and facing their save reads (a bare create leaves those null and there is
     * no recreate-from-packet at this band, so the save would throw); every other object is created bare from its
     * {@link EntityList} registry name, so the caller's own snap-to-position, synced-value, and equipment application
     * fill it in. Null for an id this band does not spawn as an object, or a name with no factory.
     */
    static @Nullable Entity createSpawnObject(SPacketSpawnObject add, World level) {
        int spawnObjectType = add.getType();
        double x = add.getX();
        double y = add.getY();
        double z = add.getZ();
        if (spawnObjectType == SPAWN_OBJECT_MINECART) {
            return EntityMinecart.create(level, x, y, z, EntityMinecart.Type.getById(add.getData()));
        }
        if (spawnObjectType == SPAWN_OBJECT_ITEM_FRAME) {
            // The packet field is a HORIZONTAL index, 0 to 3, which is what the server writes
            // (EntityItemFrame.facingDirection.getHorizontalIndex()) and what the vanilla client reads back. The
            // all-six lookup would map 0 and 1 onto down and up, which a hanging entity rejects outright, and 3 onto
            // south where the server meant east.
            return new EntityItemFrame(level, new BlockPos(x, y, z), EnumFacing.getHorizontal(add.getData()));
        }
        if (spawnObjectType == SPAWN_OBJECT_LEASH_KNOT) {
            return new EntityLeashKnot(level,
                    new BlockPos(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z)));
        }
        ResourceLocation name = objectEntityName(spawnObjectType);
        return name == null ? null : EntityList.createEntityByIDFromName(name, level);
    }

    /**
     * Reconstruct the entity a spawn packet describes, dispatching on the concrete spawn packet the way the vanilla
     * client's per-packet handlers do: an object through {@link #createSpawnObject}, a mob from its {@code EntityList}
     * type id, a painting from its own constructor, an experience orb from its value. Null for a packet this does not
     * spawn from, or a type with no factory. The caller applies the identity, position, synced values, and equipment.
     */
    static @Nullable Entity createSpawnEntity(Packet<?> spawn, World level) {
        if (spawn instanceof SPacketSpawnObject) {
            return createSpawnObject((SPacketSpawnObject) spawn, level);
        }
        if (spawn instanceof SPacketSpawnMob) {
            return EntityList.createEntityByID(((SPacketSpawnMob) spawn).getEntityType(), level);
        }
        if (spawn instanceof SPacketSpawnPainting) {
            SPacketSpawnPainting painting = (SPacketSpawnPainting) spawn;
            return new EntityPainting(level, painting.getPosition(), painting.getFacing(), painting.getTitle());
        }
        if (spawn instanceof SPacketSpawnExperienceOrb) {
            SPacketSpawnExperienceOrb orb = (SPacketSpawnExperienceOrb) spawn;
            return new EntityXPOrb(level, orb.getX(), orb.getY(), orb.getZ(), orb.getXPValue());
        }
        return null;
    }
}
