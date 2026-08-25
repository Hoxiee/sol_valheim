package vice.sol_valheim.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vice.sol_valheim.SOLValheimClient;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin
{
    /**
     * @return true when the player is allowed to start sprinting, which in this mod means "has eaten".
     * Reads the synced server flags, not the local config - the server enforces its own numbers.
     */
    @Unique
    private static boolean sol_valheim$canSprint(LocalPlayer player, boolean vanilla)
    {
        if (!SOLValheimClient.sprintRequiresFood())
            return vanilla;

        if (player.getAbilities().mayfly)
            return true;

        // a fresh spawn gets a moment to run for cover before the rule kicks in
        if (player.tickCount < SOLValheimClient.respawnGracePeriod() * 20)
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

    /**
     * Vanilla treats every health packet below the current health as a hit: {@code hurtTo} sets
     * {@code hurtTime}, which tilts the camera through bobHurt. That is correct for damage and wrong
     * for the food decay clamp - while dishes fade (or a whole night is slept off), max health slides
     * down and the server keeps clamping health onto it, so the client plays the hurt animation
     * continuously without anything ever attacking.
     * <p>
     * A value landing exactly on the maximum is that clamp (or a heal up to full), never a hit - but
     * comparing against the local attribute alone proved unreliable in practice: the attribute sync
     * can lag the health packet by a tick or more, the stale maximum then reads as "damage", and the
     * camera tilts anyway. So wherever the game carries the damage event packet, real hits are
     * recognised by their own signal instead: every one of them arrives through
     * {@code handleDamageEvent} first, which stamps {@code lastDamageStamp} before the matching
     * health packet can land. The decay clamp never comes with one - it always falls outside the
     * window of any past hit's stamp, no matter how recently that hit was. Anything without recent
     * damage is applied silently.
     */
    @Inject(at = @At("HEAD"), method = "hurtTo(F)V", cancellable = true)
    public void sol_valheim$silentMaxHealthClamp(float health, CallbackInfo ci)
    {
        var player = (LocalPlayer) (Object) this;

        // death and respawn hand back to vanilla untouched
        if (player.isDeadOrDying() || player.getHealth() <= 0)
            return;

        boolean matchesMax = health >= player.getMaxHealth() - 1.0e-3f;

        #if PRE_CURRENT_MC_1_19_2
        // no damage event packet on this version - the exact-max check is all there is
        if (matchesMax) {
            player.setHealth(health);
            ci.cancel();
        }
        #else
        // generous window: a hit's own health packet follows its damage event within a tick or two,
        // across a tick boundary at worst
        long gameTime = player.level().getGameTime();
        boolean recentDamage = gameTime
                - ((LivingEntityDamageAccessor) player).getLastDamageStamp() <= 40;

        if (!recentDamage || matchesMax) {
            player.setHealth(health);
            ci.cancel();
        }
        #endif
    }

}
