package vice.sol_valheim.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.bus.api.EventPriority;
import vice.sol_valheim.SOLValheim;


@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = "sol_valheim", value = {Dist.CLIENT}, bus = EventBusSubscriber.Bus.GAME)
public class ClientEvents {
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onItemTooltip(ItemTooltipEvent event) {
        SOLValheim.addTooltip(event.getItemStack(), event.getFlags(), event.getToolTip());
    }

    @SubscribeEvent
    public static void onRenderGUI(RenderGuiLayerEvent.Pre event) {
        if (event.getName() == VanillaGuiLayers.FOOD_LEVEL)
            event.setCanceled(true);
    }
}
