package vice.sol_valheim.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vice.sol_valheim.SOLValheim;
import vice.sol_valheim.ValheimFoodData;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin
{
    /**
     * Drinks never reach {@code Player.eat} because most of them are not edible items, so the drink
     * slot is filled from here instead. Splash and lingering potions are thrown rather than drunk and
     * {@code #sol_valheim:not_consumable} excludes modded items that only borrow the animation - both
     * are handled by {@link ValheimFoodData#isDrinkable}.
     */
    @Inject(at = @At("HEAD"), method = "completeUsingItem")
    private void sol_valheim$onCompleteUsingItem(CallbackInfo ci)
    {
        var player = (ServerPlayer) (Object) this;
        var useItem = player.getUseItem();

        if (!player.isUsingItem() || !ValheimFoodData.isDrinkable(useItem))
            return;

        ((PlayerEntityMixinDataAccessor) player).sol_valheim$consume(useItem);
    }

    /**
     * Scales every dish down once at the moment of dying instead of wiping the stomach. This lives
     * here and not on {@code Player.die} for a hard technical reason: ServerPlayer.die overrides its
     * parent completely and never calls super, so the parent body never executes for real players -
     * a Player-level hook compiled fine but silently never ran. {@code die} fires exactly once per
     * death (not again while the player sits on their death screen), so there is no repeat-scaling
     * to guard against, and these scaled values are what the restoreFrom hook below hands to the
     * respawning instance.
     */
    @Inject(at = @At("TAIL"), method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V")
    private void sol_valheim$onDie(DamageSource source, CallbackInfo ci)
    {
        if (SOLValheim.Config == null)
            return;

        var player = (ServerPlayer) (Object) this;
        var config = SOLValheim.Config.common;
        var accessor = (PlayerEntityMixinDataAccessor) player;
        var foodData = accessor.sol_valheim$getFoodData();

        // the counter rides the food data across the respawn instance swap, then the tick loop
        // rebuilds the visible effect and its health modifier on the other side
        if (config.weakenedOnDeath && foodData != null) {
            foodData.weakenedTicks = Math.max(1, config.weakenedSeconds) * 20;
            accessor.sol_valheim$sync();
        }

        var keep = config.keepFoodPercentageOnDeath;

        if (keep >= 1f || foodData == null || foodData.isEmpty())
            return;

        for (var entry : foodData.ItemEntries)
            entry.ticksLeft = Math.max(0, (int) (entry.ticksLeft * keep));

        foodData.ItemEntries.removeIf(entry -> entry.ticksLeft <= 0);

        if (foodData.DrinkSlot != null) {
            foodData.DrinkSlot.ticksLeft = Math.max(0, (int) (foodData.DrinkSlot.ticksLeft * keep));
            if (foodData.DrinkSlot.ticksLeft <= 0)
                foodData.DrinkSlot = null;
        }

        accessor.sol_valheim$sync();
    }

    /**
     * Carries the dead player's dishes over to the instance that respawns. Vanilla copies hunger
     * here by handing over the whole FoodData object; synched entity data is not transferred at all,
     * so without this hook every death would silently drop whatever keepFoodPercentageOnDeath had
     * just spared. The same path covers returning from The End, which also builds a fresh player.
     */
    @Inject(at = @At("TAIL"), method = "restoreFrom")
    private void sol_valheim$onRestoreFrom(ServerPlayer oldPlayer, boolean keepEverything, CallbackInfo ci)
    {
        var oldData = ((PlayerEntityMixinDataAccessor) oldPlayer).sol_valheim$getFoodData();
        if (oldData == null || oldData.isEmpty())
            return;

        // a deep copy: the old instance still writes its own NBT when the world saves
        var copy = ValheimFoodData.read(oldData.save(new CompoundTag()));
        ((PlayerEntityMixinDataAccessor) (Object) this).sol_valheim$setFoodData(copy);
    }
}
