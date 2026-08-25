package vice.sol_valheim.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import vice.sol_valheim.SOLValheim;
import vice.sol_valheim.SOLValheimClient;

public class FabricClientInitializer implements ClientModInitializer
{
    @Override
    public void onInitializeClient()
    {
        SOLValheimClient.init();
        // fabric api grew a tooltip context argument on the 1.21 line
        #if PRE_CURRENT_MC_1_20_1
        ItemTooltipCallback.EVENT.register(SOLValheim::addTooltip);
        #else
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipFlag, lines) ->
                SOLValheim.addTooltip(stack, tooltipFlag, lines));
        #endif
    }
}