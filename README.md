# SOL: Valheim

Replaces Minecraft's hunger bar with Valheim's food system.

You do not fill a bar — you keep **three dishes** running at once. Each one grants hearts and health
regeneration for as long as it lasts, and when it runs out you lose what it was giving you. Eating
well is how you get a big health pool; eating nothing at all leaves you at three hearts and unable
to sprint.

Built with [Architectury](https://github.com/architectury/architectury) for **Fabric** and **Forge**
on **1.19.2** and **1.20.1**.

## Requirements

| | 1.20.1 |
|---|---|
| Fabric Loader | 0.15.1+ |
| Fabric API | 0.87.0+ |
| Forge | 47.1.43+ |
| Architectury API | 9.1.12+ |
| Cloth Config | 11.0.99+ |

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

**Regeneration.** Health comes back continuously, at a rate set by what you have eaten: about one
heart every 40 seconds on an empty stomach, versus one heart every four seconds with three good
dishes.
Taking damage pauses it for 10 seconds; the HUD shows that cooldown as a small dial. Vanilla's own
"eat to full, regenerate for free" behaviour is switched off so that food is the only source of
regeneration — set `vanillaRegeneration` if you would rather have both.

**Sprinting** needs at least one food slot filled. There is a 10 second grace period after
respawning so a death is not an instant soft-lock.

**Speed.** Filling your hearts to at least 10 (configurable) grants +20 % movement speed.

**Sleeping** runs food down for the whole skipped night, so you cannot sleep away a hunger problem.

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

- `nutrition` — drives hearts, duration and regeneration.
- `saturationModifier` — multiplies duration.
- `healthRegenModifier` — multiplies regeneration.
- `time` / `health` / `regen` — skip the arithmetic and set the result outright. `time` is in ticks,
  `health` in half hearts.
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

`not_consumable` is the escape hatch for modded items that borrow `UseAnim.DRINK` for something that
is not a drink.

## Commands

| | |
|---|---|
| `/solvalheim status [targets]` | List active dishes with time left, hearts and regeneration. |
| `/solvalheim clear [targets]` | Empty every slot. |
| `/solvalheim reload` | Re-read the config, re-resolve every food value, and push the result to all connected clients — no restart. |

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
| `defaultTimer` | 180 | Seconds of duration per point of nutrition. |
| `minFoodSeconds` | 300 | Floor on any dish's duration. |
| `nutritionHealthModifier` | 1.0 | Scales hearts from food. |
| `regenSpeedModifier` | 5 | Ticks between regeneration steps; lower is faster. |
| `regenDelay` | 200 | Ticks regeneration waits after taking damage. |
| `eatAgainPercentage` | 0.2 | Fraction remaining below which a dish can be replaced. |
| `eatAgainMinSeconds` | 60 | Seconds remaining below which a dish can always be replaced. |
| `speedBoost` | 0.20 | Movement bonus at high hearts; 0 disables. |
| `speedBoostMinHearts` | 10 | Hearts needed before the bonus applies. |
| `sprintRequiresFood` | true | Require a filled food slot to sprint. |
| `respawnGracePeriod` | 10 | Seconds after spawning before that applies. |
| `drinkSlotFoodEffectivenessBonus` | 0.10 | Bonus to all food while a drink is active. |
| `passTicksDuringNight` | true | Run food down across a skipped night. |
| `vanillaRegeneration` | false | Keep vanilla natural regeneration as well. |
| `syncFoodValuesToClients` | true | Send resolved values to clients so HUD and tooltips match the server. |
| `persistGeneratedFoodValues` | true | Write generated values into the config for hand editing. |
| `foodConfigs` | *generated* | Per item values — see [Food values](#food-values). |

### Client

| Option | Default | |
|---|---|---|
| `showFoodHud` | true | Draw the food HUD at all. |
| `useLargeIcons` | true | Larger icons; required for timer text. |
| `showTimerText` | true | Remaining minutes on each slot. |
| `showRegenMeter` | true | Post-damage regeneration dial. |
| `foodHudConfig` | | Anchor, offset and per-slot gaps for the food row. |
| `regenHudConfig` | | Anchor and offset for the regeneration dial. |

`foodHudConfig.slotOffsets` nudges individual slots — first entry is the rightmost slot. Use it to
work around another mod's HUD.

## Using this in a modpack

- **Food values are generated for every mod in the pack**, so nothing is invisible to the system just
  because it came from a food mod. Tune what matters with a datapack and leave the rest.
- **The server's values are authoritative.** With `syncFoodValuesToClients` on, clients render the
  server's numbers even when their own config or datapacks differ — which they will.
- **Other mods hooking `Item.use` keep working.** This mod only cancels that method when it is
  actually refusing a meal; everything else falls through to vanilla untouched.
- **Items that disappear degrade quietly.** Removing a mod drops its dishes from a player's slots on
  load rather than leaving a slot that can never be cleared.
- Turning `vanillaRegeneration` on alongside another regeneration mod will stack; that is the point
  of the option, but it does undercut the food system.

## Building

```sh
./gradlew build                  # jars for every enabled platform
./gradlew :fabric:remapJar       # Fabric only
```

Jars land in `fabric/build/libs` and `forge/build/libs`. Requires JDK 17.

The target Minecraft version is `mcVer` in `gradle.properties`; per-version dependencies live in
`<version>.properties`. Version-specific code is selected by the Manifold preprocessor
(`#if PRE_CURRENT_MC_1_19_2` / `#elif POST_CURRENT_MC_1_20_1`) against the generated
`build.properties`, so a single source tree compiles for both.

## Credits

Originally by **anthxnymc**. Licensed under **GNU LGPL 3.0** — see [LICENSE](LICENSE).

Ships with English and Russian translations.
