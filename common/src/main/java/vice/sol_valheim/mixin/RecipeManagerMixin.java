package vice.sol_valheim.mixin;

import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vice.sol_valheim.FoodConfigManager;
import vice.sol_valheim.FoodEffort;

/**
 * Feeds the recipe table to {@link FoodEffort} so food can be priced by how much work it takes to make.
 * <p>
 * This has to be a mixin rather than a reload listener. Recipes are loaded by a listener of their own,
 * and a listener cannot see its siblings' results - so a listener of ours can only ask the server, and
 * {@code MinecraftServer.getRecipeManager()} still answers with the <em>outgoing</em> table until the
 * whole reload has committed. Reading it from a listener would leave food one {@code /reload} behind
 * every time a datapack changes a recipe.
 * <p>
 * Both hooks re-resolve the food table afterwards, because either one can be the last word depending on
 * loader and listener order, and a second pass over a few hundred items is cheap next to a data reload.
 */
@Mixin(RecipeManager.class)
public class RecipeManagerMixin
{
    /** Server side data load and every {@code /reload} after it. */
    @Inject(method = "apply", at = @At("TAIL"))
    private void sol_valheim$onRecipesLoaded(CallbackInfo ci) {
        sol_valheim$capture("RecipeManager.apply");
    }

    /** Client side, when the server syncs its recipes down on join. */
    @Inject(method = "replaceRecipes", at = @At("TAIL"))
    private void sol_valheim$onRecipesReplaced(CallbackInfo ci) {
        // in singleplayer the integrated server has already priced food off its own table; a client
        // capture here would stomp that with whatever this client's tag state happens to be at
        // recipe-sync time - which is exactly the kind of thing that changes between world re-entries
        if (net.minecraft.client.Minecraft.getInstance().getSingleplayerServer() != null)
            return;

        sol_valheim$capture("RecipeManager.replaceRecipes");
    }

    private void sol_valheim$capture(String hook) {
        var manager = (RecipeManager) (Object) this;

        try {
            FoodEffort.capture(manager.getRecipes());
            FoodConfigManager.rebuild(hook);
            vice.sol_valheim.SOLValheim.FINAL_PRICING_PASS.set(true);
        } catch (Exception exception) {
            // pricing food is not worth failing a resource reload over - without a table every dish
            // simply reads as gathered, which is the same as having the effort term turned off
            vice.sol_valheim.SOLValheim.LOGGER.warn("[sol_valheim] could not price food off the recipe table", exception);
        }
    }
}
