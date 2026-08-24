package vice.sol_valheim;

import com.mojang.logging.LogUtils;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.registry.ReloadListenerRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import net.minecraft.ChatFormatting;

#if PRE_CURRENT_MC_1_19_2
import net.minecraft.core.Registry;
#elif POST_CURRENT_MC_1_20_1
import net.minecraft.core.registries.Registries;
#endif

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.*;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import me.shedaniel.autoconfig.serializer.PartitioningSerializer;
import org.slf4j.Logger;
import vice.sol_valheim.utils.TextPlural;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static vice.sol_valheim.ValheimFoodData.CAN_EAT_EARLY;
import static vice.sol_valheim.ValheimFoodData.RESETS_FOOD;

public class SOLValheim
{
	#if PRE_CURRENT_MC_1_19_2
	public static final DeferredRegister<Item> ITEMS = DeferredRegister.create("sol_valheim", Registry.ITEM_REGISTRY);
	public static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create("sol_valheim", Registry.MOB_EFFECT_REGISTRY);
	#elif POST_CURRENT_MC_1_20_1
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create("sol_valheim", Registries.ITEM);
	public static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create("sol_valheim", Registries.MOB_EFFECT);
    #endif

	public static ModConfig Config;
	public static final String MOD_ID = "sol_valheim";

	public static final Logger LOGGER = LogUtils.getLogger();

	/**
	 * Fixed ids so the modifiers can be looked up and removed again. The previous code cached a single
	 * modifier instance without an id, which meant a config change never took effect and turning the
	 * boost off left the already granted bonus in place forever.
	 */
	public static final UUID SPEED_BUFF_ID = UUID.fromString("0e2a0d2f-8f47-4d2f-9f0b-4c1c22a5b6d1");
	public static final UUID FOOD_HEALTH_ID = UUID.fromString("6a1d9e79-4c4a-4f68-8f2e-2d9b5a0c7a11");

	public static AttributeModifier createSpeedBuffModifier() {
		return new AttributeModifier(SPEED_BUFF_ID, "sol_valheim_speed_buff", Config.common.speedBoost, AttributeModifier.Operation.MULTIPLY_BASE);
	}

	public static void init() {
		AutoConfig.register(ModConfig.class, PartitioningSerializer.wrap(JanksonConfigSerializer::new));
		Config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
		Config.common.validatePostLoad();
		Config.client.validatePostLoad();

		EntityDataSerializers.registerSerializer(ValheimFoodData.FOOD_DATA_SERIALIZER);

		// datapacks get the last word on food values, so re-resolve on every data reload
		ReloadListenerRegistry.register(PackType.SERVER_DATA, FoodConfigManager.datapackListener(),
				new ResourceLocation(MOD_ID, "food_values"));

		// runs once every mod has registered its items, which is when the item registry is complete
		LifecycleEvent.SETUP.register(FoodConfigManager::rebuild);

		PlayerEvent.PLAYER_JOIN.register(SOLValheimNetwork::sendTo);

		SOLValheimCommands.register();
	}

	public static void addTooltip(ItemStack item, TooltipFlag flag, List<Component> list)
	{
		var food = item.getItem();
		var drinkable = ValheimFoodData.isDrinkable(item);

		if (!(food.isEdible() || drinkable))
			return;

		if (item.is(RESETS_FOOD)) {
			list.add(Component.translatable("tooltip.sol_valheim.empty_stomach").withStyle(ChatFormatting.GREEN));
			return;
		}

		var config = ModConfig.getFoodConfig(food);
		if (config == null)
			return;

		var halfHearts = config.getHearts();
		var heartCount = halfHearts / 2f;
		var hearts = halfHearts % 2 == 0
				? String.valueOf(halfHearts / 2)
				: String.format(Locale.ROOT, "%.1f", heartCount);

		list.add(TextPlural.translatable("tooltip.sol_valheim.hearts", heartCount, hearts)
			.withStyle(ChatFormatting.RED)
		);
		list.add(Component.translatable("tooltip.sol_valheim.regen", String.format(Locale.ROOT, "%.1f", config.getHealthRegen()))
			.withStyle(ChatFormatting.DARK_RED)
		);

		var minutes = (float) config.getTime() / (20 * 60);
		list.add(TextPlural.translatable("tooltip.sol_valheim.duration", Math.round(minutes))
			.withStyle(ChatFormatting.GOLD)
		);

		for (var effect : config.extraEffects) {
			var mobEffect = effect.getEffect();
			if (mobEffect == null)
				continue;

			var name = mobEffect.getDisplayName().copy();
			if (effect.amplifier > 1)
				name.append(" ").append(Component.translatable("potion.potency." + Math.min(effect.amplifier - 1, 5)));

			list.add(Component.translatable("tooltip.sol_valheim.effect", name).withStyle(ChatFormatting.GREEN));
		}

		if (drinkable) {
			list.add(Component.translatable("tooltip.sol_valheim.refreshing").withStyle(ChatFormatting.AQUA));
		} else if (item.is(CAN_EAT_EARLY) || (food.getFoodProperties() != null && food.getFoodProperties().canAlwaysEat())) {
			list.add(Component.translatable("tooltip.sol_valheim.consume").withStyle(ChatFormatting.DARK_PURPLE));
		}
	}
}
