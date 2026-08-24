package vice.sol_valheim.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;

@Mixin(CakeBlock.class)
public class CakeBlockMixin
{
    /**
     * Cake is the one food that is eaten straight off a block: it calls {@code FoodData.eat(int, float)}
     * and never touches {@code Player.eat}, so it needs its own hook.
     */
    @Inject(at = @At("HEAD"), method = "eat(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/InteractionResult;", cancellable = true)
    private static void sol_valheim$canEatCake(LevelAccessor level, BlockPos pos, BlockState state, Player player, CallbackInfoReturnable<InteractionResult> cir)
    {
        var accessor = (PlayerEntityMixinDataAccessor) player;
        var foodData = accessor.sol_valheim$getFoodData();
        if (foodData == null)
            return;

        if (!foodData.canEat(Items.CAKE))
        {
            // PASS leaves the slice in place, so the player can come back to it later
            cir.setReturnValue(InteractionResult.PASS);
            return;
        }

        if (level.isClientSide())
            return;

        accessor.sol_valheim$consume(Items.CAKE.getDefaultInstance());
    }
}
