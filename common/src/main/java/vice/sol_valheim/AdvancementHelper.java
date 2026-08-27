package vice.sol_valheim;

#if MC_1_21_1
import net.minecraft.advancements.AdvancementHolder;
#endif
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import vice.sol_valheim.utils.RegistryHelper;

import java.util.Map;

/**
 * Awards the mod's built-in advancements straight from code. The JSON files use the
 * {@code minecraft:impossible} criterion - it never fires on its own, so {@code award} is the only
 * way a progress step is ever granted.
 * <p>
 * Everything is null safe and idempotent: a missing advancement JSON (a stripped down server pack,
 * another mod removing ours) is skipped quietly, and an already finished advancement is a no-op,
 * so call sites do not need to track what they have awarded.
 */
public final class AdvancementHelper
{
    #if MC_1_21_1
    private static volatile Map<ResourceLocation, AdvancementHolder> sol_valheim$cache = null;
    #endif


    private static volatile boolean sol_valheim$cacheDirty = true;

    public static void sol_valheim$markCacheDirty() { sol_valheim$cacheDirty = true; }

    public static void award(Player player, String id) {
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.server == null)
            return;

        var advancementId = RegistryHelper.of(SOLValheim.MOD_ID, id);

        #if PRE_CURRENT_MC_1_20_1
        var advancement = serverPlayer.server.getAdvancements()
                .getAdvancement(advancementId);
        #elif POST_CURRENT_MC_1_20_1
        // 1.20.2 replaced direct lookups with holders. Built-in tree is tiny, so we keep an
        // immutable index rebuilt only on a flagged reload, not on every bite.
        AdvancementHolder advancement;
        if (!sol_valheim$cacheDirty) {
            var cached = sol_valheim$cache;
            if (cached != null) {
                advancement = cached.get(advancementId);
            } else {
                advancement = null;
            }
        } else {
            var fresh = new java.util.HashMap<ResourceLocation, AdvancementHolder>();
            for (var candidate : serverPlayer.server.getAdvancements().getAllAdvancements())
                fresh.put(candidate.id(), candidate);
            sol_valheim$cache = java.util.Map.copyOf(fresh);
            sol_valheim$cacheDirty = false;
            advancement = sol_valheim$cache.get(advancementId);
        }
        #endif

        if (advancement == null)
            return;

        AdvancementProgress progress = serverPlayer.getAdvancements().getOrStartProgress(advancement);
        if (progress.isDone())
            return;

        for (var criterion : progress.getRemainingCriteria())
            serverPlayer.getAdvancements().award(advancement, criterion);
    }
}
