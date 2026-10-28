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
 * {@code SPacketRespawn} and {@code SPacketJoinGame} are the two inbound packets that announce the world the stream has
 * moved to, so both are routed to the accumulator's dimension marker and to the sampler, whose id book the rebuilt
 * level invalidates either way. Reading the client's live level here instead would be wrong by a tick: the tee sees the
 * packet on the Netty thread before the main thread applies it, so the entity spawns that follow it on the wire would
 * be stamped with the world the player has left.
 */
final class EntityPacketCapture
        extends
        EntityPacketAccumulator<Packet<?>, EntityDataManager.DataEntry<?>, EquipmentEntry> {
    private static volatile @Nullable EntityPacketCapture active;

    private static final int SPAWN_OBJECT_MINECART = 10;
    private static final int SPAWN_OBJECT_ITEM_FRAME = 71;
    private static final int SPAWN_OBJECT_LEASH_KNOT = 77;
    private static final int SPAWN_OBJECT_ARMOR_STAND = 78;

    private final Set<String> receivedFrames = ConcurrentHashMap.newKeySet();

    private final boolean dumpReceivedFrames;

    private final SendRangeEstimator sendRange;

    private final SendRangeSampler sampler;

    EntityPacketCapture(boolean dumpReceivedFrames, SendRangeEstimator sendRange, SendRangeSampler sampler,
            String dimensionId) {
        super(dimensionId);
        this.dumpReceivedFrames = dumpReceivedFrames;
        this.sendRange = sendRange;
        this.sampler = sampler;
    }

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

    public static @Nullable EntityPacketCapture active() {
        return active;
    }

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
        EntityPos pos = new EntityPos(add.getX(), add.getY(), add.getZ(),
                decodeAngle((byte) add.getYaw()), decodeAngle((byte) add.getPitch()));
        spawn(add.getEntityID(), add.getUniqueId(), chunkKey(add.getX(), add.getZ()), pos, add);
        if (dumpReceivedFrames && add.getType() == SPAWN_OBJECT_ITEM_FRAME) {
            receivedFrames.add(MathHelper.floor(add.getX()) + " " + MathHelper.floor(add.getY()) + " "
                    + MathHelper.floor(add.getZ()) + " " + add.getData());
        }
    }

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

    private void onAddPainting(SPacketSpawnPainting painting) {
        BlockPos block = painting.getPosition();
        EntityPos pos = new EntityPos(block.getX(), block.getY(), block.getZ(), 0.0F, 0.0F);
        spawn(painting.getEntityID(), painting.getUniqueId(), chunkKey(block.getX(), block.getZ()), pos, painting);
    }

    private void onAddExperienceOrb(SPacketSpawnExperienceOrb orb) {
        EntityPos pos = new EntityPos(orb.getX(), orb.getY(), orb.getZ(), 0.0F, 0.0F);
        spawn(orb.getEntityID(), syntheticUuid(orb.getEntityID()), chunkKey(orb.getX(), orb.getZ()), pos, orb);
    }

    private static UUID syntheticUuid(int id) {
        return new UUID(0L, id & 0xFFFFFFFFL);
    }

    private static boolean isDecorationObject(int spawnObjectType) {
        return spawnObjectType == SPAWN_OBJECT_ITEM_FRAME || spawnObjectType == SPAWN_OBJECT_ARMOR_STAND;
    }

    private static boolean isDecoration(Entity entity) {
        return entity instanceof EntityItemFrame || entity instanceof EntityPainting
                || entity instanceof EntityArmorStand;
    }

    /**
     * Reads the player and dimension from the client singleton at netty time; the render-distance bound caps what a
     * stale read can commit.
     */
    private void observeSendRange(SPacketSpawnObject add, IntSet bundleNamedIds) {
        if (!isDecorationObject(add.getType())) {
            return;
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
     * The live passenger-chain check gates SAMPLING AND REGISTRATION both (a pre-start mount is invisible to the packet
     * stream, so a frozen boarding position must never enter the book).
     */
    void primeSeed(Entity entity, double playerX, double playerZ, int plausibleMaxBlocks, String dimensionId) {
        if (!isDecoration(entity)) {
            return;
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
     * Vanilla's tracker sends a rider rotation but never a position, so a rider's held coordinates stay frozen at the
     * last position the server sent; a chunk home derived from them would revert the re-home onto its vehicle and split
     * the pair across two drain batches. A rotation-only update therefore leaves the home alone.
     */
    public void onMove(int id, short xa, short ya, short za, SPacketEntity move) {
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

    private static float decodeAngle(byte packed) {
        return packed * 360 / 256.0F;
    }

    /**
     * The minecart id defers its subtype to the packet data, so the caller resolves that ahead of this default
     * resolution.
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
     * The item frame is a hanging entity built through its positioned constructor, which sets the facing its save reads
     * (a bare create leaves it null and there is no recreate-from-packet at this band, so the save would throw).
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
            return new EntityItemFrame(level, new BlockPos(x, y, z), EnumFacing.byHorizontalIndex(add.getData()));
        }
        if (spawnObjectType == SPAWN_OBJECT_LEASH_KNOT) {
            return new EntityLeashKnot(level,
                    new BlockPos(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z)));
        }
        ResourceLocation name = objectEntityName(spawnObjectType);
        return name == null ? null : EntityList.createEntityByIDFromName(name, level);
    }

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
