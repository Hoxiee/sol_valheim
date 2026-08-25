package vice.sol_valheim.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.bus.api.SubscribeEvent;
import vice.sol_valheim.SOLValheimClient;

@EventBusSubscriber(modid = "sol_valheim", value = {Dist.CLIENT}, bus = EventBusSubscriber.Bus.MOD)
public class NeoForgeClientInitializer {
    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        SOLValheimClient.init();
    }
}
