package vice.sol_valheim.utils;

#if PRE_CURRENT_MC_1_19_2
import net.minecraft.core.Registry;
#elif POST_CURRENT_MC_1_20_1
import net.minecraft.core.registries.BuiltInRegistries;
#endif

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Version independent, null safe registry lookups.
 * <p>
 * Unknown ids return null instead of the registry default. That matters because the item registry
 * is defaulted: {@code get("somemod:pie")} for an uninstalled mod used to hand back air, which then
 * became an invisible food slot the player could never clear.
 */
public final class RegistryHelper
{
    private RegistryHelper() {}

    public static List<Item> allItems() {
        #if PRE_CURRENT_MC_1_19_2
        return Registry.ITEM.stream().toList();
        #elif POST_CURRENT_MC_1_20_1
        return BuiltInRegistries.ITEM.stream().toList();
        #endif
    }

    public static Item getItem(String id) {
        return getItem(parse(id));
    }

    public static Item getItem(ResourceLocation id) {
        if (id == null)
            return null;

        #if PRE_CURRENT_MC_1_19_2
        if (!Registry.ITEM.containsKey(id))
            return null;
        var item = Registry.ITEM.get(id);
        #elif POST_CURRENT_MC_1_20_1
        if (!BuiltInRegistries.ITEM.containsKey(id))
            return null;
        var item = BuiltInRegistries.ITEM.get(id);
        #endif

        return (item == null || item == Items.AIR) ? null : item;
    }

    public static ResourceLocation getItemId(Item item) {
        if (item == null)
            return null;

        #if PRE_CURRENT_MC_1_19_2
        return Registry.ITEM.getKey(item);
        #elif POST_CURRENT_MC_1_20_1
        return BuiltInRegistries.ITEM.getKey(item);
        #endif
    }

    public static MobEffect getMobEffect(String id) {
        var location = parse(id);
        if (location == null)
            return null;

        #if PRE_CURRENT_MC_1_19_2
        return Registry.MOB_EFFECT.get(location);
        #elif POST_CURRENT_MC_1_20_1
        return BuiltInRegistries.MOB_EFFECT.get(location);
        #endif
    }

    /** Lenient id parse - returns null rather than throwing on garbage from a config file. */
    public static ResourceLocation parse(String id) {
        if (id == null || id.isBlank())
            return null;

        return ResourceLocation.tryParse(id.trim());
    }
}
