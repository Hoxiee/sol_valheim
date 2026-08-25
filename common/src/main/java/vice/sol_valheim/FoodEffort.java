package vice.sol_valheim;

import net.minecraft.core.RegistryAccess;
#if PRE_CURRENT_MC_1_19_2
import net.minecraft.core.Registry;
#elif POST_CURRENT_MC_1_20_1
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
#endif
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import vice.sol_valheim.utils.RegistryHelper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How much work a dish is, measured off the recipe graph.
 * <p>
 * This is the term that lets the balance model tell a steak from a stew, and it exists because
 * {@link net.minecraft.world.food.FoodProperties} cannot. Those numbers are authored against
 * vanilla's <em>hunger</em> bar, where the whole scale tops out at 20: Farmer's Delight's most
 * involved meal ships nutrition 14 against a steak's 8, so raw nutrition says a five ingredient
 * cooking pot dish is 1.75x a slab of meat you got by holding one item over a fire. Read purely off
 * nutrition, complex cooking can never be worth the trouble - which is exactly the state this class
 * was written to fix.
 * <p>
 * Two things are measured, and only ratios of ingredients, never their amounts:
 * <ul>
 *   <li><b>variety</b> - how many <em>distinct</em> primitive ingredients the dish bottoms out in.
 *       A golden apple is gold plus an apple, so two, no matter that it eats eight ingots. Counting
 *       amounts instead would rate it above a five ingredient meal and make the term a wealth tax
 *       rather than a complexity measure.</li>
 *   <li><b>depth</b> - how many processing steps deep the chain runs. Pasta is wheat to dough to raw
 *       pasta to the finished dish; a steak is one step.</li>
 * </ul>
 * Both are read off whichever recipe is <em>easiest</em>, not whichever is listed first: a player
 * takes the cheap path, so the model has to price the cheap path.
 * <p>
 * This deliberately replaces the per namespace inflation estimate that used to live in
 * {@link FoodBalance}. That measured a whole mod by the median of its numbers and damped every dish
 * it shipped by the same factor - so a mod was punished for having a good food catalogue, and
 * vanilla's own cheap outliers were exempt by definition. Effort is per dish and mentions no mod by
 * name, which is the property the hardcoded Farmer's Delight bump before it also failed.
 * <p>
 * Two rules exist because a real 283 mod pack breaks the naive version of this, badly:
 * <ul>
 *   <li><b>A recipe that needs what it makes is discarded.</b> Reverse and storage recipes are
 *       everywhere - a cake can be crafted from seven cake slices, a milk bucket from four milk
 *       bottles, wheat from a hay block - and each is a one ingredient recipe, so the cheapest path
 *       through the graph is straight back down the way you came. Measured naively, cake and milk read
 *       as primitives.</li>
 *   <li><b>A recipe with an ingredient we cannot resolve is discarded, not shortened.</b> Skipping the
 *       ingredient would make the recipe look cheaper than it is and let it win as the cheapest path.
 *       An empty tag usually means the mod that filled it is not installed, so the recipe cannot be
 *       crafted at all and has no business pricing anything.</li>
 * </ul>
 * The one thing a recipe graph cannot see is food that is never crafted at all, so an item nothing
 * produces falls back on {@link #uncrafted}.
 * <p>
 * Best effort by design. Anything unreadable - a recipe type that hides its ingredients, an unbound
 * tag - degrades to gathered rather than throwing. A wrong guess costs a bounded multiplier, so it is
 * never worth crashing a world over.
 */
public final class FoodEffort
{
    /**
     * @param variety distinct primitive ingredients the chain bottoms out in
     * @param depth processing steps, 0 for something picked up as is
     */
    public record Effort(int variety, int depth) {
        /** The single scalar {@link #multiplier} reads. Depth counts for less than variety: a long
         *  chain of one ingredient is still a boring dish. */
        public double raw() {
            return variety + DEPTH_WEIGHT * depth;
        }
    }

    /** Picked up, farmed or dropped: one thing, no steps. Also the fallback for anything unreadable. */
    public static final Effort GATHERED = new Effort(1, 0);

    /**
     * A plain cooked ingredient - one primitive, one step, so {@code 1 + 0.75}. The scale is pinned
     * here rather than at zero so that a steak reads as exactly 1.0 and leaves vanilla's staple food
     * where it is; everything else is priced as a ratio against it.
     */
    private static final double EFFORT_REF = 1.75;

    private static final double DEPTH_WEIGHT = 0.75;

    /** Square root, so a four times more involved dish is worth twice as much, not four times. */
    private static final double EFFORT_GAMMA = 0.5;

    // Containment. The floor keeps a raw ingredient from reaching zero, and the ceiling is what makes
    // this term safe to hand a modpack: no recipe chain, however baroque, buys more than 2.4x.
    private static final double EFFORT_MIN = 0.75;
    private static final double EFFORT_MAX = 2.4;

    /** Deep enough for wheat to dough to pasta to a finished dish, with room to spare. */
    private static final int MAX_DEPTH = 6;

    /** A dish with many recipes is priced off its cheapest; this bounds the search, not the answer. */
    private static final int MAX_RECIPES_PER_ITEM = 12;

    /** Past this the multiplier is pegged anyway, so counting further roots buys nothing. */
    private static final int MAX_ROOTS = 16;

    private static volatile List<Entry> recipes = List.of();
    private static volatile Map<Item, Effort> index = Collections.emptyMap();

    #if POST_CURRENT_MC_1_20_1
    /** 1.20.1 asks for registries to read a recipe result. Null until a server hands us real ones. */
    private static volatile RegistryAccess registries = null;
    #endif

    private FoodEffort() {}

    /**
     * Takes a fresh recipe table and prices every result in it. Called from the recipe manager itself
     * rather than from a reload listener, because a listener cannot see its siblings: on
     * {@code /reload} the server's own {@code getRecipeManager()} still answers with the outgoing
     * table until the whole reload has finished.
     * <p>
     * 1.21 moved the recipe id onto a {@code RecipeHolder} wrapper, so the two targets hand over
     * different collections - exactly one of these overloads exists per compile.
     */
    #if MC_1_21_1
    public static synchronized void capture(java.util.Collection<net.minecraft.world.item.crafting.RecipeHolder<?>> table) {
        List<Entry> entries = new ArrayList<>(table == null ? 0 : table.size());
        if (table != null) {
            for (var holder : table)
                entries.add(new Entry(String.valueOf(holder.id()), holder.value()));
        }
        recipes = List.copyOf(entries);
        rebuildIndex();
    }
    #else
    public static synchronized void capture(Collection<Recipe<?>> table) {
        List<Entry> entries = new ArrayList<>(table == null ? 0 : table.size());
        if (table != null) {
            for (var recipe : table)
                entries.add(new Entry(String.valueOf(recipe.getId()), recipe));
        }
        recipes = List.copyOf(entries);
        rebuildIndex();
    }
    #endif

    /** A recipe alongside a stable id to sort it by. */
    public record Entry(String id, Recipe<?> recipe) {}

    /** Why recipes were dropped at the last capture, for the log line and the dump header. */
    public record CaptureStats(int recipes, int priced, int unreadable, int noResult,
                               int emptyIngredients, int unresolvableReads) {}

    private static volatile CaptureStats lastStats =
            new CaptureStats(0, 0, 0, 0, 0, 0);

    public static CaptureStats captureStats() {
        return lastStats;
    }

    /**
     * Hands over the real registries once a server exists. Until then results are read against empty
     * ones, which every vanilla and every mainstream modded recipe ignores - but a mod that does look
     * something up gets a second, correct pass out of this rather than being silently mispriced.
     * <p>
     * No early return when the access is unchanged: a second world in the same JVM can reuse the
     * registry instance while its recipe table was re-captured meanwhile, and the index must always
     * follow the last capture.
     */
    public static synchronized void useRegistries(RegistryAccess access) {
        #if POST_CURRENT_MC_1_20_1
        registries = access;
        #endif
        rebuildIndex();
    }

    /** Called when a world closes, so one save's recipes cannot price the next one's food. */
    public static synchronized void clear() {
        recipes = List.of();
        index = Collections.emptyMap();

        #if POST_CURRENT_MC_1_20_1
        registries = null;
        #endif
    }

    /** Effort for every item any recipe produces. Empty before the first recipe reload. */
    public static Map<Item, Effort> index() {
        return index;
    }

    /** @return measured effort, or an {@link #uncrafted} guess for anything no recipe produces. */
    public static Effort of(Item item) {
        if (item == null)
            return GATHERED;

        var known = index.get(item);
        return known == null ? uncrafted(item) : known;
    }

    /**
     * What to charge for food no recipe produces. Loot only food is invisible to a recipe graph - an
     * end game drop and an apple both bottom out at "picked up" - which would leave the rarest food in
     * the game priced under a steak. So the one general signal the game itself carries is used instead:
     * the item's own rarity. Mods set it on their flagship items for the same reason vanilla puts EPIC
     * on the notch apple, and nothing here names a mod or an item.
     * <p>
     * Unproduced items only, on purpose. A dish with a recipe is priced by its recipe, so a mod cannot
     * buy a stronger meal by declaring it rare - only by making it cost something.
     */
    private static Effort uncrafted(Item item) {
        try {
            // deliberately mirrors what a crafted chain of that size would have cost, so a rare drop is
            // worth a real recipe rather than living on a scale of its own
            return switch (new ItemStack(item).getRarity()) {
                case UNCOMMON -> new Effort(1, 1);
                case RARE -> new Effort(2, 2);
                case EPIC -> new Effort(3, 3);
                default -> GATHERED;
            };
        } catch (Exception exception) {
            return GATHERED;
        }
    }

    /** Whether a recipe actually priced this item, as opposed to {@link #uncrafted} guessing. */
    public static boolean isPriced(Item item) {
        return item != null && index.containsKey(item);
    }

    /**
     * What the dish's nutrition is multiplied by before it buys any budget - see
     * {@link FoodBalance#shape}. Bounded at both ends, so this can reorder food but never run away
     * with it.
     *
     * @param weight {@code balanceEffortWeight}; 0 turns the term off entirely and returns 1
     */
    public static double multiplier(Effort effort, double weight) {
        if (effort == null || weight <= 0)
            return 1.0;

        return Mth.clamp(Math.pow(effort.raw() / EFFORT_REF, EFFORT_GAMMA * weight), EFFORT_MIN, EFFORT_MAX);
    }

    /**
     * Effort constants as machine readable {@code key = value} lines - see
     * {@link FoodBalance#describeConstants} for why they travel with the dump.
     */
    public static List<String> describeConstants() {
        return List.of(
                "effort_ref = " + EFFORT_REF,
                "depth_weight = " + DEPTH_WEIGHT,
                "effort_gamma = " + EFFORT_GAMMA,
                "effort_min = " + EFFORT_MIN,
                "effort_max = " + EFFORT_MAX
        );
    }

    /**
     * Whether item tags resolve yet, probed the same way {@link Ingredient.TagValue} resolves them.
     * <p>
     * Tags bind to their registries only after a whole resource reload finishes - after recipes,
     * advancements and everything else have applied - so during the first world load there is a
     * window where recipe data exists but tag lookups answer empty. Reading an ingredient in that
     * window is not just wrong once: Ingredient caches its resolved items on first use, so the
     * emptiness sticks for the life of the object. Every walk over recipes therefore waits for
     * this to turn true rather than trusting whatever the registry happens to hold.
     */
#if PRE_CURRENT_MC_1_19_2
    private static final TagKey<Item> TAG_PROBE = TagKey.create(Registry.ITEM_REGISTRY, vice.sol_valheim.utils.RegistryHelper.of("minecraft", "planks"));

    public static boolean tagsBound() {
        return Registry.ITEM.getTagOrEmpty(TAG_PROBE).iterator().hasNext();
    }
#elif POST_CURRENT_MC_1_20_1
    private static final TagKey<Item> TAG_PROBE = TagKey.create(Registries.ITEM, vice.sol_valheim.utils.RegistryHelper.of("minecraft", "planks"));

    public static boolean tagsBound() {
        return BuiltInRegistries.ITEM.getTagOrEmpty(TAG_PROBE).iterator().hasNext();
    }
#endif

    private static void rebuildIndex() {
        var table = recipes;
        if (table.isEmpty()) {
            index = Collections.emptyMap();
            lastStats = new CaptureStats(0, 0, 0, 0, 0, 0);
            return;
        }

        // Walking recipes while item tags are unbound reads every tag ingredient as empty - and the
        // Ingredient caches that emptiness on first touch, so no later pass can recover it. On a
        // fresh JVM recipes apply before their tags bind, which is exactly how a first world entry
        // used to price half of Farmer's Delight as gathered while a re-entry looked fine (it was
        // quietly reading the previous load's tags). Keep whatever index exists and let the tick or
        // join pass redo this once the probe below turns true.
        if (!tagsBound()) {
            SOLValheim.LOGGER.info("[sol_valheim] item tags are not bound yet - effort pricing deferred");
            return;
        }

        var walker = new Walker(table);
        var priced = walker.priceAll();
        index = Collections.unmodifiableMap(priced);
        lastStats = new CaptureStats(table.size(), priced.size(),
                walker.unreadable, walker.noResult, walker.noIngredients, walker.unresolvableReads);

        // INFO on purpose: when a dish suddenly reads as "gathered" between two sessions, this line
        // is the difference between a bug report and a shrug
        SOLValheim.LOGGER.info("[sol_valheim] Priced {} craftable items from {} recipes (dropped: {} no result, "
                        + "{} empty ingredients, {} unreadable, {} unresolvable ingredient reads)",
                priced.size(), table.size(), walker.noResult, walker.noIngredients,
                walker.unreadable, walker.unresolvableReads);
    }

    /**
     * One pass over the recipe graph. Not thread safe and not reused - {@link #rebuildIndex} makes a
     * fresh one per reload, because the memo it keeps is only valid for one recipe table.
     */
    private static final class Walker
    {
        /** Every recipe that makes a given item. None is assumed cheapest; all of them are tried. */
        private final Map<Item, List<Entry>> producers = new IdentityHashMap<>();

        /** Results that involved no cycle truncation, and are therefore true regardless of caller. */
        private final Map<Item, Node> stable = new IdentityHashMap<>();

        /**
         * Results that only hold for the item currently being priced, because a cycle was cut
         * somewhere below them. Cleared per top level item: caching these globally is what makes a
         * cake slice's effort depend on whether cake happened to be priced first.
         */
        private final Map<Item, Node> scratch = new IdentityHashMap<>();

        private final Set<Item> visiting = Collections.newSetFromMap(new IdentityHashMap<>());

        /** Items that something below the current walk turned out to need. See {@link #resolve}. */
        private final Set<Item> blocked = Collections.newSetFromMap(new IdentityHashMap<>());

        private int cycleHits;
        private int unreadable;
        private int noResult;
        private int noIngredients;
        private int unresolvableReads;

        private record Node(Set<Item> roots, int depth) {
            double cost() {
                return roots.size() + DEPTH_WEIGHT * depth;
            }
        }

        private Walker(List<Entry> table) {
            // sorted by id so the answer is the same on every launch. Recipes arrive out of a hash map,
            // and a dish whose hearts flip between restarts is a bug report nobody can reproduce
            List<Entry> sorted = new ArrayList<>(table);
            sorted.sort(Comparator.comparing(Entry::id));

            for (var entry : sorted) {
                var recipe = entry.recipe();
                if (recipe == null)
                    continue;

                Item result;
                try {
                    result = resultOf(recipe);

                    // a recipe that will not name its ingredients - map cloning, most custom recipe
                    // types - tells us nothing about effort, and treating it as a producer would make
                    // its result look primitive
                    if (result == null) {
                        noResult++;
                        continue;
                    }

                    if (recipe.getIngredients().isEmpty()) {
                        noIngredients++;
                        continue;
                    }
                } catch (Exception exception) {
                    unreadable++;
                    continue;
                }

                producers.computeIfAbsent(result, key -> new ArrayList<>(2)).add(entry);
            }
        }

        private Map<Item, Effort> priceAll() {
            Map<Item, Effort> priced = new IdentityHashMap<>(producers.size());

            for (var item : producers.keySet()) {
                scratch.clear();
                var node = resolve(item, MAX_DEPTH);
                priced.put(item, new Effort(node.roots().size(), node.depth()));
            }

            return priced;
        }

        /**
         * @param depthLeft steps still allowed below here. Results reached by cutting a cycle or by
         *                  running out of depth are not treated as generally true - see {@link #scratch}.
         */
        private Node resolve(Item item, int depthLeft) {
            var known = scratch.get(item);
            if (known == null)
                known = stable.get(item);
            if (known != null)
                return known;

            // Something below us needs an item that is still being resolved above us, so this branch
            // presupposes what it is trying to build. Report it upwards - the owner of the cycle drops
            // the recipe - and stop here so the recursion terminates.
            if (visiting.contains(item)) {
                blocked.add(item);
                cycleHits++;
                return gathered(item);
            }

            if (depthLeft <= 0) {
                cycleHits++;
                return gathered(item);
            }

            visiting.add(item);
            var hitsBefore = cycleHits;

            Node best = null;
            var candidates = producers.get(item);

            if (candidates != null) {
                var considered = 0;

                for (var entry : candidates) {
                    if (considered++ >= MAX_RECIPES_PER_ITEM)
                        break;

                    blocked.remove(item);
                    var candidate = walk(entry.recipe(), depthLeft);

                    // this recipe turned out to need the very item it produces - seven cake slices for
                    // a cake, four milk bottles for a bucket of milk. It says nothing about how hard
                    // the item is to get, and being a short recipe it would otherwise win outright
                    if (blocked.remove(item))
                        continue;

                    if (candidate == null)
                        continue;

                    // the easiest way to get the item is the one a player will actually use, so it is
                    // the one the dish is priced at
                    if (best == null || candidate.cost() < best.cost())
                        best = candidate;
                }
            }

            visiting.remove(item);

            var result = best == null ? gathered(item) : best;
            (cycleHits == hitsBefore ? stable : scratch).put(item, result);
            return result;
        }

        private Node walk(Recipe<?> recipe, int depthLeft) {
            List<Ingredient> ingredients;
            try {
                ingredients = recipe.getIngredients();
            } catch (Exception exception) {
                return null;
            }

            // a set, so eight gold ingots count once. Amounts are deliberately not part of this - see
            // the class doc on why variety and not wealth
            Set<Item> roots = Collections.newSetFromMap(new IdentityHashMap<>());
            var deepest = 0;
            var used = 0;

            for (var ingredient : ingredients) {
                // a blank slot in a shaped recipe's grid, which is not a missing ingredient
                if (ingredient == null || ingredient.isEmpty())
                    continue;

                var representative = representativeOf(ingredient);

                // an ingredient we cannot read - almost always a tag left empty by an absent mod, so
                // the recipe is uncraftable anyway. Dropping the whole recipe is the honest answer;
                // dropping just the ingredient would make it the cheapest path and underprice the dish
                if (representative == null) {
                    unresolvableReads++;
                    return null;
                }

                used++;
                var child = resolve(representative, depthLeft - 1);
                deepest = Math.max(deepest, child.depth());

                for (var root : child.roots()) {
                    if (roots.size() >= MAX_ROOTS)
                        break;

                    roots.add(root);
                }
            }

            if (used == 0 || roots.isEmpty())
                return null;

            return new Node(roots, Math.min(MAX_DEPTH, deepest + 1));
        }

        /**
         * One stand in item for a tag ingredient. Which member hardly matters - any plank resolves to
         * a log - but it has to be the <em>same</em> member every launch, so the lowest id wins rather
         * than whatever the tag happens to list first.
         */
        private static Item representativeOf(Ingredient ingredient) {
            try {
                Item best = null;
                ResourceLocation bestId = null;

                for (var stack : ingredient.getItems()) {
                    if (stack == null || stack.isEmpty())
                        continue;

                    var id = RegistryHelper.getItemId(stack.getItem());
                    if (id == null)
                        continue;

                    if (bestId == null || id.compareTo(bestId) < 0) {
                        best = stack.getItem();
                        bestId = id;
                    }
                }

                return best;
            } catch (Exception exception) {
                // a modded ingredient that resolves lazily against something we do not have yet
                return null;
            }
        }

        private static Item resultOf(Recipe<?> recipe) {
            #if PRE_CURRENT_MC_1_19_2
            var stack = recipe.getResultItem();
            #elif POST_CURRENT_MC_1_20_1
            var access = registries;
            var stack = recipe.getResultItem(access == null ? RegistryAccess.EMPTY : access);
            #endif

            return stack == null || stack.isEmpty() ? null : stack.getItem();
        }

        private static Node gathered(Item item) {
            return new Node(Set.of(item), 0);
        }
    }
}
