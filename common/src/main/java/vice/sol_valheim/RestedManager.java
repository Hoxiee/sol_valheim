package vice.sol_valheim;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Grants and maintains the {@link RestedEffect}.
 * <p>
 * Two ways to earn it, both topping the timer up to the same configured duration: sleeping through
 * a night (the big one), and standing under a roof within smelling distance of fire - Valheim's
 * "sheltered by the campfire" comfort without its sitting requirement.
 * <p>
 * The effect is applied ambient: no swirl particles on grant or refresh, just the inventory icon
 * with its timer - noticing you are Rested should not compete with noticing a skeleton.
 */
public final class RestedManager
{
    /** Server ticks between shelter checks - a roof does not move, no need to ask every tick. */
    public static final int SCAN_INTERVAL = 40;

    /** Horizontal/vertical reach of the fire scan around the player's feet. */
    private static final int FIRE_RADIUS = 4;

    private RestedManager() {}

    /** Tops the effect up to the configured duration; never shortens an existing, longer Rested. */
    public static void topUp(Player player) {
        var config = SOLValheim.Config;
        if (config == null || !config.common.restedEnabled)
            return;

        var effect = SOLValheim.RESTED.get();
        if (effect == null)
            return;

        int wanted = config.common.restedDurationSeconds * 20;
        // 1.20.5+ effect APIs take the registry holder, not the bare effect
        var holder = vice.sol_valheim.utils.RegistryHelper.effectHolder(effect);
        var existing = player.getEffect(holder);
        if (existing != null && existing.getDuration() >= wanted)
            return;

        player.addEffect(new MobEffectInstance(holder, wanted, 0, true, false, true));
        AdvancementHelper.award(player, "rested");
    }

    /**
     * True when the player stands under something solid with open flame nearby - the standing
     * equivalent of Valheim's resting by a campfire. The roof check is one heightmap lookup, so it
     * gates the much wider block scan behind it.
     */
    public static boolean isShelteredByFire(Player player) {
        #if PRE_CURRENT_MC_1_19_2
        Level level = player.level;
        #elif POST_CURRENT_MC_1_20_1
        Level level = player.level();
        #endif

        BlockPos feet = player.blockPosition();

        // under a roof: the blocking heightmap sits above the player's own head
        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, feet.getX(), feet.getZ()) <= feet.getY() + 1)
            return false;

        for (var pos : BlockPos.betweenClosed(
                feet.offset(-FIRE_RADIUS, -1, -FIRE_RADIUS),
                feet.offset(FIRE_RADIUS, 2, FIRE_RADIUS))) {

            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof BaseFireBlock || state.is(BlockTags.CAMPFIRES))
                return true;
        }

        return false;
    }
}
