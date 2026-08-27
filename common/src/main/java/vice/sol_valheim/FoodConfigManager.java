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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Owns the resolved food values for every item in the game.
 * <p>
 * Three sources feed into it, highest priority first:
 * <ol>
 *   <li>datapack files at {@code data/<namespace>/sol_valheim/food/<item>.json}</li>
 *   <li>hand written entries in the {@code foodConfigs} block of the common config</li>
 *   <li>values generated from the item's vanilla {@link FoodProperties}</li>
 * </ol>
 * Whichever source wins supplies the raw inputs; hearts, duration and regeneration then come out of
 * {@link FoodBalance}, unless the entry pins them outright with an {@code overrides} block. See
 * {@link #rebuild()} for why balanced values are never persisted back into the config.
 * <p>
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

    /** Crafting effort measured for each food on the last rebuild. Read by {@code /solvalheim balance}. */
    private static volatile Map<Item, FoodEffort.Effort> lastEfforts = Collections.emptyMap();

    /** Items whose values did not come from the balance model on the last rebuild. */
    private static volatile Set<Item> lastUnbalanced = Collections.emptySet();

    /**
     * Bumped whenever generated values need regenerating rather than reusing. 1 dropped the hardcoded
     * Farmer's Delight bump and 2 the hardcoded golden apple one, both of which were baked straight
     * into the persisted numbers - they are the model's inputs, so leaving them in place would have the
     * new model read someone else's thumb on the scale as the item's own strength.
     */
    private static final int CURRENT_FOOD_CONFIG_VERSION = 2;

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

    /** Crafting effort per food from the last rebuild. Diagnostic only, for {@code /solvalheim balance}. */
    public static Map<Item, FoodEffort.Effort> efforts() {
        return lastEfforts;
    }

    /**
     * Items whose values did not come from the balance model on the last rebuild. Every
     * food when {@code balanceFoodValues} is off. Diagnostic only, for {@code /solvalheim balance}.
     */
    public static Set<Item> unbalancedEntries() {
        return lastUnbalanced;
    }

    /**
     * True when {@code item}'s raw inputs currently come from a datapack override.
     * <p>
     * Server-side only: {@code datapackEntries} is populated exclusively by the datapack reload
     * listener, which never runs on the logical client, so on the client this method always
     * returns {@code false} even when the player connects to a server that ships datapack
     * overrides. Callers that need a true cross-side answer must derive it themselves.
     */
    public static boolean isDatapackSourced(Item item) {
        return item != null && datapackEntries.containsKey(item);
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
     * <p>
     * Balanced values are written into <em>copies</em> and published only here - never back into
     * {@code foodConfigs}. Persisting them would make the next rebuild read them as authored, pinned
     * values, so nothing would ever rebalance after a new mod was installed or a recipe changed.
     */
    public static synchronized void rebuild() {
        rebuild("manual");
    }

    /** The source names the trigger in the log line, which is how pricing-order bugs get diagnosed. */
    public static synchronized void rebuild(String trigger) {
        var config = SOLValheim.Config;
        if (config == null)
            return;

        var common = config.common;
        var migrated = migrate(common);

        var datapack = datapackEntries;
        Map<Item, ModConfig.Common.FoodConfig> resolved = new IdentityHashMap<>(1024);
        Map<String, ModConfig.Common.FoodConfig> generated = new LinkedHashMap<>();

        Map<Item, FoodEffort.Effort> efforts = new IdentityHashMap<>(1024);
        Set<Item> unbalanced = Collections.newSetFromMap(new IdentityHashMap<>());
        var balanced = 0;

        for (var item : RegistryHelper.allItems()) {
            if (!isFoodLike(item))
                continue;

            var id = RegistryHelper.getItemId(item);
            if (id == null)
                continue;

            var key = id.toString();

            // the config keeps an entry for every food even when a datapack currently wins, so the
            // file stays a complete record of what is installed
            var entry = common.foodConfigs.get(key);
            if (entry == null) {
                entry = generateDefault(item, id);
                generated.put(key, entry);
            }

            // a datapack replaces the raw inputs and still feeds the same model; only the explicit
            // time/health/regen block skips it, which is exactly what "set the value outright" means
            var override = datapack.get(item);
            var source = override != null ? override : entry;

            // recorded even for pinned dishes, so the audit command can say what a pin is overruling
            FoodEffort.Effort effort;
            try {
                effort = FoodEffort.of(item);
            } catch (Exception exception) {
                SOLValheim.LOGGER.warn("[sol_valheim] Failed to read effort for {}; using gathered", id, exception);
                effort = FoodEffort.GATHERED;
            }
            efforts.put(item, effort);

            // nothing left to decide, or the model is off
            if (!common.balanceFoodValues || source.isFullyPinned()) {
                resolved.put(item, source);
                unbalanced.add(item);
                continue;
            }

            var multiplier = FoodEffort.multiplier(effort, common.balanceEffortWeight);

            var copy = source.copy();
            FoodBalance.applyTo(copy, FoodBalance.shape(source, common.balancePivot, multiplier), common);
            resolved.put(item, copy);
            balanced++;
        }

        localCache = Collections.unmodifiableMap(resolved);
        lastEfforts = Collections.unmodifiableMap(efforts);
        lastUnbalanced = Collections.unmodifiableSet(unbalanced);

        var persisting = !generated.isEmpty() && common.persistGeneratedFoodValues;
        if (persisting)
            common.foodConfigs.putAll(generated);

        if (persisting || migrated) {
            AutoConfig.getConfigHolder(ModConfig.class).save();
            if (persisting)
                SOLValheim.LOGGER.debug("[sol_valheim] Wrote {} newly generated food values to the config", generated.size());
        }

        SOLValheim.LOGGER.debug("[sol_valheim] Resolved food values for {} items ({} from datapacks, {} balanced, "
                        + "{} craftable items priced) via {}", resolved.size(), datapack.size(), balanced,
                FoodEffort.index().size(), trigger);

        #if MC_1_21_1
        vice.sol_valheim.mixin.FoodDataMixin.sol_valheim$markPropertiesDirty();
        vice.sol_valheim.AdvancementHelper.sol_valheim$markCacheDirty();
        #endif
    }

    /**
     * Drops generated entries written under an older scheme so this rebuild can replace them.
     * <p>
     * Needed because the old generators baked their thumb on the scale straight into the persisted
     * numbers: Farmer's Delight nutrition times 1.25 ({@code nutrition: 17} rather than 14), and the
     * golden apple written down as nutrition 10 rather than its actual 4. Those numbers are the model's
     * <em>inputs</em>, so leaving them in place would have the new model read a removed hardcode as the
     * item's genuine strength, and the inflation would outlive the code that caused it.
     * <p>
     * The config records no provenance, so "did we write this or did a human?" is answered by comparing
     * against what the old generator would have produced - see {@link #legacyDefault}. Anything that
     * differs is left strictly alone.
     * <p>
     * {@code legacyDefault} reproduces both hardcodes at once, which means a schema 1 config's Farmer's
     * Delight entries do not match it and survive. That is fine rather than lucky: schema 1 already
     * dropped that bump, so those entries hold the item's real values and are exactly what would be
     * regenerated in their place.
     *
     * @return true when the config was changed and needs saving
     */
    private static boolean migrate(ModConfig.Common common) {
        if (common.foodConfigVersion >= CURRENT_FOOD_CONFIG_VERSION)
            return false;

        // legacyDefault and generateDefault are pure and idempotent - on a 1000-entry config
        // naive calling would build 2000 FoodProperties records per migration. Memoise per id so
        // the work is done once per namespace-key.
        Map<ResourceLocation, ModConfig.Common.FoodConfig> legacyCache = new HashMap<>();
        Map<ResourceLocation, ModConfig.Common.FoodConfig> currentCache = new HashMap<>();

        var dropped = 0;
        var iterator = common.foodConfigs.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var item = RegistryHelper.getItem(entry.getKey());

            // the mod that owned it is not installed right now; the entry is harmless and might come
            // back, and we cannot tell whether it was generated without its FoodProperties
            if (item == null)
                continue;

            var id = RegistryHelper.parse(entry.getKey());
            if (!matchesLegacyDefault(entry.getValue(), item, id, legacyCache)
                    && !matchesCurrentDefault(entry.getValue(), item, id, currentCache))
                continue;

            iterator.remove();
            dropped++;
        }

        common.foodConfigVersion = CURRENT_FOOD_CONFIG_VERSION;
        SOLValheim.LOGGER.info("[sol_valheim] Food config upgraded to schema {}: dropped {} untouched generated "
                + "entries, regenerating them now (hand edited entries were kept)", CURRENT_FOOD_CONFIG_VERSION, dropped);
        return true;
    }

    /** True when {@code entry} looks exactly like something the pre balance generator wrote. */
    private static boolean matchesLegacyDefault(ModConfig.Common.FoodConfig entry, Item item, ResourceLocation id,
                                                Map<ResourceLocation, ModConfig.Common.FoodConfig> cache) {
        if (entry == null || id == null)
            return false;

        // pinned values or extra effects are hand authored by definition - the generator wrote neither
        if (entry.overrides != null)
            return false;

        if (entry.extraEffects != null && !entry.extraEffects.isEmpty())
            return false;

        var legacy = cache.computeIfAbsent(id, k -> legacyDefault(item, k));
        return entry.nutrition == legacy.nutrition
                && near(entry.saturationModifier, legacy.saturationModifier)
                && near(entry.healthRegenModifier, legacy.healthRegenModifier);
    }

    /**
     * True when {@code entry} matches what the current generator would write. Acts as a second
     * guard against the legacy trap: if a hand edit happens to share a value with {@code legacyDefault}
     * (e.g. golden apple at 10/1.5), the legacy matcher alone would delete it; requiring a non-match
     * with the current default keeps any human edit intact.
     */
    private static boolean matchesCurrentDefault(ModConfig.Common.FoodConfig entry, Item item, ResourceLocation id,
                                                 Map<ResourceLocation, ModConfig.Common.FoodConfig> cache) {
        if (entry == null || id == null)
            return false;

        if (entry.overrides != null)
            return false;

        if (entry.extraEffects != null && !entry.extraEffects.isEmpty())
            return false;

        var current = cache.computeIfAbsent(id, k -> generateDefault(item, k));
        return entry.nutrition == current.nutrition
                && near(entry.saturationModifier, current.saturationModifier)
                && near(entry.healthRegenModifier, current.healthRegenModifier);
    }

    /** Saturation round trips through json as 0.30000001192092896, so exact equality is no use. */
    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= 1e-4f;
    }

    /**
     * Reproduces the old generators, hardcodes included. Migration only: it exists to recognise the
     * mod's own past output, not to produce values. Delete once the schema version has been in the wild
     * long enough that no unmigrated config is left.
     */
    private static ModConfig.Common.FoodConfig legacyDefault(Item item, ResourceLocation id) {
        var config = generateDefault(item, id);

        // schema 0 and 1 both wrote the golden apple down as nutrition 10 - four times what the item
        // actually carries - to force it above every other snack
        if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
            config.nutrition = 10;
            config.healthRegenModifier = 1.5f;
        }

        // schema 0 only
        if (id.getNamespace().equals("farmersdelight") || id.getNamespace().startsWith("farmers")) {
            config.nutrition = (int) (config.nutrition * 1.25f);
            config.saturationModifier = config.saturationModifier * 1.10f;
            config.healthRegenModifier = 1.25f;
        }

        config.validate();
        return config;
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

        // eating became component-driven in 1.21
        #if PRE_CURRENT_MC_1_20_1
        if (item.isEdible())
            return true;
        #else
        if (item.getDefaultInstance().has(net.minecraft.core.component.DataComponents.FOOD))
            return true;
        #endif

        return ValheimFoodData.isDrinkable(item);
    }

    /**
     * The item's own food values, or null when it has none. Cake gets the same synthetic values on
     * every target - it is eaten off the block, so its item-side component is beside the point.
     */
    private static FoodProperties foodPropertiesOf(Item item) {
        // Item#getFoodProperties went away along with everything else that moved to components
        #if PRE_CURRENT_MC_1_20_1
        return item == Items.CAKE
                ? new FoodProperties.Builder().nutrition(10).saturationMod(0.7f).build()
                : item.getFoodProperties();
        #else
        if (item == Items.CAKE)
            return new FoodProperties(10, 7f, false, 1.6f, java.util.Optional.empty(), java.util.List.of());

        return item.getDefaultInstance().get(net.minecraft.core.component.DataComponents.FOOD);
        #endif
    }

    private static ModConfig.Common.FoodConfig generateDefault(Item item, ResourceLocation id) {
        try {
            return generateDefaultInternal(item, id);
        } catch (Exception exception) {
            SOLValheim.LOGGER.warn("[sol_valheim] Failed to derive default food values for {}; using zeroed config", id, exception);
            var fallback = new ModConfig.Common.FoodConfig();
            fallback.nutrition = 0;
            fallback.saturationModifier = 1f;
            fallback.healthRegenModifier = 1f;
            fallback.validate();
            return fallback;
        }
    }

    private static ModConfig.Common.FoodConfig generateDefaultInternal(Item item, ResourceLocation id) {

        var food = foodPropertiesOf(item);
        var config = new ModConfig.Common.FoodConfig();

        // Drinks are not edible, so they have no vanilla food properties to read. Edible drinks -
        // honey bottles, most modded juices - keep their own values instead of being flattened.
        if (food == null) {
            var path = id.getPath();
            // the newer constructor takes absolute saturation; these build the same values the old
            // builder expressed as nutrition x modifier
            #if PRE_CURRENT_MC_1_20_1
            if (item instanceof PotionItem || path.contains("potion"))
                food = new FoodProperties.Builder().nutrition(4).saturationMod(0.75f).build();
            else if (item instanceof MilkBucketItem || path.contains("milk"))
                food = new FoodProperties.Builder().nutrition(6).saturationMod(1f).build();
            else
                food = new FoodProperties.Builder().nutrition(2).saturationMod(0.5f).build();
            #else
            if (item instanceof PotionItem || path.contains("potion"))
                food = new FoodProperties(4, 3f, false, 1.6f, java.util.Optional.empty(), java.util.List.of());
            else if (item instanceof MilkBucketItem || path.contains("milk"))
                food = new FoodProperties(6, 6f, false, 1.6f, java.util.Optional.empty(), java.util.List.of());
            else
                food = new FoodProperties(2, 1f, false, 1.6f, java.util.Optional.empty(), java.util.List.of());
            #endif
        }

        config.nutrition = getNutrition(food);
        config.saturationModifier = getSaturationModifier(food);
        config.healthRegenModifier = 1f;

        // No per mod special cases here on purpose. "Cooked meals should beat their ingredients" is
        // measured rather than asserted - see FoodEffort.
        //
        // The golden apples are the one exception, and only on the axis their reputation actually comes
        // from. They used to be written down as nutrition 10 against the 4 the item really carries,
        // which put a two ingredient snack above every cooked meal in the game; that is gone. What is
        // left says a golden apple heals fast, not that it feeds you well, and it costs the dish
        // duration and hearts to say it - the budget is conserved.
        if (item == Items.GOLDEN_APPLE)
            config.healthRegenModifier = 1.5f;
        else if (item == Items.ENCHANTED_GOLDEN_APPLE)
            config.healthRegenModifier = 2.0f;

        config.validate();
        return config;
    }

    /** {@code FoodProperties} is a plain record from 1.20.5, so the getters differ by target. */
    private static int getNutrition(FoodProperties food) {
        #if PRE_CURRENT_MC_1_20_1
        return food.getNutrition();
        #else
        return food.nutrition();
        #endif
    }

    /**
     * The saturation <em>modifier</em> the balance model expects. 1.20.5 stores absolute saturation
     * instead (nutrition x modifier), so the modifier is derived back out - a zero nutrition food
     * keeps its raw saturation as the modifier rather than dividing by nothing.
     */
    private static float getSaturationModifier(FoodProperties food) {
        #if PRE_CURRENT_MC_1_20_1
        return food.getSaturationModifier();
        #else
        var nutrition = Math.max(1, food.nutrition());
        return food.saturation() / nutrition;
        #endif
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
            rebuild("datapack reload");
        }
    }
}
