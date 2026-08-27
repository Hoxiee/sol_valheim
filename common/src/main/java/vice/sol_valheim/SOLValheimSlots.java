package vice.sol_valheim;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import vice.sol_valheim.event.SOLValheimEvents;
import vice.sol_valheim.accessors.PlayerEntityMixinDataAccessor;


/**
 * Public, server-authoritative surface for changing a player's food slot cap at runtime.
 * <p>
 * This is the contract the {@code /solvalheim slots} command, mob-effect addons and any
 * advancements / scripts use to read and mutate the cap. The event fires on the server thread,
 * after the trim and the sync, so subscribers see the post-mutation state.
 * <p>
 * {@code getMaxSlots} is safe on either side; the mutators are no-ops on the client - the
 * authoritative cap lives on the server, and the client only sees what the server has synced down.
 */
public final class SOLValheimSlots
{
    private SOLValheimSlots() {}
    /**
     * @return the player's effective cap, or {@code -1} when no player is supplied. The client's
     * local view is fine here - it is what the HUD will draw next frame.
     */
    public static int getMaxSlots(Player player) {
        if (player == null)
            return -1;

        var accessor = (PlayerEntityMixinDataAccessor) player;
        var data = accessor.sol_valheim$getFoodData();
        return data != null ? data.getMaxItemSlots() : -1;
    }

    /**
     * Server-side only. Sets the absolute slot cap, trims excess entries, syncs to the client and
     * fires {@link SOLValheimEvents#SLOTS_CHANGED}.
     *
     * @return false on the client, when the player has no food data, or when the new value is
     * outside {@code [1, ValheimFoodData.SLOT_LIMIT]} or matches the current cap.
     */
    public static boolean setMaxSlots(ServerPlayer player, int slots) {
        if (player == null)
            return false;

        var data = ((PlayerEntityMixinDataAccessor) player).sol_valheim$getFoodData();
        if (data == null)
            return false;

        if (slots < 1 || slots > ValheimFoodData.SLOT_LIMIT) {
            SOLValheim.LOGGER.warn("[sol_valheim] setMaxSlots rejected: {} is outside [1, {}]", slots, ValheimFoodData.SLOT_LIMIT);
            return false;
        }

        int oldSlots = data.getMaxItemSlots();
        if (!data.setMaxItemSlots(slots))
            return false;

        ((PlayerEntityMixinDataAccessor) player).sol_valheim$sync();
        SOLValheimEvents.SLOTS_CHANGED.invoker().onSlotsChanged(player, oldSlots, slots);
        return true;
    }

    /**
     * Server-side only. Equivalent to {@code setMaxSlots(player, getMaxSlots(player) + delta)}.
     * Returns false when the resulting value would be out of range, matches the current cap, or
     * the underlying mutator rejected the call.
     */
    public static boolean addMaxSlots(ServerPlayer player, int delta) {
        if (player == null)
            return false;

        int current = getMaxSlots(player);
        if (current < 0)
            return false;

        int target = current + delta;
        if (target < 1 || target > ValheimFoodData.SLOT_LIMIT) {
            SOLValheim.LOGGER.warn("[sol_valheim] addMaxSlots rejected: {} + {} = {} is outside [1, {}]",
                    current, delta, target, ValheimFoodData.SLOT_LIMIT);
            return false;
        }

        return setMaxSlots(player, target);
    }
}
