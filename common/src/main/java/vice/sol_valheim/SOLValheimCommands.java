package vice.sol_valheim;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;
import vice.sol_valheim.utils.RegistryHelper;

import java.util.Collection;
import java.util.List;

/**
 * {@code /solvalheim} - inspect and reset food, and reload the config without restarting.
 * <p>
 * Reloading matters most for a modpack: tuning the food table otherwise means a server restart per
 * change, and there is no other way to hand generated values a second look after installing a mod.
 */
public final class SOLValheimCommands
{
    private SOLValheimCommands() {}

    public static void register() {
        CommandRegistrationEvent.EVENT.register((dispatcher, context, selection) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("solvalheim");

            root.then(Commands.literal("status")
                    .executes(ctx -> status(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException())))
                    .then(Commands.argument("targets", EntityArgument.players())
                            .requires(source -> source.hasPermission(2))
                            .executes(ctx -> status(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))));

            root.then(Commands.literal("clear")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> clear(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException())))
                    .then(Commands.argument("targets", EntityArgument.players())
                            .executes(ctx -> clear(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))));

            root.then(Commands.literal("reload")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> reload(ctx.getSource())));

            dispatcher.register(root);
        });
    }

    private static int status(CommandSourceStack source, Collection<ServerPlayer> targets) {
        for (var player : targets) {
            var data = ((PlayerEntityMixinDataAccessor) player).sol_valheim$getFoodData();
            reply(source, Component.literal(player.getGameProfile().getName() + ": "
                    + data.ItemEntries.size() + "/" + data.getMaxItemSlots() + " slots").withStyle(ChatFormatting.GOLD));

            for (var entry : data.ItemEntries)
                reply(source, describe("  ", entry));

            if (data.DrinkSlot != null)
                reply(source, describe("  drink: ", data.DrinkSlot));
        }

        return targets.size();
    }

    private static Component describe(String prefix, ValheimFoodData.EatenFoodItem entry) {
        var id = RegistryHelper.getItemId(entry.item);
        var config = ModConfig.getFoodConfig(entry.item);
        var minutes = entry.ticksLeft / (20 * 60);
        var seconds = (entry.ticksLeft / 20) % 60;

        var text = String.format("%s%s - %d:%02d left", prefix, id == null ? "?" : id, minutes, seconds);
        if (config != null)
            text += String.format(" (%.1f hearts, %.2f regen)", config.getHearts() / 2f, config.getHealthRegen());

        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static int clear(CommandSourceStack source, Collection<ServerPlayer> targets) {
        for (var player : targets) {
            var accessor = (PlayerEntityMixinDataAccessor) player;
            accessor.sol_valheim$getFoodData().clear();
            accessor.sol_valheim$sync();
        }

        reply(source, Component.translatable("commands.sol_valheim.cleared", targets.size()).withStyle(ChatFormatting.GREEN));
        return targets.size();
    }

    private static int reload(CommandSourceStack source) {
        var holder = AutoConfig.getConfigHolder(ModConfig.class);
        holder.load();
        SOLValheim.Config = holder.getConfig();
        SOLValheim.Config.common.validatePostLoad();
        SOLValheim.Config.client.validatePostLoad();

        FoodConfigManager.rebuild();

        var server = source.getServer();
        SOLValheimNetwork.sendToAll(server.getPlayerList().getPlayers());

        reply(source, Component.translatable("commands.sol_valheim.reloaded",
                FoodConfigManager.localEntries().size()).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static void reply(CommandSourceStack source, Component message) {
        #if PRE_CURRENT_MC_1_19_2
        source.sendSuccess(message, false);
        #elif POST_CURRENT_MC_1_20_1
        source.sendSuccess(() -> message, false);
        #endif
    }
}
