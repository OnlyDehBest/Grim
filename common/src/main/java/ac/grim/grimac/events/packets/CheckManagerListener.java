package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsB;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.PlayerBaseTick;
import ac.grim.grimac.utils.anticheat.update.*;
import ac.grim.grimac.utils.blockplace.BlockPlaceResult;
import ac.grim.grimac.utils.blockplace.ConsumesBlockPlace;
import ac.grim.grimac.utils.change.BlockModification;
import ac.grim.grimac.utils.data.*;
import ac.grim.grimac.utils.inventory.Inventory;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import ac.grim.grimac.utils.math.VectorUtils;
import ac.grim.grimac.utils.nmsutil.*;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.defaulttags.BlockTags;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.protocol.world.states.type.StateValue;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import org.jetbrains.annotations.NotNull;

public class CheckManagerListener extends PacketListenerAbstract {
    private static final boolean TELEPORT_CONTAINS_POSITION = PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_26_3);

    public CheckManagerListener() {
        super(PacketListenerPriority.LOW);
    }

    private static void placeWaterLavaSnowBucket(GrimPlayer player, ItemStack held, StateType toPlace, InteractionHand hand, int sequence) {
        HitData data = WorldRayTrace.getNearestBlockHitResult(player, StateTypes.AIR, false, true, true);
        if (data == null) {
            return;
        }

        BlockPlace blockPlace = new BlockPlace(player, hand, data.position(), data.closestDirection().getFaceValue(), data.closestDirection(), held, data, sequence);
        boolean didPlace = false;

        if (Materials.isPlaceableWaterBucket(blockPlace.itemStack.getType()) && PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)) {
            blockPlace.replaceClicked = true;
            WrappedBlockState existing = blockPlace.getExistingBlockData();
            if (existing.hasProperty(StateValue.WATERLOGGED) && !existing.isWaterlogged()) {
                didPlace = true;
            }
        }

        if (!didPlace) {
            blockPlace.replaceClicked = false;
            blockPlace.set(toPlace);
        }

        if (player.gamemode != GameMode.CREATIVE) {
            player.inventory.markSlotAsResyncing(blockPlace);
            ItemStack bucketItem = ItemStack.builder().type(ItemTypes.BUCKET).amount(1).build();
            if (hand == InteractionHand.MAIN_HAND) {
                player.inventory.inventory.setHeldItem(bucketItem);
            } else {
                player.inventory.inventory.setPlayerInventoryItem(Inventory.SLOT_OFFHAND, bucketItem);
            }
        }
    }

    public static void handleQueuedPlaces(GrimPlayer player, boolean hasLook, float pitch, float yaw, long now) {
        BlockPlaceSnapshot snapshot;
        while ((snapshot = player.placeUseItemPackets.poll()) != null) {
            double lastX = player.x;
            double lastY = player.y;
            double lastZ = player.z;
            boolean lastSneaking = player.isSneaking;

            Vector3d claimed = player.packetStateData.lastClaimedPosition;
            player.x = claimed.getX();
            player.y = claimed.getY();
            player.z = claimed.getZ();
            player.isSneaking = snapshot.sneaking();

            if (player.inVehicle()) {
                Vector3d posFromVehicle = BoundingBoxSize.getRidingOffsetFromVehicle(player.compensatedEntities.self.getRiding(), player);
                player.x = posFromVehicle.getX();
                player.y = posFromVehicle.getY();
                player.z = posFromVehicle.getZ();
            }

            if ((now - player.lastBlockPlaceUseItem < 15 || player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) && hasLook) {
                player.yaw = yaw;
                player.pitch = pitch;
            }

            player.compensatedWorld.startPredicting();
            handleBlockPlaceOrUseItem(snapshot.wrapper(), player);
            player.compensatedWorld.stopPredicting(snapshot.wrapper());

            player.x = lastX;
            player.y = lastY;
            player.z = lastZ;
            player.isSneaking = lastSneaking;
        }
    }

    public static void handleQueuedBreaks(GrimPlayer player, boolean hasLook, float pitch, float yaw, long now) {
        BlockBreak blockBreak;
        while ((blockBreak = player.queuedBreaks.poll()) != null) {
            double lastX = player.x;
            double lastY = player.y;
            double lastZ = player.z;

            Vector3d claimed = player.packetStateData.lastClaimedPosition;
            player.x = claimed.getX();
            player.y = claimed.getY();
            player.z = claimed.getZ();

            if (player.inVehicle()) {
                Vector3d posFromVehicle = BoundingBoxSize.getRidingOffsetFromVehicle(player.compensatedEntities.self.getRiding(), player);
                player.x = posFromVehicle.getX();
                player.y = posFromVehicle.getY();
                player.z = posFromVehicle.getZ();
            }

            if ((now - player.lastBlockBreak < 120 || player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) && hasLook) {
                player.yaw = yaw;
                player.pitch = pitch;
            }

            player.checkManager.onPostFlyingBlockBreak(blockBreak);

            player.x = lastX;
            player.y = lastY;
            player.z = lastZ;
        }
    }

    private static void handleUseItem(GrimPlayer player, ItemStack placedWith, InteractionHand hand, int sequence) {
        if (placedWith.getType() == ItemTypes.LILY_PAD) {
            placeLilypad(player, hand, sequence);
            return;
        }

        StateType toBucketMat = Materials.transformBucketMaterial(placedWith.getType());
        if (toBucketMat != null) {
            placeWaterLavaSnowBucket(player, placedWith, toBucketMat, hand, sequence);
        }

        if (placedWith.getType() == ItemTypes.BUCKET) {
            placeBucket(player, hand, sequence);
        }
    }

    private static void handleBlockPlaceOrUseItem(PacketWrapper<?> packet, GrimPlayer player) {
        if (player.gamemode == GameMode.SPECTATOR || player.gamemode == GameMode.ADVENTURE) {
            return;
        }

        if (packet instanceof WrapperPlayClientPlayerBlockPlacement place && PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_9)) {
            if (place.getFace() == BlockFace.OTHER) {
                ItemStack placedWith = (place.getHand() == InteractionHand.OFF_HAND) ? player.inventory.getOffHand() : player.inventory.getHeldItem();
                handleUseItem(player, placedWith, place.getHand(), place.getSequence());
                return;
            }
        }

        if (packet instanceof WrapperPlayClientUseItem place) {
            ItemStack placedWith = (place.getHand() == InteractionHand.OFF_HAND) ? player.inventory.getOffHand() : player.inventory.getHeldItem();
            handleUseItem(player, placedWith, place.getHand(), place.getSequence());
            return;
        }

        if (packet instanceof WrapperPlayClientPlayerBlockPlacement place) {
            ItemStack placedWith = (place.getHand() == InteractionHand.OFF_HAND) ? player.inventory.getOffHand() : player.inventory.getHeldItem();
            ItemStack offhand = player.inventory.getOffHand();
            boolean onlyAir = placedWith.isEmpty() && offhand.isEmpty();

            if ((!player.isSneaking || onlyAir) && place.getHand() == InteractionHand.MAIN_HAND) {
                Vector3i blockPosition = place.getBlockPosition();
                BlockPlace blockPlace = new BlockPlace(player, place.getHand(), blockPosition, place.getFaceId(), place.getFace(), placedWith, WorldRayTrace.getNearestBlockHitResult(player, null, true, false, false), place.getSequence());

                StateType placedAgainst = blockPlace.getPlacedAgainstMaterial();
                if (isClientSideBlockInteraction(player, placedAgainst)) {
                    player.checkManager.onPostFlyingBlockPlace(blockPlace);
                    Vector3i location = blockPlace.position;
                    player.compensatedWorld.tickOpenable(location.x, location.y, location.z);
                    return;
                }

                if (ConsumesBlockPlace.consumesPlace(player, player.compensatedWorld.getBlock(blockPlace.position), blockPlace)) {
                    player.checkManager.onPostFlyingBlockPlace(blockPlace);
                    return;
                }
            }

            Vector3i blockPosition = place.getBlockPosition();
            BlockFace face = place.getFace();
            BlockPlace blockPlace = new BlockPlace(player, place.getHand(), blockPosition, place.getFaceId(), face, placedWith, WorldRayTrace.getNearestBlockHitResult(player, null, true, false, false), place.getSequence());
            player.checkManager.onPostFlyingBlockPlace(blockPlace);

            blockPlace.isInside = place.getInsideBlock().orElse(false);

            if (placedWith.getType().getPlacedType() != null || placedWith.getType() == ItemTypes.FLINT_AND_STEEL || placedWith.getType() == ItemTypes.FIRE_CHARGE) {
                BlockPlaceResult.getMaterialData(placedWith.getType()).applyBlockPlaceToWorld(player, blockPlace);
            }
        }
    }

    private static boolean isClientSideBlockInteraction(GrimPlayer player, StateType placedAgainst) {
        return (player.getClientVersion().isOlderThan(ClientVersion.V_1_11) && (placedAgainst == StateTypes.IRON_TRAPDOOR
                || placedAgainst == StateTypes.IRON_DOOR || BlockTags.FENCES.contains(placedAgainst)))
                || (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8) && BlockTags.CAULDRONS.contains(placedAgainst))
                || Materials.isClientSideInteractable(placedAgainst);
    }

    private static void placeBucket(GrimPlayer player, InteractionHand hand, int sequence) {
        HitData data = WorldRayTrace.getNearestBlockHitResult(player, null, true, false, true);
        if (data == null) {
            return;
        }

        BlockPlace blockPlace = new BlockPlace(player, hand, data.position(), data.closestDirection().getFaceValue(), data.closestDirection(), ItemStack.EMPTY, data, sequence);
        blockPlace.replaceClicked = true;

        boolean placed = false;
        ItemType type = null;

        if (data.state().getType() == StateTypes.POWDER_SNOW) {
            blockPlace.set(StateTypes.AIR);
            type = ItemTypes.POWDER_SNOW_BUCKET;
            placed = true;
        } else if (data.state().getType() == StateTypes.LAVA) {
            blockPlace.set(StateTypes.AIR);
            type = ItemTypes.LAVA_BUCKET;
            placed = true;
        }

        if (!placed && !player.compensatedWorld.isWaterSourceBlock(data.position().getX(), data.position().getY(), data.position().getZ())) {
            return;
        }

        StateType hitType = data.state().getType();
        if (hitType == StateTypes.KELP || hitType == StateTypes.SEAGRASS || hitType == StateTypes.TALL_SEAGRASS) {
            return;
        }

        if (!placed) {
            type = ItemTypes.WATER_BUCKET;
        }

        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)) {
            WrappedBlockState existing = blockPlace.getExistingBlockData();
            if (existing.hasProperty(StateValue.WATERLOGGED)) {
                existing.setWaterlogged(false);
                blockPlace.set(existing);
                placed = true;
            }
        }

        if (!placed) {
            blockPlace.set(StateTypes.AIR);
        }

        if (player.gamemode != GameMode.CREATIVE) {
            player.inventory.markSlotAsResyncing(blockPlace);
            setPlayerItem(player, hand, type);
        }
    }

    public static void setPlayerItem(GrimPlayer player, InteractionHand hand, ItemType type) {
        if (player.gamemode == GameMode.CREATIVE) {
            return;
        }

        ItemStack item = ItemStack.builder().type(type).amount(1).build();
        if (hand == InteractionHand.MAIN_HAND) {
            ItemStack held = player.inventory.getHeldItem();
            if (held.getAmount() == 1) {
                player.inventory.inventory.setHeldItem(item);
            } else {
                player.inventory.inventory.add(item);
                held.setAmount(held.getAmount() - 1);
            }
        } else {
            ItemStack offHand = player.inventory.getOffHand();
            if (offHand.getAmount() == 1) {
                player.inventory.inventory.setPlayerInventoryItem(Inventory.SLOT_OFFHAND, item);
            } else {
                player.inventory.inventory.add(Inventory.SLOT_OFFHAND, item);
                offHand.setAmount(offHand.getAmount() - 1);
            }
        }
    }

    private static void placeLilypad(GrimPlayer player, InteractionHand hand, int sequence) {
        HitData data = WorldRayTrace.getNearestBlockHitResult(player, null, true, false, true);
        if (data == null) {
            return;
        }

        Vector3i pos = data.position();
        if (player.compensatedWorld.getFluidLevelAt(pos.getX(), pos.getY() + 1, pos.getZ()) > 0) {
            return;
        }

        BlockPlace blockPlace = new BlockPlace(player, hand, pos, data.closestDirection().getFaceValue(), data.closestDirection(), ItemStack.EMPTY, data, sequence);
        blockPlace.replaceClicked = false;

        StateType stateType = data.state().getType();
        if (player.compensatedWorld.getWaterFluidLevelAt(pos.getX(), pos.getY(), pos.getZ()) > 0 || stateType == StateTypes.ICE || stateType == StateTypes.FROSTED_ICE) {
            Vector3i targetPos = pos.add(0, 1, 0);
            blockPlace.set(targetPos, StateTypes.LILY_PAD.createBlockState(CompensatedWorld.blockVersion));

            if (player.gamemode != GameMode.CREATIVE) {
                player.inventory.markSlotAsResyncing(blockPlace);
                if (hand == InteractionHand.MAIN_HAND) {
                    player.inventory.inventory.getHeldItem().setAmount(player.inventory.inventory.getHeldItem().getAmount() - 1);
                } else {
                    player.inventory.getOffHand().setAmount(player.inventory.getOffHand().getAmount() - 1);
                }
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player == null) {
            return;
        }

        if (event.getConnectionState() != ConnectionState.PLAY) {
            if (event.getConnectionState() == ConnectionState.CONFIGURATION) {
                player.checkManager.onPacketReceive(event);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.VEHICLE_MOVE) {
            WrapperPlayClientVehicleMove move = new WrapperPlayClientVehicleMove(event);
            Vector3d position = move.getPosition();
            player.packetStateData.lastPacketWasTeleport = player.getSetbackTeleportUtil().checkVehicleTeleportQueue(position.getX(), position.getY(), position.getZ());
        }

        TeleportAcceptData teleportData = null;
        WrapperPlayClientTeleportConfirm teleportConfirm = null;

        if (TELEPORT_CONTAINS_POSITION && event.getPacketType() == PacketType.Play.Client.TELEPORT_CONFIRM) {
            teleportConfirm = new WrapperPlayClientTeleportConfirm(event);

            if (!Double.isFinite(teleportConfirm.getX()) || !Double.isFinite(teleportConfirm.getY()) || !Double.isFinite(teleportConfirm.getZ()) || !Float.isFinite(teleportConfirm.getYaw()) || !Float.isFinite(teleportConfirm.getPitch())) {
                event.setCancelled(true);
                player.onPacketCancel();
                return;
            }

            Vector3d position = VectorUtils.clampVector(new Vector3d(teleportConfirm.getX(), teleportConfirm.getY(), teleportConfirm.getZ()));
            teleportData = player.getSetbackTeleportUtil().checkTeleportQueue(position.getX(), position.getY(), position.getZ(), teleportConfirm.getYaw(), teleportConfirm.getPitch(), teleportConfirm.getTeleportId());
            player.packetStateData.lastPacketWasTeleport = teleportData.isTeleport();
        } else if (!TELEPORT_CONTAINS_POSITION && event.getPacketType() == PacketType.Play.Client.TELEPORT_CONFIRM) {
            WrapperPlayClientTeleportConfirm confirm = new WrapperPlayClientTeleportConfirm(event);
            player.getSetbackTeleportUtil().onTeleportConfirm(confirm.getTeleportId());
        }

        WrapperPlayClientPlayerFlying flying = null;
        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            player.serverOpenedInventoryThisTick = false;
            flying = new WrapperPlayClientPlayerFlying(event);

            Location location = flying.getLocation();
            Vector3d position = VectorUtils.clampVector(location.getPosition());
            float checkYaw = flying.hasRotationChanged() ? location.getYaw() : player.yaw;
            float checkPitch = flying.hasRotationChanged() ? location.getPitch() : player.pitch;

            teleportData = !TELEPORT_CONTAINS_POSITION && flying.hasPositionChanged() ? player.getSetbackTeleportUtil().checkTeleportQueue(position.getX(), position.getY(), position.getZ(), checkYaw, checkPitch) : TeleportAcceptData.EMPTY;
            player.packetStateData.lastPacketWasTeleport = teleportData.isTeleport();

            if (flying.hasRotationChanged() && !flying.hasPositionChanged() && !flying.isOnGround() && !flying.isHorizontalCollision()) {
                RotationData data = player.pendingRotations.peek();

                if (data != null && data.transaction() == player.getLastTransactionReceived()) {
                    player.pendingRotations.remove();
                    if (data.allowRotation(location.getYaw(), location.getPitch())) {
                        player.packetStateData.lastPacketWasTeleport = true;
                    } else {
                        player.checkManager.get(BadPacketsB.class).flag();
                    }
                }
            }

            player.packetStateData.lastPacketWasOnePointSeventeenDuplicate = isDuplicatePacket(player, flying);
            if (player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
                handleDuplicatePacket(player, event, flying);
            }
        }

        if (!player.packetStateData.lastPacketWasTeleport && (player.inVehicle() ? event.getPacketType() == PacketType.Play.Client.VEHICLE_MOVE : flying != null && !player.packetStateData.lastPacketWasOnePointSeventeenDuplicate)) {
            int kbEntityId = player.inVehicle() ? player.getRidingVehicleId() : player.entityID;
            int lastTrans = player.lastTransactionReceived.get();
            TeleportData pendingTeleport = !player.inVehicle() ? player.getSetbackTeleportUtil().pendingTeleports.peek() : null;
            if (pendingTeleport != null) {
                lastTrans = Math.min(lastTrans, pendingTeleport.getTransaction());
            }

            VelocityData calculatedFirstBreadKb = player.checkManager.getKnockbackHandler().calculateFirstBreadKnockback(kbEntityId, lastTrans);
            VelocityData calculatedRequireKb = player.checkManager.getKnockbackHandler().calculateRequiredKB(kbEntityId, lastTrans, false);
            player.firstBreadKB = calculatedFirstBreadKb == null ? player.firstBreadKB : calculatedFirstBreadKb;
            player.likelyKB = calculatedRequireKb == null ? player.likelyKB : calculatedRequireKb;

            VelocityData calculateFirstBreadExplosion = player.checkManager.getExplosionHandler().getFirstBreadAddedExplosion(lastTrans);
            VelocityData calculateRequiredExplosion = player.checkManager.getExplosionHandler().getPossibleExplosions(lastTrans, false);
            player.firstBreadExplosion = calculateFirstBreadExplosion == null ? player.firstBreadExplosion : calculateFirstBreadExplosion;
            player.likelyExplosions = calculateRequiredExplosion == null ? player.likelyExplosions : calculateRequiredExplosion;
        }

        player.checkManager.onPrePredictionReceivePacket(event);

        if (event.isCancelled() && (teleportConfirm != null || flying != null || event.getPacketType() == PacketType.Play.Client.VEHICLE_MOVE)) {
            player.packetStateData.cancelDuplicatePacket = false;
            player.packetStateData.lastPacketWasTeleport = false;
            return;
        }

        if (teleportConfirm != null && teleportData.isTeleport()) {
            player.serverOpenedInventoryThisTick = false;
            handleFlying(player, teleportConfirm.getX(), teleportConfirm.getY(), teleportConfirm.getZ(), teleportConfirm.getYaw(), teleportConfirm.getPitch(), true, true, false, teleportData);
        }

        if (flying != null) {
            Location pos = flying.getLocation();
            boolean ignoreRotation = player.packetStateData.lastPacketWasOnePointSeventeenDuplicate && player.isIgnoreDuplicatePacketRotation();
            handleFlying(player, pos.getX(), pos.getY(), pos.getZ(), ignoreRotation ? 0 : pos.getYaw(), ignoreRotation ? 0 : pos.getPitch(), flying.hasPositionChanged(), flying.hasRotationChanged() && !ignoreRotation, flying.isOnGround(), teleportData);
        }

        if (event.getPacketType() == PacketType.Play.Client.VEHICLE_MOVE && player.inVehicle()) {
            WrapperPlayClientVehicleMove move = new WrapperPlayClientVehicleMove(event);
            Vector3d position = move.getPosition();

            player.lastX = player.x;
            player.lastY = player.y;
            player.lastZ = player.z;

            Vector3d clamp = VectorUtils.clampVector(position);
            player.x = clamp.getX();
            player.y = clamp.getY();
            player.z = clamp.getZ();

            player.yaw = move.getYaw();
            player.pitch = move.getPitch();

            final VehiclePositionUpdate update = new VehiclePositionUpdate(clamp, position, move.getYaw(), move.getPitch(), move.isOnGround(), player.packetStateData.lastPacketWasTeleport);
            player.checkManager.onVehiclePositionUpdate(update);

            player.packetStateData.receivedSteerVehicle = false;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            handleDigging(player, event);
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            handleBlockPlacementPacket(player, event);
        }

        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            handleUseItemPacket(player, event);
        }

        player.checkManager.onPacketReceive(event);

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())
                || event.getPacketType() == PacketType.Play.Client.WINDOW_CONFIRMATION
                || event.getPacketType() == PacketType.Play.Client.PONG) {
            player.packetStateData.horseInteractCausedForcedRotation = false;
        }

        if (player.packetStateData.cancelDuplicatePacket) {
            event.setCancelled(true);
            player.packetStateData.cancelDuplicatePacket = false;
        }

        if (flying != null && player.packetStateData.lastPacketWasTeleport) {
            SetBackData setback = teleportData.getSetback();
            if (flying.hasPositionChanged() && setback != null && !setback.isPlugin() && !setback.isVehicle()) {
                flying.setOnGround(setback.isOnGround());
                event.markForReEncode(true);
            }
        }

        if (event.getPacketType() == PacketType.Play.Client.CLIENT_TICK_END && player.supportsEndTick()) {
            player.serverOpenedInventoryThisTick = false;
            if (!player.packetStateData.didSendMovementBeforeTickEnd) {
                player.packetStateData.didLastLastMovementIncludePosition = player.packetStateData.didLastMovementIncludePosition;
                player.packetStateData.didLastMovementIncludePosition = false;

                if (!player.inVehicle()) {
                    player.dashableEntities.tick();
                    PlayerBaseTick.updatePlayerPose(player);
                }
            }
            player.packetStateData.didSendMovementBeforeTickEnd = false;
        }

        player.packetStateData.lastPacketWasOnePointSeventeenDuplicate = false;
        player.packetStateData.lastPacketWasTeleport = false;
    }

    private static void handleBlockPlacementPacket(GrimPlayer player, PacketReceiveEvent event) {
        WrapperPlayClientPlayerBlockPlacement packet = new WrapperPlayClientPlayerBlockPlacement(event);
        player.lastBlockPlaceUseItem = System.currentTimeMillis();

        ItemStack placedWith = (packet.getHand() == InteractionHand.OFF_HAND) ? player.inventory.getOffHand() : player.inventory.getHeldItem();

        if (placedWith != null && placedWith.getType() == ItemTypes.ENDER_PEARL) {
            player.uncertaintyHandler.lastEnderPearlThrow.reset();
        }

        if (packet.getFace() == BlockFace.OTHER && PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_9)) {
            player.placeUseItemPackets.add(new BlockPlaceSnapshot(packet, player.isSneaking));
            return;
        }

        BlockPlace blockPlace = new BlockPlace(player, packet.getHand(), packet.getBlockPosition(), packet.getFaceId(), packet.getFace(), placedWith, WorldRayTrace.getNearestBlockHitResult(player, null, true, false, false), packet.getSequence());
        blockPlace.cursor = packet.getCursorPosition();

        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_11) && player.getClientVersion().isOlderThan(ClientVersion.V_1_11)) {
            Vector3f cursor = packet.getCursorPosition();
            if (cursor.getX() * 15 % 1 == 0 && cursor.getY() * 15 % 1 == 0 && cursor.getZ() * 15 % 1 == 0) {
                int trueByteX = (int) (cursor.getX() * 15);
                int trueByteY = (int) (cursor.getY() * 15);
                int trueByteZ = (int) (cursor.getZ() * 15);
                blockPlace.cursor = new Vector3f(trueByteX / 16f, trueByteY / 16f, trueByteZ / 16f);
            }
        }

        player.checkManager.onBlockPlace(blockPlace);

        if (event.isCancelled() || blockPlace.isCancelled() || player.getSetbackTeleportUtil().shouldBlockMovement()) {
            if (!event.isCancelled()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }

            Vector3i facePos = new Vector3i(packet.getBlockPosition().getX() + packet.getFace().getModX(), packet.getBlockPosition().getY() + packet.getFace().getModY(), packet.getBlockPosition().getZ() + packet.getFace().getModZ());
            player.resyncPosition(packet.getBlockPosition(), packet.getSequence());
            player.resyncPosition(facePos, packet.getSequence());

            if (player.platformPlayer != null) {
                if (packet.getHand() == InteractionHand.MAIN_HAND) {
                    ItemStack mainHand = player.platformPlayer.getInventory().getItemInHand();
                    player.user.sendPacket(new WrapperPlayServerSetSlot(0, player.inventory.stateID, 36 + player.packetStateData.lastSlotSelected, mainHand));
                } else {
                    ItemStack offHand = player.platformPlayer.getInventory().getItemInOffHand();
                    player.user.sendPacket(new WrapperPlayServerSetSlot(0, player.inventory.stateID, 45, offHand));
                }
            }
        } else {
            player.placeUseItemPackets.add(new BlockPlaceSnapshot(packet, player.isSneaking));
        }
    }

    private static void handleUseItemPacket(GrimPlayer player, PacketReceiveEvent event) {
        WrapperPlayClientUseItem packet = new WrapperPlayClientUseItem(event);
        player.placeUseItemPackets.add(new BlockPlaceSnapshot(packet, player.isSneaking));
        player.lastBlockPlaceUseItem = System.currentTimeMillis();

        ItemStack mainHand = player.inventory.getHeldItem();
        ItemStack offHand = player.inventory.getOffHand();
        if ((mainHand != null && mainHand.getType() == ItemTypes.ENDER_PEARL) || (offHand != null && offHand.getType() == ItemTypes.ENDER_PEARL)) {
            player.uncertaintyHandler.lastEnderPearlThrow.reset();
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getConnectionState() != ConnectionState.PLAY) {
            return;
        }
        GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player == null) {
            return;
        }
        player.checkManager.onPacketSend(event);
    }

    private static boolean isDuplicatePacket(@NotNull GrimPlayer player, @NotNull WrapperPlayClientPlayerFlying flying) {
        if (player.packetStateData.lastPacketWasTeleport) {
            return false;
        }

        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_17) || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21)) {
            return false;
        }

        if (!flying.hasPositionChanged() || !flying.hasRotationChanged()) {
            return false;
        }

        if (player.inVehicle()) {
            return true;
        }

        final Vector3d position = flying.getLocation().getPosition();
        final double threshold = player.getMovementThreshold();

        return flying.isOnGround() == player.packetStateData.packetPlayerOnGround
                && player.filterMojangStupidityOnMojangStupidity.distanceSquared(position) < threshold * threshold;
    }

    private static void handleDuplicatePacket(
            @NotNull GrimPlayer player,
            @NotNull PacketReceiveEvent event,
            @NotNull WrapperPlayClientPlayerFlying flying) {
        final float yaw = flying.getLocation().getYaw();
        final float pitch = flying.getLocation().getPitch();
        final Vector3d position = flying.getLocation().getPosition();

        if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_9) && player.isCancelDuplicatePacket()) {
            player.packetStateData.cancelDuplicatePacket = true;
        } else {
            flying.setLocation(new Location(
                    player.filterMojangStupidityOnMojangStupidity.getX(),
                    player.filterMojangStupidityOnMojangStupidity.getY(),
                    player.filterMojangStupidityOnMojangStupidity.getZ(),
                    yaw,
                    pitch
            ));
            event.markForReEncode(true);
        }

        player.packetStateData.lastPacketWasOnePointSeventeenDuplicate = true;

        if (!player.isIgnoreDuplicatePacketRotation()) {
            if (player.yaw != yaw || player.pitch != pitch) {
                player.lastYaw = player.yaw;
                player.lastPitch = player.pitch;
            }

            player.yaw = yaw;
            player.pitch = pitch;
        }

        player.packetStateData.lastClaimedPosition = position;
    }

    private static void handleFlying(GrimPlayer player, double x, double y, double z, float yaw, float pitch, boolean hasPosition, boolean hasLook, boolean onGround, TeleportAcceptData teleportData) {
        long now = System.currentTimeMillis();

        if (!hasPosition) {
            player.uncertaintyHandler.lastPointThree.reset();
        }

        if (hasLook && (!player.packetStateData.lastPacketWasOnePointSeventeenDuplicate || player.yaw != yaw || player.pitch != pitch)) {
            player.lastYaw = player.yaw;
            player.lastPitch = player.pitch;
        }

        CheckManagerListener.handleQueuedPlaces(player, hasLook, pitch, yaw, now);
        CheckManagerListener.handleQueuedBreaks(player, hasLook, pitch, yaw, now);

        if (hasPosition) {
            player.packetStateData.lastClaimedPosition = new Vector3d(x, y, z);
        }

        if (!hasPosition && onGround != player.packetStateData.packetPlayerOnGround && !player.inVehicle()) {
            boolean canFeasiblyPointThree = Collisions.slowCouldPointThreeHitGround(player, player.x, player.y, player.z);
            if (!canFeasiblyPointThree && !player.compensatedWorld.isNearHardEntity(player.boundingBox.copy().expand(4))
                    || player.clientVelocity.getY() > 0.06 && !player.uncertaintyHandler.wasAffectedByStuckSpeed()) {
                player.getSetbackTeleportUtil().executeForceResync();
            } else {
                player.lastOnGround = onGround;
                player.clientClaimsLastOnGround = onGround;
                player.uncertaintyHandler.onGroundUncertain = true;
            }
        }

        if (!player.packetStateData.lastPacketWasTeleport) {
            player.packetStateData.packetPlayerOnGround = onGround;
        }

        if (hasLook) {
            player.yaw = yaw;
            player.pitch = pitch;
            player.vehicleData.playerPitch = pitch;
            player.vehicleData.playerYaw = yaw;

            final RotationUpdate update = new RotationUpdate(player.lastYaw, player.lastPitch, player.yaw, player.pitch);
            player.checkManager.onRotationUpdate(update);
        }

        if (hasPosition) {
            Vector3d position = new Vector3d(x, y, z);
            Vector3d clampVector = VectorUtils.clampVector(position);
            final PositionUpdate update = new PositionUpdate(new Vector3d(player.x, player.y, player.z), position, onGround, teleportData.getSetback(), teleportData.getTeleportData(), teleportData.isTeleport());

            if (!player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
                player.filterMojangStupidityOnMojangStupidity.copy(clampVector);
            }

            if (!player.inVehicle() && !player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
                player.lastX = player.x;
                player.lastY = player.y;
                player.lastZ = player.z;

                player.x = clampVector.getX();
                player.y = clampVector.getY();
                player.z = clampVector.getZ();

                player.checkManager.onPositionUpdate(update);
            } else if (update.isTeleport()) {
                player.getSetbackTeleportUtil().onPredictionComplete(new PredictionComplete(0, update, true));
            }
        }

        player.packetStateData.didLastLastMovementIncludePosition = player.packetStateData.didLastMovementIncludePosition;
        player.packetStateData.didLastMovementIncludePosition = hasPosition;

        if (!player.packetStateData.lastPacketWasTeleport) {
            player.packetStateData.didSendMovementBeforeTickEnd = true;
        }
    }

    private static boolean isBreakable(@NotNull StateType type) {
        return !type.isAir() && type.getHardness() != -1.0f && type != StateTypes.WATER && type != StateTypes.LAVA;
    }

    private static void handleDigging(GrimPlayer player, PacketReceiveEvent event) {
        player.lastBlockBreak = System.currentTimeMillis();

        final WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
        final DiggingAction action = packet.getAction();

        if (action != DiggingAction.START_DIGGING
                && action != DiggingAction.FINISHED_DIGGING
                && action != DiggingAction.CANCELLED_DIGGING) {
            return;
        }

        final BlockBreak blockBreak = new BlockBreak(player, packet.getBlockPosition(), packet.getBlockFace(), packet.getBlockFaceId(), action, packet.getSequence(), player.compensatedWorld.getBlock(packet.getBlockPosition()));

        player.checkManager.onBlockBreak(blockBreak);

        if (blockBreak.isCancelled()) {
            event.setCancelled(true);
            player.onPacketCancel();
            player.resyncPosition(blockBreak.position, packet.getSequence());
            return;
        }

        player.queuedBreaks.add(blockBreak);

        if (action == DiggingAction.FINISHED_DIGGING && isBreakable(blockBreak.block.getType())) {
            player.compensatedWorld.startPredicting();
            player.compensatedWorld.updateBlock(blockBreak.position.x, blockBreak.position.y, blockBreak.position.z, 0);
            player.compensatedWorld.stopPredicting(packet);
        }

        if (action == DiggingAction.START_DIGGING) {
            double damage = BlockBreakSpeed.getBlockDamage(player, blockBreak.block);

            if (damage >= 1) {
                player.compensatedWorld.startPredicting();
                player.blockHistory.add(
                        new BlockModification(
                                player.compensatedWorld.getBlock(blockBreak.position),
                                WrappedBlockState.getByGlobalId(0),
                                blockBreak.position,
                                GrimAPI.INSTANCE.getTickManager().currentTick,
                                BlockModification.Cause.START_DIGGING
                        )
                );
                if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_13) && Materials.isWaterSource(player.getClientVersion(), blockBreak.block)) {
                    player.compensatedWorld.updateBlock(blockBreak.position, StateTypes.WATER.createBlockState(CompensatedWorld.blockVersion));
                } else {
                    player.compensatedWorld.updateBlock(blockBreak.position.x, blockBreak.position.y, blockBreak.position.z, 0);
                }
                player.compensatedWorld.stopPredicting(packet);
            }
        }

        player.compensatedWorld.handleBlockBreakPrediction(packet);
    }
}
