// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket;
import net.minecraft.network.protocol.game.ClientboundAddMobPacket;
import net.minecraft.network.protocol.game.ClientboundAddPaintingPacket;
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
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.SendRangeSampler;

/**
 * The production specialization of {@link EntityPacketAccumulator}, binding the MC packet types and mapping each
 * inbound entity packet to the generic state the main thread reconstructs from. This band splits a non-player spawn
 * across several packets ({@code AddEntity} for objects and living display entities, {@code AddMob} for living
 * entities, {@code AddPainting}, and {@code AddExperienceOrb}), so the spawn payload is the erased {@code Packet} and
 * {@link #createSpawnEntity} dispatches on the concrete type at reconstruct; the newer bands fold every one of these
 * into {@code AddEntity} at 1.19 and 1.21.5. The lightning bolt ({@code AddGlobalEntity}) is deliberately left unread:
 * its entity type is not serializable, so the unified bands never save it either, and reading it would only add sink
 * refusals. {@code SynchedEntityData.DataValue} is the synced-value payload and the equipment slot/stack pair the
 * equipment payload. This band has no {@code Entity.recreateFromPacket} (added at 1.17), so the reconstruct builds each
 * hanging entity through its positioned constructor rather than letting the packet fill the facing.
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
        EntityPacketAccumulator<Packet<?>, SynchedEntityData.DataItem<?>, EquipmentEntry> {
    private static volatile @Nullable EntityPacketCapture active;

    /**
     * The four static decoration types the covered overlay reports on. A decoration always qualifies for range
     * sampling, independent of the range-10 rule and the exclusion list.
     */
    private static final Set<EntityType<?>> DECORATION_TYPES = ImmutableSet.of(
            EntityType.ITEM_FRAME, EntityType.PAINTING, EntityType.ARMOR_STAND);

    /**
     * Range-10 types excluded from range sampling, for two reasons. The player-mountables: a riding player raises their
     * broadcast range to the player's own (TrackedEntity.getEffectiveRange takes the max over indirect passengers), and
     * passenger state is not visible at AddEntity time, so a boosted sample would over-claim the range. The Display
     * types: category-configured servers commonly track their display category farther than the decorations' categories
     * (a plugin hologram near the player would over-claim teal until the first decoration takes over). Only range-10
     * non-decoration types are otherwise sampled, so this lists only members whose vanilla clientTrackingRange is 10:
     * the range-8 mounts (all minecarts, the mule) are never sampled and need no entry. Re-derive per band from
     * PlayerRideable, ItemSteerable, Boat, AbstractHorse, canAddPassenger overrides, and the Display hierarchy, keeping
     * only the range-10 members; a missing entry over-claims coverage, the failure the measured range exists to
     * prevent. Interaction is range 10, non-mountable, and absent from vanilla worlds; its real-world use is plugin
     * frameworks that teleport interaction and display pairs to follow players, the hazard profile that excluded the
     * Displays.
     */
    private static final Set<EntityType<?>> RANGE_SAMPLING_EXCLUSIONS = ImmutableSet.of(
            EntityType.BOAT,
            EntityType.HORSE, EntityType.DONKEY, EntityType.SKELETON_HORSE, EntityType.ZOMBIE_HORSE,
            EntityType.LLAMA, EntityType.TRADER_LLAMA,
            EntityType.PIG);

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
        } else if (packet instanceof ClientboundAddMobPacket) {
            ClientboundAddMobPacket mob = (ClientboundAddMobPacket) packet;
            onAddMob(mob);
        } else if (packet instanceof ClientboundAddPaintingPacket) {
            ClientboundAddPaintingPacket painting = (ClientboundAddPaintingPacket) packet;
            onAddPainting(painting);
        } else if (packet instanceof ClientboundAddExperienceOrbPacket) {
            ClientboundAddExperienceOrbPacket orb = (ClientboundAddExperienceOrbPacket) packet;
            onAddExperienceOrb(orb);
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
        if (add.getType() == EntityType.PLAYER) {
            return; // the client builds a RemotePlayer for PLAYER, and players are not saved as entities
        }
        EntityPos pos = new EntityPos(add.getX(), add.getY(), add.getZ(),
                decodeAngle((byte) add.getyRot()), decodeAngle((byte) add.getxRot()));
        spawn(add.getId(), add.getUUID(), chunkKey(add.getX(), add.getZ()), pos, add);
        if (dumpReceivedFrames
                && add.getType() == EntityType.ITEM_FRAME) {
            // Diagnostic key for the received-frame diff: block_pos = floor(spawn pos), Facing = the data int
            // (the frame's get3DDataValue, which is also what it saves). floor recovers the block whether the
            // packet carries the block pos or the entity pos (the offset is in [0,1)).
            receivedFrames.add(Mth.floor(add.getX()) + " " + Mth.floor(add.getY()) + " " + Mth.floor(add.getZ())
                    + " " + add.getData());
        }
    }

    /**
     * Ingest a living-entity spawn ({@code AddMob}, this band's separate mob spawn, folded into {@code AddEntity} at
     * 1.19). Unlike the earlier bands, this band's spawn carries no synched data inline (it moved to a following
     * {@code SetEntityData} at 1.15), so {@link #onSetData} records those values; only the identity and position are
     * taken here.
     */
    private void onAddMob(ClientboundAddMobPacket mob) {
        EntityPos pos = new EntityPos(mob.getX(), mob.getY(), mob.getZ(),
                decodeAngle(mob.getyRot()), decodeAngle(mob.getxRot()));
        spawn(mob.getId(), mob.getUUID(), chunkKey(mob.getX(), mob.getZ()), pos, mob);
    }

    /**
     * Ingest a painting spawn ({@code AddPainting}, separate until 1.19). A painting is a hanging entity fixed by its
     * block pos and facing, so the accumulated position is that block, which the reconstruct rebuilds it on.
     */
    private void onAddPainting(ClientboundAddPaintingPacket painting) {
        BlockPos block = painting.getPos();
        EntityPos pos = new EntityPos(block.getX(), block.getY(), block.getZ(), 0.0F, 0.0F);
        spawn(painting.getId(), painting.getUUID(), chunkKey(block.getX(), block.getZ()), pos, painting);
    }

    /**
     * Ingest an experience-orb spawn ({@code AddExperienceOrb}, separate until 1.21.5). The packet carries no UUID, so
     * a synthetic id-derived one stands in as the accumulator's identity key; orbs are transient and merge.
     */
    private void onAddExperienceOrb(ClientboundAddExperienceOrbPacket orb) {
        EntityPos pos = new EntityPos(orb.getX(), orb.getY(), orb.getZ(), 0.0F, 0.0F);
        spawn(orb.getId(), syntheticUuid(orb.getId()), chunkKey(orb.getX(), orb.getZ()), pos, orb);
    }

    /** A stable id-derived UUID for a spawn packet that carries none (the experience orb). */
    private static UUID syntheticUuid(int id) {
        return new UUID(0L, id & 0xFFFFFFFFL);
    }

    /** Whether this type feeds the range estimator: a decoration, or a non-excluded vanilla range-10 type. */
    static boolean qualifies(EntityType<?> type) {
        return DECORATION_TYPES.contains(type)
                || (type.chunkRange() == 10 && !RANGE_SAMPLING_EXCLUSIONS.contains(type));
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
        if (!qualifies(add.getType())) {
            return; // PLAYER is range 32 and never qualifies; no belt check needed
        }
        int id = add.getId();
        sampler.registerArrival(id, add.getX(), add.getZ());
        if (bundleNamedIds.contains(id)) {
            sampler.markRidden(id);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        int distanceBlocks = sampler.arrivalSample(player.getX(), player.getZ(), add.getX(), add.getZ());
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
        Vec3 base = entity.position();
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
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        int plausibleMaxBlocks = SendRangeSampler.plausibleMaxBlocks(minecraft.options.renderDistance);
        String dimensionId = DimensionType.getName(level.getDimension().getType()).toString();
        int[] ids = packet.getEntityIds();
        for (int i = 0; i < ids.length; i++) {
            int distanceBlocks = sampler.removalSample(ids[i], player.getX(), player.getZ());
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
        sampler.markMovedRelative(id, move.hasPosition() && (xa != 0 || ya != 0 || za != 0));
        EntityPos current = positionOf(id);
        if (current == null) {
            return;
        }
        float yRot = move.hasRotation() ? decodeAngle(move.getyRot()) : current.yRot();
        float xRot = move.hasRotation() ? decodeAngle(move.getxRot()) : current.xRot();
        if (!move.hasPosition()) {
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
     * Reconstruct the entity a spawn packet describes, dispatching on the concrete spawn packet the way the vanilla
     * client's per-packet handlers do: an object or living display from its {@link EntityType}, a mob from its type id,
     * a painting from its own constructor, an experience orb from its value. The item frame and leash knot are hanging
     * entities built through their positioned constructors, which set the block pos and facing their save reads; a bare
     * {@code EntityType.create} would leave those null and this band has no {@code Entity.recreateFromPacket} to fill
     * them, so {@code Entity.save} would throw. Null for a packet this does not spawn from, or a type with no factory.
     * The caller applies the identity, position, synced values, and equipment.
     */
    static @Nullable Entity createSpawnEntity(Packet<?> spawn, Level level) {
        if (spawn instanceof ClientboundAddEntityPacket) {
            ClientboundAddEntityPacket add = (ClientboundAddEntityPacket) spawn;
            EntityType<?> type = add.getType();
            if (type == EntityType.ITEM_FRAME) {
                return new ItemFrame(level, new BlockPos(add.getX(), add.getY(), add.getZ()),
                        Direction.from3DDataValue(add.getData()));
            }
            if (type == EntityType.LEASH_KNOT) {
                return new LeashFenceKnotEntity(level, new BlockPos(add.getX(), add.getY(), add.getZ()));
            }
            return type.create(level);
        }
        if (spawn instanceof ClientboundAddMobPacket) {
            return EntityType.create(((ClientboundAddMobPacket) spawn).getType(), level);
        }
        if (spawn instanceof ClientboundAddPaintingPacket) {
            ClientboundAddPaintingPacket painting = (ClientboundAddPaintingPacket) spawn;
            return new Painting(level, painting.getPos(), painting.getDirection(), painting.getMotive());
        }
        if (spawn instanceof ClientboundAddExperienceOrbPacket) {
            ClientboundAddExperienceOrbPacket orb = (ClientboundAddExperienceOrbPacket) spawn;
            return new ExperienceOrb(level, orb.getX(), orb.getY(), orb.getZ(), orb.getValue());
        }
        return null;
    }
}
