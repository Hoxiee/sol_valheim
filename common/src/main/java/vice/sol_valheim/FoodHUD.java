package vice.sol_valheim;


import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;

#if PRE_CURRENT_MC_1_19_2
import net.minecraft.client.renderer.*;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;

#elif POST_CURRENT_MC_1_20_1
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

#endif

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;
import vice.sol_valheim.mixin.LivingEntityDamageAccessor;
import vice.sol_valheim.utils.RegistryHelper;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FoodHUD implements ClientGuiEvent.RenderHud
{
    static Minecraft client;

    private static final String BACKGROUND_SPRITE = "textures/gui/sprites/meter_background/default.png";
    private static final String BACKGROUND_LARGE_SPRITE = "textures/gui/sprites/meter_background/default_large.png";
    private static final String EMPTY_SPRITE = "textures/gui/sprites/panel_background/empty.png";
    private static final String EMPTY_LARGE_SPRITE = "textures/gui/sprites/panel_background/empty_large.png";
    private static final String DRINK_SPRITE = "textures/gui/sprites/placeholder_icon/drink.png";
    private static final String DRINK_LARGE_SPRITE = "textures/gui/sprites/placeholder_icon/drink_large.png";
    private static final String FOOD_SPRITE = "textures/gui/sprites/placeholder_icon/food.png";
    private static final String FOOD_LARGE_SPRITE = "textures/gui/sprites/placeholder_icon/food_large.png";
    private static final String PANEL_SPRITE = "textures/gui/sprites/panel_background/default.png";
    private static final String PANEL_LARGE_SPRITE = "textures/gui/sprites/panel_background/default_large.png";
    private static final String OUTLINE_SPRITE = "textures/gui/sprites/meter_outline/default.png";
    private static final String OUTLINE_LARGE_SPRITE = "textures/gui/sprites/meter_outline/default_large.png";
    private static final String REGEN_OUTLINE_SPRITE = "textures/gui/sprites/meter_outline/regen.png";
    private static final String REGEN_SPRITE = "textures/gui/sprites/meter_background/regen.png";
    private static final String SPRINT_SPRITE = "textures/gui/sprites/hint/sprint.png";
    private static final String SPRINT_SPRITE_LARGE = "textures/gui/sprites/hint/sprint_large.png";

    /**
     * Cached sprite path -> ResourceLocation. Sprite lookups happen on every HUD frame; the path
     * strings are constants, so a {@link ConcurrentHashMap} pays for itself the first frame and
     * keeps the per-frame work down to a hash lookup.
     */
    private static final Map<String, ResourceLocation> SPRITES = new ConcurrentHashMap<>();

    private static ResourceLocation sprite(String path) {
        return SPRITES.computeIfAbsent(path, p -> ResourceLocation.tryBuild("sol_valheim", p));
    }

    private static final int WHITE = FastColor.ARGB32.color(255, 255, 255, 255);
    private static final int WHITE_BG = FastColor.ARGB32.color(128, 255, 255, 255);
    private static final int YELLOW = FastColor.ARGB32.color(255, 255, 200, 37);
    private static final int YELLOW_BG = FastColor.ARGB32.color(150, 255, 200, 37);
    private static final int RED = FastColor.ARGB32.color(255, 237, 57, 57);

    /**
     * Farmer's Delight replaces the cake item with a slice when a piece is eaten. Resolved once and
     * remembered - the old code hit the item registry once per slot per frame.
     */
    static Item cakeSliceItem;
    static boolean cakeSliceResolved;
    /** Last game time the FD slice probe ran; the check is cheap but still gated by ~5s. */
    private static long cakeSliceLastCheck;

    /**
     * Frees every registry-keyed {@link ItemStack} the HUD has built up and drops the cached
     * Farmer's Delight slice lookup. Called from the client quit handler so the next world starts
     * with an empty map, not 5000 stale entries. Safe to call repeatedly.
     */
    public static void clearDisplayStacks() {
        DISPLAY_STACKS.clear();
        cakeSliceItem = null;
        cakeSliceResolved = false;
        cakeSliceLastCheck = 0L;
    }

    /** Items are registry singletons and these stacks are never mutated, so one per item forever. */
    private static final Map<Item, ItemStack> DISPLAY_STACKS = new IdentityHashMap<>();

    /** How long the "your dish just ran out" highlight stays up, in milliseconds. */
    private static final int EXPIRY_FLASH_MS = 1500;
    private static long expiryFlashUntil;
    /** Which cell the current flash points at - a drink frees the last slot, not the first. */
    private static boolean expiryFlashIsDrink;

    /** Sprint hint states; anything but ALLOWED draws, transitions fade in and out. */
    private enum SprintState { ALLOWED, GRACE, LOCKED }

    private static final int SPRINT_HINT_FADE_MS = 250;
    private static SprintState sprintState = SprintState.ALLOWED;
    private static long sprintStateChangedAt;

    /**
     * Small status light above the food row: a boot tinted red while the stomach is empty (slow
     * pulse, it is a state not an alarm) or yellow with the seconds left while the respawn grace
     * period is running down. Mirrors the exact gate LocalPlayerMixin and the server both use, so
     * the icon never promises something the game would refuse.
     */
    private static void renderSprintHint(#if PRE_CURRENT_MC_1_19_2 PoseStack #elif POST_CURRENT_MC_1_20_1 GuiGraphics #endif graphics) {
        var config = SOLValheim.Config;
        if (!config.client.showSprintHint || !SOLValheimClient.sprintRequiresFood())
            return;

        var player = client.player;

        SprintState now;
        if (player.getAbilities().mayfly)
            now = SprintState.ALLOWED;
        else if (player.tickCount < SOLValheimClient.respawnGracePeriod() * 20)
            now = SprintState.GRACE;
        else {
            var foodData = ((PlayerEntityMixinDataAccessor) player).sol_valheim$getFoodData();
            now = foodData != null && !foodData.ItemEntries.isEmpty() ? SprintState.ALLOWED : SprintState.LOCKED;
        }

        long millis = Util.getMillis();
        if (now != sprintState) {
            sprintState = now;
            sprintStateChangedAt = millis;
        }

        if (now == SprintState.ALLOWED)
            return;

        float appear = Math.min(1f, (millis - sprintStateChangedAt) / (float) SPRINT_HINT_FADE_MS);
        var hudConfig = config.client.sprintHudConfig;
        boolean large = config.client.useLargeIcons;
        int size = large ? 14 : 9;
        int x = (int) ((client.getWindow().getGuiScaledWidth() * hudConfig.xAnchor) + hudConfig.xOffset);
        // the large sprite hangs 5px lower from the same top-left anchor; lift it back onto the row
        int y = (int) ((client.getWindow().getGuiScaledHeight() * hudConfig.yAnchor) + hudConfig.yOffset) - (size - 9);

        // the locked pulse breathes slowly; grace stays solid because it carries a countdown
        float pulse = now == SprintState.LOCKED ? (float) (0.8d + 0.2d * Math.abs(Math.sin(millis / 400d))) : 1f;
        int baseColor = now == SprintState.GRACE ? YELLOW : RED;
        int alpha = (int) (255 * appear * pulse);
        int color = FastColor.ARGB32.color(alpha,
                FastColor.ARGB32.red(baseColor), FastColor.ARGB32.green(baseColor), FastColor.ARGB32.blue(baseColor));

        blit(graphics, large ? SPRINT_SPRITE_LARGE : SPRINT_SPRITE, size, size, x, y, color);

        if (now == SprintState.GRACE) {
            var ticksLeft = SOLValheimClient.respawnGracePeriod() * 20 - player.tickCount;
            var secondsLeft = Math.max(0, Mth.ceil(ticksLeft / 20f));
            drawFont(graphics, secondsLeft + "s", x + size + 2, y + (size - 8) / 2 + 1, color);
        }
    }

    /**
     * Single source of truth for where a slot lands on the HUD. {@code index} is zero-based
     * (0 = the first food slot). {@code scale} is the uniform row shrink from
     * {@code maxRowWidth} - it multiplies every length (step, anchor correction, per-slot
     * nudges) by the same factor, so the layout is the default one, just smaller. Without
     * {@code autoFit} the configured anchor keeps its historical meaning - the row starts one
     * step away from it - so existing {@code xOffset}/{@code yOffset} configs render
     * pixel-identical to the pre-helper layout whatever {@code totalSlots} is. With
     * {@code autoFit} the anchor instead pins the slot picked by {@code autoFitMode}
     * (rightmost / leftmost / midpoint of the row).
     */
    private static int[] computeSlotPos(int index, int totalSlots, int size, float scale,
            ModConfig.Client.FoodComponentConfig cfg, int anchorX, int anchorY) {
        int stepX = Math.round((size + 1) * cfg.xGap * scale);
        int stepY = Math.round((size + 1) * cfg.yGap * scale);

        // which slot the anchor pins; -1 is the historical "one step before the first slot" origin
        int refIndex = -1;
        if (cfg.autoFit && cfg.autoFitMode != null) {
            refIndex = switch (cfg.autoFitMode) {
                case RIGHT_EDGE -> stepX < 0 ? 0 : totalSlots - 1;
                case LEFT_EDGE -> stepX > 0 ? 0 : totalSlots - 1;
                case CENTER -> (totalSlots - 1) / 2;
            };
        }

        int baseX = anchorX - stepX * refIndex;
        int baseY = anchorY - stepY * refIndex;

        int perSlotX = 0;
        int perSlotY = 0;
        if (cfg.slotOffsets != null && index >= 0 && index < cfg.slotOffsets.size() && cfg.slotOffsets.get(index) != null) {
            var perSlot = cfg.slotOffsets.get(index);
            perSlotX = perSlot.xOffset;
            perSlotY = perSlot.yOffset;
        }

        int slotX = baseX + stepX * index + Math.round(perSlotX * scale);
        int slotY = baseY + stepY * index + Math.round(perSlotY * scale);
        return new int[] { slotX, slotY };
    }

    public FoodHUD() {
        ClientGuiEvent.RENDER_HUD.register(this);
        client = Minecraft.getInstance();
    }

    /** Arms the brief highlight shown where a dish has just run out. */
    public static void pulseExpiry(boolean drinkExpired) {
        expiryFlashUntil = Util.getMillis() + EXPIRY_FLASH_MS;
        expiryFlashIsDrink = drinkExpired;
    }

    private static ItemStack displayStack(Item item) {
        return DISPLAY_STACKS.computeIfAbsent(item, i -> new ItemStack(i, 1));
    }

    private static Item farmersDelightCakeSlice() {
        // re-probe every 100 ticks (5s) so a mod that loads after the first HUD frame still gets
        // picked up - isModLoaded is a Map lookup, the gate is the only cost worth mentioning
        long tick = client != null && client.level != null ? client.level.getGameTime() : 0L;
        if (!cakeSliceResolved || tick - cakeSliceLastCheck > 100) {
            cakeSliceResolved = true;
            cakeSliceLastCheck = tick;
            if (Platform.isModLoaded("farmersdelight"))
                cakeSliceItem = RegistryHelper.getItem("farmersdelight:cake_slice");
            else
                cakeSliceItem = null;
        }

        return cakeSliceItem;
    }

    @Override
    #if PRE_CURRENT_MC_1_19_2
    public void renderHud(PoseStack graphics, float tickDelta) {
    #elif MC_1_21_1
    public void renderHud(GuiGraphics graphics, net.minecraft.client.DeltaTracker ignoredTickDelta) {
    #else
    public void renderHud(GuiGraphics graphics, float tickDelta) {
    #endif


        if (client.player == null)
            return;


        if (client.player.isCreative() || client.player.isSpectator())
            return;

        if (SOLValheim.Config == null)
            return;

        // the sprint hint has its own toggle and keeps working while the food hud is switched off
        renderSprintHint(graphics);

        if (!SOLValheim.Config.client.showFoodHud)
            return;

        var solPlayer = (PlayerEntityMixinDataAccessor) client.player;

        var foodData = solPlayer.sol_valheim$getFoodData();
        if (foodData == null)
            return;

        ModConfig.Client configData = SOLValheim.Config.client;
        ModConfig.Client.FoodComponentConfig foodHudConfig = configData.foodHudConfig;
        ModConfig.Client.RegenComponentConfig regenHudConfig = configData.regenHudConfig;

        // Health regen timer
        var level = client.level;
        if (configData.showRegenMeter && level != null) {
            var regenDelay = SOLValheimClient.regenDelay();
            var timeSinceHurt = level.getGameTime() - ((LivingEntityDamageAccessor) client.player).getLastDamageStamp();
            if (timeSinceHurt < regenDelay) {
                int width = (int) ((client.getWindow().getGuiScaledWidth() * regenHudConfig.xAnchor) + regenHudConfig.xOffset);
                int height = (int) ((client.getWindow().getGuiScaledHeight() * regenHudConfig.yAnchor) + regenHudConfig.yOffset);

                float regenAlpha = 1 - ((float) timeSinceHurt / regenDelay);
                blit(graphics, REGEN_OUTLINE_SPRITE, 9, 9, width, height, WHITE);
                renderRadialBar(graphics, REGEN_SPRITE, 9, 9, width, height, WHITE, regenAlpha);
            }
        }

        boolean useLargeIcons = configData.useLargeIcons;

        int size = useLargeIcons ? 14 : 9;
        int width = (int) ((client.getWindow().getGuiScaledWidth() * foodHudConfig.xAnchor) + foodHudConfig.xOffset);
        int height = (int) ((client.getWindow().getGuiScaledHeight() * foodHudConfig.yAnchor) + foodHudConfig.yOffset - (useLargeIcons ? 6 : 0));

        int anchorX;
        int anchorY;
        if (foodHudConfig.autoFit) {
            anchorX = (int) (client.getWindow().getGuiScaledWidth() * foodHudConfig.autoFitAnchorX) + foodHudConfig.autoFitOffsetX;
            anchorY = (int) (client.getWindow().getGuiScaledHeight() * foodHudConfig.autoFitAnchorY) + foodHudConfig.autoFitOffsetY;
        } else {
            anchorX = width;
            anchorY = height;
        }

        int totalSlots = foodData.getMaxItemSlots() + 1; // food row + drink

        // maxRowWidth: when the row would outgrow the budget, shrink it as a whole - icons,
        // gaps and text all scale by the same factor, the geometry (which slot sits where
        // relative to the others) never changes. A vertical layout (xGap == 0) has no width
        // to bound and is left alone.
        float scale = 1f;
        if (foodHudConfig.maxRowWidth > 0 && totalSlots > 1 && foodHudConfig.xGap != 0) {
            int rowWidth = size + Math.abs((size + 1) * foodHudConfig.xGap) * (totalSlots - 1);
            if (rowWidth > foodHudConfig.maxRowWidth)
                scale = foodHudConfig.maxRowWidth / (float) rowWidth;
        }
        int drawSize = Math.max(1, Math.round(size * scale));

        int index = 0;
        // Food
        for (var food : foodData.ItemEntries) {
            var pos = computeSlotPos(index, totalSlots, size, scale, foodHudConfig, anchorX, anchorY);
            renderFoodSlot(graphics, food, pos[0], pos[1], useLargeIcons, drawSize, scale);
            index++;
        }
        // Empty Food
        for (int i = 0; i < foodData.getMaxItemSlots() - foodData.ItemEntries.size(); i++) {
            var pos = computeSlotPos(index, totalSlots, size, scale, foodHudConfig, anchorX, anchorY);
            renderEmptyFoodSlot(graphics, pos[0], pos[1], useLargeIcons, EMPTY_LARGE_SPRITE, EMPTY_SPRITE, FOOD_LARGE_SPRITE, FOOD_SPRITE, drawSize, WHITE);
            index++;
        }
        // Drink
        if (foodData.DrinkSlot != null) {
            var pos = computeSlotPos(index, totalSlots, size, scale, foodHudConfig, anchorX, anchorY);
            renderFoodSlot(graphics, foodData.DrinkSlot, pos[0], pos[1], useLargeIcons, drawSize, scale);
        } else {
            var pos = computeSlotPos(index, totalSlots, size, scale, foodHudConfig, anchorX, anchorY);
            renderEmptyFoodSlot(graphics, pos[0], pos[1], useLargeIcons, EMPTY_LARGE_SPRITE, EMPTY_SPRITE, DRINK_LARGE_SPRITE, DRINK_SPRITE, drawSize, WHITE);
        }

        renderExpiryFlash(graphics, foodData, foodHudConfig, useLargeIcons, size, scale, drawSize, anchorX, anchorY, totalSlots);
    }

    /**
     * Brief fading highlight over the first empty slot - the place a dish has just vanished from.
     * Runs on the millisecond clock so it survives pause menus; a second long, silent, no message.
     */
    private static void renderExpiryFlash(#if PRE_CURRENT_MC_1_19_2 PoseStack #elif POST_CURRENT_MC_1_20_1 GuiGraphics #endif graphics,
                                          ValheimFoodData foodData, ModConfig.Client.FoodComponentConfig foodHudConfig,
                                          boolean useLargeIcons, int size, float scale, int drawSize, int anchorX, int anchorY, int totalSlots) {
        long now = Util.getMillis();
        if (now >= expiryFlashUntil)
            return;
        float progress = (expiryFlashUntil - now) / (float) EXPIRY_FLASH_MS;
        int alpha = (int) (255 * progress * (0.85f + 0.15f * Math.abs(Math.sin(now / 90d))));
        int color = FastColor.ARGB32.color(alpha, FastColor.ARGB32.red(YELLOW), FastColor.ARGB32.green(YELLOW), FastColor.ARGB32.blue(YELLOW));

        // same geometry as the slot loop. The dish is gone by the time the flash fires, so
        // ItemEntries.size() is the slot it vanished from (the first empty one); a drink frees
        // the cell after the whole food row. Death can empty the list entirely - the index then
        // points at slot 0, never below it
        int index = expiryFlashIsDrink ? totalSlots - 1 : foodData.ItemEntries.size();
        var pos = computeSlotPos(index, totalSlots, size, scale, foodHudConfig, anchorX, anchorY);
        String outlineSprite = useLargeIcons ? OUTLINE_LARGE_SPRITE : OUTLINE_SPRITE;
        blit(graphics, outlineSprite, drawSize, drawSize, pos[0], pos[1], color);
    }

    private static void renderEmptyFoodSlot(#if PRE_CURRENT_MC_1_19_2 PoseStack #elif POST_CURRENT_MC_1_20_1 GuiGraphics #endif graphics, int x, int y, boolean useLargeIcons, String bigPanelSprite, String panelSprite, String bigIconSprite, String iconSprite, int size, int color) {        String currentPanelSprite = useLargeIcons ? bigPanelSprite : panelSprite;
        String currentIconSprite = useLargeIcons ? bigIconSprite : iconSprite;

        blit(graphics, currentPanelSprite, size, size, x, y, color);
        blit(graphics, currentIconSprite, size, size, x, y, color);
    }

    private static void renderFoodSlot(#if PRE_CURRENT_MC_1_19_2 PoseStack #elif POST_CURRENT_MC_1_20_1 GuiGraphics #endif graphics, ValheimFoodData.EatenFoodItem food, int x, int y, boolean useLargeIcons, int size, float rowScale)
    {
        if (food.item == null)
            return;

        // a food whose values went away - mod removed, datapack edited - still gets its slot drawn,
        // otherwise every icon to the left of it would jump across the screen
        var foodConfig = ModConfig.getFoodConfig(food.item);
        var totalTime = foodConfig != null ? foodConfig.getTime() : Math.max(1, food.ticksLeft);
        var effectCount = foodConfig != null ? foodConfig.extraEffects.size() : 0;

        ModConfig.Client configData = SOLValheim.Config.client;

        var isDrink = ValheimFoodData.isDrinkable(food.item);
        boolean canEat = food.canEatEarly();
        float ticksLeftPercent = Mth.clamp((float) food.ticksLeft / totalTime, 0.0F, 1.0F);

        // dishes losing hearts to the decay curve darken with them; drinks and exempt items never do
        var decayMode = SOLValheimClient.foodDecayMode();
        float decayShade = 1f;
        if (!isDrink && decayMode != ModConfig.Common.FoodDecayMode.OFF && !ValheimFoodData.isDecayExempt(food.item))
            decayShade = 0.5f + 0.5f * decayMode.heartsFactor(ticksLeftPercent,
                    SOLValheimClient.foodDecayStartFraction(), SOLValheimClient.foodDecayMinFraction());

        // todo replace drink background to use a different sprite instead of tinting
        int bgColor = isDrink ? FastColor.ARGB32.color(200, 26, 52, 81) : FastColor.ARGB32.color(180, 0, 0, 0);
        int barColor = canEat ? YELLOW : WHITE;
        int barBgColor = canEat ? YELLOW_BG : WHITE_BG;

        var time = (float) food.ticksLeft / (20 * 60);
        var isSeconds = false;

        if (time < 1f) {
            isSeconds = true;
            time =  (float) food.ticksLeft / 20;
        }
        var minutes = Integer.toString((int) Math.floor(time + 0.5));

        // Background
        String panelTexture = useLargeIcons ? PANEL_LARGE_SPRITE : PANEL_SPRITE;
        blit(graphics, panelTexture, size, size, x, y, bgColor);
        // Meter Background
        String bgTexture = useLargeIcons ? BACKGROUND_LARGE_SPRITE : BACKGROUND_SPRITE;
        renderRadialBar(graphics, bgTexture, size, size, x, y, barBgColor, ticksLeftPercent);
        // Outline
        String outlineTexture = useLargeIcons ? OUTLINE_LARGE_SPRITE : OUTLINE_SPRITE;
        var blinkIntensity = 1 - (Math.min(ticksLeftPercent, 0.5) / 0.5) ;
        var outlineAlpha = canEat ? 1 - (((Math.sin((double) food.ticksLeft / 5) / 2) + 0.5) * blinkIntensity) : 1;
        var outlineColor = FastColor.ARGB32.color((int) (outlineAlpha * 255), FastColor.ARGB32.red(barColor), FastColor.ARGB32.green(barColor), FastColor.ARGB32.blue(barColor));
        renderRadialBar(graphics, outlineTexture, size, size, x, y, outlineColor, ticksLeftPercent);

        // Item - the 16px gui item is scaled down to fit the slot, with the same counter-translate
        // the old code used so it lands back on the slot's own top-left. The row-scale wrap shrinks
        // everything around the slot's top-left when maxRowWidth compressed the row, so the item,
        // its position and the text below all shrink by the same factor. The pose is popped before
        // any text draws, so the timer/effect numbers stay sharp and never see a scaled matrix
        // longer than their own draw
        var displayItem = food.item;
        if (displayItem == Items.CAKE) {
            var slice = farmersDelightCakeSlice();
            if (slice != null)
                displayItem = slice;
        }

        var itemScale = useLargeIcons ? 0.75f : 0.5f;
        var pose = #if PRE_CURRENT_MC_1_19_2 graphics #elif POST_CURRENT_MC_1_20_1 graphics.pose() #endif;
        pose.pushPose();
        if (rowScale != 1f) {
            pose.translate(x, y, 0f);
            pose.scale(rowScale, rowScale, rowScale);
            pose.translate(-x, -y, 0f);
        }
        pose.scale(itemScale, itemScale, itemScale);
        pose.translate(x * (useLargeIcons ? 0.3333f : 1f), y * (useLargeIcons ? 0.3333f : 1f), 0f);

        RenderSystem.setShaderColor(decayShade, decayShade, decayShade, 1f);
        renderGUIItem(graphics, displayStack(displayItem), x + 1, y + 1);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        pose.popPose();

        // Text - large icons overlay timer + effect badge. Both are scaled to 0.75 about the
        // slot's own top-left, which is the size they historically rendered at (they used to
        // live inside the same 0.75 pose as the item); unscaled, the 9px glyphs overflow the
        // 14px slot. rowScale multiplies in so a maxRowWidth-compressed row shrinks its text
        // along with everything else
        if (useLargeIcons && (configData.showTimerText || effectCount > 0)) {
            float textScale = 0.75f * rowScale;
            pose.pushPose();
            pose.translate(x, y, 0f);
            pose.scale(textScale, textScale, textScale);
            pose.translate(-x, -y, 0f);
            // the item sprite writes depth, so the text has to ride above it - the historical
            // code carried the same +200 bump, without it the timer hides behind the food
            pose.translate(0f, 0f, 200f);
            if (configData.showTimerText)
                drawFont(graphics, minutes, x + (minutes.length() > 1 ? 6 : 12), y + 10, isSeconds ? RED : WHITE);
            if (effectCount > 0)
                drawFont(graphics, "+" + effectCount, x + 6, y, YELLOW);
            pose.popPose();
        }
    }

    private static void blit(#if PRE_CURRENT_MC_1_19_2 PoseStack #elif POST_CURRENT_MC_1_20_1 GuiGraphics #endif graphics, String texture, int width, int height, int x, int y, int color) {
        #if MC_1_21_1
        var id = sprite(texture);
        graphics.setColor(FastColor.ARGB32.red(color) / 255f, FastColor.ARGB32.green(color) / 255f,
                FastColor.ARGB32.blue(color) / 255f, FastColor.ARGB32.alpha(color) / 255f);
        graphics.blit(id, x, y, 0, 0, 0, width, height, width, height);
        graphics.setColor(1f, 1f, 1f, 1f);
        #else
        #if PRE_CURRENT_MC_1_19_2
        Matrix4f matrix4f = graphics.last().pose();
        #else
        Matrix4f matrix4f = graphics.pose().last().pose();
        #endif
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();

        RenderSystem.enableBlend();
        RenderSystem.setShaderTexture(0, sprite(texture));
        RenderSystem.setShader(GameRenderer::getPositionColorTexShader);
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);

        buffer.vertex(matrix4f, x, y, 0).color(color).uv(0, 0).endVertex();
        buffer.vertex(matrix4f, x, y + height, 0).color(color).uv(0, 1).endVertex();
        buffer.vertex(matrix4f, x + width, y + height, 0).color(color).uv(1, 1).endVertex();
        buffer.vertex(matrix4f, x + width, y, 0).color(color).uv(1, 0).endVertex();

        tesselator.end();
        RenderSystem.disableBlend();
        #endif
    }

    private static Vector3f calcCircularCoords(float alpha) {
        var angle = -alpha * 2 * Math.PI;
        var hyp = Math.sqrt(2);
        var a = Mth.clamp(Math.sin(angle) * hyp, -1, 1);
        var b = Mth.clamp(-Math.cos(angle) * hyp, -1, 1);
        return new Vector3f((float) a, (float) b, 0);
    }

    private static void renderRadialBar(#if PRE_CURRENT_MC_1_19_2 PoseStack #elif POST_CURRENT_MC_1_20_1 GuiGraphics #endif graphics, String texture, int width, int height, int x, int y, int color, float alpha) {
        #if MC_1_21_1
        Matrix4f matrix4f = graphics.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Tesselator tesselator = Tesselator.getInstance();
        // 1.21 moved the buffer lifecycle into begin/build/draw
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_TEX_COLOR);

        float middleX = x + ((float) width / 2);
        float middleY = y + ((float) height / 2);

        if (alpha < 1.00) {
            buffer.addVertex(matrix4f, middleX, middleY, 0).setUv(0.5F, 0.5F).setColor(color);
        }
        buffer.addVertex(matrix4f, middleX, y, 0).setUv(0.5F, 0F).setColor(color);
        if (alpha > 0.125) { // TOP LEFT
            buffer.addVertex(matrix4f, x, y, 0).setUv(0F, 0F).setColor(color);
        }
        if (alpha > 0.375) { // BOTTOM LEFT
            buffer.addVertex(matrix4f, x, y + height, 0).setUv(0F, 1F).setColor(color);
        }
        if (alpha > 0.625) { // BOTTOM RIGHT
            buffer.addVertex(matrix4f, x + width, y + height, 0).setUv(1F, 1F).setColor(color);
        }
        if (alpha > 0.875) { // TOP RIGHT
            buffer.addVertex(matrix4f, x + width, y, 0).setUv(1F, 0F).setColor(color);
        }
        if (alpha < 1.00) {
            Vector3f ePos = calcCircularCoords(alpha);
            buffer.addVertex(matrix4f, (middleX + (ePos.x() * ((float) width / 2))), (middleY + (ePos.y() * ((float) height / 2))), 0)
                    .setUv((ePos.x() / 2) + 0.5F, (ePos.y() / 2) + 0.5F)
                    .setColor(color);
        }

        RenderSystem.enableBlend();
        RenderSystem.setShaderTexture(0, sprite(texture));
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(buffer.build());
        RenderSystem.disableBlend();
        #else
        #if PRE_CURRENT_MC_1_19_2
        Matrix4f matrix4f = graphics.last().pose();
        #else
        Matrix4f matrix4f = graphics.pose().last().pose();
        #endif
        RenderSystem.setShader(GameRenderer::getPositionColorTexShader);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();

        RenderSystem.enableBlend();
        RenderSystem.setShaderTexture(0, sprite(texture));
        buffer.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR_TEX);

        float middleX = x + ((float) width / 2);
        float middleY = y + ((float) height / 2);

        if (alpha < 1.00) {
            buffer.vertex(matrix4f, middleX, middleY, 0).color(color).uv(0.5F,0.5F).endVertex();
        }
        buffer.vertex(matrix4f, middleX, y, 0).color(color).uv(0.5F, 0F).endVertex();
        if (alpha > 0.125) { // TOP LEFT
            buffer.vertex(matrix4f, x, y, 0).color(color).uv(0F, 0F).endVertex();
        }
        if (alpha > 0.375) { // BOTTOM LEFT
            buffer.vertex(matrix4f, x, y + height, 0).color(color).uv(0F, 1F).endVertex();
        }
        if (alpha > 0.625) { // BOTTOM RIGHT
            buffer.vertex(matrix4f, x + width, y + height, 0).color(color).uv(1F, 1F).endVertex();
        }
        if (alpha > 0.875) { // TOP RIGHT
            buffer.vertex(matrix4f, x + width, y, 0).color(color).uv(1F, 0F).endVertex();
        }
        if (alpha < 1.00) {
            Vector3f ePos = calcCircularCoords(alpha);
            buffer.vertex(matrix4f, (middleX + (ePos.x() * ((float) width / 2))), (middleY + (ePos.y() * ((float) height / 2))), 0)
                    .color(color)
                    .uv((ePos.x() / 2) + 0.5F, (ePos.y() / 2) + 0.5F)
                    .endVertex();
        }
        tesselator.end();
        RenderSystem.disableBlend();
        #endif
    }

    private static void renderGUIItem(#if PRE_CURRENT_MC_1_19_2 PoseStack #elif POST_CURRENT_MC_1_20_1 GuiGraphics #endif graphics, ItemStack stack, int x, int y)
    {
        #if PRE_CURRENT_MC_1_19_2

        var itemRenderer = client.getItemRenderer();
        var bakedModel = itemRenderer.getModel(stack, null, null, 0);

        //itemRenderer.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).setFilter(false, false);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        PoseStack poseStack = RenderSystem.getModelViewStack();
        poseStack.pushPose();
        poseStack.translate((double)x, (double)y, (double)(100.0F + itemRenderer.blitOffset));
        poseStack.translate(8.0, 8.0, 0.0);
        poseStack.scale(1.0F, -1.0F, 1.0F);
        poseStack.scale(16.0F, 16.0F, 16.0F);

        poseStack.scale(0.75f, 0.75f, 0.75f);
        poseStack.translate(-0.15, 0.15, 0f);

        RenderSystem.applyModelViewMatrix();
        PoseStack poseStack2 = new PoseStack();
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        boolean bl = !bakedModel.usesBlockLight();
        if (bl) {
            Lighting.setupForFlatItems();
        }

        try {
            itemRenderer.render(stack, ItemTransforms.TransformType.GUI, false, poseStack2, bufferSource, 15728880, OverlayTexture.NO_OVERLAY, bakedModel);
        } finally {
            // restore vanilla-default GL state so the next draw call sees a clean baseline;
            // matching what other GUI renderers do without forcing a depth-test toggle
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            if (bl)
                Lighting.setupFor3DItems();
        }
        bufferSource.endBatch();

        poseStack.popPose();
        RenderSystem.applyModelViewMatrix();

        #elif POST_CURRENT_MC_1_20_1
        graphics.renderItem(stack, x, y);
        #endif
    }

    private static void drawFont(#if PRE_CURRENT_MC_1_19_2 PoseStack #elif POST_CURRENT_MC_1_20_1 GuiGraphics #endif graphics, String str, int x, int y, int color)
    {
        #if PRE_CURRENT_MC_1_19_2
        client.font.draw(graphics, str, x, y, color);
        #elif POST_CURRENT_MC_1_20_1
        graphics.drawString(client.font, str, x, y, color);
        #endif
    }


}
