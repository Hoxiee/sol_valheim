package vice.sol_valheim;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
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
 */
public final class SOLValheimNetwork
{
    public static final ResourceLocation FOOD_VALUES = new ResourceLocation(SOLValheim.MOD_ID, "food_values");

    /** Entries per packet. Each entry is a handful of bytes, so this stays far below the 32k limit. */
    private static final int BATCH_SIZE = 512;

    /** Client side accumulator for a multi packet transfer. */
    private static Map<Item, ModConfig.Common.FoodConfig> incoming;

    private SOLValheimNetwork() {}

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
    }

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

        if (!NetworkManager.canPlayerReceive(player, FOOD_VALUES))
            return;

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

    private static void sendBatch(ServerPlayer player, boolean first, boolean last, List<Map.Entry<Item, ModConfig.Common.FoodConfig>> entries) {
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
}
