package vice.sol_valheim;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * The "Weakened" debuff, granted on death. A marker only: the actual maximum health cut is applied
 * by the tick loop in PlayerEntityMixin as an attribute modifier scaled by
 * {@code weakenedHealthPenalty}, so the strength stays a config value rather than being baked into
 * the effect - the same split as {@link RestedEffect} and its regeneration multiplier. The counter
 * that keeps it alive across respawns lives on {@link ValheimFoodData}, which already survives the
 * player instance swap.
 */
public class WeakenedEffect extends MobEffect
{
    public WeakenedEffect() {
        super(MobEffectCategory.HARMFUL, 0x7A5C9E);
    }
}
