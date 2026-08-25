package vice.sol_valheim;

#if MC_1_21_1
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * One batch of resolved food values, server to client - see {@link SOLValheimNetwork} for why the
 * table travels at all. A large pack needs several of these, hence the first/last markers.
 */
public record FoodValuesPayload(boolean first, boolean last, List<Batch> batches) implements CustomPacketPayload
{
    public static final Type<FoodValuesPayload> TYPE =
            new Type<>(vice.sol_valheim.utils.RegistryHelper.of(SOLValheim.MOD_ID, "food_values"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FoodValuesPayload> CODEC =
            StreamCodec.of(FoodValuesPayload::encode, FoodValuesPayload::decode);

    /** One dish: its id plus the three derived numbers and whatever effects travel with it. */
    public record Batch(ResourceLocation item, int time, int hearts, float regen, List<EffectData> effects) {}

    public record EffectData(String id, float duration, int amplifier) {}

    private static void encode(RegistryFriendlyByteBuf buffer, FoodValuesPayload payload) {
        buffer.writeBoolean(payload.first());
        buffer.writeBoolean(payload.last());
        buffer.writeVarInt(payload.batches().size());

        for (var batch : payload.batches()) {
            buffer.writeResourceLocation(batch.item());
            buffer.writeVarInt(batch.time());
            buffer.writeVarInt(batch.hearts());
            buffer.writeFloat(batch.regen());

            buffer.writeVarInt(batch.effects().size());
            for (var effect : batch.effects()) {
                buffer.writeUtf(effect.id() == null ? "" : effect.id(), 256);
                buffer.writeFloat(effect.duration());
                buffer.writeVarInt(effect.amplifier());
            }
        }
    }

    private static FoodValuesPayload decode(RegistryFriendlyByteBuf buffer) {
        var first = buffer.readBoolean();
        var last = buffer.readBoolean();
        var count = buffer.readVarInt();

        // read every field before resolving anything, so an unknown item cannot desync the buffer
        var batches = new ArrayList<Batch>(count);
        for (int index = 0; index < count; index++) {
            var item = buffer.readResourceLocation();
            var time = buffer.readVarInt();
            var hearts = buffer.readVarInt();
            var regen = buffer.readFloat();

            var effects = buffer.readVarInt();
            var effectList = new ArrayList<EffectData>(effects);
            for (int e = 0; e < effects; e++) {
                effectList.add(new EffectData(buffer.readUtf(256), buffer.readFloat(), buffer.readVarInt()));
            }

            batches.add(new Batch(item, time, hearts, regen, effectList));
        }

        return new FoodValuesPayload(first, last, batches);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
#endif
