# Valheim Feast Effects

A datapack for SOL: Valheim that adds combat/utility effects to dishes — balanced around the mod's
own food model. The principle is the mod's own: **effects reward cooking, not shopping.**

Drop the `valheim_feast_effects` folder into a world's `datapacks/` directory (or add it to your
pack). Reload with `/reload` — no restart needed.

## Design rules

Effects are free in the mod's budget model (hearts/duration/regen are priced; `extraEffects` are
not), so the balance lives in *which* dishes get what, at what level and uptime:

1. **Raw food and one-step staples get nothing.** Steak, bread, cooked salmon — pure stats. The
   cheap meta stays cheap; if you want Speed, cook.
2. **Level I, full uptime** for anything that lasts the whole dish (`duration: 1.0`) — a permanent
   Speed I while fed is strong enough; no permanent level II from food.
3. **Level II only as a short burst** (`duration ≤ 0.33`) on top-tier dishes — a pop, not a
   lifestyle. [reserved for expansion]
4. **No Strength, Resistance, Regeneration or Fire Resistance at full uptime.** Those are potions'
   and golden apples' niche. Food gets mobility, senses and utility.
5. **One effect per dish**; two only on the single best stew (rabbit) — where the recipe effort
   model prices the dish highest.
6. **Nothing stacks by accident.** Dishes with the same effect are alternatives, not combos —
   the mod's slot system already lets you mix, so each tier offers a *different* effect.

## The table

| Dish | Effect | Uptime | Rationale |
|---|---|---|---|
| Mushroom Stew | Speed I | 100% | The entry-level cooked dish (bowl + 2 ingredients). Weak stats, so a clean mobility buff. |
| Beetroot Soup | Jump Boost I | 100% | Same effort tier as mushroom stew; Jump over Speed so the two stews differ. |
| Pumpkin Pie | Haste I | 100% | One step above the stews (3 ingredients incl. sugar); Haste for builders, not fighters. |
| Golden Carrot | Night Vision I | 100% | Gold-priced snack; Night Vision is pure QoL, no combat value, fits the "expensive convenience" slot. |
| Golden Apple | Absorption I | 50% | Keeps its niche as pre-fight insurance — the vanilla absorption nerfed to a half-uptime food buff. |
| Rabbit Stew | Speed I + Absorption I (25%) | — | The most involved vanilla recipe (6 ingredients). Best stats + the only two-effect dish; the Absorption burst marks it as the "boss meal". |
| Enchanted Golden Apple | Absorption I + Fire Res I (15%) | — | Treasure-tier; full-uptime Absorption I plus a short Fire Res pop. Still no Regeneration/Resistance — those stay gone with the recipe's removal. |
| Honey Bottle (drink) | Slow Falling I | 25% | Drink slot utility; short because the drink slot already carries a stats bonus (drinkSlotFoodEffectivenessBonus). |

Vanilla inputs (`nutrition`, `saturationModifier`) are mirrored in every file so the balance model
produces **exactly the same hearts/duration/regen as without the pack** — the datapack adds effects
without touching stats. If a future mod version changes default generation, a `/solvalheim reload`
re-derives everything cleanly.

## Tuning

- Effect strength over the dish's lifetime follows the server's `foodEffectMode` (`ONCE`,
  `REAPPLY`, `FADE`). With `FADE`, a level-I effect simply persists until the dish is spent.
- To rebalance, edit the JSONs and `/reload`. To give a dish stats *and* pin them, add
  `time`/`health`/`regen` — see the mod's README (Datapack format) — but pinning opts the dish out
  of the model, so prefer leaving stats alone.
