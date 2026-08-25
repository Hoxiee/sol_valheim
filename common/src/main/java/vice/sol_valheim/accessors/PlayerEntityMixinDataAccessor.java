package vice.sol_valheim.accessors;

import net.minecraft.world.item.ItemStack;
import vice.sol_valheim.ValheimFoodData;

public interface PlayerEntityMixinDataAccessor
{
    ValheimFoodData sol_valheim$getFoodData();

    /**
     * The single entry point for eating. Vanilla can route one bite through up to three different
     * methods, so this deduplicates per tick, applies any extra effects and syncs exactly once.
     * A no-op on the client.
     *
     * @return true when something actually happened - the dish was taken or the stomach was cleared
     */
    boolean sol_valheim$consume(ItemStack stack);

    /**
     * Replaces the whole food data and syncs. Used by the respawn handler to carry a dead player's
     * dishes over to their new instance.
     */
    void sol_valheim$setFoodData(ValheimFoodData data);

    /** Pushes the current food data to the tracking clients. */
    void sol_valheim$sync();
}
