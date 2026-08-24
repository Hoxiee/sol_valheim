package vice.sol_valheim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MilkBucketItem;
import net.minecraft.world.item.PotionItem;
import vice.sol_valheim.utils.RegistryHelper;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns the resolved food values for every item in the game.
 * <p>
 * Three sources feed into it, highest priority first:
 * <ol>
 *   <li>datapack files at {@code data/<namespace>/sol_valheim/food/<item>.json}</li>
 *   <li>hand written entries in the {@code foodConfigs} block of the common config</li>
 *   <li>values generated from the item's vanilla {@link FoodProperties}</li>
 * </ol>
 * The resolved table is rebuilt as a whole and published as an immutable snapshot, so lookups from
 * the render thread and from the tooltip callback never see a half built map and never mutate one.
 * The old behaviour - generating a config entry inside the getter - could and did write to a shared
 * {@code LinkedHashMap} from the render thread.
 */
public final class FoodConfigManager
{
    /** Values resolved locally: from datapacks and the local config. */
    private static volatile Map<Item, ModConfig.Common.FoodConfig> localCache = Collections.emptyMap();

    /** Values a remote server sent us. Null when singleplayer or when the server does not sync. */
    private static volatile Map<Item, ModConfig.Common.FoodConfig> syncedCache = null;

    /** Overrides read from datapacks on the last reload. */
    private static volatile Map<Item, ModConfig.Common.FoodConfig> datapackEntries = Collections.emptyMap();

    private FoodConfigManager() {}

    /**
     * @return the resolved values for {@code item}, or null when the item is not food as far as this
     *         mod is concerned.
     */
    public static ModConfig.Common.FoodConfig get(Item item) {
        if (item == null)
            return null;

        var synced = syncedCache;
        if (synced != null) {
            var found = synced.get(item);
            if (found != null)
                return found;
        }

        return localCache.get(item);
    }

    public static Map<Item, ModConfig.Common.FoodConfig> localEntries() {
        return localCache;
    }

    public static void setSynced(Map<Item, ModConfig.Common.FoodConfig> entries) {
        syncedCache = entries == null ? null : Collections.unmodifiableMap(entries);
    }

    /** Called when leaving a world so a remote server's values do not leak into the next one. */
    public static void clearSynced() {
        syncedCache = null;
    }

    public static PreparableReloadListener datapackListener() {
        return new DatapackLoader();
    }

    /**
     * Rebuilds the whole table from the current config plus the last datapack reload. Cheap enough to
     * run on world load and on {@code /solvalheim reload}; roughly one pass over the item registry.
     */
    public static synchronized void rebuild() {
        var config = SOLValheim.Config;
        if (config == null)
            return;

        var overrides = datapackEntries;
        Map<Item, ModConfig.Common.FoodConfig> resolved = new IdentityHashMap<>(1024);
        Map<String, ModConfig.Common.FoodConfig> generated = new LinkedHashMap<>();

        for (var item : RegistryHelper.allItems()) {
            if (!isFoodLike(item))
                continue;

            var id = RegistryHelper.getItemId(item);
            if (id == null)
                continue;

            var key = id.toString();
            var entry = config.common.foodConfigs.get(key);
            if (entry == null) {
                entry = generateDefault(item, id);
                generated.put(key, entry);
            }

            var override = overrides.get(item);
            resolved.put(item, override != null ? override : entry);
        }

        localCache = Collections.unmodifiableMap(resolved);

        if (!generated.isEmpty() && config.common.persistGeneratedFoodValues) {
            config.common.foodConfigs.putAll(generated);
            AutoConfig.getConfigHolder(ModConfig.class).save();
            SOLValheim.LOGGER.info("[sol_valheim] Wrote {} newly generated food values to the config", generated.size());
        }

        SOLValheim.LOGGER.info("[sol_valheim] Resolved food values for {} items ({} from datapacks)", resolved.size(), overrides.size());
    }

    /**
     * Anything the mod should track. Deliberately does not consult item tags: this runs before tags
     * are bound, where {@code ItemStack.is(TagKey)} silently answers false for everything.
     */
    public static boolean isFoodLike(Item item) {
        if (item == null || item == Items.AIR)
            return false;

        // cake is eaten as a block, so the item itself is not edible
        if (item == Items.CAKE)
            return true;

        if (item.isEdible())
            return true;

        return ValheimFoodData.isDrinkable(item);
    }

    private static ModConfig.Common.FoodConfig generateDefault(Item item, ResourceLocation id) {
        var config = new ModConfig.Common.FoodConfig();

        FoodProperties food = item == Items.CAKE
                ? new FoodProperties.Builder().nutrition(10).saturationMod(0.7f).build()
                : item.getFoodProperties();

        // Drinks are not edible, so they have no vanilla food properties to read. Edible drinks -
        // honey bottles, most modded juices - keep their own values instead of being flattened.
        if (food == null) {
            var path = id.getPath();
            if (item instanceof PotionItem || path.contains("potion"))
                food = new FoodProperties.Builder().nutrition(4).saturationMod(0.75f).build();
            else if (item instanceof MilkBucketItem || path.contains("milk"))
                food = new FoodProperties.Builder().nutrition(6).saturationMod(1f).build();
            else
                food = new FoodProperties.Builder().nutrition(2).saturationMod(0.5f).build();
        }

        config.nutrition = food.getNutrition();
        config.saturationModifier = food.getSaturationModifier();
        config.healthRegenModifier = 1f;

        // Farmer's Delight style cooked meals are meant to beat their raw ingredients
        if (id.getNamespace().equals("farmersdelight") || id.getNamespace().startsWith("farmers")) {
            config.nutrition = (int) (config.nutrition * 1.25f);
            config.saturationModifier = config.saturationModifier * 1.10f;
            config.healthRegenModifier = 1.25f;
        }

        if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
            config.nutrition = 10;
            config.healthRegenModifier = 1.5f;
        }

        config.validate();
        return config;
    }

    private static ModConfig.Common.FoodConfig parse(JsonObject json) {
        var config = new ModConfig.Common.FoodConfig();
        config.nutrition = json.has("nutrition") ? json.get("nutrition").getAsInt() : 0;
        config.saturationModifier = json.has("saturationModifier") ? json.get("saturationModifier").getAsFloat() : 1f;
        config.healthRegenModifier = json.has("healthRegenModifier") ? json.get("healthRegenModifier").getAsFloat() : 1f;

        if (json.has("time") || json.has("health") || json.has("regen")) {
            config.overrides = new ModConfig.Common.OverridesConfig();
            if (json.has("time")) config.overrides.time = json.get("time").getAsInt();
            if (json.has("health")) config.overrides.health = json.get("health").getAsInt();
            if (json.has("regen")) config.overrides.regen = json.get("regen").getAsFloat();
        }

        if (json.has("effects")) {
            for (var element : json.getAsJsonArray("effects")) {
                var object = element.getAsJsonObject();
                var effect = new ModConfig.Common.MobEffectConfig();
                effect.ID = object.get("id").getAsString();
                effect.duration = object.has("duration") ? object.get("duration").getAsFloat() : 1f;
                effect.amplifier = object.has("amplifier") ? object.get("amplifier").getAsInt() : 1;
                config.extraEffects.add(effect);
            }
        }

        config.validate();
        return config;
    }

    private static final class DatapackLoader extends SimpleJsonResourceReloadListener
    {
        private static final Gson GSON = new GsonBuilder().setLenient().create();

        private DatapackLoader() {
            super(GSON, "sol_valheim/food");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager manager, ProfilerFiller profiler) {
            Map<Item, ModConfig.Common.FoodConfig> parsed = new IdentityHashMap<>();

            for (var entry : entries.entrySet()) {
                try {
                    var json = entry.getValue().getAsJsonObject();

                    // the file name names the item unless an explicit "item" field says otherwise
                    var target = json.has("item")
                            ? RegistryHelper.getItem(json.get("item").getAsString())
                            : RegistryHelper.getItem(entry.getKey());

                    if (target == null) {
                        SOLValheim.LOGGER.warn("[sol_valheim] Ignoring food override {} - no such item is registered", entry.getKey());
                        continue;
                    }

                    parsed.put(target, parse(json));
                } catch (Exception exception) {
                    SOLValheim.LOGGER.error("[sol_valheim] Could not read food override {}", entry.getKey(), exception);
                }
            }

            datapackEntries = Collections.unmodifiableMap(parsed);
            rebuild();
        }
    }
}
