package vice.sol_valheim.mixin;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;

import static vice.sol_valheim.ValheimFoodData.RESETS_FOOD;

@Mixin({Item.class})
public class ItemMixin
{
    /**
     * Stops a player from eating when every food slot is still full.
     * <p>
     * This deliberately does as little as possible. The previous version answered every single
     * {@code Item.use} call - including for pickaxes, buckets, spawn eggs and every modded item in the
     * game - with {@code pass} and then cancelled, which silently threw away the injections other mods
     * put on the same method. Now non-food falls straight through, and food only gets cancelled when
     * the answer is actually "no": the allowed case is handed back to vanilla, whose own
     * {@code Player.canEat} check the mod already answers.
     */
    @Inject(at = {@At("HEAD")}, method = {"use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;"}, cancellable = true)
    private void onCanConsume(Level level, Player player, InteractionHand usedHand, CallbackInfoReturnable<InteractionResultHolder<ItemStack>> info)
    {
        var item = (Item) (Object) this;
        if (!item.isEdible())
            return;

        var stack = player.getItemInHand(usedHand);

        // clearing your stomach is always allowed
        if (stack.is(RESETS_FOOD))
            return;

        var foodData = ((PlayerEntityMixinDataAccessor) player).sol_valheim$getFoodData();
        if (foodData == null)
            return;

        var properties = item.getFoodProperties();
        if (foodData.canEat(item) || (properties != null && properties.canAlwaysEat()))
            return;

        info.setReturnValue(InteractionResultHolder.fail(stack));
    }
}
