package vice.sol_valheim;

#if PRE_CURRENT_MC_1_20_1
import net.minecraft.advancements.AdvancementProgress;
#elif POST_CURRENT_MC_1_20_1
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
#endif
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import vice.sol_valheim.utils.RegistryHelper;

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
    private AdvancementHelper() {}

    public static void award(Player player, String id) {
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.server == null)
            return;

        var advancementId = RegistryHelper.of(SOLValheim.MOD_ID, id);

        #if PRE_CURRENT_MC_1_20_1
        var advancement = serverPlayer.server.getAdvancements()
                .getAdvancement(advancementId);
        #elif POST_CURRENT_MC_1_20_1
        // 1.20.2 replaced direct lookups with holders - the built-in tree is tiny, so a linear scan
        // over what the server actually holds beats keeping a second index in sync with reloads
        AdvancementHolder advancement = null;
        for (var candidate : serverPlayer.server.getAdvancements().getAllAdvancements()) {
            if (advancementId.equals(candidate.id())) {
                advancement = candidate;
                break;
            }
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
