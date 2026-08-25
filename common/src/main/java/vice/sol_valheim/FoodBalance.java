package vice.sol_valheim;

import net.minecraft.util.Mth;

import java.util.List;

/**
 * Turns a food's raw inputs into balanced hearts, duration and regeneration.
 * <p>
 * The problem this solves: the legacy formulas in {@link ModConfig.Common.FoodConfig} are linear and
 * unbounded in {@code nutrition}, so nutrition is a single "how good is this" scalar and all three
 * characteristics rise together with no ceiling and no trade-off. A mod that ships generous numbers
 * gets more hearts <em>and</em> more duration <em>and</em> more regeneration for free - Farmer's
 * Delight alone shipped a dozen dishes worth 17 half hearts and 42 minutes each, where vanilla's best
 * is 10 and 36.
 * <p>
 * The model here is <b>budget times shape</b>:
 * <ol>
 *   <li><b>Budget</b> - a Hill curve of raw power, bounded by an asymptote. Doubling a mod's numbers
 *       does not double its strength; past the pivot the curve flattens hard.</li>
 *   <li><b>Shape</b> - a direction built only from <em>ratios</em> of the inputs, so inflating every
 *       number cannot change which way a dish leans, only its (bounded) tier.</li>
 *   <li><b>Projection</b> onto an L2 ball of radius {@code budget}, which makes the budget a
 *       conservation law: stronger on one axis is necessarily weaker on the others. That trade-off is
 *       the whole point of the Valheim food system.</li>
 * </ol>
 * Raw power is nutrition times saturation times <b>crafting effort</b> - see {@link FoodEffort}. That
 * last term is what makes a stew beat a steak, and it is the one thing the two earlier attempts at this
 * got wrong: a hardcoded Farmer's Delight bump named one mod and helped no other, and the per namespace
 * inflation estimate that replaced it damped a mod's <em>entire</em> catalogue in proportion to how good
 * its food was - punishing exactly the mods worth cooking in, while leaving vanilla's own cheap outliers
 * untouched because vanilla was the yardstick. Effort is measured per dish, off recipes, and names
 * nobody.
 * <p>
 * The envelope constants below are fitted to vanilla's own table, which is what keeps vanilla staples
 * roughly where players expect them while containing modded outliers. They are the anchor of the whole
 * scale, not free parameters - changing one on its own breaks the calibration, which is why they live
 * here rather than in the config.
 * <p>
 * Pure math: nothing here touches the world, the registry or a player, so it is safe to call from any
 * thread and from {@link FoodConfigManager#rebuild()}.
 */
public final class FoodBalance
{
    /** Saturation that leans neither towards hearts nor towards duration. Vanilla's rough midpoint. */
    private static final double SATURATION_REF = 0.65;

    /** How sharply saturation skews the split. 1 would make it proportional; below 1 softens it. */
    private static final double SHAPE_GAMMA = 0.8;

    /**
     * Regeneration's share of the budget, for a dish with no regen bonus of its own.
     * <p>
     * Regen used to ride the hearts direction, which was defensible while nothing distinguished dishes
     * but nutrition - but it meant a food's regen fell as its saturation rose, so the long lasting meals
     * that ought to keep you alive in a fight regenerated the <em>least</em>. It is its own direction
     * now, driven by {@code healthRegenModifier}, which is what that field was always for.
     */
    private static final double REGEN_BIAS = 0.50;

    /**
     * How hard {@code healthRegenModifier} pulls. Above 1 on purpose: this is the only axis a dish can
     * be deliberately built for, and at gamma 1 a 1.5x modifier moved regen by so little that authoring
     * one was pointless.
     */
    private static final double REGEN_GAMMA = 1.4;

    /** Hill exponent. Higher is a sharper knee between "weak" and "good" food. */
    private static final double HILL_K = 1.67;

    /** Norm used for the budget ball. 2 spreads the trade-off smoothly across the three axes. */
    private static final double NORM_P = 2.0;

    // Envelope fitted to the vanilla food table. Ceilings are pure-axis asymptotes, not caps: real food
    // splits its budget three ways and lives around u = 0.2-0.45, so it never approaches them. Tighter
    // than they once were, because effort now spreads dishes across the curve instead of bunching them
    // all onto its rising limb - the old ceilings only ever compressed the result.
    private static final double HEART_FLOOR = 2.0,    HEART_CEIL = 43.5;    // half hearts
    private static final double DURATION_FLOOR = 4.0, DURATION_CEIL = 74.5; // minutes
    private static final double REGEN_FLOOR = 0.18,   REGEN_CEIL = 5.28;

    private FoodBalance() {}

    /**
     * Every constant the model is built from, as machine readable {@code key = value} lines for the
     * header of {@code /solvalheim dump}. The offline recalculation tool parses these instead of
     * carrying its own copy, so its idea of the model cannot silently drift from the game's - a
     * constant changed here is a constant the tool picks up on the next dump.
     */
    public static List<String> describeConstants() {
        return List.of(
                "saturation_ref = " + SATURATION_REF,
                "shape_gamma = " + SHAPE_GAMMA,
                "regen_bias = " + REGEN_BIAS,
                "regen_gamma = " + REGEN_GAMMA,
                "hill_k = " + HILL_K,
                "norm_p = " + NORM_P,
                "heart_floor = " + HEART_FLOOR,
                "heart_ceil = " + HEART_CEIL,
                "duration_floor = " + DURATION_FLOOR,
                "duration_ceil = " + DURATION_CEIL,
                "regen_floor = " + REGEN_FLOOR,
                "regen_ceil = " + REGEN_CEIL
        );
    }

    /**
     * A food's position on the budget ball. {@code budget} is the radius and the three components are
     * already scaled by it, so {@code hypot(hearts, duration, regen) == budget} always holds.
     */
    public record Shape(double budget, double hearts, double duration, double regen) {}

    /**
     * The single scalar a food's strength is read from, before effort. Nutrition alone is not enough: a
     * dish with nutrition 17 and saturation 0.1 is not remotely the same as one at saturation 0.9.
     */
    public static double rawPower(ModConfig.Common.FoodConfig entry) {
        if (entry == null)
            return 0;

        return Math.max(0, (double) entry.nutrition * entry.saturationModifier);
    }

    /**
     * @param pivot raw power at which a food sits mid budget. Larger means the same numbers buy less.
     * @param effort what raw power is multiplied by for how much work the dish takes to make - see
     *               {@link FoodEffort#multiplier}. 1 leaves the dish priced on its numbers alone.
     */
    public static Shape shape(ModConfig.Common.FoodConfig entry, double pivot, double effort) {
        if (entry == null)
            return new Shape(0, 0, 0, 0);

        // guarded against zero: modded drinks and placeholder items really do ship saturation 0
        var saturation = Math.max(entry.saturationModifier, 1e-3);
        var regenModifier = Math.max(entry.healthRegenModifier, 1e-3);

        var affinityHearts = Math.pow(SATURATION_REF / saturation, SHAPE_GAMMA);
        var affinityDuration = Math.pow(saturation / SATURATION_REF, SHAPE_GAMMA);
        var affinityRegen = REGEN_BIAS * Math.pow(regenModifier, REGEN_GAMMA);

        // effort buys budget rather than any one axis, so a complex dish is better food, not food that
        // leans somewhere else. Which way it leans stays entirely the author's call.
        var budget = budget(rawPower(entry) * Math.max(0, effort), pivot);

        var norm = Math.pow(
                Math.pow(affinityHearts, NORM_P)
                        + Math.pow(affinityDuration, NORM_P)
                        + Math.pow(affinityRegen, NORM_P),
                1.0 / NORM_P);

        if (norm <= 0)
            return new Shape(budget, 0, 0, 0);

        return new Shape(budget,
                budget * affinityHearts / norm,
                budget * affinityDuration / norm,
                budget * affinityRegen / norm);
    }

    /** Bounded, strictly increasing in {@code raw}, and flat well before absurd inputs matter. */
    public static double budget(double raw, double pivot) {
        if (raw <= 0)
            return 0;

        var p = Math.max(pivot, 1e-3);
        var a = Math.pow(raw, HILL_K);
        return a / (a + Math.pow(p, HILL_K));
    }

    /**
     * Writes the balanced numbers into {@code target}'s {@code overrides} block. That reuses the
     * existing escape hatch rather than adding a parallel one: the three getters already consult
     * {@code overrides} first, so tooltips, the HUD, {@code /solvalheim status} and the network sync
     * all pick the balanced values up with no further changes.
     * <p>
     * Always hand this a copy - see {@link ModConfig.Common.FoodConfig#copy()}. Writing into an entry
     * that lives in {@code foodConfigs} would persist a derived value as an authored one, and the next
     * rebuild would then treat it as pinned and never rebalance it again.
     */
    public static void applyTo(ModConfig.Common.FoodConfig target, Shape shape, ModConfig.Common common) {
        if (target == null || shape == null)
            return;

        var hearts = HEART_FLOOR + (HEART_CEIL - HEART_FLOOR) * shape.hearts();
        var minutes = DURATION_FLOOR + (DURATION_CEIL - DURATION_FLOOR) * shape.duration();
        var regen = REGEN_FLOOR + (REGEN_CEIL - REGEN_FLOOR) * shape.regen();

        // A server wide difficulty dial, and a pure multiplier, so it survives the model untouched:
        // scaling every dish by the same factor changes no ratio and breaks no containment. Honoured
        // here so that turning balancing on does not silently discard someone's tuning. defaultTimer
        // gets no such treatment - "seconds per point of nutrition" describes the old formula's shape,
        // and the model has no per point anything to scale.
        hearts *= Math.max(0f, common.nutritionHealthModifier);

        // Safety net for input no real mod ships. The envelope ceilings already bound every axis
        // mathematically, but a lopsided dish (huge nutrition, near zero saturation) can push the
        // hearts axis towards its asymptote, and one dish covering the entire health cap would make
        // the other slots pointless. Derived from the config rather than picked: a full row of dishes
        // can reach the cap, a single dish cannot.
        var heartsPerDishCap = (double) common.maxFoodHealth * 2.0 / Math.max(1, common.maxSlots);
        hearts = Math.min(hearts, heartsPerDishCap);

        var seconds = Math.max(minutes * 60.0, common.minFoodSeconds);

        if (target.overrides == null)
            target.overrides = new ModConfig.Common.OverridesConfig();

        // Only the axes the author left open. Pinning one value should not quietly hand the other two
        // back to the legacy formula, which is what skipping the whole entry used to do.
        if (target.overrides.time == null)
            target.overrides.time = (int) Math.max(1, Math.round(seconds * 20.0));

        if (target.overrides.health == null)
            target.overrides.health = (int) Math.max(0, Math.round(hearts));

        if (target.overrides.regen == null)
            target.overrides.regen = (float) Mth.clamp(regen, 0.0, REGEN_CEIL);
    }
}
