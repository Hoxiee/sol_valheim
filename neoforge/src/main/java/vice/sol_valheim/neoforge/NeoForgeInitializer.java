package vice.sol_valheim.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import vice.sol_valheim.SOLValheim;

@Mod(SOLValheim.MOD_ID)
public class NeoForgeInitializer
{
    public NeoForgeInitializer(IEventBus modEventBus) {
        // architectury attaches its own registrations to the mod on neoforge - no event bus
        // handover needed here. The tracked-data serializer, however, must go through the
        // neoforge registry instead of vanilla's static method, so it is wired up first.
        NeoForgeDataSerializers.register(modEventBus);
        SOLValheim.platformHandledDataSerializers = true;

        SOLValheim.init();
    }
}
