package vice.sol_valheim;

import dev.architectury.event.events.client.ClientPlayerEvent;

public class SOLValheimClient
{
    static FoodHUD hud;

    public static void init() {
        hud = new FoodHUD();

        SOLValheimNetwork.registerClientReceivers();

        // drop the previous server's food values so they cannot bleed into the next world
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> FoodConfigManager.clearSynced());
    }
}
