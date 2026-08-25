#!/usr/bin/env python3
"""Offline recalculation of the SOL: Valheim food table.

Reads the dump written by /solvalheim dump (config/sol_valheim/food_dump.md), re-runs the
balance model over every dish and writes food_recalc.md next to it: old vs new values,
sorted by how much they would move. The point is to tune the curve without relaunching the
game per idea - and to find artifacts like a dish that measures as hard to make while its
ingredients grow on a bamboo kelp farm.

With no overrides the tool self-checks: its output must match the dump line for line. The
constants come from the dump header, so the check fails loudly if this replication ever
drifts from the Java model.

Usage:
  python3 tools/food_recalc.py                                  # self-check only
  python3 tools/food_recalc.py --set hill_k=1.4 --set pivot=12  # experiment
  python3 tools/food_recalc.py --ns farmersdelight --top 50     # focus
"""

import argparse
import math
import re
import sys
from pathlib import Path

DEFAULT_DUMP = Path("config/sol_valheim/food_dump.md")

HEADER_RE = re.compile(r"^-\s*(\w+)\s*=\s*(-?\d+(?:\.\d+)?)\s*$")
ROW_KEYS = ("id", "ns", "hearts", "min", "regen", "nut", "sat", "regenMod",
            "var", "dep", "mult", "src", "model")
TOLERANCE = {"hearts": 0.001, "min": 0.006, "regen": 0.0011, "mult": 0.0011}


def parse_dump(path: Path):
    constants, rows, in_table = {}, [], False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("| id |"):
            in_table = True
            continue
        if in_table:
            if not line.startswith("|"):
                break
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if len(cells) != len(ROW_KEYS) or cells[0] in ("id", "---"):
                continue
            row: dict = dict(zip(ROW_KEYS, cells))
            for key in ("hearts", "min", "regen", "sat", "regenMod", "mult"):
                row[key] = float(row[key])
            for key in ("nut", "var", "dep"):
                row[key] = int(row[key])
            rows.append(row)
            continue
        header = HEADER_RE.match(line.strip())
        if header:
            constants[header.group(1)] = float(header.group(2))
    return constants, rows


def effort_multiplier(variety: int, depth: int, c: dict) -> float:
    weight = c["effort_weight"]
    if weight <= 0:
        return 1.0
    raw = variety + c["depth_weight"] * depth
    value = (raw / c["effort_ref"]) ** (c["effort_gamma"] * weight)
    return min(c["effort_max"], max(c["effort_min"], value))


def java_round(value: float) -> int:
    # Math.round: floor(x + 0.5), not Python's banker's rounding
    return math.floor(value + 0.5)


def compute(row: dict, c: dict):
    sat = max(row["sat"], 1e-3)
    regen_mod = max(row["regenMod"], 1e-3)

    aff_h = (c["saturation_ref"] / sat) ** c["shape_gamma"]
    aff_d = (sat / c["saturation_ref"]) ** c["shape_gamma"]
    aff_r = c["regen_bias"] * regen_mod ** c["regen_gamma"]

    mult = effort_multiplier(row["var"], row["dep"], c)
    raw = max(0.0, row["nut"] * sat * mult)
    if raw > 0:
        a = raw ** c["hill_k"]
        budget = a / (a + c["pivot"] ** c["hill_k"])
    else:
        budget = 0.0

    norm = (aff_h ** c["norm_p"] + aff_d ** c["norm_p"] + aff_r ** c["norm_p"]) ** (1.0 / c["norm_p"])
    if norm <= 0:
        shape_h = shape_d = shape_r = 0.0
    else:
        shape_h, shape_d, shape_r = budget * aff_h / norm, budget * aff_d / norm, budget * aff_r / norm

    hearts = c["heart_floor"] + (c["heart_ceil"] - c["heart_floor"]) * shape_h
    hearts *= max(0.0, c["nutrition_health_modifier"])
    hearts = min(hearts, c["max_food_health"] * 2.0 / max(1.0, c["max_slots"]))

    minutes = c["duration_floor"] + (c["duration_ceil"] - c["duration_floor"]) * shape_d
    seconds = max(minutes * 60.0, c["min_food_seconds"])
    # Java: Math.max(1, Math.round(seconds * 20.0))
    ticks = max(1, java_round(seconds * 20.0))

    regen = c["regen_floor"] + (c["regen_ceil"] - c["regen_floor"]) * shape_r
    regen = min(max(regen, 0.0), c["regen_ceil"])

    return java_round(hearts) / 2.0, ticks / 1200.0, regen, mult


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dump", type=Path, default=DEFAULT_DUMP, help="path to the food_dump.md")
    parser.add_argument("--out", type=Path, default=None, help="output path (default: food_recalc.md next to the dump)")
    parser.add_argument("--set", action="append", default=[], metavar="KEY=VALUE",
                        help="override a model constant, e.g. --set hill_k=1.4")
    parser.add_argument("--ns", action="append", default=[], help="only these namespaces (repeatable)")
    parser.add_argument("--top", type=int, default=0, help="only show the N rows that move the most")
    args = parser.parse_args()

    if not args.dump.exists():
        print(f"dump not found: {args.dump}\nrun /solvalheim dump in game first", file=sys.stderr)
        return 1

    constants, rows = parse_dump(args.dump)
    required = ("pivot", "effort_weight", "max_food_health", "max_slots", "min_food_seconds",
                "nutrition_health_modifier", "saturation_ref", "shape_gamma", "regen_bias", "regen_gamma",
                "hill_k", "norm_p", "heart_floor", "heart_ceil", "duration_floor", "duration_ceil",
                "regen_floor", "regen_ceil", "effort_ref", "depth_weight", "effort_gamma",
                "effort_min", "effort_max")
    missing = [key for key in required if key not in constants]
    if missing:
        print(f"dump header is missing constants: {', '.join(missing)}", file=sys.stderr)
        return 1

    for override in args.set:
        key, _, value = override.partition("=")
        if key not in constants or not value:
            known = ", ".join(sorted(constants))
            print(f"unknown constant {override!r}; known: {known}", file=sys.stderr)
            return 1
        constants[key] = float(value)

    if args.ns:
        rows = [row for row in rows if row["ns"] in args.ns]

    mismatches = []
    results = []
    # with overridden constants the output is SUPPOSED to disagree with the dump - the self-check
    # only means something when reproducing the game's own numbers
    checking = not args.set
    for row in rows:
        hearts, minutes, regen, mult = compute(row, constants)
        if checking and row["model"] == "ok":
            for key, new in (("hearts", hearts), ("min", minutes), ("regen", regen), ("mult", mult)):
                if abs(new - row[key]) > TOLERANCE[key]:
                    mismatches.append(f"  {row['id']}: {key} dumped {row[key]} vs computed {new:.4f}")
        results.append((row, hearts, minutes, regen, mult))

    changed = [(row, h, m, r) for row, h, m, r, _ in results
               if row["model"] != "pinned" and abs(h - row["hearts"]) > 1e-9]
    changed.sort(key=lambda item: abs(item[1] - item[0]["hearts"]), reverse=True)
    if args.top:
        changed = changed[: args.top]

    def stats(scope):
        deltas = [abs(h - row["hearts"]) for row, h, _, _, _ in results
                  if row["model"] == "ok" and (scope is None or row["ns"] == scope)]
        if not deltas:
            return None
        return sum(deltas) / len(deltas), max(deltas)

    out = args.out or args.dump.parent / "food_recalc.md"
    lines = ["# SOL: Valheim - recalculation\n"]
    lines.append("Constants from the dump" + (", overridden: " + ", ".join(
        f"{o.partition('=')[0]}={o.partition('=')[2]}" for o in args.set) if args.set else " (self-check)") + ".\n")

    if mismatches:
        lines.append(f"**Self-check: {len(mismatches)} mismatches** - this replication disagrees with the "
                     f"Java model, do not trust the deltas below until that is understood:\n")
        lines.extend(mismatches)
        lines.append("")
    else:
        lines.append(f"Self-check passed: {sum(1 for r in results if r[0]['model'] == 'ok')} modelled rows "
                     "reproduce the dump exactly.\n")

    for scope, label in (("minecraft", "vanilla"), (None, "modded")):
        stat = stats(scope)
        if stat:
            lines.append(f"- {label}: mean |dhearts| {stat[0]:.3f}, max {stat[1]:.3f}")
    lines.append("")

    if changed:
        lines.append("| id | ns | hearts | -> | min | -> | regen | -> |")
        lines.append("|---|---|---:|---:|---:|---:|---:|---:|")
        for row, hearts, minutes, regen in changed:
            lines.append(f"| {row['id']} | {row['ns']} | {row['hearts']:.2f} | {hearts:.2f} "
                         f"| {row['min']:.2f} | {minutes:.2f} | {row['regen']:.3f} | {regen:.3f} |")
    else:
        lines.append("Nothing moves with these constants.")

    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {out}")
    if mismatches:
        print(f"WARNING: {len(mismatches)} self-check mismatches", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
