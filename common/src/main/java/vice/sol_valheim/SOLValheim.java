package vice.sol_valheim;

import com.mojang.logging.LogUtils;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.registry.ReloadListenerRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
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
import java.util.concurrent.atomic.AtomicBoolean;

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

	public static final RegistrySupplier<MobEffect> RESTED = MOB_EFFECTS.register("rested", RestedEffect::new);
	public static final RegistrySupplier<MobEffect> WEAKENED = MOB_EFFECTS.register("weakened", WeakenedEffect::new);

	public static ModConfig Config;

	/** Set by the forge/neoforge initializers: the tracked-data serializer went through the platform registry. */
	public static boolean platformHandledDataSerializers;
	public static final String MOD_ID = "sol_valheim";

	/**
	 * Set by every recipe capture, consumed once by the first server tick that follows it - see the
	 * tick registration in {@link #init} for why the authoritative pricing pass waits for that tick.
	 */
	public static final AtomicBoolean FINAL_PRICING_PASS = new AtomicBoolean(false);
	public static final Logger LOGGER = LogUtils.getLogger();

	/**
	 * Fixed ids so the modifiers can be looked up and removed again. The previous code cached a single
	 * modifier instance without an id, which meant a config change never took effect and turning the
	 * boost off left the already granted bonus in place forever. 1.21 keys attribute modifiers by
	 * {@link ResourceLocation} instead of UUID, so each target keeps its own constant set.
	 */
	#if PRE_CURRENT_MC_1_20_1
	public static final UUID SPEED_BUFF_ID = UUID.fromString("0e2a0d2f-8f47-4d2f-9f0b-4c1c22a5b6d1");
	public static final UUID FOOD_HEALTH_ID = UUID.fromString("6a1d9e79-4c4a-4f68-8f2e-2d9b5a0c7a11");
	public static final UUID WEAKENED_ID = UUID.fromString("3f7b8a52-c6d9-4e01-b2a4-58d3f19e7c62");
	#elif MC_1_21_1
	public static final ResourceLocation SPEED_BUFF_ID = vice.sol_valheim.utils.RegistryHelper.of(MOD_ID, "speed_buff");
	public static final ResourceLocation FOOD_HEALTH_ID = vice.sol_valheim.utils.RegistryHelper.of(MOD_ID, "food_health");
	public static final ResourceLocation WEAKENED_ID = vice.sol_valheim.utils.RegistryHelper.of(MOD_ID, "weakened");
	#endif

	public static AttributeModifier createSpeedBuffModifier() {
		#if PRE_CURRENT_MC_1_20_1
		return new AttributeModifier(SPEED_BUFF_ID, "sol_valheim_speed_buff", Config.common.speedBoost, AttributeModifier.Operation.MULTIPLY_BASE);
		#elif MC_1_21_1
		return new AttributeModifier(SPEED_BUFF_ID, Config.common.speedBoost, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
		#endif
	}

	public static void init() {
		AutoConfig.register(ModConfig.class, PartitioningSerializer.wrap(JanksonConfigSerializer::new));
		Config = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
		Config.common.validatePostLoad();
		Config.client.validatePostLoad();

		MOB_EFFECTS.register();

		// forge/neoforge forbid vanilla's static serializer registration and want their own
		// registries instead - their initializers route it there and flip this flag first
		if (!platformHandledDataSerializers)
			EntityDataSerializers.registerSerializer(ValheimFoodData.FOOD_DATA_SERIALIZER);

		// datapacks get the last word on food values, so re-resolve on every data reload
		ReloadListenerRegistry.register(PackType.SERVER_DATA, FoodConfigManager.datapackListener(),
				vice.sol_valheim.utils.RegistryHelper.of(MOD_ID, "food_values"));

		// runs once every mod has registered its items, which is when the item registry is complete
		LifecycleEvent.SETUP.register(() -> FoodConfigManager.rebuild("registry setup"));

		// recipes exist by now, and so do the registries a 1.20 recipe needs to report its own result
		LifecycleEvent.SERVER_STARTED.register(server -> {
			FoodEffort.useRegistries(server.registryAccess());
			FoodConfigManager.rebuild("server started");
		});

		// Capturing recipes can land before item tags have been committed - on a fresh JVM the first
		// world entry prices every tag-ingredient dish as gathered, and the second entry inherits the
		// bindings, which is exactly why values used to change between re-entries. Two independent
		// moments try to consume the armed pass; whichever lands first wins the compareAndSet, so the
		// table is rebuilt exactly once, and the log says which moment it was. Neither consumes while
		// FoodEffort.tagsBound() is still false - retrying a tick later is free, walking recipes
		// before tags bind is what broke the first entry in the first place.
		TickEvent.SERVER_PRE.register(server -> {
			if (!FINAL_PRICING_PASS.get() || !FoodEffort.tagsBound())
				return;
			if (!FINAL_PRICING_PASS.compareAndSet(true, false))
				return;

			FoodEffort.useRegistries(server.registryAccess());
			FoodConfigManager.rebuild("final pricing pass (first server tick)");
		});
		PlayerEvent.PLAYER_JOIN.register(player -> {
			if (FINAL_PRICING_PASS.get() && FoodEffort.tagsBound()
					&& FINAL_PRICING_PASS.compareAndSet(true, false)) {
				FoodEffort.useRegistries(player.server.registryAccess());
				FoodConfigManager.rebuild("final pricing pass (player join)");
			}

			SOLValheimNetwork.sendTo(player);
			SOLValheimNetwork.sendFlags(player);

			// temporary diagnostics for the missing-advancement-tabs report: says whether the server
			// still holds the full tree at the moment the client sync would go out
			SOLValheim.LOGGER.info("[sol_valheim] {} joined; server advancement table holds {} entries",
					player.getGameProfile().getName(), player.server.getAdvancements().getAllAdvancements().size());
		});

		// one save's recipe table has no business pricing the next save's food
		LifecycleEvent.SERVER_STOPPED.register(server -> {
			FoodEffort.clear();
			FINAL_PRICING_PASS.set(false);
		});

		SOLValheimCommands.register();
	}

	public static void addTooltip(ItemStack item, TooltipFlag flag, List<Component> list)
	{
		var food = item.getItem();
		var drinkable = ValheimFoodData.isDrinkable(item);

		// 1.20.5 moved edibility onto data components, so the check reads off the stack there
		#if PRE_CURRENT_MC_1_20_1
		if (!(food.isEdible() || drinkable))
			return;
		#elif MC_1_21_1
		if (!(item.has(net.minecraft.core.component.DataComponents.FOOD) || drinkable))
			return;
		#endif

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
		if (SOLValheimClient.foodDecayMode() != ModConfig.Common.FoodDecayMode.OFF)
			list.add(Component.translatable("tooltip.sol_valheim.decaying").withStyle(ChatFormatting.DARK_GRAY));
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

			// in fade mode the level steps down with the food, so say so up front
			if (SOLValheimClient.foodEffectMode() == ModConfig.Common.FoodEffectMode.FADE
					&& effect.amplifier > 1 && !mobEffect.isInstantenous())
				name.append(" ").append(Component.translatable("tooltip.sol_valheim.fading").withStyle(ChatFormatting.DARK_GRAY));

			list.add(Component.translatable("tooltip.sol_valheim.effect", name).withStyle(ChatFormatting.GREEN));
		}

		if (drinkable) {
			list.add(Component.translatable("tooltip.sol_valheim.refreshing").withStyle(ChatFormatting.AQUA));
		} else if (item.is(CAN_EAT_EARLY) || canAlwaysEat(item)) {
			list.add(Component.translatable("tooltip.sol_valheim.consume").withStyle(ChatFormatting.DARK_PURPLE));
		}
	}

	/** {@code FoodProperties.canAlwaysEat} - read off the item's food component from 1.20.5 on. */
	public static boolean canAlwaysEat(ItemStack stack) {
		#if PRE_CURRENT_MC_1_20_1
		var properties = stack.getItem().getFoodProperties();
		return properties != null && properties.canAlwaysEat();
		#elif MC_1_21_1
		var properties = stack.get(net.minecraft.core.component.DataComponents.FOOD);
		return properties != null && properties.canAlwaysEat();
		#endif
	}
}
