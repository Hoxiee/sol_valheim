package vice.sol_valheim;

import dev.architectury.networking.NetworkManager;
#if PRE_CURRENT_MC_1_20_1
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
#endif
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import vice.sol_valheim.utils.RegistryHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server to client transfer of the resolved food values.
 * <p>
 * Without this, a client whose config or datapacks differ from the server's shows wrong hearts,
 * wrong timers and wrong tooltips - the common case in a modpack, where the server config is the
 * one that was tuned. Only the derived numbers are sent, not the raw config, and the table is split
 * over several packets because a large pack easily has a few thousand food items.
 * <p>
 * Two transports behind one API: up to 1.20.1 packets are raw buffers keyed by id, from 1.20.5 on
 * vanilla wants typed payloads, and so does Architectury's networking.
 */
public final class SOLValheimNetwork
{
    public static final ResourceLocation FOOD_VALUES = RegistryHelper.of(SOLValheim.MOD_ID, "food_values");
    public static final ResourceLocation SERVER_FLAGS = RegistryHelper.of(SOLValheim.MOD_ID, "server_flags");

    /** Entries per packet. Each entry is a handful of bytes, so this stays far below the 32k limit. */
    private static final int BATCH_SIZE = 512;

    /** Client side accumulator for a multi packet transfer. */
    private static Map<Item, ModConfig.Common.FoodConfig> incoming;

    private SOLValheimNetwork() {}

    #if PRE_CURRENT_MC_1_20_1
    /** Must only be called from client initialisation - S2C receivers do not exist on a server. */
    public static void registerClientReceivers() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, FOOD_VALUES, (buffer, context) -> {
            var first = buffer.readBoolean();
            var last = buffer.readBoolean();
            var count = buffer.readVarInt();

            Map<Item, ModConfig.Common.FoodConfig> batch = new HashMap<>(count);
            for (int index = 0; index < count; index++) {
                // read before the lookup so an unknown item does not desync the buffer
                var id = buffer.readResourceLocation();
                var entry = readEntry(buffer);
                var item = RegistryHelper.getItem(id);
                if (item != null)
                    batch.put(item, entry);
            }

            context.queue(() -> accept(first, last, batch));
        });

        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SERVER_FLAGS, (buffer, context) -> {
            // read everything before touching the holder so a malformed packet cannot half-apply
            var sprintRequiresFood = buffer.readBoolean();
            var respawnGracePeriod = buffer.readVarInt();
            var regenDelay = buffer.readVarInt();
            var modeOrdinal = buffer.readByte();
            var decayOrdinal = buffer.readByte();
            var decayStart = buffer.readFloat();
            var decayMin = buffer.readFloat();

            context.queue(() -> SOLValheimClient.acceptFlags(sprintRequiresFood, respawnGracePeriod, regenDelay,
                    modeOrdinal, decayOrdinal, decayStart, decayMin));
        });
    }
    #elif MC_1_21_1
    /**
     * Payload types this JVM has already put into the network registry. Registering a receiver
     * registers the type as a side effect, and registering anything twice throws - so the client
     * entrypoint marks them here, and the server-side send path only registers what is still
     * missing (which is exactly the dedicated-server case, where no receiver ever ran).
     */
    private static final java.util.Set<Object> REGISTERED_TYPES = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private static boolean markRegistered(Object type) {
        return REGISTERED_TYPES.add(type);
    }

    /** Must only be called from client initialisation - S2C receivers do not exist on a server. */
    public static void registerClientReceivers() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, FoodValuesPayload.TYPE, FoodValuesPayload.CODEC,
                (payload, context) -> context.queue(() -> {
                    Map<Item, ModConfig.Common.FoodConfig> batch = new HashMap<>(payload.batches().size());
                    for (var entry : payload.batches()) {
                        // an item from a mod this client lacks simply drops out of the batch
                        var item = RegistryHelper.getItem(entry.item());
                        if (item != null)
                            batch.put(item, fromBatch(entry));
                    }

                    accept(payload.first(), payload.last(), batch);
                }));

        // registerReceiver registered the type as a side effect - remember that for the send path
        markRegistered(FoodValuesPayload.TYPE);

        NetworkManager.registerReceiver(NetworkManager.Side.S2C, ServerFlagsPayload.TYPE, ServerFlagsPayload.CODEC,
                (payload, context) -> context.queue(() ->
                        SOLValheimClient.acceptFlags(payload.sprintRequiresFood(), payload.respawnGracePeriod(),
                                payload.regenDelay(), payload.effectModeOrdinal(), payload.decayModeOrdinal(),
                                payload.decayStartFraction(), payload.decayMinFraction())));

        markRegistered(ServerFlagsPayload.TYPE);
    }

    private static ModConfig.Common.FoodConfig fromBatch(FoodValuesPayload.Batch batch) {
        var config = new ModConfig.Common.FoodConfig();

        // the numbers arrive already resolved, so they go in as explicit overrides
        config.overrides = new ModConfig.Common.OverridesConfig();
        config.overrides.time = batch.time();
        config.overrides.health = batch.hearts();
        config.overrides.regen = batch.regen();

        for (var effect : batch.effects()) {
            var mobEffect = new ModConfig.Common.MobEffectConfig();
            mobEffect.ID = effect.id();
            mobEffect.duration = effect.duration();
            mobEffect.amplifier = effect.amplifier();
            config.extraEffects.add(mobEffect);
        }

        return config;
    }
    #endif

    private static void accept(boolean first, boolean last, Map<Item, ModConfig.Common.FoodConfig> batch) {
        if (first || incoming == null)
            incoming = new HashMap<>();

        incoming.putAll(batch);

        if (last) {
            FoodConfigManager.setSynced(incoming);
            SOLValheim.LOGGER.info("[sol_valheim] Received {} food values from the server", incoming.size());
            incoming = null;
        }
    }

    public static void sendTo(ServerPlayer player) {
        if (SOLValheim.Config == null || !SOLValheim.Config.common.syncFoodValuesToClients)
            return;

        #if PRE_CURRENT_MC_1_20_1
        if (!NetworkManager.canPlayerReceive(player, FOOD_VALUES))
            return;
        #elif MC_1_21_1
        // dedicated server: nobody ran the client receivers, so the types are not known yet
        if (markRegistered(FoodValuesPayload.TYPE))
            NetworkManager.registerS2CPayloadType(FoodValuesPayload.TYPE, FoodValuesPayload.CODEC);
        if (markRegistered(ServerFlagsPayload.TYPE))
            NetworkManager.registerS2CPayloadType(ServerFlagsPayload.TYPE, ServerFlagsPayload.CODEC);

        if (!NetworkManager.canPlayerReceive(player, FoodValuesPayload.TYPE))
            return;
        #endif

        var entries = new ArrayList<>(FoodConfigManager.localEntries().entrySet());
        if (entries.isEmpty()) {
            sendBatch(player, true, true, List.of());
            return;
        }

        for (int start = 0; start < entries.size(); start += BATCH_SIZE) {
            var end = Math.min(entries.size(), start + BATCH_SIZE);
            sendBatch(player, start == 0, end == entries.size(), entries.subList(start, end));
        }
    }

    public static void sendToAll(Iterable<ServerPlayer> players) {
        for (var player : players)
            sendTo(player);
    }

    /**
     * The handful of common config values the client's own gates and HUD read: the sprint rule and
     * grace period, the regeneration delay and the effect mode for tooltips. Without this a client
     * whose local config differs from the server's shows hints the server then contradicts. Always
     * sent - unlike food values these are a few bytes, and without them the client simply lies.
     */
    public static void sendFlags(ServerPlayer player) {
        #if PRE_CURRENT_MC_1_20_1
        if (SOLValheim.Config == null || !NetworkManager.canPlayerReceive(player, SERVER_FLAGS))
            return;

        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        writeFlags(buffer);
        NetworkManager.sendToPlayer(player, SERVER_FLAGS, buffer);
        #else
        if (SOLValheim.Config == null)
            return;

        // dedicated server: nobody ran the client receivers, so register what is missing.
        // In singleplayer the client entrypoint already did, and markRegistered says so.
        if (!REGISTERED_TYPES.contains(FoodValuesPayload.TYPE) && markRegistered(FoodValuesPayload.TYPE))
            NetworkManager.registerS2CPayloadType(FoodValuesPayload.TYPE, FoodValuesPayload.CODEC);
        if (markRegistered(ServerFlagsPayload.TYPE))
            NetworkManager.registerS2CPayloadType(ServerFlagsPayload.TYPE, ServerFlagsPayload.CODEC);

        if (!NetworkManager.canPlayerReceive(player, ServerFlagsPayload.TYPE))
            return;

        var config = SOLValheim.Config.common;
        NetworkManager.sendToPlayer(player, new ServerFlagsPayload(
                config.sprintRequiresFood,
                config.respawnGracePeriod,
                config.regenDelay,
                (byte) (config.foodEffectMode == null ? 0 : config.foodEffectMode.ordinal()),
                (byte) (config.foodDecayMode == null ? 0 : config.foodDecayMode.ordinal()),
                config.foodDecayStartFraction,
                config.foodDecayMinFraction));
        #endif
    }

    public static void sendFlagsToAll(Iterable<ServerPlayer> players) {
        for (var player : players)
            sendFlags(player);
    }

    private static void sendBatch(ServerPlayer player, boolean first, boolean last, List<Map.Entry<Item, ModConfig.Common.FoodConfig>> entries) {
        #if PRE_CURRENT_MC_1_20_1
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBoolean(first);
        buffer.writeBoolean(last);
        buffer.writeVarInt(entries.size());

        for (var entry : entries) {
            var id = RegistryHelper.getItemId(entry.getKey());
            if (id == null)
                continue;

            buffer.writeResourceLocation(id);
            writeEntry(buffer, entry.getValue());
        }

        NetworkManager.sendToPlayer(player, FOOD_VALUES, buffer);
        #elif MC_1_21_1
        // Only the three derived numbers plus the effect list travel. Nutrition and the multipliers
        // are server side inputs to those numbers and are of no use to the client.
        var batches = new ArrayList<FoodValuesPayload.Batch>(entries.size());
        for (var entry : entries) {
            var id = RegistryHelper.getItemId(entry.getKey());
            if (id == null)
                continue;

            var config = entry.getValue();
            var effects = new ArrayList<FoodValuesPayload.EffectData>(config.extraEffects.size());
            for (var effect : config.extraEffects)
                effects.add(new FoodValuesPayload.EffectData(effect.ID == null ? "" : effect.ID, effect.duration, effect.amplifier));

            batches.add(new FoodValuesPayload.Batch(id, config.getTime(), config.getHearts(),
                    config.getHealthRegen(), effects));
        }

        NetworkManager.sendToPlayer(player, new FoodValuesPayload(first, last, batches));
        #endif
    }

    #if PRE_CURRENT_MC_1_20_1
    private static void writeFlags(FriendlyByteBuf buffer) {
        var config = SOLValheim.Config.common;
        buffer.writeBoolean(config.sprintRequiresFood);
        buffer.writeVarInt(config.respawnGracePeriod);
        buffer.writeVarInt(config.regenDelay);
        buffer.writeByte(config.foodEffectMode == null ? 0 : config.foodEffectMode.ordinal());
        buffer.writeByte(config.foodDecayMode == null ? 0 : config.foodDecayMode.ordinal());
        buffer.writeFloat(config.foodDecayStartFraction);
        buffer.writeFloat(config.foodDecayMinFraction);
    }

    /**
     * Only the three derived numbers plus the effect list travel. Nutrition and the multipliers are
     * server side inputs to those numbers and are of no use to the client.
     */
    private static void writeEntry(FriendlyByteBuf buffer, ModConfig.Common.FoodConfig config) {
        buffer.writeVarInt(config.getTime());
        buffer.writeVarInt(config.getHearts());
        buffer.writeFloat(config.getHealthRegen());

        buffer.writeVarInt(config.extraEffects.size());
        for (var effect : config.extraEffects) {
            buffer.writeUtf(effect.ID == null ? "" : effect.ID, 256);
            buffer.writeFloat(effect.duration);
            buffer.writeVarInt(effect.amplifier);
        }
    }

    private static ModConfig.Common.FoodConfig readEntry(FriendlyByteBuf buffer) {
        var config = new ModConfig.Common.FoodConfig();

        // the numbers arrive already resolved, so they go in as explicit overrides
        config.overrides = new ModConfig.Common.OverridesConfig();
        config.overrides.time = buffer.readVarInt();
        config.overrides.health = buffer.readVarInt();
        config.overrides.regen = buffer.readFloat();

        var effects = buffer.readVarInt();
        for (int index = 0; index < effects; index++) {
            var effect = new ModConfig.Common.MobEffectConfig();
            effect.ID = buffer.readUtf(256);
            effect.duration = buffer.readFloat();
            effect.amplifier = buffer.readVarInt();
            config.extraEffects.add(effect);
        }

        return config;
    }
    #endif
}
