package vice.sol_valheim;

#if MC_1_21_1
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The handful of common config values the client's own gates and HUD read, server to client - see
 * {@link SOLValheimNetwork}. A few bytes, always sent on join.
 */
public record ServerFlagsPayload(boolean sprintRequiresFood, int respawnGracePeriod, int regenDelay,
                                 byte effectModeOrdinal, byte decayModeOrdinal,
                                 float decayStartFraction, float decayMinFraction) implements CustomPacketPayload
{
    public static final Type<ServerFlagsPayload> TYPE =
            new Type<>(vice.sol_valheim.utils.RegistryHelper.of(SOLValheim.MOD_ID, "server_flags"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerFlagsPayload> CODEC =
            StreamCodec.of(ServerFlagsPayload::encode, ServerFlagsPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ServerFlagsPayload payload) {
        buffer.writeBoolean(payload.sprintRequiresFood());
        buffer.writeVarInt(payload.respawnGracePeriod());
        buffer.writeVarInt(payload.regenDelay());
        buffer.writeByte(payload.effectModeOrdinal());
        buffer.writeByte(payload.decayModeOrdinal());
        buffer.writeFloat(payload.decayStartFraction());
        buffer.writeFloat(payload.decayMinFraction());
    }

    private static ServerFlagsPayload decode(RegistryFriendlyByteBuf buffer) {
        // read everything before touching the holder so a malformed packet cannot half-apply
        var sprint = buffer.readBoolean();
        var grace = buffer.readVarInt();
        var regen = buffer.readVarInt();
        var effectMode = buffer.readByte();
        var decayMode = buffer.readByte();
        var decayStart = buffer.readFloat();
        var decayMin = buffer.readFloat();

        return new ServerFlagsPayload(sprint, grace, regen, effectMode, decayMode, decayStart, decayMin);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
#endif
