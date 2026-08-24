package vice.sol_valheim;

#if PRE_CURRENT_MC_1_19_2
import net.minecraft.core.Registry;
#elif POST_CURRENT_MC_1_20_1
import net.minecraft.core.registries.Registries;
#endif
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.*;
import vice.sol_valheim.utils.RegistryHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Everything the mod knows about what a player has eaten. Lives on the player as synched entity
 * data, so the very same layout is used for disk, for the network and for the hud.
 */
public class ValheimFoodData
{
    /** Upper bound for {@code maxSlots} - the hud only lays out so many icons sensibly. */
    public static final int SLOT_LIMIT = 8;

    private static final String NBT_MAX_SLOTS = "max_slots";
    private static final String NBT_COUNT = "count";
    private static final String NBT_DRINK = "drink";
    private static final String NBT_DRINK_TICKS = "drinkticks";

    #if PRE_CURRENT_MC_1_19_2
    public static final TagKey<Item> RESETS_FOOD = TagKey.create(Registry.ITEM_REGISTRY, new ResourceLocation(SOLValheim.MOD_ID, "resets_food"));
    public static final TagKey<Item> CAN_EAT_EARLY = TagKey.create(Registry.ITEM_REGISTRY, new ResourceLocation(SOLValheim.MOD_ID, "can_eat_early"));
    public static final TagKey<Item> NOT_CONSUMABLE = TagKey.create(Registry.ITEM_REGISTRY, new ResourceLocation(SOLValheim.MOD_ID, "not_consumable"));
    #elif POST_CURRENT_MC_1_20_1
    public static final TagKey<Item> RESETS_FOOD = TagKey.create(Registries.ITEM, new ResourceLocation(SOLValheim.MOD_ID, "resets_food"));
    public static final TagKey<Item> CAN_EAT_EARLY = TagKey.create(Registries.ITEM, new ResourceLocation(SOLValheim.MOD_ID, "can_eat_early"));
    public static final TagKey<Item> NOT_CONSUMABLE = TagKey.create(Registries.ITEM, new ResourceLocation(SOLValheim.MOD_ID, "not_consumable"));
    #endif

    public static final EntityDataSerializer<ValheimFoodData> FOOD_DATA_SERIALIZER = new EntityDataSerializer<>(){
        @Override
        public void write(FriendlyByteBuf buffer, ValheimFoodData value)
        {
            buffer.writeNbt(value.save(new CompoundTag()));
        }

        @Override
        public ValheimFoodData read(FriendlyByteBuf buffer) {
            return ValheimFoodData.read(buffer.readNbt());
        }

        @Override
        public ValheimFoodData copy(ValheimFoodData value)
        {
            var ret = new ValheimFoodData();
            ret.MaxItemSlots = value.MaxItemSlots;
            ret.ItemEntries = value.ItemEntries.stream().map(EatenFoodItem::new).collect(Collectors.toCollection(ArrayList::new));
            if (value.DrinkSlot != null)
                ret.DrinkSlot = new EatenFoodItem(value.DrinkSlot);
            return ret;
        }

    };

    public List<EatenFoodItem> ItemEntries = new ArrayList<>();
    public EatenFoodItem DrinkSlot;

    /**
     * Authoritative on the server, informational on the client. Zero means "not known yet", which is
     * what old save data and freshly deserialised instances carry - {@link #getMaxItemSlots()} falls
     * back to the config so a zero can never lock a player out of eating.
     */
    public int MaxItemSlots = configuredMaxSlots();

    /** The configured slot count, clamped, and safe to call before the config has been loaded. */
    public static int configuredMaxSlots() {
        var config = SOLValheim.Config;
        if (config == null || config.common == null)
            return 3;

        return Mth.clamp(config.common.maxSlots, 1, SLOT_LIMIT);
    }

    /**
     * True for anything the player drinks and that therefore belongs in the dedicated drink slot.
     * Splash and lingering potions are thrown, not drunk, and {@code #sol_valheim:not_consumable}
     * lets a pack exclude modded items that merely borrow the drinking animation.
     */
    public static boolean isDrinkable(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return false;

        if (stack.getUseAnimation() != UseAnim.DRINK)
            return false;

        if (stack.getItem() instanceof ThrowablePotionItem)
            return false;

        return !stack.is(NOT_CONSUMABLE);
    }

    public static boolean isDrinkable(Item item) {
        return item != null && isDrinkable(item.getDefaultInstance());
    }

    public int getMaxItemSlots() {
        return MaxItemSlots > 0 ? Math.min(MaxItemSlots, SLOT_LIMIT) : configuredMaxSlots();
    }

    /** @return true when the food was actually taken, so callers know whether to sync. */
    public boolean eatItem(Item food)
    {
        if (food == null)
            return false;

        var stack = food.getDefaultInstance();
        if (stack.is(RESETS_FOOD))
            return false;

        var config = ModConfig.getFoodConfig(food);
        if (config == null)
            return false;

        if (isDrinkable(stack)) {
            if (DrinkSlot != null && !DrinkSlot.canEatEarly())
                return false;

            if (DrinkSlot == null)
                DrinkSlot = new EatenFoodItem(food, config.getTime());
            else {
                DrinkSlot.ticksLeft = config.getTime();
                DrinkSlot.item = food;
            }

            return true;
        }

        var existing = getEatenFood(food);
        if (existing != null)
        {
            if (!existing.canEatEarly())
                return false;

            existing.ticksLeft = config.getTime();
            sort();
            return true;
        }

        if (ItemEntries.size() < getMaxItemSlots())
        {
            ItemEntries.add(new EatenFoodItem(food, config.getTime()));
            sort();
            return true;
        }

        // entries are kept sorted, so this replaces the slot that is closest to running out
        for (var item : ItemEntries)
        {
            if (item.canEatEarly())
            {
                item.ticksLeft = config.getTime();
                item.item = food;
                sort();
                return true;
            }
        }

        return false;
    }

    public boolean canEat(Item food)
    {
        if (food == null)
            return false;

        var stack = food.getDefaultInstance();
        if (stack.is(RESETS_FOOD))
            return true;

        if (isDrinkable(stack))
            return DrinkSlot == null || DrinkSlot.canEatEarly();

        var existing = getEatenFood(food);
        if (existing != null)
            return existing.canEatEarly();

        if (ItemEntries.size() < getMaxItemSlots())
            return true;

        return ItemEntries.stream().anyMatch(EatenFoodItem::canEatEarly);
    }

    public EatenFoodItem getEatenFood(Item food) {
        return ItemEntries.stream()
                .filter((item) -> item.item == food)
                .findFirst()
                .orElse(null);
    }

    public boolean isEmpty() {
        return ItemEntries.isEmpty() && DrinkSlot == null;
    }

    public void clear()
    {
        ItemEntries.clear();
        DrinkSlot = null;
    }

    /** @return true when a slot expired, i.e. when the change is worth a sync packet. */
    public boolean tick()
    {
        return advance(1);
    }

    /**
     * Runs {@code ticks} worth of time off every slot at once. Used by the regular tick and by the
     * sleep handler, which skips the whole night in one go.
     *
     * @return true when at least one slot ran out
     */
    public boolean advance(int ticks)
    {
        if (ticks <= 0)
            return false;

        boolean expired = false;
        for (var item : ItemEntries)
            item.ticksLeft -= ticks;

        if (DrinkSlot != null) {
            DrinkSlot.ticksLeft -= ticks;
            if (DrinkSlot.ticksLeft <= 0) {
                DrinkSlot = null;
                expired = true;
            }
        }

        if (ItemEntries.removeIf(item -> item.ticksLeft <= 0))
            expired = true;

        return expired;
    }

    /** Drops the closest-to-expiring entries when the configured slot count shrinks. */
    public boolean trimToSlots()
    {
        int max = getMaxItemSlots();
        if (ItemEntries.size() <= max)
            return false;

        sort();
        while (ItemEntries.size() > max)
            ItemEntries.remove(0);

        return true;
    }

    /** Ascending by remaining time, so the hud order is stable and slot reuse picks the oldest. */
    private void sort()
    {
        ItemEntries.sort(Comparator.comparingInt(a -> a.ticksLeft));
    }

    public float getTotalFoodNutrition()
    {
        float nutrition = 0f;
        for (var item : ItemEntries)
        {
            ModConfig.Common.FoodConfig food = ModConfig.getFoodConfig(item.item);
            if (food == null)
                continue;

            nutrition += food.getHearts();
        }

        if (DrinkSlot != null)
        {
            ModConfig.Common.FoodConfig food = ModConfig.getFoodConfig(DrinkSlot.item);
            if (food != null)
            {
                nutrition += food.getHearts();
            }

            nutrition = nutrition * (1.0f + SOLValheim.Config.common.drinkSlotFoodEffectivenessBonus);
        }

        return nutrition;
    }

    public float getRegenSpeed()
    {
        float regen = 0.25f;
        for (var item : ItemEntries)
        {
            ModConfig.Common.FoodConfig food = ModConfig.getFoodConfig(item.item);
            if (food == null)
                continue;

            regen += food.getHealthRegen();
        }

        if (DrinkSlot != null)
        {
            ModConfig.Common.FoodConfig food = ModConfig.getFoodConfig(DrinkSlot.item);
            if (food != null)
            {
                regen += food.getHealthRegen();
            }

            regen = regen * (1.0f + SOLValheim.Config.common.drinkSlotFoodEffectivenessBonus);
        }

        return regen;
    }

    public CompoundTag save(CompoundTag tag) {
        tag.putInt(NBT_MAX_SLOTS, getMaxItemSlots());

        // count is written last: entries whose item no longer exists are skipped, so the number of
        // entries actually written is not necessarily ItemEntries.size()
        int count = 0;
        for (var item : ItemEntries)
        {
            var id = RegistryHelper.getItemId(item.item);
            if (id == null)
                continue;

            tag.putString("id" + count, id.toString());
            tag.putInt("ticks" + count, item.ticksLeft);
            count++;
        }
        tag.putInt(NBT_COUNT, count);

        if (DrinkSlot != null)
        {
            var id = RegistryHelper.getItemId(DrinkSlot.item);
            if (id != null)
            {
                tag.putString(NBT_DRINK, id.toString());
                tag.putInt(NBT_DRINK_TICKS, DrinkSlot.ticksLeft);
            }
        }

        return tag;
    }

    public static ValheimFoodData read(CompoundTag tag) {
        var instance = new ValheimFoodData();
        if (tag == null || tag.isEmpty())
            return instance;

        instance.MaxItemSlots = tag.contains(NBT_MAX_SLOTS)
                ? Mth.clamp(tag.getInt(NBT_MAX_SLOTS), 1, SLOT_LIMIT)
                : configuredMaxSlots();

        var size = tag.getInt(NBT_COUNT);
        for (int count = 0; count < size; count++)
        {
            // an item from a mod that has since been removed simply loses its slot
            var item = RegistryHelper.getItem(tag.getString("id" + count));
            if (item == null)
                continue;

            var ticks = tag.getInt("ticks" + count);
            if (ticks <= 0)
                continue;

            instance.ItemEntries.add(new EatenFoodItem(item, ticks));
        }
        instance.sort();

        var drink = RegistryHelper.getItem(tag.getString(NBT_DRINK));
        var drinkTicks = tag.getInt(NBT_DRINK_TICKS);
        if (drink != null && drinkTicks > 0)
            instance.DrinkSlot = new EatenFoodItem(drink, drinkTicks);

        return instance;
    }

    public static class EatenFoodItem {
        public Item item;
        public int ticksLeft;

        public boolean canEatEarly() {
            if (item == null)
                return true;

            var config = SOLValheim.Config;
            var stack = item.getDefaultInstance();

            var minTicks = config == null ? 1200 : Math.max(0, config.common.eatAgainMinSeconds * 20);
            if (ticksLeft < minTicks)
                return true;

            if (item.isEdible() && item.getFoodProperties() != null && item.getFoodProperties().canAlwaysEat())
                return true;

            if (stack.is(CAN_EAT_EARLY) || isDrinkable(stack))
                return true;

            var foodConfig = ModConfig.getFoodConfig(item);
            if (foodConfig == null || config == null)
                return false;

            var total = foodConfig.getTime();
            if (total <= 0)
                return true;

            return ((float) this.ticksLeft / total) < config.common.eatAgainPercentage;
        }

        public EatenFoodItem(Item item, int ticksLeft)
        {
            this.item = item;
            this.ticksLeft = ticksLeft;
        }

        public EatenFoodItem(EatenFoodItem eaten)
        {
            this.item = eaten.item;
            this.ticksLeft = eaten.ticksLeft;
        }
    }
}
