package vice.sol_valheim.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vice.sol_valheim.ValheimFoodData;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin
{
    /**
     * Drinks never reach {@code Player.eat} because most of them are not edible items, so the drink
     * slot is filled from here instead. Splash and lingering potions are thrown rather than drunk and
     * {@code #sol_valheim:not_consumable} excludes modded items that only borrow the animation - both
     * are handled by {@link ValheimFoodData#isDrinkable}.
     */
    @Inject(at = @At("HEAD"), method = "completeUsingItem")
    private void sol_valheim$onCompleteUsingItem(CallbackInfo ci)
    {
        var player = (ServerPlayer) (Object) this;
        var useItem = player.getUseItem();

        if (!player.isUsingItem() || !ValheimFoodData.isDrinkable(useItem))
            return;

        ((PlayerEntityMixinDataAccessor) player).sol_valheim$consume(useItem);
    }
}
