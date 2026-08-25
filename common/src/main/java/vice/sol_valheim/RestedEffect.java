package vice.sol_valheim;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * The "Rested" buff. Earned by sleeping through a night or by sheltering beside a fire, and spent
 * as a flat multiplier on the food based regeneration while it lasts - Valheim's reward for
 * sleeping somewhere warm.
 * <p>
 * Deliberately has no tick behaviour of its own: the regeneration loop in PlayerEntityMixin reads
 * the effect's presence and applies {@code restedRegenMultiplier} itself, so the strength stays a
 * config value rather than being baked into the effect.
 */
public class RestedEffect extends MobEffect
{
    public RestedEffect() {
        super(MobEffectCategory.BENEFICIAL, 0xE8A33D);
    }
}
