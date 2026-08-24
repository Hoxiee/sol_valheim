package vice.sol_valheim.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vice.sol_valheim.SOLValheim;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin
{
    /**
     * @return true when the player is allowed to start sprinting, which in this mod means "has eaten".
     */
    @Unique
    private static boolean sol_valheim$canSprint(LocalPlayer player, boolean vanilla)
    {
        var config = SOLValheim.Config;
        if (config == null || !config.common.sprintRequiresFood)
            return vanilla;

        if (player.getAbilities().mayfly)
            return true;

        // a fresh spawn gets a moment to run for cover before the rule kicks in
        if (player.tickCount < config.common.respawnGracePeriod * 20)
            return true;

        var foodData = ((PlayerEntityMixinDataAccessor) player).sol_valheim$getFoodData();
        if (foodData == null)
            return vanilla;

        return !foodData.ItemEntries.isEmpty();
    }

    #if PRE_CURRENT_MC_1_19_2
    @ModifyVariable(at = @At("STORE"), method = "aiStep", ordinal = 4)
    public boolean canStartSprinting(boolean bool)
    {
        return sol_valheim$canSprint((LocalPlayer) (Object) this, bool);
    }

    #elif POST_CURRENT_MC_1_20_1
    @Inject(at = @At("HEAD"), method = "hasEnoughFoodToStartSprinting", cancellable = true)
    public void canStartSprinting(CallbackInfoReturnable<Boolean> cir)
    {
        cir.setReturnValue(sol_valheim$canSprint((LocalPlayer) (Object) this, true));
    }
    #endif

}
