package vice.sol_valheim.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vice.sol_valheim.SOLValheim;
import vice.sol_valheim.accessors.FoodDataPlayerAccessor;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;

@Mixin(FoodData.class)
public class FoodDataMixin implements FoodDataPlayerAccessor
{
    @Unique
    private Player sol_valheim$player;

    @Override
    public Player sol_valheim$getPlayer() { return sol_valheim$player;}

    @Override
    public void sol_valheim$setPlayer(Player player) { sol_valheim$player = player; }

    /**
     * Catches food eaten by mods that talk to {@link FoodData} directly instead of going through
     * {@code Player.eat}. Duplicate calls for a single bite are collapsed by the consumer.
     */
    @Inject(at = @At("HEAD"), method = "eat(Lnet/minecraft/world/item/Item;Lnet/minecraft/world/item/ItemStack;)V")
    public void onEatFood(Item item, ItemStack stack, CallbackInfo ci)
    {
        if (sol_valheim$player == null)
        {
            // getFoodData() sets the back reference, so this only happens if something bypasses it
            SOLValheim.LOGGER.warn("sol_valheim: FoodData has no player attached, ignoring {}", item);
            return;
        }

        var consumed = stack != null && !stack.isEmpty()
                ? stack
                : (item == null ? ItemStack.EMPTY : item.getDefaultInstance());

        ((PlayerEntityMixinDataAccessor) sol_valheim$player).sol_valheim$consume(consumed);
    }

    /**
     * Suppresses vanilla natural regeneration so health only ever comes from the food slots.
     * <p>
     * The mod cancels {@code causeFoodExhaustion} and zeroes saturation every tick, which pins the
     * hunger bar near full - and a full hunger bar is exactly the condition vanilla heals on. Without
     * this the player quietly gained free health after every meal, on top of the food regen, which
     * made the whole regen configuration meaningless. Set {@code vanillaRegeneration} to keep it.
     */
    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;heal(F)V"),
            require = 0, expect = 0)
    private void sol_valheim$vanillaRegen(Player player, float amount)
    {
        var config = SOLValheim.Config;
        if (config != null && config.common != null && !config.common.vanillaRegeneration)
            return;

        player.heal(amount);
    }
}
