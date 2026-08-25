package vice.sol_valheim.mixin;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vice.sol_valheim.SOLValheim;
import vice.sol_valheim.accessors.FoodDataPlayerAccessor;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;

@Mixin(FoodData.class)
public class FoodDataMixin implements FoodDataPlayerAccessor
{
    @Unique
    private Player sol_valheim$player;

    @Override
    public Player sol_valheim$getPlayer() { return sol_valheim$player;}

    @Override
    public void sol_valheim$setPlayer(Player player) { sol_valheim$player = player; }

    /**
     * Catches food eaten by mods that talk to {@link FoodData} directly instead of going through
     * {@code Player.eat}. Duplicate calls for a single bite are collapsed by the consumer.
     * <p>
     * 1.20.5 took the item out of the signature - vanilla now hands over bare {@link FoodProperties}
     * read off the item's component - so the newer target identifies the dish by reverse lookup: the
     * component instances are shared per registered item, so a reference match names the eater. See
     * {@link #sol_valheim$lookupProperties}.
     */
    #if PRE_CURRENT_MC_1_20_1
    @Inject(at = @At("HEAD"), method = "eat(Lnet/minecraft/world/item/Item;Lnet/minecraft/world/item/ItemStack;)V")
    public void onEatFood(Item item, ItemStack stack, CallbackInfo ci)
    {
        if (sol_valheim$player == null)
        {
            // getFoodData() sets the back reference, so this only happens if something bypasses it
            SOLValheim.LOGGER.warn("sol_valheim: FoodData has no player attached, ignoring {}", item);
            return;
        }

        var consumed = stack != null && !stack.isEmpty()
                ? stack
                : (item == null ? ItemStack.EMPTY : item.getDefaultInstance());

        ((PlayerEntityMixinDataAccessor) sol_valheim$player).sol_valheim$consume(consumed);
    }
    #elif MC_1_21_1
    @Inject(at = @At("HEAD"), method = "eat(Lnet/minecraft/world/food/FoodProperties;)V")
    public void onEatFood(FoodProperties properties, CallbackInfo ci)
    {
        if (sol_valheim$player == null)
        {
            // getFoodData() sets the back reference, so this only happens if something bypasses it
            SOLValheim.LOGGER.warn("sol_valheim: FoodData has no player attached, ignoring food with {} nutrition", properties.nutrition());
            return;
        }

        var item = sol_valheim$lookupProperties(properties);
        if (item == null)
        {
            // a properties instance nothing in the registry carries - synthetic values from somewhere
            SOLValheim.LOGGER.warn("sol_valheim: FoodData.eat called with unknown food properties ({} nutrition), ignoring",
                    properties.nutrition());
            return;
        }

        ((PlayerEntityMixinDataAccessor) sol_valheim$player).sol_valheim$consume(item.getDefaultInstance());
    }

    /**
     * Reverse lookup from a {@link FoodProperties} instance back to its item.
     * <p>
     * Identity is only as exact as vanilla's component sharing allows. Before 1.21 every item
     * carried its own {@code FoodProperties} and reference matching named the eater precisely;
     * 1.21 turned the class into a record <em>and</em> started interning equal component maps, so
     * sibling foods with identical numbers - raw beef and raw rabbit both read nutrition 3,
     * saturation 0.3 - now share one instance between them. For such a group no lookup can tell
     * which member was eaten; this returns the first registered one, deterministically.
     * <p>
     * That guess is safe because it never doubles up: the normal eat path has already consumed the
     * real stack through {@code Player.eat} with the same shared properties instance, and the
     * consumer's dedupe keys on that instance too, so this call collapses into it instead of
     * registering a phantom dish under whichever sibling won the group.
     * <p>
     * The index is built lazily on first use and rebuilt once if a lookup ever misses, which covers
     * a datapack or /reload swapping an item's component instance mid session.
     */
    @Unique
    private static java.util.IdentityHashMap<FoodProperties, Item> sol_valheim$propertiesIndex;

    @Unique
    private static Item sol_valheim$lookupProperties(FoodProperties properties) {
        var cached = sol_valheim$propertiesIndex;
        if (cached != null) {
            var found = cached.get(properties);
            if (found != null)
                return found;
        }

        var rebuilt = new java.util.IdentityHashMap<FoodProperties, Item>(2048);
        Item match = null;
        for (var candidate : vice.sol_valheim.utils.RegistryHelper.allItems()) {
            var props = candidate.getDefaultInstance().get(net.minecraft.core.component.DataComponents.FOOD);
            if (props == null)
                continue;
            // first registered wins a shared-instance group, so the guess never flips around
            rebuilt.putIfAbsent(props, candidate);
            if (match == null && props == properties)
                match = candidate;
        }

        sol_valheim$propertiesIndex = rebuilt;
        return match;
    }
    #endif

    /**
     * Suppresses vanilla natural regeneration so health only ever comes from the food slots.
     * <p>
     * The mod cancels {@code causeFoodExhaustion} and zeroes saturation every tick, which pins the
     * hunger bar near full - and a full hunger bar is exactly the condition vanilla heals on. Without
     * this the player quietly gained free health after every meal, on top of the food regen, which
     * made the whole regen configuration meaningless. Set {@code vanillaRegeneration} to keep it.
     */
    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;heal(F)V"),
            require = 0, expect = 0)
    private void sol_valheim$vanillaRegen(Player player, float amount)
    {
        var config = SOLValheim.Config;
        if (config != null && config.common != null && !config.common.vanillaRegeneration)
            return;

        player.heal(amount);
    }
}
