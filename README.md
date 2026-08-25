# SOL: Valheim Continued

Replaces Minecraft's hunger bar with Valheim's food system.

You do not fill a bar — you keep **three dishes** running at once. Each one grants hearts and health
regeneration for as long as it lasts, and when it runs out you lose what it was giving you. Eating
well is how you get a big health pool; eating nothing at all leaves you at three hearts and unable
to sprint.

Built with [Architectury](https://github.com/architectury/architectury): **Fabric** and **Forge** on
**1.19.2** and **1.20.1**, **Fabric** and **NeoForge** on **1.21.1**.

## Requirements

| | 1.19.2 | 1.20.1 | 1.21.1 |
|---|---|---|---|
| Fabric Loader | 0.14.21+ | 0.15.1+ | 0.19.3+ |
| Fabric API | 0.76.0+ | 0.87.0+ | 0.116.15+ |
| Forge | 43.2.4+ | 47.1.43+ | — |
| NeoForge | — | — | 21.1.248+ |
| Architectury API | 6.5.85+ | 9.1.12+ | 13.0.11+ |
| Cloth Config | 8.3.115+ | 11.0.99+ | 15.0.140+ |

## How it works

**Food slots.** Three by default (configurable, 1–8). Each slot holds one dish and counts down. You
cannot eat the same dish twice to refresh it early, and you cannot swap a dish out, until it is
nearly spent — by default below 20 % remaining or under 60 seconds left, whichever comes first. The
HUD outlines a slot in yellow and pulses it once that dish can be replaced.

**A separate drink slot.** Anything with the drinking animation — potions, milk, modded juices — goes
into its own fourth slot and does not compete with food. Having a drink active also multiplies
everything your food is giving you (+10 % by default).

**Hearts.** Every active dish adds half hearts based on its nutrition. Your maximum health is three
hearts plus whatever your food is currently granting, capped at 40 hearts in total. Lose a dish and you lose
those hearts — including the ones you were standing on, so let food expire on a cliff at your own
risk.

**Fading.** A dish's hearts shrink as it runs out — the closer to expiry, the less it gives, and
never below `foodDecayMinFraction` of its full value. `foodDecayMode` picks the curve; the default
`VALHEIM` scales hearts as *remaining time^0.3* — the original game's own curve, so a dish stays
significant almost to the end (75 % of its time left is still ~92 % of its heart range, 10 % left
still about half). `LINEAR` declines from the moment of eating, `LATE` holds full hearts until
`foodDecayStartFraction` remains and only then fades (the gentlest on balance), `STEPS` drops in
quarters, and `OFF` keeps full hearts until expiry. Food slots only — the drink slot always gives
its full value, and regeneration is unaffected either way. The HUD icon darkens with the dish's
fading hearts, a quiet sound plays when a dish starts visibly weakening (`decayCue`), and
`#sol_valheim:no_decay` exempts a dish from all of it.

**Dying** wipes your stomach by default; `keepFoodPercentageOnDeath` keeps a fraction of every
dish's remaining time instead (half, for example, for a gentler game). Death also leaves you
**Weakened** (`weakenedOnDeath`, on by default): for `weakenedSeconds` after respawning your
maximum health is cut by `weakenedHealthPenalty` — the timer survives the respawn itself, so there
is no eating your way out of it. A quiet sound and a brief highlight over the emptied slot tell you
when a dish runs out — no chat spam, and both can be turned off client-side with `expiryCue`.

**Regeneration.** Health comes back continuously, at a rate set by what you have eaten: about one
heart every 40 seconds on an empty stomach, versus one heart every four seconds with three good
dishes.
Taking damage pauses it for 10 seconds; the HUD shows that cooldown as a small dial. Vanilla's own
"eat to full, regenerate for free" behaviour is switched off so that food is the only source of
regeneration — set `vanillaRegeneration` if you would rather have both.

**Sprinting** needs at least one food slot filled. There is a 10 second grace period after
respawning so a death is not an instant soft-lock. The server checks this too, so a modified or
desynced client cannot sprint its way around an empty stomach. While sprinting is unavailable the
HUD shows a small boot — red and pulsing on an empty stomach, yellow with the seconds left while
the grace period runs down (`showSprintHint`).

**An empty stomach** can also hurt directly: `emptyStomachWeakness`, `emptyStomachSlowness` and
`emptyStomachMiningFatigue` apply those effects (by level) while every food slot is empty. They
default to weakness I/off/mining fatigue I; flight, creative mode and the respawn grace period are exempt,
same as the sprint rule.

**Speed.** Filling your hearts to at least 10 (configurable) grants +20 % movement speed.

**Sleeping** runs food down for the whole skipped night, so you cannot sleep away a hunger problem —
but it also grants **Rested**: a status effect that multiplies your food regeneration (×1.5 by
default, `restedRegenMultiplier`) for eight minutes (`restedDurationSeconds`). Rested is only
awarded for the full Valheim ritual: the bed has to sit under a roof with fire burning within a few
blocks — sleep anywhere else and you wake up just as tired. Waking up from a full
night also restores your health to its current maximum (`healFullOnSleep`).
The effect icon and its countdown are vanilla's own; `restedEnabled` turns the whole thing off.
Like its Valheim namesake, Rested also boosts **experience** — every point you pick up is worth
+50 % more (`restedXpMultiplier`), from mob kills and ore to fishing, trading and furnaces.

## Food values

Every item that vanilla considers edible, plus everything drinkable, gets values derived from its own
`FoodProperties` — so **a pack with a dozen food mods works out of the box** with no manual entries.
Values resolve from three sources, highest priority first:

1. **Datapacks** — `data/<namespace>/sol_valheim/food/<item>.json`
2. **The config** — the `foodConfigs` block of `config/sol_valheim/common.json5`
3. **Generated** from the item's vanilla food properties

Generated values are written back into the config on first launch (`persistGeneratedFoodValues`), so
they are there to be edited by hand. Datapacks always win over the config, which makes them the right
place for pack-wide balance: they are versioned with the pack, they reload with `/reload`, and they
do not fight a player's local config file.

Whichever source wins supplies `nutrition`, `saturationModifier` and `healthRegenModifier` — the
*inputs*. Hearts, duration and regeneration are then worked out by the balance model below.

### Balancing

`balanceFoodValues` is on by default and has to hold two things at once: **a food mod cannot outclass
the game just by shipping bigger numbers, and cooking still has to be worth doing.** Both halves are
real failure modes. Left alone, three Farmer's Delight flagship dishes came to 25.5 hearts against
vanilla's best three at 15.7, and the only thing stopping them was the hard `maxFoodHealth` cap.
Contained too hard, the opposite happens: a steak matches a five ingredient pot meal, and there is no
reason to ever light a cooking pot.

Each dish gets a **budget** and spends it on a **shape**:

- **Budget** grows with `nutrition × saturationModifier × crafting effort` along a curve that flattens
  out. Raw power 13.7 spends half the scale; raw power 80 spends 0.95. A 5.8× bigger input buys 1.9×
  more food.
- **Shape** is decided by the *ratio* of the inputs, never their size, so inflating every number cannot
  change which way a dish leans. High saturation leans towards duration, high nutrition per point of
  saturation leans towards hearts, and `healthRegenModifier` leans towards regeneration.
- The budget is then a **conservation law**: the three axes are projected onto a sphere of that radius,
  so a dish that is stronger on one axis is necessarily weaker on the others. Two dishes with the same
  raw power always cost the same, and the author chooses what to buy with it — which is the trade-off
  Valheim's food system is built on.

On top of that, **how much work a dish took is measured, not asserted.** `FoodProperties` cannot
express it: those numbers are authored against vanilla's 20 point hunger bar, so Farmer's Delight's
most involved meal ships nutrition 14 against a steak's 8 — raw nutrition claims a five ingredient pot
meal is 1.75× a slab of meat held over a fire. Read that way, cooking is never worth the trouble.

So the recipe graph is walked instead, and two things are counted:

- **variety** — how many *distinct* primitive ingredients the chain bottoms out in. A golden apple is
  gold plus an apple: two, no matter that it eats eight ingots. Counting amounts would make this a
  wealth tax rather than a complexity measure.
- **depth** — how many processing steps deep it runs. Pasta is wheat → dough → raw pasta → the dish;
  a steak is one step.

Each dish is priced off whichever of its recipes is *easiest*, because that is the one a player will
actually use. `variety + 0.75 × depth` is compared against a plain cooked ingredient's `1.75`, square
rooted, and clamped to **0.75×–2.4×** — so this can reorder food, but no recipe chain however baroque
runs away with it. Nothing in it names a mod, an item or a namespace.

Food that no recipe produces is invisible to all of that, so it falls back on the one general signal
the game already carries: **the item's own rarity**. An `EPIC` drop is charged like a three step
recipe, `COMMON` like something picked up. This applies to unproduced items only — a dish with a recipe
is priced by its recipe, so a mod cannot buy a stronger meal by declaring it rare, only by making it
cost something.

The envelope is fitted to vanilla's own table, so **vanilla staples barely move** — cooked beef goes
from 8 half hearts / 19.2 min to 7 / 15.9. What changes is everything above them. On a real 283 mod
pack (376 foods, 7079 recipes):

| plate (3 dishes + milk) | before | after |
|---|---|---|
| steak + cooked salmon + golden apple | 20.1 hearts | 14.6 |
| steak + cooked porkchop + bread | 18.4 hearts | 15.7 |
| noodle soup + noodles + hamburger | 28.3 hearts | 34.9 |
| vanilla's best: rabbit stew + cake + golden carrot | 20.6 hearts | 25.0 |

Cooked food over the cheap meta goes from **1.41× to 2.23×**, and the twelve strongest dishes in that
pack are all cooked pot meals. Nothing reaches the per dish cap, so the whole gradient is expressed
rather than clipped.

Run `/solvalheim balance` to see all of this for the pack you actually have installed: it prints each
dish's multiplier and its `variety/depth`, and counts per mod how many dishes no recipe could price.

**The escape hatch is `time` / `health` / `regen`.** Whichever of them you set, in a datapack or by
hand in the config, is used verbatim — and the ones you leave out still come from the model, so pinning
`health` does not quietly hand duration and regeneration back to the old formula. This is also the
answer for a dish the model cannot see: loot only food with no recipe and no rarity to go on reads as
gathered, and `/solvalheim balance` tells you which dishes those are.

For tuning the curve itself there is `/solvalheim dump` plus `tools/food_recalc.py`: the script reads
the dump back, re-runs the model offline with different constants and reports what would move — no
relaunch per idea, and it self-checks against the Java model's own numbers.

Where all of this is heading next is tracked in [ROADMAP.md](ROADMAP.md).

`balanceFoodValues = false` turns the model off entirely and restores the old linear formulas.

### Datapack format

`data/mypack/sol_valheim/food/cooked_beef.json` overrides `minecraft:cooked_beef`. Every field is
optional.

```json
{
  "nutrition": 8,
  "saturationModifier": 1.0,
  "healthRegenModifier": 1.25,

  "time": 24000,
  "health": 8,
  "regen": 1.5,

  "effects": [
    { "id": "minecraft:speed", "duration": 0.5, "amplifier": 1 }
  ]
}
```

- `nutrition` — how much food this is. Drives the budget, and leans it towards hearts.
- `saturationModifier` — leans the budget towards duration, and counts towards the budget itself.
- `healthRegenModifier` — leans the budget towards regeneration.
- `time` / `health` / `regen` — set that result outright instead of letting the model decide it.
  `time` is in ticks, `health` in half hearts. Mix freely with the inputs above; see
  [Balancing](#balancing).
- `effects` — `duration` is a fraction of the food's own duration (`1.0` = the whole time),
  `amplifier` is the level as players read it (`1` = Speed I).
- `"item": "somemod:pie"` — target a different item than the file name.

The file name is a path, so a nested file like `sol_valheim/food/cooked_beef.json` under namespace
`minecraft` targets `minecraft:cooked_beef`. Overrides for items that are not installed are skipped
with a warning instead of breaking the reload — so one datapack can cover several optional mods.

### Item tags

| Tag | Effect |
|---|---|
| `#sol_valheim:resets_food` | Eating it clears every slot. Ships with rotten flesh and spider eyes. |
| `#sol_valheim:can_eat_early` | Can always be swapped in, ignoring the "nearly spent" rule. |
| `#sol_valheim:not_consumable` | Uses the drinking animation but should *not* fill the drink slot. |
| `#sol_valheim:no_decay` | Keeps full hearts for its whole duration, exempt from `foodDecayMode`. |

`not_consumable` is the escape hatch for modded items that borrow `UseAnim.DRINK` for something that
is not a drink.

## Commands

| | |
|---|---|
| `/solvalheim status [targets]` | List active dishes with time left, hearts and regeneration. |
| `/solvalheim clear [targets]` | Empty every slot. |
| `/solvalheim grant <item> [targets]` | Hand a dish straight to a player. Goes through the normal eating rules, so a full row of fresh dishes refuses it. |
| `/solvalheim reload` | Re-read the config, re-resolve every food value, and push the result to all connected clients — no restart. |
| `/solvalheim balance` | Audit the resolved table: per-mod inflation factors, the strongest dishes, and anything towering over vanilla's best. |
| `/solvalheim dump` | Write the whole resolved table to `config/sol_valheim/food_dump.md` — inputs, measured effort and model constants per dish. |

`status` on yourself needs no permission; everything else needs level 2.

## Config

`config/sol_valheim/common.json5` and `config/sol_valheim/client.json5`, editable through Cloth
Config. Every value is clamped on load, so a hand-edited file cannot put the game into a state it
cannot run.

### Common — server authoritative

| Option | Default | |
|---|---|---|
| `maxSlots` | 3 | Food slots, 1–8. |
| `startingHealth` | 3 | Hearts with nothing eaten. |
| `maxFoodHealth` | 40 | Cap on total hearts, starting health included. |
| `defaultTimer` | 180 | Seconds of duration per point of nutrition. Only used with balancing off. |
| `minFoodSeconds` | 300 | Floor on any dish's duration. |
| `nutritionHealthModifier` | 1.0 | Scales hearts from food. Applies either way. |
| `balanceFoodValues` | true | Derive hearts, duration and regeneration from the balance model instead of the old linear formulas — see [Balancing](#balancing). |
| `balancePivot` | 13.7 | Raw power (`nutrition × saturationModifier × effort`) at which a dish counts as good. Lower makes everything stronger. |
| `balanceEffortWeight` | 1.0 | How much a dish's crafting cost matters. 0 prices food on its numbers alone; 2 doubles the spread between a steak and a stew. |
| `regenSpeedModifier` | 5 | Ticks between regeneration steps; lower is faster. |
| `regenDelay` | 200 | Ticks regeneration waits after taking damage. |
| `eatAgainPercentage` | 0.25 | Fraction remaining below which a dish can be replaced. |
| `eatAgainMinSeconds` | 60 | Seconds remaining below which a dish can always be replaced. |
| `speedBoost` | 0.20 | Movement bonus at high hearts; 0 disables. |
| `speedBoostMinHearts` | 10 | Hearts needed before the bonus applies. |
| `sprintRequiresFood` | true | Require a filled food slot to sprint. |
| `respawnGracePeriod` | 10 | Seconds after spawning before that applies. |
| `drinkSlotFoodEffectivenessBonus` | 0.10 | Bonus to all food while a drink is active. |
| `keepFoodPercentageOnDeath` | 0 | Fraction of every dish's remaining time kept after dying; 0 wipes the stomach. |
| `emptyStomachWeakness` | 1 | Weakness level while every food slot is empty; 0 disables. |
| `emptyStomachSlowness` | 0 | Slowness level while every food slot is empty; 0 disables. |
| `emptyStomachMiningFatigue` | 1 | Mining fatigue level while every food slot is empty; 0 disables. |
| `healFullOnSleep` | true | Restore full health after sleeping through a night. |
| `weakenedOnDeath` | true | Grant the Weakened effect on death, cutting maximum health. |
| `weakenedSeconds` | 120 | Seconds of Weakened per death; survives respawns. |
| `weakenedHealthPenalty` | 0.3 | Fraction of maximum health cut while Weakened. |
| `foodEffectMode` | ONCE | What a dish's extra effects do: `ONCE` - applied once on eating; `REAPPLY` - kept topped up while the dish lasts; `FADE` - the level steps down with the remaining food (Strength 3 → 2 → 1 → gone). Replaces the old `reapplyFoodEffects` boolean. |
| `foodDecayMode` | VALHEIM | How a dish's hearts behave as it runs out: `OFF` - full hearts until expiry; `LINEAR` - they fall with the remaining time from the moment of eating; `LATE` - full hearts until `foodDecayStartFraction` remains, then a fade down to the floor; `STEPS` - they step down in quarters (100 → 75 → 50 → floor %); `VALHEIM` - they scale as *remaining^0.3* towards the floor. Food slots only; the drink slot never fades. |
| `foodDecayStartFraction` | 0.5 | `LATE` mode: fraction of the dish's lifetime below which its hearts begin to fade. |
| `foodDecayMinFraction` | 0.25 | Fraction of its hearts a dish still gives at the moment it expires. |
| `passTicksDuringNight` | true | Run food down across a skipped night. |
| `restedEnabled` | true | Grant the Rested effect for sleeping in a bed under a roof, near fire. |
| `restedDurationSeconds` | 480 | Seconds of Rested per grant. |
| `restedRegenMultiplier` | 1.5 | Food regeneration multiplier while Rested. |
| `restedXpMultiplier` | 1.5 | Experience point multiplier while Rested; 1 disables the bonus. |
| `vanillaRegeneration` | false | Keep vanilla natural regeneration as well. |
| `syncFoodValuesToClients` | true | Send resolved values to clients so HUD and tooltips match the server. |
| `persistGeneratedFoodValues` | true | Write generated values into the config for hand editing. |
| `foodConfigs` | *generated* | Per item values — see [Food values](#food-values). |
| `foodConfigVersion` | *managed* | Schema version of the generated entries. Written by the mod; do not edit. |

### Client

| Option | Default | |
|---|---|---|
| `showFoodHud` | true | Draw the food HUD at all. |
| `useLargeIcons` | true | Larger icons; required for timer text. |
| `showTimerText` | true | Remaining minutes on each slot. |
| `showRegenMeter` | true | Post-damage regeneration dial. |
| `showSprintHint` | true | Small boot indicator while sprinting is blocked or during the respawn grace. |
| `expiryCue` | BOTH | What plays when a dish runs out: `BOTH`, `SOUND`, `HUD` or `NONE` — a quiet sound and/or a brief slot highlight, never a chat message. |
| `decayCue` | true | Quiet sound when a dish starts fading (with `foodDecayMode`). |
| `foodHudConfig` | | Anchor, offset and per-slot gaps for the food row. |
| `regenHudConfig` | | Anchor and offset for the regeneration dial. |
| `sprintHudConfig` | | Anchor and offset for the sprint indicator. |

`foodHudConfig.slotOffsets` nudges individual slots — first entry is the rightmost slot. Use it to
work around another mod's HUD.

## Advancements

A small built-in tree, awarded by the mod itself: **SOL: Valheim** (root) → **First Meal** (eat a
dish) → **Full Table** (all food slots filled at once) and **Rested** (sleep through a night in a
bed under a roof, near a fire), plus **Refreshed** (fill the drink slot). No rewards attached - a pack can
extend or replace the JSONs at `data/sol_valheim/advancements/` freely.

## Using this in a modpack

- **Food values are generated for every mod in the pack**, so nothing is invisible to the system just
  because it came from a food mod. Tune what matters with a datapack and leave the rest.
- **A mod's generosity is contained automatically.** Nothing has to be hand-nerfed per mod — check the
  result with `/solvalheim balance` and reach for a datapack only where you disagree with it.
- **The server's values are authoritative.** With `syncFoodValuesToClients` on, clients render the
  server's numbers even when their own config or datapacks differ — which they will.
- **The sprint rule, respawn grace, regen delay and effect mode always sync from the server**, so
  client-side hints and tooltips match what the server actually enforces, whatever the local config
  says.
- **Other mods hooking `Item.use` keep working.** This mod only cancels that method when it is
  actually refusing a meal; everything else falls through to vanilla untouched.
- **Items that disappear degrade quietly.** Removing a mod drops its dishes from a player's slots on
  load rather than leaving a slot that can never be cleared.
- Turning `vanillaRegeneration` on alongside another regeneration mod will stack; that is the point
  of the option, but it does undercut the food system.

## For developers

Two server-side events, informational and non-cancellable — refusing a meal is the config's job:

```java
SOLValheimEvents.FOOD_EATEN.register((player, item, config) -> { ... });
SOLValheimEvents.FOOD_EXPIRED.register((player, item) -> { ... });
```

`FOOD_EXPIRED` also fires for slots that run down across a skipped night.

## Building

```sh
./gradlew build                  # jars for every enabled platform
./gradlew :fabric:remapJar       # Fabric only
```

Jars land in `fabric/build/libs`, `forge/build/libs` and `neoforge/build/libs`. Requires JDK 17;
compiling the 1.21.1 target needs JDK 21.

The target Minecraft version is `mcVer` in `gradle.properties`; per-version dependencies — and
which loaders build against each version (`fabric, forge` up to 1.20.1, `fabric, neoforge` on
1.21.1) — live in `<version>.properties`. Version-specific code is selected by the Manifold
preprocessor (`#if PRE_CURRENT_MC_1_19_2` / `#elif POST_CURRENT_MC_1_20_1`) against the generated
`build.properties`, so a single source tree compiles for all three versions.

## Credits

- **[anthxnymc](https://github.com/txnimc)** — original [SOL: Valheim](https://github.com/txnimc/sol_valheim)
- **[kinghzrd](https://github.com/kinghzrd)** — [experimental-tweaks fork](https://github.com/kinghzrd/sol_valheim), the one this continues from
- **[Hoxiee](https://github.com/Hoxiee)** — current maintenance, renamed to **SOL: Valheim Continued**

Licensed under **GNU LGPL 3.0** — see [LICENSE](LICENSE).

Ships with English and Russian translations.
