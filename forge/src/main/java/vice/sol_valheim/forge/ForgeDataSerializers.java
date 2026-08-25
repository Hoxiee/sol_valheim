package vice.sol_valheim.forge;

import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import vice.sol_valheim.SOLValheim;
import vice.sol_valheim.ValheimFoodData;

/**
 * Forge, same as NeoForge, forbids vanilla's static serializer registration - the ids must come
 * from its own registry. The serializer instance is the one common code already holds; only its
 * registry id is assigned here.
 */
public final class ForgeDataSerializers
{
    private static final DeferredRegister<EntityDataSerializer<?>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS, SOLValheim.MOD_ID);

    static {
        SERIALIZERS.register("food_data", () -> ValheimFoodData.FOOD_DATA_SERIALIZER);
    }

    /** Called from the mod constructor with the mod event bus. */
    public static void register(IEventBus modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
