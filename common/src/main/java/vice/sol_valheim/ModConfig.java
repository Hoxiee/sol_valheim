package vice.sol_valheim;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.serializer.PartitioningSerializer;
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import vice.sol_valheim.utils.RegistryHelper;

import java.util.*;


@Config(name = SOLValheim.MOD_ID)
@Config.Gui.Background("minecraft:textures/block/stone.png")
public class ModConfig extends PartitioningSerializer.GlobalData {

    /**
     * Resolved food values for an item, or null when the item is not treated as food at all.
     * <p>
     * Resolution order is datapack override &gt; config entry &gt; auto generated - see
     * {@link FoodConfigManager}. This is a pure lookup: it never mutates the config, so it is safe
     * to call from the render thread and from tooltips.
     */
    public static Common.FoodConfig getFoodConfig(Item item) {
        return FoodConfigManager.get(item);
    }

    @ConfigEntry.Category("common")
    @ConfigEntry.Gui.TransitiveObject()
    public Common common = new Common();

    @ConfigEntry.Category("client")
    @ConfigEntry.Gui.TransitiveObject()
    public Client client = new Client();

    @Config(name = "common")
    public static final class Common implements ConfigData {


        @ConfigEntry.Gui.Tooltip() @Comment("Default time in seconds that food should last per saturation level")
        public int defaultTimer = 180;

        @ConfigEntry.Gui.Tooltip() @Comment("Maximum number of hearts achievable via food")
        public int maxFoodHealth = 40;

        @ConfigEntry.Gui.Tooltip() @Comment("Multiplier for health gained from food")
        public float nutritionHealthModifier = 1f;

        @ConfigEntry.Gui.Tooltip() @Comment("Ticks between regeneration steps (lower is faster, minimum 1)")
        public int regenSpeedModifier = 5;

        @ConfigEntry.Gui.Tooltip() @Comment("Time in ticks that regeneration should wait after taking damage")
        public int regenDelay = 20 * 10;

        @ConfigEntry.Gui.Tooltip() @Comment("Time in seconds after spawning before sprinting is disabled")
        public int respawnGracePeriod = 10;

        @ConfigEntry.Gui.Tooltip() @Comment("Extra speed given when your hearts are full (0 to disable)")
        public float speedBoost = 0.20f;

        @ConfigEntry.Gui.Tooltip() @Comment("Hearts from food required before the speed boost applies")
        public int speedBoostMinHearts = 10;

        @ConfigEntry.Gui.Tooltip() @Comment("Number of hearts to start with")
        public int startingHealth = 3;

        @ConfigEntry.Gui.Tooltip() @Comment("Number of food slots (range 1-8, default 3)")
        public int maxSlots = 3;

        @ConfigEntry.Gui.Tooltip() @Comment("Percentage remaining before you can eat again")
        public float eatAgainPercentage = 0.25F;

        @ConfigEntry.Gui.Tooltip() @Comment("Seconds of food left below which you can always eat again")
        public int eatAgainMinSeconds = 60;

        @ConfigEntry.Gui.Tooltip() @Comment("Shortest time in seconds any food can last")
        public int minFoodSeconds = 300;

        @ConfigEntry.Gui.Tooltip() @Comment("Require at least one food slot to be filled in order to sprint")
        public boolean sprintRequiresFood = true;

        @ConfigEntry.Gui.Tooltip() @Comment("Keep vanilla natural regeneration on top of the food based regeneration")
        public boolean vanillaRegeneration = false;

        @ConfigEntry.Gui.Tooltip() @Comment("Send the server food values to clients so tooltips and the hud always match the server")
        public boolean syncFoodValuesToClients = true;

        @ConfigEntry.Gui.Tooltip() @Comment("Write auto generated food values into this file so they can be edited by hand")
        public boolean persistGeneratedFoodValues = true;

        @ConfigEntry.Gui.Tooltip() @Comment("Grant the Rested effect for sleeping through a night or sheltering beside a fire")
        public boolean restedEnabled = true;

        @ConfigEntry.Gui.Tooltip() @Comment("Seconds of Rested granted by sleeping; sheltering by a fire tops up to this")
        public int restedDurationSeconds = 480;

        @ConfigEntry.Gui.Tooltip() @Comment("Health regeneration multiplier while Rested")
        public float restedRegenMultiplier = 1.5F;

        @ConfigEntry.Gui.Tooltip() @Comment("Fraction of every dish's remaining time kept after dying (0 loses everything, 1 keeps everything)")
        public float keepFoodPercentageOnDeath = 0F;

        @ConfigEntry.Gui.Tooltip() @Comment("Weakness level applied while every food slot is empty (0 disables)")
        public int emptyStomachWeakness = 1;

        @ConfigEntry.Gui.Tooltip() @Comment("Slowness level applied while every food slot is empty (0 disables)")
        public int emptyStomachSlowness = 0;

        @ConfigEntry.Gui.Tooltip() @Comment("Mining fatigue level applied while every food slot is empty (0 disables)")
        public int emptyStomachMiningFatigue = 1;

        @ConfigEntry.Gui.Tooltip() @Comment("Restore full health after sleeping through a night")
        public boolean healFullOnSleep = true;

        @ConfigEntry.Gui.Tooltip() @Comment("Grant the Weakened effect on death, temporarily cutting maximum health")
        public boolean weakenedOnDeath = true;

        @ConfigEntry.Gui.Tooltip() @Comment("Seconds the Weakened effect lasts, across respawns")
        public int weakenedSeconds = 120;

        @ConfigEntry.Gui.Tooltip() @Comment("Fraction of maximum health cut while Weakened (0.3 = minus 30%)")
        public float weakenedHealthPenalty = 0.3F;

        @ConfigEntry.Gui.Tooltip() @Comment("What happens to a dish's extra effects: ONCE - applied once on eating; REAPPLY - kept topped up for as long as the dish lasts; FADE - the level steps down with the remaining food (Strength 3 -> 2 -> 1 -> gone)")
        public FoodEffectMode foodEffectMode = FoodEffectMode.ONCE;

        @ConfigEntry.Gui.Tooltip(count = 2) @Comment("""
            What happens to a dish's hearts as it runs out: OFF - full hearts until expiry;
            LINEAR - hearts fall with the remaining time from the moment of eating;
            LATE - full hearts while more than foodDecayStartFraction remains, then they fall towards foodDecayMinFraction;
            STEPS - hearts step down in quarters (100% -> 75% -> 50% -> floor);
            VALHEIM - hearts scale as remaining^0.3 towards foodDecayMinFraction, so a dish stays significant almost to the end.
            Applies to food slots only; the drink slot always gives its full value.
            """)
        public FoodDecayMode foodDecayMode = FoodDecayMode.VALHEIM;

        @ConfigEntry.Gui.Tooltip() @Comment("LATE mode: fraction of the dish's lifetime below which its hearts begin to fade")
        public float foodDecayStartFraction = 0.5F;

        @ConfigEntry.Gui.Tooltip() @Comment("Fraction of its hearts a dish still gives at the moment it expires")
        public float foodDecayMinFraction = 0.25F;

        /**
         * How a dish's hearts scale with the share of its lifetime that is left. Every mode receives
         * the same inputs and returns a multiplier on the dish's configured hearts, so new decay
         * curves are a new constant here and nothing else changes.
         */
        public enum FoodDecayMode {
            /** Full hearts until the dish expires. */
            OFF {
                public float heartsFactor(float remaining, float start, float floor) { return 1f; }
            },
            /** Hearts fall in a straight line from full towards the floor across the whole meal. */
            LINEAR {
                public float heartsFactor(float remaining, float start, float floor) {
                    remaining = Mth.clamp(remaining, 0f, 1f);
                    return floor + (1f - floor) * remaining;
                }
            },
            /** Full hearts until {@code start} of the lifetime remains, then a straight line to the floor. */
            LATE {
                public float heartsFactor(float remaining, float start, float floor) {
                    remaining = Mth.clamp(remaining, 0f, 1f);
                    if (start <= 0f)
                        return LINEAR.heartsFactor(remaining, start, floor);

                    if (remaining >= start)
                        return 1f;

                    return floor + (1f - floor) * (remaining / start);
                }
            },
            /** Hearts hold at quarters of the lifetime: full, then 75%, then 50%, then the floor. */
            STEPS {
                public float heartsFactor(float remaining, float start, float floor) {
                    remaining = Mth.clamp(remaining, 0f, 1f);
                    var level = (float) Math.ceil(remaining * 4.0) / 4f;
                    return Math.max(floor, level);
                }
            },
            /**
             * Valheim's own curve: hearts scale as {@code remaining^0.3} towards the floor. The shallow
             * exponent keeps a dish significant almost to the end - 75% of its time left is still ~92%
             * of its heart range, 10% left still half.
             */
            VALHEIM {
                public float heartsFactor(float remaining, float start, float floor) {
                    remaining = Mth.clamp(remaining, 0f, 1f);
                    return floor + (1f - floor) * (float) Math.pow(remaining, VALHEIM_EXPONENT);
                }
            };

            /** The exponent of {@link #VALHEIM} - the original game's own constant. */
            public static final float VALHEIM_EXPONENT = 0.3f;

            /**
             * @param remaining fraction of the dish's lifetime left, 0 at expiry
             * @param start LATE mode threshold below which fading begins
             * @param floor multiplier at the moment of expiry
             * @return multiplier on the dish's configured hearts
             */
            public abstract float heartsFactor(float remaining, float start, float floor);
        }

        /** How a dish's extra effects behave over the dish's lifetime. */
        public enum FoodEffectMode {
            /** Applied once on eating; how long they stick around is the duration fraction. */
            ONCE,
            /** Kept topped up for as long as the dish lasts, at full strength. */
            REAPPLY,
            /** The effect lasts the whole dish, its level stepping down as the food depletes. */
            FADE
        }

        @ConfigEntry.Gui.Tooltip() @Comment("Boost given to other foods when drinking")
        public float drinkSlotFoodEffectivenessBonus = 0.10F;

        @ConfigEntry.Gui.Tooltip() @Comment("Simulate food ticking down during night")
        public boolean passTicksDuringNight = true;

        @ConfigEntry.Gui.Tooltip(count = 4) @Comment("""
            Balance generated food values against vanilla instead of using the raw linear formulas.
            Keeps one dish from being better on every axis at once: hearts, duration and regen share a
            bounded budget, so a stronger dish on one axis is weaker on the others, and how big that
            budget is depends on how much work the dish took to make - measured from the recipe tree, not
            from the mod it came from. Turn off for the legacy behaviour. "overrides" always win either way.
        """)
        public boolean balanceFoodValues = true;

        @ConfigEntry.Gui.Tooltip() @Comment("Raw power (nutrition x saturationModifier) at which a plainly cooked dish sits mid budget. Raise to make all food weaker")
        public float balancePivot = 13.7F;

        @ConfigEntry.Gui.Tooltip(count = 3) @Comment("""
            How much a dish's crafting cost is allowed to matter (0 disables, 1 is default, 2 doubles it).
            Cost is distinct ingredients plus how deep the recipe tree goes, so a pot meal of five things
            outclasses a steak on a fire. At 0 a steak and a stew with the same nutrition are equal.
        """)
        public float balanceEffortWeight = 0.3F;

        @ConfigEntry.Gui.Tooltip() @Comment("Schema version of the generated values below - managed by the mod, do not edit")
        public int foodConfigVersion = 0;

        @ConfigEntry.Gui.Tooltip(count = 5) @Comment("""
            Food nutrition and effect overrides (Auto Generated if Empty)
            These are the INPUTS. With balanceFoodValues on, hearts/duration/regen are worked out from
            them; balanced results are never written back here, so a new mod always gets re-weighed.
            - nutrition: How much food this is. Drives the budget, and leans it towards hearts
            - saturationModifier: Leans the budget towards duration, and counts towards the budget
            - healthRegenModifier: Leans the budget towards regen
            - extraEffects: Extra effects provided by eating the food. Format: { String ID, float duration, int amplifier }
            - overrides: Ignore doing calculations and set the value explicitly. Values can be set to null when not overriding. Format: { int time, int health, float regen }
              Whatever you set here wins; whatever you leave null still comes from the balance model.
              This is the escape hatch for a dish with no recipe to measure - loot only food reads as
              gathered, so an end game drop looks as cheap as an apple until you say otherwise
            
            Behaviours controlled by tags:
            #sol_valheim:resets_food - Resets all active food
            #sol_valheim:can_eat_early - Food that can be eaten prematurely. Note: Some food can be eaten early even without this tag.
            #sol_valheim:not_consumable - Items that use the drinking animation but should not fill the drink slot

            Datapacks win over this file. Drop json files at data/<namespace>/sol_valheim/food/<item>.json:
            { "nutrition": 8, "saturationModifier": 1.0, "healthRegenModifier": 1.25, "time": 24000, "health": 8, "regen": 1.5,
              "effects": [ { "id": "minecraft:speed", "duration": 0.5, "amplifier": 1 } ] }
        """)
        public LinkedHashMap<String, FoodConfig> foodConfigs = new LinkedHashMap<>();

        /**
         * Clamps every value into a range the game can actually run with. Without this a hand edited
         * config could divide by zero in the regeneration tick or leave the player unable to eat.
         */
        @Override
        public void validatePostLoad() {
            defaultTimer = Mth.clamp(defaultTimer, 1, 60 * 60 * 24);
            maxFoodHealth = Mth.clamp(maxFoodHealth, 1, 512);
            nutritionHealthModifier = Mth.clamp(nutritionHealthModifier, 0f, 100f);
            regenSpeedModifier = Mth.clamp(regenSpeedModifier, 1, 20 * 60);
            regenDelay = Mth.clamp(regenDelay, 0, 20 * 60 * 60);
            respawnGracePeriod = Mth.clamp(respawnGracePeriod, 0, 60 * 60);
            speedBoost = Mth.clamp(speedBoost, 0f, 10f);
            startingHealth = Mth.clamp(startingHealth, 1, maxFoodHealth);
            speedBoostMinHearts = Mth.clamp(speedBoostMinHearts, 0, maxFoodHealth);
            maxSlots = Mth.clamp(maxSlots, 1, ValheimFoodData.SLOT_LIMIT);
            eatAgainPercentage = Mth.clamp(eatAgainPercentage, 0f, 1f);
            eatAgainMinSeconds = Mth.clamp(eatAgainMinSeconds, 0, 60 * 60 * 24);
            minFoodSeconds = Mth.clamp(minFoodSeconds, 1, 60 * 60 * 24);
            drinkSlotFoodEffectivenessBonus = Mth.clamp(drinkSlotFoodEffectivenessBonus, -0.99f, 10f);
            balancePivot = Mth.clamp(balancePivot, 1f, 1000f);
            balanceEffortWeight = Mth.clamp(balanceEffortWeight, 0f, 2f);
            foodConfigVersion = Mth.clamp(foodConfigVersion, 0, 1000);
            keepFoodPercentageOnDeath = Mth.clamp(keepFoodPercentageOnDeath, 0f, 1f);
            restedDurationSeconds = Mth.clamp(restedDurationSeconds, 30, 60 * 60 * 24);
            restedRegenMultiplier = Mth.clamp(restedRegenMultiplier, 1f, 10f);
            foodDecayStartFraction = Mth.clamp(foodDecayStartFraction, 0f, 1f);
            foodDecayMinFraction = Mth.clamp(foodDecayMinFraction, 0f, 1f);
            emptyStomachWeakness = Mth.clamp(emptyStomachWeakness, 0, 5);
            emptyStomachSlowness = Mth.clamp(emptyStomachSlowness, 0, 5);
            emptyStomachMiningFatigue = Mth.clamp(emptyStomachMiningFatigue, 0, 5);
            weakenedSeconds = Mth.clamp(weakenedSeconds, 1, 60 * 60 * 24);
            weakenedHealthPenalty = Mth.clamp(weakenedHealthPenalty, 0f, 0.95f);
            if (foodEffectMode == null)
                foodEffectMode = FoodEffectMode.ONCE;
            if (foodDecayMode == null)
                foodDecayMode = FoodDecayMode.OFF;

            if (foodConfigs == null)
                foodConfigs = new LinkedHashMap<>();

            foodConfigs.values().removeIf(Objects::isNull);
            foodConfigs.values().forEach(FoodConfig::validate);
        }

        public static final class FoodConfig implements ConfigData {
            public int nutrition;
            public float saturationModifier = 1f;
            public float healthRegenModifier = 1f;
            public List<MobEffectConfig> extraEffects = new ArrayList<>();

            public OverridesConfig overrides = null;

            /** Duration in ticks. Always at least one tick, so callers can divide by it safely. */
            public int getTime() {
                if (overrides != null && overrides.time != null)
                    return Math.max(1, overrides.time);

                var common = SOLValheim.Config.common;
                return (int) Math.max(common.defaultTimer * 20L * saturationModifier * nutrition, common.minFoodSeconds * 20L);
            }

            /** Health in half hearts. */
            public int getHearts() {
                if (overrides != null && overrides.health != null)
                    return Math.max(0, overrides.health);

                return Math.round(Math.max(nutrition * SOLValheim.Config.common.nutritionHealthModifier, 2));
            }

            public float getHealthRegen() {
                if (overrides != null && overrides.regen != null)
                    return Math.max(0f, overrides.regen);

                return Mth.clamp(nutrition * 0.10f * healthRegenModifier, 0.25f, 2f);
            }

            /**
             * Deep copy, so a derived value can be written somewhere that is not the config.
             * {@link FoodConfigManager#rebuild()} publishes balanced entries into its own cache and must
             * not touch the instances living in {@code foodConfigs} - those hold the raw inputs, and
             * overwriting them would persist a computed value as an authored one.
             */
            /**
             * True when all three derived values are set outright, so nothing is left for the balance
             * model to decide. Partial pins - {@code health} alone, say - are deliberately not "pinned":
             * the axes the author left blank still go through the model rather than falling back to the
             * legacy formula behind their back.
             */
            public boolean isFullyPinned() {
                return overrides != null && overrides.time != null && overrides.health != null && overrides.regen != null;
            }

            public FoodConfig copy() {
                var clone = new FoodConfig();
                clone.nutrition = nutrition;
                clone.saturationModifier = saturationModifier;
                clone.healthRegenModifier = healthRegenModifier;

                clone.extraEffects = new ArrayList<>();
                if (extraEffects != null) {
                    for (var effect : extraEffects) {
                        if (effect == null)
                            continue;

                        var copied = new MobEffectConfig();
                        copied.ID = effect.ID;
                        copied.duration = effect.duration;
                        copied.amplifier = effect.amplifier;
                        clone.extraEffects.add(copied);
                    }
                }

                if (overrides != null) {
                    clone.overrides = new OverridesConfig();
                    clone.overrides.time = overrides.time;
                    clone.overrides.health = overrides.health;
                    clone.overrides.regen = overrides.regen;
                }

                return clone;
            }

            public void validate() {
                nutrition = Mth.clamp(nutrition, 0, 1024);
                saturationModifier = Mth.clamp(saturationModifier, 0f, 100f);
                healthRegenModifier = Mth.clamp(healthRegenModifier, 0f, 100f);

                if (extraEffects == null)
                    extraEffects = new ArrayList<>();

                extraEffects.removeIf(Objects::isNull);
                for (var effect : extraEffects) {
                    effect.duration = Mth.clamp(effect.duration, 0f, 100f);
                    effect.amplifier = Mth.clamp(effect.amplifier, 1, 256);
                }

                if (overrides != null) {
                    if (overrides.time != null) overrides.time = Math.max(1, overrides.time);
                    if (overrides.health != null) overrides.health = Math.max(0, overrides.health);
                    if (overrides.regen != null) overrides.regen = Math.max(0f, overrides.regen);
                }
            }

            @Override
            public String toString() {
                return "FoodConfig{" +
                        "nutrition=" + nutrition +
                        ", saturationModifier=" + saturationModifier +
                        ", healthRegenModifier=" + healthRegenModifier +
                        ", extraEffects=" + extraEffects +
                        '}';
            }
        }

        public static final class MobEffectConfig implements ConfigData {
            @ConfigEntry.Gui.Tooltip() @Comment("Mob Effect ID")
            public String ID;

            @ConfigEntry.Gui.Tooltip() @Comment("Effect duration percentage (1f is the entire food duration)")
            public float duration = 1f;

            @ConfigEntry.Gui.Tooltip() @Comment("Effect Level")
            public int amplifier = 1;

            /** @return null for a blank, malformed or unknown effect id rather than throwing. */
            public MobEffect getEffect() {
                return RegistryHelper.getMobEffect(ID);
            }
        }

        public static final class OverridesConfig implements ConfigData {
            @ConfigEntry.Gui.Tooltip() @Comment("How long the specified food lasts in ticks")
            public Integer time;

            @ConfigEntry.Gui.Tooltip() @Comment("How much health the specified food gives (1 = half a heart)")
            public Integer health;

            @ConfigEntry.Gui.Tooltip() @Comment("How much regen the specified food gives")
            public Float regen;
        }

    }

    @Config(name = "client")
    public static final class Client implements ConfigData {
        @ConfigEntry.Gui.Tooltip @Comment("Show the food hud")
        public boolean showFoodHud = true;
        @ConfigEntry.Gui.Tooltip @Comment("Enlarge the currently eaten food icons, small icons disable timer text")
        public boolean useLargeIcons = true;
        @ConfigEntry.Gui.Tooltip @Comment("Show the remaining time on each food (requires large icons)")
        public boolean showTimerText = true;
        @ConfigEntry.Gui.Tooltip @Comment("Position configuration for the root food hud")
        public FoodComponentConfig foodHudConfig = new FoodComponentConfig();
        @ConfigEntry.Gui.Tooltip @Comment("Show regen delay meter")
        public boolean showRegenMeter = true;
        @ConfigEntry.Gui.Tooltip @Comment("Show a small indicator when sprinting is unavailable (empty stomach) or about to become unavailable (respawn grace running out)")
        public boolean showSprintHint = true;
        @ConfigEntry.Gui.Tooltip @Comment("What plays when a dish runs out")
        public ExpiryCue expiryCue = ExpiryCue.BOTH;
        @ConfigEntry.Gui.Tooltip @Comment("Play a quiet sound when a dish starts fading (foodDecayMode)")
        public boolean decayCue = true;
        @ConfigEntry.Gui.Tooltip @Comment("Position configuration for the regen indicator")
        public RegenComponentConfig regenHudConfig = new RegenComponentConfig();
        @ConfigEntry.Gui.Tooltip @Comment("Position configuration for the sprint indicator")
        public RegenComponentConfig sprintHudConfig = new SprintComponentConfig();

        /** How the client reacts to a locally counted dish hitting zero. */
        public enum ExpiryCue {
            NONE,
            /** A brief highlight over the slot that just emptied. */
            HUD,
            /** One quiet sound, never a chat or action bar message. */
            SOUND,
            BOTH
        }

        @Override
        public void validatePostLoad() {
            if (expiryCue == null)
                expiryCue = ExpiryCue.BOTH;
            if (foodHudConfig == null)
                foodHudConfig = new FoodComponentConfig();
            if (regenHudConfig == null)
                regenHudConfig = new RegenComponentConfig();
            if (sprintHudConfig == null)
                sprintHudConfig = new SprintComponentConfig();
            if (foodHudConfig.slotOffsets == null)
                foodHudConfig.slotOffsets = new ArrayList<>();

            foodHudConfig.slotOffsets.removeIf(Objects::isNull);
        }

        public static class RegenComponentConfig {
            @ConfigEntry.Gui.Tooltip @Comment("X position offset in scaled pixels")
            public int xOffset = -100;
            @ConfigEntry.Gui.Tooltip @Comment("Y position offset in scaled pixels")
            public int yOffset = -39;
            @ConfigEntry.Gui.Tooltip @Comment("X position relative to screen, 0 = left, 1 = right")
            public float xAnchor = 0.5f;
            @ConfigEntry.Gui.Tooltip @Comment("Y position relative to screen, 0 = up, 1 = down")
            public float yAnchor = 1.0f;
        }

        /** Same fields as the regen dial, but defaults to hugging the hearts from the right. */
        public static class SprintComponentConfig extends RegenComponentConfig {
            public SprintComponentConfig() {
                xOffset = 94;
                yOffset = -39;
            }
        }

        public static class SlotComponentConfig {
            @ConfigEntry.Gui.Tooltip @Comment("X position offset in scaled pixels")
            public int xOffset = 0;
            @ConfigEntry.Gui.Tooltip @Comment("Y position offset in scaled pixels")
            public int yOffset = 0;
        }

        public static class FoodComponentConfig {
            @ConfigEntry.Gui.Tooltip @Comment("X position offset in scaled pixels")
            public int xOffset = 92;
            @ConfigEntry.Gui.Tooltip @Comment("Y position offset in scaled pixels")
            public int yOffset = -39;
            @ConfigEntry.Gui.Tooltip @Comment("X position relative to screen, 0 = left, 1 = right")
            public float xAnchor = 0.5f;
            @ConfigEntry.Gui.Tooltip @Comment("Y position relative to screen, 0 = up, 1 = down")
            public float yAnchor = 1.0f;
            @ConfigEntry.Gui.Tooltip @Comment("X position multiplier between elements, 1 = shift right, -1 = shift left, 0 = unaffected")
            public int xGap = -1;
            @ConfigEntry.Gui.Tooltip @Comment("Y position multiplier between elements, 1 = shift down, -1 = shift up, 0 = unaffected")
            public int yGap = 0;
            @ConfigEntry.Gui.Tooltip @Comment("""
                    You should have the same number of entries as your max number of slots + drink slot, otherwise some slots may be un-styled.
                    Should follow the format: {int xOffset, int yOffset} for each slot you want to style. First entry affects first/rightmost slot, second affects second slot, etc...
                    """)
            public List<SlotComponentConfig> slotOffsets = new ArrayList<>();
        }
    }
}