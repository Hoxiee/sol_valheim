package vice.sol_valheim.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vice.sol_valheim.SOLValheim;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;

import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public class ServerLevelMixin
{
    /**
     * Runs the food timers down over a night that was slept through.
     * <p>
     * The old code clamped every slot to {@code max(1200, left - passed)}, which handed free time to
     * anything with under a minute left - sleeping actually topped your food back up to a minute.
     * Now the elapsed ticks are simply subtracted and empty slots expire like they would have.
     */
    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setDayTime(J)V"), method = "tick")
    public void onSleep(BooleanSupplier hasTimeLeft, CallbackInfo ci)
    {
        if (SOLValheim.Config == null || !SOLValheim.Config.common.passTicksDuringNight)
            return;

        var level = (ServerLevel) (Object) this;
        var dayTime = level.getLevelData().getDayTime();

        var l = dayTime + 24000L;
        var newTime = l - l % 24000L;

        var passedTicks = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, newTime - dayTime));
        if (passedTicks == 0)
            return;

        for (var player : level.players()) {
            var accessor = (PlayerEntityMixinDataAccessor) player;
            var foodData = accessor.sol_valheim$getFoodData();
            if (foodData == null || foodData.isEmpty())
                continue;

            foodData.advance(passedTicks);
            accessor.sol_valheim$sync();
        }
    }
}
