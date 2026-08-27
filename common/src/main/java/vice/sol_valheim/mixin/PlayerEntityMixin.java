package vice.sol_valheim.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vice.sol_valheim.AdvancementHelper;
import vice.sol_valheim.ModConfig;
import vice.sol_valheim.SOLValheim;
import vice.sol_valheim.SOLValheimClient;
import vice.sol_valheim.ValheimFoodData;
import vice.sol_valheim.accessors.FoodDataPlayerAccessor;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;
import vice.sol_valheim.event.SOLValheimEvents;
import vice.sol_valheim.extenders.SynchedEntityDataExtender;

import java.util.ArrayList;
import java.util.List;

import static vice.sol_valheim.ValheimFoodData.RESETS_FOOD;

@Mixin({Player.class})
public abstract class PlayerEntityMixin extends LivingEntity implements PlayerEntityMixinDataAccessor
{
    @Unique
    private static final EntityDataAccessor<ValheimFoodData> sol_valheim$DATA_ACCESSOR = SynchedEntityData.defineId(Player.class, ValheimFoodData.FOOD_DATA_SERIALIZER);

    /**
     * How long the server waits before re-sending unchanged food data. Syncing every tick - what the
     * mod used to do - resent the whole nbt blob 20 times a second per player; the client now counts
     * its own timers down between updates and this is only a correction heartbeat.
     */
    @Unique
    private static final int sol_valheim$SYNC_INTERVAL = 100;

    @Shadow
    protected FoodData foodData;

    @Unique
    private ValheimFoodData sol_valheim$food_data = new ValheimFoodData();

    @Unique
    private int sol_valheim$syncTimer;

    @Unique
    private double sol_valheim$lastAppliedHealth = Double.NaN;

    /** Last item consumed and when, used to collapse the several vanilla eat paths into one. */
    @Unique
    private Item sol_valheim$lastConsumed;

    /**
     * Last-frame snapshot of the player's food entries, used to detect items that the server
     * removed and synced down before the client could run its local tick. Without this check the
     * last dish in a session disappears silently - the server's tick already returned it, so the
     * client's tick has nothing left to expire.
     */
    @Unique
    private java.util.HashSet<Item> sol_valheim$lastDisplayed;
    /**
     * The food properties instance of the last bite. 1.21 turned {@code FoodProperties} into a
     * record and vanilla interns equal component maps, so sibling foods with identical numbers
     * (raw beef and raw rabbit, say) hand over the very same instance from two different stacks -
     * keying the collapse on it as well as the item keeps one bite from registering twice.
     */
    @Unique
    private net.minecraft.world.food.FoodProperties sol_valheim$lastConsumedProperties;

    @Unique
    private int sol_valheim$lastConsumedTick = -1;

    protected PlayerEntityMixin(EntityType<? extends LivingEntity> entityType, Level level) { super(entityType, level); }

    @Override
    @Unique
    public ValheimFoodData sol_valheim$getFoodData() {
        var player = (Player) (LivingEntity)this;
        return player.getEntityData().get(sol_valheim$DATA_ACCESSOR);
    }

    @Override
    @Unique
    public void sol_valheim$sync() {
        sol_valheim$syncTimer = sol_valheim$SYNC_INTERVAL;

        // the tracked value is the very same object we mutate, so the dirty flag has to be forced
        #if PRE_CURRENT_MC_1_19_2
        ((SynchedEntityDataExtender) this.entityData).set(sol_valheim$DATA_ACCESSOR, sol_valheim$food_data, true);
        #elif POST_CURRENT_MC_1_20_1
        this.entityData.set(sol_valheim$DATA_ACCESSOR, sol_valheim$food_data, true);
        #endif
    }

    @Override
    @Unique
    public void sol_valheim$setFoodData(ValheimFoodData data) {
        if (data == null)
            return;

        // no longer clobbering data.MaxItemSlots here: the new SOLValheimSlots API can raise or
        // lower the cap at runtime, and the saved/incoming data may carry that runtime value
        data.trimToSlots();

        sol_valheim$food_data = data;
        sol_valheim$sync();
    }

    @Override
    @Unique
    public boolean sol_valheim$consume(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return false;

        #if PRE_CURRENT_MC_1_19_2
        var level = this.level;
        #elif POST_CURRENT_MC_1_20_1
        var level = this.level();
        #endif

        if (level.isClientSide)
            return false;

        // Player.eat, FoodData.eat and completeUsingItem can all fire for a single bite
        var item = stack.getItem();

        #if MC_1_21_1
        var properties = stack.get(net.minecraft.core.component.DataComponents.FOOD);
        if (sol_valheim$lastConsumedTick == this.tickCount
                && (sol_valheim$lastConsumed == item
                || (properties != null && properties == sol_valheim$lastConsumedProperties)))
            return false;

        sol_valheim$lastConsumedProperties = properties;
        #else
        if (sol_valheim$lastConsumed == item && sol_valheim$lastConsumedTick == this.tickCount)
            return false;
        #endif

        sol_valheim$lastConsumed = item;
        sol_valheim$lastConsumedTick = this.tickCount;

        if (stack.is(RESETS_FOOD)) {
            if (!sol_valheim$food_data.isEmpty()) {
                sol_valheim$food_data.clear();
                sol_valheim$sync();
                return true;
            }
            return false;
        }

        var config = ModConfig.getFoodConfig(item);
        if (!sol_valheim$food_data.eatItem(item))
            return false;

        sol_valheim$applyExtraEffects(item);
        sol_valheim$sync();

        var self = (Player) (LivingEntity) this;
        AdvancementHelper.award(self, "root");
        AdvancementHelper.award(self, "first_meal");
        if (ValheimFoodData.isDrinkable(item))
            AdvancementHelper.award(self, "refreshed");
        if (sol_valheim$food_data.ItemEntries.size() >= sol_valheim$food_data.getMaxItemSlots())
            AdvancementHelper.award(self, "full_table");

        SOLValheimEvents.FOOD_EATEN.invoker().onFoodEaten(self, item, config);

        return true;
    }

    /**
     * Applies the {@code extraEffects} block of the food's config. The config has always had the
     * field; nothing ever read it.
     */
    @Unique
    private void sol_valheim$applyExtraEffects(Item item) {
        var config = ModConfig.getFoodConfig(item);
        if (config == null || config.extraEffects.isEmpty())
            return;

        for (var entry : config.extraEffects) {
            var effect = entry.getEffect();
            if (effect == null)
                continue;

            // duration is a fraction of how long the food itself lasts
            var duration = effect.isInstantenous() ? 1 : Math.max(1, Math.round(config.getTime() * entry.duration));

            // "Effect Level 1" in the config means level I, which vanilla calls amplifier 0
            var amplifier = Math.max(0, entry.amplifier - 1);

            this.addEffect(new MobEffectInstance(vice.sol_valheim.utils.RegistryHelper.effectHolder(effect), duration, amplifier, false, true, true));
        }
    }

    @Inject(at = {@At("HEAD")}, method = {"causeFoodExhaustion(F)V"}, cancellable = true)
    private void onAddExhaustion(float exhaustion, CallbackInfo info) {
        info.cancel();
    }

    /**
     * Valheim's undocumented Rested bonus: experience points are worth more while Rested. Every
     * source the game has - orbs from kills, mining, fishing, breeding and trading (furnaces and
     * grindstones spawn orbs too), advancement rewards and /xp - funnels through this one method
     * with the same signature in all supported versions, so one argument rewrite covers everything.
     * Only positive amounts scale: level changes run through giveExperienceLevels and stay
     * untouched. Server side only, like every vanilla caller.
     */
    @ModifyVariable(method = "giveExperiencePoints(I)V", at = @At("HEAD"), argsOnly = true)
    private int sol_valheim$restedXpBoost(int amount) {
        var config = SOLValheim.Config;
        if (config == null || !config.common.restedEnabled || amount <= 0)
            return amount;

        if (!hasEffect(vice.sol_valheim.utils.RegistryHelper.effectHolder(SOLValheim.RESTED.get())))
            return amount;

        return Math.round(amount * config.common.restedXpMultiplier);
    }

    @Inject(at = {@At("HEAD")}, method = {"getFoodData"})
    private void onGetFoodData(CallbackInfoReturnable<FoodData> cir) {
        // hack workaround for player data not being accessible in FoodData
        ((FoodDataPlayerAccessor) foodData).sol_valheim$setPlayer((Player) (LivingEntity) this);
    }

    // the 1.21 line hands the item's food properties into eat as a third argument
    #if PRE_CURRENT_MC_1_20_1
    @Inject(at = {@At("HEAD")}, method = {"eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"})
    private void onEatFood(Level world, ItemStack stack, CallbackInfoReturnable<ItemStack> info) {
        sol_valheim$consume(stack);
    }
    #elif MC_1_21_1
    @Inject(at = {@At("HEAD")}, method = {"eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/food/FoodProperties;)Lnet/minecraft/world/item/ItemStack;"})
    private void onEatFood(Level world, ItemStack stack, net.minecraft.world.food.FoodProperties properties, CallbackInfoReturnable<ItemStack> info) {
        sol_valheim$consume(stack);
    }
    #endif

    @Inject(at = {@At("HEAD")}, method = {"tick"})
    private void onTick(CallbackInfo info) {
        sol_valheim$tick();
    }

    @Unique
    private void sol_valheim$tick() {
        #if PRE_CURRENT_MC_1_19_2
        var level = this.level;
        #elif POST_CURRENT_MC_1_20_1
        var level = this.level();
        #endif

        if (level.isClientSide) {
            // the server no longer resends every tick, so run the countdown locally for a smooth hud
            var displayed = this.entityData.get(sol_valheim$DATA_ACCESSOR);
            // only your own meals may cue
            if ((Object) this == net.minecraft.client.Minecraft.getInstance().player) {
                // detect items the server removed and synced down before this tick could expire
                // them locally - without this the last dish in a session disappears silently
                if (displayed != null && sol_valheim$lastDisplayed != null) {
                    for (var prev : sol_valheim$lastDisplayed) {
                        var stillThere = false;
                        for (var entry : displayed.ItemEntries)
                            if (entry.item == prev) { stillThere = true; break; }
                        var stillInDrink = displayed.DrinkSlot != null && displayed.DrinkSlot.item == prev;
                        if (!stillThere && !stillInDrink)
                            SOLValheimClient.onFoodsExpired(ValheimFoodData.isDrinkable(prev));
                    }
                }
            }
            if (displayed != null && !displayed.isEmpty()) {
                var expired = displayed.tick();
                if ((Object) this == net.minecraft.client.Minecraft.getInstance().player) {
                    if (!expired.isEmpty()) {
                        var anyDrink = false;
                        for (var item : expired)
                            anyDrink |= ValheimFoodData.isDrinkable(item);

                        SOLValheimClient.onFoodsExpired(anyDrink);
                    }

                    SOLValheimClient.tickDecayCues(displayed);
                }
            } else if ((Object) this == net.minecraft.client.Minecraft.getInstance().player) {
                SOLValheimClient.tickDecayCues(displayed);
            }
            // remember the current set for next tick
            if ((Object) this == net.minecraft.client.Minecraft.getInstance().player) {
                if (sol_valheim$lastDisplayed == null)
                    sol_valheim$lastDisplayed = new java.util.HashSet<>();
                else
                    sol_valheim$lastDisplayed.clear();
                if (displayed != null) {
                    for (var entry : displayed.ItemEntries)
                        sol_valheim$lastDisplayed.add(entry.item);
                    if (displayed.DrinkSlot != null)
                        sol_valheim$lastDisplayed.add(displayed.DrinkSlot.item);
                }
            }
            return;
        }

        if (SOLValheim.Config == null)
            return;

        var config = SOLValheim.Config.common;
        Player player = (Player) (LivingEntity) this;

        // dishes survive death scaled down by keepFoodPercentageOnDeath; see onDie
        if (isDeadOrDying())
            return;

        var changed = false;

        // let a config change to maxSlots reach players who are already in the world. Tracked
        // against lastConfiguredSlots, not against MaxItemSlots: a runtime override set through
        // SOLValheimSlots marks the current config as consumed, so the periodic resync does not
        // spend the next 100 ticks quietly resetting a command's or an addon's change
        var slots = ValheimFoodData.configuredMaxSlots();
        if (sol_valheim$food_data.lastConfiguredSlots != slots) {
            sol_valheim$food_data.lastConfiguredSlots = slots;
            sol_valheim$food_data.MaxItemSlots = slots;
            changed |= sol_valheim$food_data.trimToSlots();
        }

        var expired = sol_valheim$food_data.tick();
        changed |= !expired.isEmpty();
        for (var food : expired)
            SOLValheimEvents.FOOD_EXPIRED.invoker().onFoodExpired(player, food);

        if (changed || --sol_valheim$syncTimer <= 0)
            sol_valheim$sync();

        // causeFoodExhaustion is cancelled in onAddExhaustion, so FoodData.tick has nothing to charge
        // against and saturation never moves from its initial value. The vanilla bar is not driven
        // from here - the mod's own gate uses ItemEntries - so a no-op write is the only legacy
        // effect of this line, and removing it costs nothing.

        var foodHealth = Math.min(config.maxFoodHealth * 2, (config.startingHealth * 2) + sol_valheim$food_data.getTotalFoodNutrition());
        sol_valheim$updateMaxHealth(player, foodHealth);

        sol_valheim$tickWeakened(player, config);

        if (config.speedBoost > 0.01f)
            sol_valheim$updateSpeedBoost(player, config, foodHealth);

        sol_valheim$enforceSprint(player, config);
        sol_valheim$applyEmptyStomachDebuffs(player, config);

        if (config.foodEffectMode != ModConfig.Common.FoodEffectMode.ONCE)
            sol_valheim$tickFoodEffects(player, config);

        var timeSinceHurt = level.getGameTime() - ((LivingEntityDamageAccessor) this).getLastDamageStamp();
        var period = Math.max(1, config.regenSpeedModifier);
        if (timeSinceHurt > config.regenDelay && player.tickCount % period == 0 && player.getHealth() < player.getMaxHealth())
        {
            var regenSpeed = sol_valheim$food_data.getRegenSpeed();
            if (config.restedEnabled && player.hasEffect(vice.sol_valheim.utils.RegistryHelper.effectHolder(SOLValheim.RESTED.get())))
                regenSpeed *= config.restedRegenMultiplier;

            player.heal(regenSpeed / 20f);
        }
    }

    /**
     * Drives max health through an additive modifier rather than {@code setBaseValue}. Setting the
     * base value every tick permanently overwrote whatever other mods, potions or attribute gear had
     * put there, and it also fought anything that changed the base itself.
     */
    @Unique
    private void sol_valheim$updateMaxHealth(Player player, float foodHealth) {
        var attribute = player.getAttribute(Attributes.MAX_HEALTH);
        if (attribute == null)
            return;
        double amount = Math.floor(foodHealth - getDefaultMaxHealth());
        var existing = attribute.getModifier(SOLValheim.FOOD_HEALTH_ID);

        if (existing == null
                || (modifierAmount(existing) != amount && sol_valheim$lastAppliedHealth != amount)) {
            if (existing != null)
                attribute.removeModifier(SOLValheim.FOOD_HEALTH_ID);

            // 1.21 drops the human readable name and keys modifiers by ResourceLocation
            #if PRE_CURRENT_MC_1_20_1
            attribute.addTransientModifier(new AttributeModifier(SOLValheim.FOOD_HEALTH_ID,
                    "sol_valheim_food_health", amount, opAddition()));
            #else
            attribute.addTransientModifier(new AttributeModifier(SOLValheim.FOOD_HEALTH_ID, amount,
                    opAddition()));
            #endif

            sol_valheim$lastAppliedHealth = amount;
        }

        var max = player.getMaxHealth();
        if (player.getHealth() > max)
            player.setHealth(max);
    }

    /** The 20 health the attribute starts with - the holder API from 1.21 reads it through {@code value()}. */
    @Unique
    private static double getDefaultMaxHealth() {
        #if PRE_CURRENT_MC_1_20_1
        return Attributes.MAX_HEALTH.getDefaultValue();
        #else
        return Attributes.MAX_HEALTH.value().getDefaultValue();
        #endif
    }

    /** 1.20.5 turned AttributeModifier into a record - the accessor differs by target. */
    @Unique
    private static double modifierAmount(AttributeModifier modifier) {
        #if PRE_CURRENT_MC_1_20_1
        return modifier.getAmount();
        #else
        return modifier.amount();
        #endif
    }

    /** 1.20.5 renamed the additive operation alongside the multiply ones. */
    @Unique
    private static AttributeModifier.Operation opAddition() {
        #if PRE_CURRENT_MC_1_20_1
        return AttributeModifier.Operation.ADDITION;
        #else
        return AttributeModifier.Operation.ADD_VALUE;
        #endif
    }

    @Unique
    private static AttributeModifier.Operation opMultipliedBase() {
        #if PRE_CURRENT_MC_1_20_1
        return AttributeModifier.Operation.MULTIPLY_BASE;
        #else
        return AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
        #endif
    }

    @Unique
    private static AttributeModifier.Operation opMultipliedTotal() {
        #if PRE_CURRENT_MC_1_20_1
        return AttributeModifier.Operation.MULTIPLY_TOTAL;
        #else
        return AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
        #endif
    }

    /**
     * Drives the death-granted Weakened effect. The counter lives on the food data (which survives
     * the respawn instance swap); this keeps a MULTIPLY_TOTAL modifier on max health and re-draws
     * the vanilla effect icon while it runs. Stateless against config changes: turning the feature
     * off, or the timer running out, simply removes the modifier again.
     */
    @Unique
    private void sol_valheim$tickWeakened(Player player, ModConfig.Common config) {
        var attribute = player.getAttribute(Attributes.MAX_HEALTH);
        if (attribute == null)
            return;

        if (!config.weakenedOnDeath || sol_valheim$food_data.weakenedTicks <= 0) {
            sol_valheim$food_data.weakenedTicks = 0;
            if (attribute.getModifier(SOLValheim.WEAKENED_ID) != null)
                attribute.removeModifier(SOLValheim.WEAKENED_ID);
            return;
        }

        sol_valheim$food_data.weakenedTicks--;

        var penalty = -Mth.clamp(config.weakenedHealthPenalty, 0f, 0.95f);
        var existing = attribute.getModifier(SOLValheim.WEAKENED_ID);
        if (existing == null || modifierAmount(existing) != penalty) {
            if (existing != null)
                attribute.removeModifier(SOLValheim.WEAKENED_ID);

            var op = opMultipliedTotal();

            #if PRE_CURRENT_MC_1_20_1
            attribute.addTransientModifier(new AttributeModifier(SOLValheim.WEAKENED_ID,
                    "sol_valheim_weakened", penalty, op));
            #else
            attribute.addTransientModifier(new AttributeModifier(SOLValheim.WEAKENED_ID, penalty, op));
            #endif
        }

        // the effect itself is cosmetic - the icon and its countdown; vanilla wipes it on death,
        // so it is quietly redrawn here from the surviving counter
        var weakened = vice.sol_valheim.utils.RegistryHelper.effectHolder(SOLValheim.WEAKENED.get());
        var marker = player.getEffect(weakened);
        if (marker == null || Math.abs(marker.getDuration() - sol_valheim$food_data.weakenedTicks) > 20)
            player.addEffect(new MobEffectInstance(weakened,
                    Math.max(1, sol_valheim$food_data.weakenedTicks), 0, false, false, true));
    }

    @Unique
    private void sol_valheim$updateSpeedBoost(Player player, ModConfig.Common config, float foodHealth) {
        var attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute == null)
            return;

        var wanted = foodHealth >= config.speedBoostMinHearts * 2;
        var existing = attribute.getModifier(SOLValheim.SPEED_BUFF_ID);

        if (!wanted) {
            if (existing != null)
                attribute.removeModifier(SOLValheim.SPEED_BUFF_ID);
            return;
        }

        // re-apply when the configured amount changed, otherwise an edit would never take effect
        if (existing != null && modifierAmount(existing) == config.speedBoost)
            return;

        if (existing != null)
            attribute.removeModifier(SOLValheim.SPEED_BUFF_ID);

        attribute.addTransientModifier(SOLValheim.createSpeedBuffModifier());
    }

    /**
     * The client decides when it starts sprinting, but the server owns whether that sticks. Without
     * this a hacked or desynced client sprints forever on an empty stomach while LocalPlayerMixin
     * only ever stopped honest ones. Mirrors that gate's exceptions exactly.
     */
    @Unique
    private void sol_valheim$enforceSprint(Player player, ModConfig.Common config) {
        if (!config.sprintRequiresFood || !player.isSprinting())
            return;

        // flight ignores the rule and a fresh spawn gets a moment to run for cover
        if (player.getAbilities().mayfly || player.tickCount < config.respawnGracePeriod * 20)
            return;

        if (sol_valheim$food_data.ItemEntries.isEmpty())
            player.setSprinting(false);
    }

    /**
     * Weakness, slowness and mining fatigue while every food slot is empty, levels from the config
     * (0 disables each). Reapplied statelessly like REAPPLY effects: a short instance topped up
     * every tick, so it lapses on its own about a second after the first bite. The sprint gate's
     * exceptions apply here too - flight ignores it and the respawn grace protects a fresh spawn.
     */
    @Unique
    private void sol_valheim$applyEmptyStomachDebuffs(Player player, ModConfig.Common config) {
        var weakness = config.emptyStomachWeakness;
        var slowness = config.emptyStomachSlowness;
        var fatigue = config.emptyStomachMiningFatigue;
        if (weakness <= 0 && slowness <= 0 && fatigue <= 0)
            return;

        if (player.getAbilities().mayfly || player.isCreative()
                || player.tickCount < config.respawnGracePeriod * 20
                || !sol_valheim$food_data.ItemEntries.isEmpty())
            return;

        // a short instance re-applied each tick, so it lapses soon after the first bite lands
        var duration = 25;
        if (weakness > 0)
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, duration, weakness - 1, false, false, true));
        if (slowness > 0)
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, slowness - 1, false, false, true));
        if (fatigue > 0)
            player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, duration, fatigue - 1, false, false, true));
    }

    /**
     * Keeps a dish's extra effects topped up for as long as the dish lasts. Stateless by design -
     * "the player is missing the effect" is the entire state machine, so nothing new has to persist.
     * Milk clearing effects gets them back next tick; that reads as intended behaviour.
     */
    /**
     * Dispatches the configured {@link ModConfig.Common.FoodEffectMode} for every active dish and
     * the drink. ONCE never reaches here - nothing to maintain after the initial application.
     */
    @Unique
    private void sol_valheim$tickFoodEffects(Player player, ModConfig.Common config) {
        if (config.foodEffectMode == ModConfig.Common.FoodEffectMode.REAPPLY) {
            sol_valheim$reapplyFoodEffects(player);
            return;
        }

        for (var entry : sol_valheim$food_data.ItemEntries)
            sol_valheim$fadeFor(player, entry);

        if (sol_valheim$food_data.DrinkSlot != null)
            sol_valheim$fadeFor(player, sol_valheim$food_data.DrinkSlot);
    }

    /**
     * FADE mode: the effect lasts the whole dish and its level steps down as the food depletes -
     * Strength 3 becomes 2, then 1, then vanishes with the last bite. The wanted level is simply
     * {@code ceil(remaining * level)}, so a level 3 dish sits at III above two thirds remaining, II
     * above one third, I after that. Stateless: the player's current instance is the only memory.
     */
    @Unique
    private void sol_valheim$fadeFor(Player player, ValheimFoodData.EatenFoodItem entry) {
        var config = ModConfig.getFoodConfig(entry.item);
        if (config == null || config.extraEffects.isEmpty() || entry.ticksLeft <= 0)
            return;

        int totalTime = config.getTime();
        float remaining = (float) entry.ticksLeft / totalTime;

        for (var effectConfig : config.extraEffects) {
            var effect = effectConfig.getEffect();
            // instant effects cannot be stretched over the remaining time, so they stay one-shot
            if (effect == null || effect.isInstantenous())
                continue;

            int levels = effectConfig.amplifier;
            int wantedAmp = Math.max(1, Math.min(levels, (int) Math.ceil(remaining * levels))) - 1;

            var current = player.getEffect(vice.sol_valheim.utils.RegistryHelper.effectHolder(effect));
            // a stronger outside effect (beacon, potion) outranks the dish; wait for it to lapse
            if (current != null && current.getAmplifier() >= wantedAmp)
                continue;

            // hold this step until the next one would begin; the last step runs to the dish's end
            int boundary = (int) Math.ceil((wantedAmp) / (double) levels * totalTime);
            int duration = Math.max(1, Math.min(entry.ticksLeft, entry.ticksLeft - boundary));

            // silent re-application: no particle burst on every step, but the hud icon stays
            player.addEffect(new MobEffectInstance(vice.sol_valheim.utils.RegistryHelper.effectHolder(effect), duration, wantedAmp, false, false, true));
        }
    }

    @Unique
    private void sol_valheim$reapplyFoodEffects(Player player) {
        for (var entry : sol_valheim$food_data.ItemEntries)
            sol_valheim$reapplyFor(player, entry);

        if (sol_valheim$food_data.DrinkSlot != null)
            sol_valheim$reapplyFor(player, sol_valheim$food_data.DrinkSlot);
    }

    @Unique
    private void sol_valheim$reapplyFor(Player player, ValheimFoodData.EatenFoodItem entry) {
        var config = ModConfig.getFoodConfig(entry.item);
        if (config == null || config.extraEffects.isEmpty() || entry.ticksLeft <= 0)
            return;

        for (var effectConfig : config.extraEffects) {
            var effect = effectConfig.getEffect();
            // instant effects cannot be stretched over the remaining time, so they stay one-shot
            if (effect == null || effect.isInstantenous() || player.hasEffect(vice.sol_valheim.utils.RegistryHelper.effectHolder(effect)))
                continue;

            var fullDuration = Math.max(1, Math.round(config.getTime() * effectConfig.duration));
            // an effect never outlives the dish that granted it
            var duration = Math.max(1, Math.min(fullDuration, entry.ticksLeft));
            var amplifier = Math.max(0, effectConfig.amplifier - 1);

            // silent re-application: no particle burst on every refresh, but the hud icon stays
            player.addEffect(new MobEffectInstance(vice.sol_valheim.utils.RegistryHelper.effectHolder(effect), duration, amplifier, false, false, true));
        }
    }

    @Inject(at = {@At("HEAD")}, method = {"canEat(Z)Z"}, cancellable = true)
    private void onCanConsume(boolean ignorehunger, CallbackInfoReturnable<Boolean> info) {
        info.setReturnValue(true);
        info.cancel();
    }

    @Inject(at = {@At("HEAD")}, method = {"hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"}, cancellable = true)
    private void onDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> info) {

        #if PRE_CURRENT_MC_1_19_2
        if (source == DamageSource.STARVE) {
        #elif POST_CURRENT_MC_1_20_1
        if (source == this.damageSources().starve()) {
        #endif
            info.setReturnValue(Boolean.FALSE);
            info.cancel();
        }
    }

    @Inject(at = {@At("TAIL")}, method = {"addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V"})
    private void onWriteCustomData(CompoundTag nbt, CallbackInfo info) {
        nbt.put("sol_food_data", sol_valheim$food_data.save(new CompoundTag()));
    }

    @Inject(at = {@At("TAIL")}, method = {"readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V"})
    private void onReadCustomData(CompoundTag nbt, CallbackInfo info) {
        if (sol_valheim$food_data == null)
            sol_valheim$food_data = new ValheimFoodData();

        var loaded = ValheimFoodData.read(nbt.getCompound("sol_food_data"));

        // honour whatever cap the save file carried over (it may be a runtime override from
        // /solvalheim slots or a mob-effect addon); the configuredMaxSlots() fallback is now
        // baked into ValheimFoodData.read for old save data that did not persist MaxItemSlots
        sol_valheim$food_data.MaxItemSlots = loaded.MaxItemSlots;
        sol_valheim$food_data.DrinkSlot = loaded.DrinkSlot;
        sol_valheim$food_data.ItemEntries = new ArrayList<>(loaded.ItemEntries);
        sol_valheim$food_data.trimToSlots();
        sol_valheim$sync();
    }

    // the 1.21 line routes tracked-data definition through a builder instead of the live SynchedEntityData
    #if PRE_CURRENT_MC_1_20_1
    @Inject(at = {@At("TAIL")}, method = {"defineSynchedData"})
    private void onInitDataTracker(CallbackInfo info) {
        if (sol_valheim$food_data == null)
            sol_valheim$food_data = new ValheimFoodData();

        this.entityData.define(sol_valheim$DATA_ACCESSOR, sol_valheim$food_data);
    }
    #else
    @Inject(at = {@At("TAIL")}, method = {"defineSynchedData(Lnet/minecraft/network/syncher/SynchedEntityData$Builder;)V"})
    private void onInitDataTracker(net.minecraft.network.syncher.SynchedEntityData.Builder builder, CallbackInfo info) {
        if (sol_valheim$food_data == null)
            sol_valheim$food_data = new ValheimFoodData();

        builder.define(sol_valheim$DATA_ACCESSOR, sol_valheim$food_data);
    }
    #endif
}
