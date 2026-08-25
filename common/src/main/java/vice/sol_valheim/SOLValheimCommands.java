package vice.sol_valheim;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;
import vice.sol_valheim.utils.RegistryHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /solvalheim} - inspect and reset food, and reload the config without restarting.
 * <p>
 * Reloading matters most for a modpack: tuning the food table otherwise means a server restart per
 * change, and there is no other way to hand generated values a second look after installing a mod.
 */
public final class SOLValheimCommands
{
    /** How many of the strongest dishes {@code balance} lists before it stops. */
    private static final int BALANCE_TOP = 15;

    /** Share of vanilla's best dish above which an entry is worth a second look. */
    private static final float BALANCE_OUTLIER_RATIO = 1.25f;

    private static final String VANILLA = "minecraft";

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

            // hands a dish straight to a player - for map makers and testing food values
            root.then(Commands.literal("grant")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("item", ItemArgument.item(context))
                            .executes(ctx -> grant(ctx.getSource(), ItemArgument.getItem(ctx, "item"),
                                    List.of(ctx.getSource().getPlayerOrException())))
                            .then(Commands.argument("targets", EntityArgument.players())
                                    .executes(ctx -> grant(ctx.getSource(), ItemArgument.getItem(ctx, "item"),
                                            EntityArgument.getPlayers(ctx, "targets"))))));

            root.then(Commands.literal("reload")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> reload(ctx.getSource())));

            // audits the resolved table rather than any one player - "did that food mod land sanely?"
            root.then(Commands.literal("balance")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> balance(ctx.getSource())));

            root.then(Commands.literal("dump")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> dump(ctx.getSource())));

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
        if (config != null) {
            // drinks never fade, mirroring how the max health tick treats them
            var factor = ValheimFoodData.isDrinkable(entry.item) ? 1f : entry.heartsDecayFactor();
            text += String.format(" (%.1f hearts, %.2f regen)", config.getHearts() * factor / 2f, config.getHealthRegen());
        }

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

    /**
     * Goes through the normal eating path, so the usual slot rules apply: a full row of fresh dishes
     * refuses the grant, which is why the reply counts who actually took it.
     */
    private static int grant(CommandSourceStack source, ItemInput item, Collection<ServerPlayer> targets) throws CommandSyntaxException {
        var stack = item.createItemStack(1, false);

        int granted = 0;
        for (var player : targets) {
            if (((PlayerEntityMixinDataAccessor) player).sol_valheim$consume(stack))
                granted++;
        }

        if (granted == 0)
            reply(source, Component.translatable("commands.sol_valheim.granted_none", stack.getHoverName()).withStyle(ChatFormatting.RED));
        else
            reply(source, Component.translatable("commands.sol_valheim.granted", stack.getHoverName(), granted).withStyle(ChatFormatting.GREEN));

        return granted;
    }

    private static int reload(CommandSourceStack source) {
        var holder = AutoConfig.getConfigHolder(ModConfig.class);
        holder.load();
        SOLValheim.Config = holder.getConfig();
        SOLValheim.Config.common.validatePostLoad();
        SOLValheim.Config.client.validatePostLoad();

        FoodConfigManager.rebuild("/solvalheim reload");

        var server = source.getServer();
        SOLValheimNetwork.sendToAll(server.getPlayerList().getPlayers());
        SOLValheimNetwork.sendFlagsToAll(server.getPlayerList().getPlayers());

        reply(source, Component.translatable("commands.sol_valheim.reloaded",
                FoodConfigManager.localEntries().size()).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /**
     * Prints the resolved table the way an admin needs to read it: how many dishes each mod contributed
     * and how many of them no recipe could price, what ended up on top and what it cost to make, and
     * whether anything towers over vanilla's best dish. The last one is the whole point - a modpack's
     * food imbalance is invisible until you put the numbers next to each other.
     * <p>
     * The uncrafted count is the actionable half: a dish with no recipe reads as gathered and is priced
     * like an apple, so a mod with a high count there is a mod whose loot food wants explicit
     * {@code overrides}.
     */
    private static int balance(CommandSourceStack source) {
        var config = SOLValheim.Config;
        if (config == null)
            return 0;

        if (!config.common.balanceFoodValues)
            reply(source, Component.translatable("commands.sol_valheim.balance.off").withStyle(ChatFormatting.YELLOW));

        var unbalanced = FoodConfigManager.unbalancedEntries();
        var efforts = FoodConfigManager.efforts();
        var weight = config.common.balanceEffortWeight;

        // dish counts, and how many of them no recipe could price - see the note above
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> uncrafted = new TreeMap<>();
        List<Row> rows = new ArrayList<>();
        var vanillaBest = 0f;

        for (var entry : FoodConfigManager.localEntries().entrySet()) {
            var id = RegistryHelper.getItemId(entry.getKey());
            if (id == null)
                continue;

            var effort = efforts.getOrDefault(entry.getKey(), FoodEffort.GATHERED);

            counts.merge(id.getNamespace(), 1, Integer::sum);
            if (!FoodEffort.isPriced(entry.getKey()))
                uncrafted.merge(id.getNamespace(), 1, Integer::sum);

            rows.add(new Row(id.toString(), id.getNamespace(), entry.getValue(), unbalanced.contains(entry.getKey()),
                    effort, FoodEffort.multiplier(effort, weight)));

            if (id.getNamespace().equals(VANILLA))
                vanillaBest = Math.max(vanillaBest, entry.getValue().getHearts());
        }

        reply(source, Component.translatable("commands.sol_valheim.balance.effort",
                        FoodEffort.index().size(), String.format(Locale.ROOT, "%.2f", weight))
                .withStyle(ChatFormatting.GOLD));

        for (var namespace : counts.entrySet()) {
            var missing = uncrafted.getOrDefault(namespace.getKey(), 0);
            reply(source, Component.literal(String.format(Locale.ROOT, "  %s - %d dishes, %d uncrafted",
                            namespace.getKey(), namespace.getValue(), missing))
                    .withStyle(missing > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        }

        rows.sort((a, b) -> Float.compare(b.config.getHearts(), a.config.getHearts()));

        reply(source, Component.translatable("commands.sol_valheim.balance.top", BALANCE_TOP).withStyle(ChatFormatting.GOLD));
        for (var row : rows.subList(0, Math.min(BALANCE_TOP, rows.size())))
            reply(source, Component.literal(row.describe()).withStyle(ChatFormatting.GRAY));

        var threshold = vanillaBest * BALANCE_OUTLIER_RATIO;
        var outliers = 0;

        for (var row : rows) {
            if (vanillaBest <= 0 || row.namespace.equals(VANILLA) || row.config.getHearts() <= threshold)
                continue;

            if (outliers++ == 0)
                reply(source, Component.translatable("commands.sol_valheim.balance.outliers",
                        String.format(Locale.ROOT, "%.1f", vanillaBest / 2f)).withStyle(ChatFormatting.RED));

            reply(source, Component.literal(row.describe()).withStyle(ChatFormatting.RED));
        }

        if (outliers == 0)
            reply(source, Component.translatable("commands.sol_valheim.balance.clean").withStyle(ChatFormatting.GREEN));

        return rows.size();
    }

    /**
     * Writes the whole resolved table to {@code config/sol_valheim/food_dump.md}: every dish with its
     * raw inputs, measured effort and the model constants that turned one into the other. The file is
     * the working document for tuning the curve - {@code tools/food_recalc.py} reads it back, re-runs
     * the model with different constants and reports what would move, so a tuning pass never needs a
     * relaunch per idea. The chat only gets the path; a big pack is thousands of rows.
     */
    private static int dump(CommandSourceStack source) {
        var config = SOLValheim.Config;
        if (config == null)
            return 0;

        var common = config.common;
        var efforts = FoodConfigManager.efforts();
        var unbalanced = FoodConfigManager.unbalancedEntries();
        var weight = common.balanceEffortWeight;

        record DumpRow(String id, String namespace, ModConfig.Common.FoodConfig config,
                       FoodEffort.Effort effort, double multiplier, boolean datapack, boolean pinned) {}

        List<DumpRow> rows = new ArrayList<>();
        var pinnedCount = 0;
        var uncrafted = 0;

        for (var entry : FoodConfigManager.localEntries().entrySet()) {
            var id = RegistryHelper.getItemId(entry.getKey());
            if (id == null)
                continue;

            var effort = efforts.getOrDefault(entry.getKey(), FoodEffort.GATHERED);
            var pinned = unbalanced.contains(entry.getKey());
            if (pinned)
                pinnedCount++;
            if (!FoodEffort.isPriced(entry.getKey()))
                uncrafted++;

            rows.add(new DumpRow(id.toString(), id.getNamespace(), entry.getValue(), effort,
                    FoodEffort.multiplier(effort, weight),
                    FoodConfigManager.isDatapackSourced(entry.getKey()), pinned));
        }

        rows.sort(Comparator.comparingInt((DumpRow row) -> row.config().getHearts()).reversed());

        var sb = new StringBuilder();
        sb.append("# SOL: Valheim - food dump\n\n");
        sb.append("Generated ").append(LocalDateTime.now()).append("\n\n");
        sb.append("- foods = ").append(rows.size()).append('\n');
        sb.append("- balanced = ").append(rows.size() - pinnedCount).append('\n');
        sb.append("- pinned = ").append(pinnedCount).append('\n');
        sb.append("- uncrafted = ").append(uncrafted).append('\n');

        var stats = FoodEffort.captureStats();
        sb.append("- recipes_captured = ").append(stats.recipes()).append('\n');
        sb.append("- effort_priced = ").append(stats.priced()).append('\n');
        sb.append("- dropped_no_result = ").append(stats.noResult()).append('\n');
        sb.append("- dropped_empty_ingredients = ").append(stats.emptyIngredients()).append('\n');
        sb.append("- dropped_unreadable = ").append(stats.unreadable()).append('\n');
        sb.append("- dropped_unresolvable_reads = ").append(stats.unresolvableReads()).append('\n');
        sb.append("- pivot = ").append(common.balancePivot).append('\n');
        sb.append("- effort_weight = ").append(weight).append('\n');
        sb.append("- max_food_health = ").append(common.maxFoodHealth).append('\n');
        sb.append("- max_slots = ").append(common.maxSlots).append('\n');
        sb.append("- min_food_seconds = ").append(common.minFoodSeconds).append('\n');
        sb.append("- nutrition_health_modifier = ").append(common.nutritionHealthModifier).append('\n');
        FoodBalance.describeConstants().forEach(line -> sb.append("- ").append(line).append('\n'));
        FoodEffort.describeConstants().forEach(line -> sb.append("- ").append(line).append('\n'));

        sb.append("\n| id | ns | hearts | min | regen | nut | sat | regenMod | var | dep | mult | src | model |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|\n");

        for (var row : rows) {
            sb.append(String.format(Locale.ROOT, "| %s | %s | %.2f | %.2f | %.3f | %d | %.4g | %.3f | %d | %d | %.3f | %s | %s |%n",
                    row.id(), row.namespace(),
                    row.config().getHearts() / 2f, row.config().getTime() / (20f * 60f), row.config().getHealthRegen(),
                    row.config().nutrition, row.config().saturationModifier, row.config().healthRegenModifier,
                    row.effort().variety(), row.effort().depth(), row.multiplier(),
                    row.datapack() ? "datapack" : "-", row.pinned() ? "pinned" : "ok"));
        }

        var path = Path.of("config", "sol_valheim", "food_dump.md");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, sb.toString());
            reply(source, Component.translatable("commands.sol_valheim.dumped", rows.size(), path.toString()).withStyle(ChatFormatting.GREEN));
        } catch (IOException exception) {
            SOLValheim.LOGGER.error("[sol_valheim] Could not write the food dump", exception);
            reply(source, Component.translatable("commands.sol_valheim.dump_failed", path.toString()).withStyle(ChatFormatting.RED));
        }

        return rows.size();
    }

    /**
     * One line of the audit. {@code pinned} entries bypassed the model, so the model cannot be blamed.
     * The trailing {@code x1.89 (4/3)} is what the recipe tree cost: four distinct things to gather,
     * three steps deep. A bare {@code x1.00 (1/0)} means nothing crafts this.
     */
    private record Row(String id, String namespace, ModConfig.Common.FoodConfig config, boolean pinned,
                       FoodEffort.Effort effort, double multiplier) {
        String describe() {
            return String.format(Locale.ROOT, "  %s - %.1f hearts, %.1f min, %.2f regen, x%.2f (%d/%d)%s",
                    id, config.getHearts() / 2f, config.getTime() / (20f * 60f), config.getHealthRegen(),
                    multiplier, effort.variety(), effort.depth(),
                    pinned ? " (pinned)" : "");
        }
    }

    private static void reply(CommandSourceStack source, Component message) {
        #if PRE_CURRENT_MC_1_19_2
        source.sendSuccess(message, false);
        #elif POST_CURRENT_MC_1_20_1
        source.sendSuccess(() -> message, false);
        #endif
    }
}
