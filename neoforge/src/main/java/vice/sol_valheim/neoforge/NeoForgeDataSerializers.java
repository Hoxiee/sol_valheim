package vice.sol_valheim.neoforge;

import net.minecraft.network.syncher.EntityDataSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import vice.sol_valheim.SOLValheim;
import vice.sol_valheim.ValheimFoodData;

/**
 * NeoForge forbids vanilla's static serializer registration - the ids must come from its own
 * registry so client and server agree on them. The serializer instance itself is the same one
 * common code already holds; only its registry id is assigned here.
 */
public final class NeoForgeDataSerializers
{
    private static final DeferredRegister<EntityDataSerializer<?>> SERIALIZERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS, SOLValheim.MOD_ID);

    static {
        SERIALIZERS.register("food_data", () -> ValheimFoodData.FOOD_DATA_SERIALIZER);
    }

    /** Called from the mod constructor with the mod event bus. */
    public static void register(IEventBus modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
