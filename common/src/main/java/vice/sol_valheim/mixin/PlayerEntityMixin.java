package vice.sol_valheim.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vice.sol_valheim.ModConfig;
import vice.sol_valheim.SOLValheim;
import vice.sol_valheim.ValheimFoodData;
import vice.sol_valheim.accessors.FoodDataPlayerAccessor;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;
import vice.sol_valheim.extenders.SynchedEntityDataExtender;

import java.util.ArrayList;

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

    /** Last item consumed and when, used to collapse the several vanilla eat paths into one. */
    @Unique
    private Item sol_valheim$lastConsumed;

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
    public void sol_valheim$consume(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return;

        #if PRE_CURRENT_MC_1_19_2
        var level = this.level;
        #elif POST_CURRENT_MC_1_20_1
        var level = this.level();
        #endif

        if (level.isClientSide)
            return;

        // Player.eat, FoodData.eat and completeUsingItem can all fire for a single bite
        var item = stack.getItem();
        if (sol_valheim$lastConsumed == item && sol_valheim$lastConsumedTick == this.tickCount)
            return;

        sol_valheim$lastConsumed = item;
        sol_valheim$lastConsumedTick = this.tickCount;

        if (stack.is(RESETS_FOOD)) {
            if (!sol_valheim$food_data.isEmpty()) {
                sol_valheim$food_data.clear();
                sol_valheim$sync();
            }
            return;
        }

        if (!sol_valheim$food_data.eatItem(item))
            return;

        sol_valheim$applyExtraEffects(item);
        sol_valheim$sync();
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

            this.addEffect(new MobEffectInstance(effect, duration, amplifier, false, true, true));
        }
    }

    @Inject(at = {@At("HEAD")}, method = {"causeFoodExhaustion(F)V"}, cancellable = true)
    private void onAddExhaustion(float exhaustion, CallbackInfo info) {
        info.cancel();
    }

    @Inject(at = {@At("HEAD")}, method = {"getFoodData"})
    private void onGetFoodData(CallbackInfoReturnable<FoodData> cir) {
        // hack workaround for player data not being accessible in FoodData
        ((FoodDataPlayerAccessor) foodData).sol_valheim$setPlayer((Player) (LivingEntity) this);
    }

    @Inject(at = {@At("HEAD")}, method = {"eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"})
    private void onEatFood(Level world, ItemStack stack, CallbackInfoReturnable<ItemStack> info) {
        sol_valheim$consume(stack);
    }

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
            if (displayed != null && !displayed.isEmpty())
                displayed.tick();
            return;
        }

        if (SOLValheim.Config == null)
            return;

        var config = SOLValheim.Config.common;
        Player player = (Player) (LivingEntity) this;

        if (isDeadOrDying()) {
            if (!sol_valheim$food_data.isEmpty()) {
                sol_valheim$food_data.clear();
                sol_valheim$sync();
            }
            return;
        }

        var changed = false;

        // let a config change to maxSlots reach players who are already in the world
        var slots = ValheimFoodData.configuredMaxSlots();
        if (sol_valheim$food_data.MaxItemSlots != slots) {
            sol_valheim$food_data.MaxItemSlots = slots;
            changed |= sol_valheim$food_data.trimToSlots();
            changed = true;
        }

        if (!sol_valheim$food_data.isEmpty())
            changed |= sol_valheim$food_data.tick();

        if (changed || --sol_valheim$syncTimer <= 0)
            sol_valheim$sync();

        player.getFoodData().setSaturation(0);

        var foodHealth = Math.min(config.maxFoodHealth * 2, (config.startingHealth * 2) + sol_valheim$food_data.getTotalFoodNutrition());
        sol_valheim$updateMaxHealth(player, foodHealth);

        if (config.speedBoost > 0.01f)
            sol_valheim$updateSpeedBoost(player, config, foodHealth);

        var timeSinceHurt = level.getGameTime() - ((LivingEntityDamageAccessor) this).getLastDamageStamp();
        var period = Math.max(1, config.regenSpeedModifier);
        if (timeSinceHurt > config.regenDelay && player.tickCount % period == 0 && player.getHealth() < player.getMaxHealth())
        {
            player.heal(sol_valheim$food_data.getRegenSpeed() / 20f);
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

        double amount = foodHealth - Attributes.MAX_HEALTH.getDefaultValue();
        var existing = attribute.getModifier(SOLValheim.FOOD_HEALTH_ID);

        if (existing == null || existing.getAmount() != amount) {
            if (existing != null)
                attribute.removeModifier(SOLValheim.FOOD_HEALTH_ID);

            attribute.addTransientModifier(new AttributeModifier(SOLValheim.FOOD_HEALTH_ID,
                    "sol_valheim_food_health", amount, AttributeModifier.Operation.ADDITION));
        }

        var max = player.getMaxHealth();
        if (player.getHealth() > max)
            player.setHealth(max);
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
        if (existing != null && existing.getAmount() == config.speedBoost)
            return;

        if (existing != null)
            attribute.removeModifier(SOLValheim.SPEED_BUFF_ID);

        attribute.addTransientModifier(SOLValheim.createSpeedBuffModifier());
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

        // the config, not the save file, decides how many slots a player has
        sol_valheim$food_data.MaxItemSlots = ValheimFoodData.configuredMaxSlots();
        sol_valheim$food_data.DrinkSlot = loaded.DrinkSlot;
        sol_valheim$food_data.ItemEntries = new ArrayList<>(loaded.ItemEntries);
        sol_valheim$food_data.trimToSlots();

        sol_valheim$sync();
    }

    @Inject(at = {@At("TAIL")}, method = {"defineSynchedData"})
    private void onInitDataTracker(CallbackInfo info) {
        if (sol_valheim$food_data == null)
            sol_valheim$food_data = new ValheimFoodData();

        this.entityData.define(sol_valheim$DATA_ACCESSOR, sol_valheim$food_data);
    }
}
