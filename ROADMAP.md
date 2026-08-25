# Roadmap

Where the food balance is heading. Concrete and checkable; no dates.

## Next
- **Fair pricing for dishes that are never crafted.** Sliced dishes (pie slices,
  kelp roll slices), food obtained from blocks, and other non-crafting chains are
  currently priced as "gathered" — the cheapest tier. They will be priced by their
  real production chain instead.

## Planned
- **Amortized multi-output recipes.** A recipe that yields several pieces at once
  (a roll cut into 3 slices, a pumpkin into 4) currently prices every piece as if
  it were crafted separately — making slicing strictly better than eating the
  whole. Per-piece effort will account for the yield.
- **Transparent audit.** `/solvalheim balance` and the dump will list which dishes
  could not be priced and why, so a pack maker knows exactly what to fix with a
  datapack.

## Under consideration
- **Recognizing "prepared" dishes.** When a mod ships an advancement for *eating*
  a dish, treat that dish as at least mid-tier even if its production chain is
  invisible to the recipe graph (feast blocks that hand out servings in code).
- **Datapack presets for common mods.** Ready-made price pins for dishes whose
  chain cannot be seen in principle.

## Known limitations
- Effort measures recipe-chain complexity, not farming cost: dishes built from
  fully autofarmable ingredients (kelp, carrots, rice) can punch above their
  real cost. A datapack pin is the answer where it bothers you.
- Food dropped by mobs stays "gathered" — that is its honest price.
