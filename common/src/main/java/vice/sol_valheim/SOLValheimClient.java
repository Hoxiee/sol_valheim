package vice.sol_valheim;

import dev.architectury.event.events.client.ClientPlayerEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;

import java.util.HashSet;
import java.util.Set;

public class SOLValheimClient
{
    static FoodHUD hud;

    /**
     * Server authoritative copies of the common config values the client's own gates read. Null
     * means "not known yet" - a singleplayer world before the packet lands, or a server without the
     * mod - and every getter falls back to the local config, which is exactly what the code read
     * before syncing existed.
     */
    private static Boolean serverSprintRequiresFood;
    private static Integer serverRespawnGracePeriod;
    private static Integer serverRegenDelay;
    private static ModConfig.Common.FoodEffectMode serverFoodEffectMode;
    private static ModConfig.Common.FoodDecayMode serverFoodDecayMode;
    private static Float serverFoodDecayStartFraction;
    private static Float serverFoodDecayMinFraction;

    public static void init() {
        hud = new FoodHUD();

        SOLValheimNetwork.registerClientReceivers();

        // drop the previous server's food values and flags so they cannot bleed into the next world
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> {
            FoodConfigManager.clearSynced();
            clearServerFlags();
            DECAY_CUE_FIRED.clear();
        });
    }

    public static void acceptFlags(boolean sprintRequiresFood, int respawnGracePeriod, int regenDelay, int modeOrdinal,
                                   int decayOrdinal, float decayStartFraction, float decayMinFraction) {
        var modes = ModConfig.Common.FoodEffectMode.values();
        serverSprintRequiresFood = sprintRequiresFood;
        serverRespawnGracePeriod = respawnGracePeriod;
        serverRegenDelay = regenDelay;
        serverFoodEffectMode = modeOrdinal >= 0 && modeOrdinal < modes.length ? modes[modeOrdinal] : ModConfig.Common.FoodEffectMode.ONCE;

        var decayModes = ModConfig.Common.FoodDecayMode.values();
        serverFoodDecayMode = decayOrdinal >= 0 && decayOrdinal < decayModes.length
                ? decayModes[decayOrdinal] : ModConfig.Common.FoodDecayMode.OFF;
        serverFoodDecayStartFraction = Mth.clamp(decayStartFraction, 0f, 1f);
        serverFoodDecayMinFraction = Mth.clamp(decayMinFraction, 0f, 1f);
    }

    private static void clearServerFlags() {
        serverSprintRequiresFood = null;
        serverRespawnGracePeriod = null;
        serverRegenDelay = null;
        serverFoodEffectMode = null;
        serverFoodDecayMode = null;
        serverFoodDecayStartFraction = null;
        serverFoodDecayMinFraction = null;
    }

    public static boolean sprintRequiresFood() {
        if (serverSprintRequiresFood != null)
            return serverSprintRequiresFood;

        var config = SOLValheim.Config;
        return config == null || config.common.sprintRequiresFood;
    }

    public static int respawnGracePeriod() {
        if (serverRespawnGracePeriod != null)
            return serverRespawnGracePeriod;

        var config = SOLValheim.Config;
        return config == null ? 10 : config.common.respawnGracePeriod;
    }

    public static int regenDelay() {
        if (serverRegenDelay != null)
            return serverRegenDelay;

        var config = SOLValheim.Config;
        return config == null ? 200 : config.common.regenDelay;
    }

    public static ModConfig.Common.FoodEffectMode foodEffectMode() {
        if (serverFoodEffectMode != null)
            return serverFoodEffectMode;

        var config = SOLValheim.Config;
        return config == null ? ModConfig.Common.FoodEffectMode.ONCE : config.common.foodEffectMode;
    }

    public static ModConfig.Common.FoodDecayMode foodDecayMode() {
        if (serverFoodDecayMode != null)
            return serverFoodDecayMode;

        var config = SOLValheim.Config;
        if (config == null || config.common.foodDecayMode == null)
            return ModConfig.Common.FoodDecayMode.OFF;

        return config.common.foodDecayMode;
    }

    public static float foodDecayStartFraction() {
        if (serverFoodDecayStartFraction != null)
            return serverFoodDecayStartFraction;

        var config = SOLValheim.Config;
        return config == null ? 0.5f : config.common.foodDecayStartFraction;
    }

    public static float foodDecayMinFraction() {
        if (serverFoodDecayMinFraction != null)
            return serverFoodDecayMinFraction;

        var config = SOLValheim.Config;
        return config == null ? 0.25f : config.common.foodDecayMinFraction;
    }

    /**
     * Called on the client when a dish it counts down locally hits zero. Deliberately quiet: one
     * soft sound plus a brief highlight over the emptied slot, and never a chat or action bar
     * message - losing a meal should be noticed, not announced.
     */
    public static void onFoodsExpired(boolean drinkExpired) {
        var cue = SOLValheim.Config == null ? null : SOLValheim.Config.client.expiryCue;
        if (cue == null || cue == ModConfig.Client.ExpiryCue.NONE)
            return;

        if (cue == ModConfig.Client.ExpiryCue.SOUND || cue == ModConfig.Client.ExpiryCue.BOTH)
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 0.35f));

        if ((cue == ModConfig.Client.ExpiryCue.HUD || cue == ModConfig.Client.ExpiryCue.BOTH) && hud != null)
            hud.pulseExpiry(drinkExpired);
    }

    /**
     * Below this share of its hearts a dish counts as "fading" and earns its one warning sound;
     * above {@link #DECAY_CUE_RESET} the flag clears again, so refreshing a dish rearms the cue.
     */
    private static final float DECAY_CUE_FIRE = 0.75f;
    private static final float DECAY_CUE_RESET = 0.85f;
    /** Keyed by item, not by slot instance - every sync packet rebuilds the slot objects. */
    private static final Set<Item> DECAY_CUE_FIRED = new HashSet<>();

    /**
     * One quiet sound the moment your first dish starts visibly losing hearts. Runs off the locally
     * counted slots each tick; the fire/reset band keeps it from retriggering while a heartbeat sync
     * replaces the slot instances mid-fade.
     */
    public static void tickDecayCues(ValheimFoodData data) {
        var config = SOLValheim.Config;
        if (config == null || !config.client.decayCue)
            return;

        var mode = foodDecayMode();
        if (mode == ModConfig.Common.FoodDecayMode.OFF)
            return;

        var start = foodDecayStartFraction();
        var min = foodDecayMinFraction();

        var cue = false;
        for (var entry : data.ItemEntries) {
            if (entry.item == null || ValheimFoodData.isDrinkable(entry.item) || ValheimFoodData.isDecayExempt(entry.item))
                continue;

            var factor = mode.heartsFactor(entry.remainingFraction(), start, min);
            if (factor >= DECAY_CUE_RESET)
                DECAY_CUE_FIRED.remove(entry.item);
            else if (factor < DECAY_CUE_FIRE && DECAY_CUE_FIRED.add(entry.item))
                cue = true;
        }

        if (!cue)
            return;

        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.55f, 0.3f));
    }
}
