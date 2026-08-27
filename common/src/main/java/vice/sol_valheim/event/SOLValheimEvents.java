package vice.sol_valheim.event;

import dev.architectury.event.Event;
import dev.architectury.event.EventFactory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import vice.sol_valheim.ModConfig;

/**
 * Server side hooks into the food system, for other mods that want to react to what players eat.
 * <p>
 * Both events are informational and cannot be cancelled - refusing a meal is the config's job.
 * {@code FOOD_EATEN} fires once per accepted bite from any of vanilla's eating paths (items,
 * drinks, cake). {@code FOOD_EXPIRED} fires for every slot that runs out, including through a
 * skipped night; several slots dying in the same tick fire once per item, not per tick.
 */
public final class SOLValheimEvents
{
    public interface FoodEatenCallback
    {
        void onFoodEaten(Player player, Item item, ModConfig.Common.FoodConfig config);
    }

    public interface FoodExpiredCallback
    {
        void onFoodExpired(Player player, Item item);
    }

    public static final Event<FoodEatenCallback> FOOD_EATEN = EventFactory.createLoop(FoodEatenCallback.class);
    public static final Event<FoodExpiredCallback> FOOD_EXPIRED = EventFactory.createLoop(FoodExpiredCallback.class);

    public interface SlotsChangedCallback
    {
        void onSlotsChanged(Player player, int oldSlots, int newSlots);
    }

    public static final Event<SlotsChangedCallback> SLOTS_CHANGED = EventFactory.createLoop(SlotsChangedCallback.class);

    private SOLValheimEvents() {}
}
