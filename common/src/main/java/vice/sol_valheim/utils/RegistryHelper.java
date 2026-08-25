package vice.sol_valheim.utils;

#if PRE_CURRENT_MC_1_19_2
import net.minecraft.core.Registry;
#elif POST_CURRENT_MC_1_20_1
import net.minecraft.core.Holder;
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
        var item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
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
        return BuiltInRegistries.MOB_EFFECT.getOptional(location).orElse(null);
        #endif
    }

    /**
     * Fixed namespaced id - the ResourceLocation constructor is private from 1.21 on.
     */
    public static ResourceLocation of(String namespace, String path) {
        #if PRE_CURRENT_MC_1_20_1
        return new ResourceLocation(namespace, path);
        #elif POST_CURRENT_MC_1_20_1
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
        #endif
    }

    /**
     * Wraps a registered effect for the effect APIs that take a registry holder from 1.20.5 on -
     * {@link net.minecraft.world.effect.MobEffectInstance}, {@code hasEffect} and friends. The return
     * type differs by target on purpose: callers feed it straight back into those APIs.
     */
    #if PRE_CURRENT_MC_1_20_1
    public static MobEffect effectHolder(MobEffect effect) {
        return effect;
    }
    #elif POST_CURRENT_MC_1_20_1
    public static Holder<MobEffect> effectHolder(MobEffect effect) {
        return BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect);
    }
    #endif

    /** Lenient id parse - returns null rather than throwing on garbage from a config file. */
    public static ResourceLocation parse(String id) {
        if (id == null || id.isBlank())
            return null;

        return ResourceLocation.tryParse(id.trim());
    }
}
