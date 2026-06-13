# Create Ore Excavation Deposits

A NeoForge add-on for **Create Ore Excavation** (COE) that replaces the flat one-chunk-per-vein model with proper **multi-chunk ore deposits** — connected blobs of a single ore type, with distance-from-spawn richness scaling, biome gating, a Xaero world-map overlay, and per-player reveal policies.

- **Minecraft**: 1.21.1
- **NeoForge**: 21.1.211+ (built on 21.1.230)
- **Required**: Create Ore Excavation
- **Soft compat**: Xaero's World Map (overlay), Xaero's Minimap (waypoints), YACL (config screen + in-game deposit editor)

---

## What it does

Vanilla COE assigns vein recipes to random chunks independently — ore types and amounts have no relationship to location. This mod layers a richer placement model on top:

1. **Multi-chunk deposits** — chunks are grouped into irregular Perlin-shaped blobs (2–40 chunks), all sharing one ore type.
2. **Distance gradient (log curve)** — the further from spawn, the larger and richer the deposits, and the more exotic the eligible ore types.
3. **Per-chunk gradient** — the core chunk yields the deposit's peak amount; outer chunks fade down to `edge_amount_mul × core` (default 30 %).
4. **Per-type budget** — each ore declares its own `per_chunk_units` range, independent of COE's global `finiteAmountBase`.
5. **Biome tag filtering** — ores can be restricted to biome tags (e.g. iron only in `c:is_mountain` / `minecraft:is_taiga`).
6. **Per-type reveal policy** — `always`, `on_discovery` (walk near it), `on_proximity` (visible while close), or `on_prospect` (Vein Finder must reveal it). Per-player tracking.
7. **Per-type placement mode** — `managed` (our blob algorithm) or `coe` (Create Ore Excavation's own spread). Both variants are fully **owned and map-tracked** by the mod (since 0.2.0); COE veins from add-ons/datapacks with no type of their own are auto-adopted onto the map. Mix and match per ore.
8. **Xaero world-map overlay** — coloured diagonal-striped chunks with a hover tooltip showing deposit name, location, and per-chunk `remaining / initial` units. Compact in-map toggle button + keybind.
9. **Multi-dimension support** — `enabled_dimensions` config selects which dimensions the picker runs in; each dimension has its own SavedData and reproducible seed.
10. **Reproducible patterns** — per-dimension `depositSeed` override lets you copy a deposit layout from one world to another (`/coedeposits seed` to read, `/coedeposits regenerate <seed>` to apply).

11. **Overlap resolution** — when a new deposit's blob collides with existing ones, each contested chunk is resolved: same ore type merges into one deposit; different types split it to the rarer (lower-`weight`) ore, trimming the more common one.

Plus auto-clearing of depleted chunks (no Vein Finder false positives), prospect pre-scan around spawn on server start, and incremental roving scans as players explore.

## In-game config & editor (0.1.3+)

No file editing needed for the common knobs or for authoring ores:

- **Config screen** — Mods → Coedeposits → Config opens a structured screen over every `coedeposits-common.toml` / `coedeposits-client.toml` value. With **YACL** installed it uses a YACL screen; otherwise it cedes to an installed auto-screen mod (Configured/Catalogue) or falls back to NeoForge's native `ConfigurationScreen`. Fine-grained probabilities are edited as whole numbers (e.g. *Core spawn — per 100k chunks*, where 50 = 0.0005/chunk) so small values stay editable and save reliably.
- **Deposit editor** (requires YACL) — a guided editor that writes `config/coedeposits/deposits.json` through structured controls (item pickers, fluid/biome dropdowns, sliders) for the vein / drilling / fluid / placement fields — no hand-editing JSON. Run `/reload` to apply.
- **Config validation** — on load, `/reload`, and player join the mod checks every deposit type (empty recipe pool, missing or unresolvable vein recipe, no drilling or extracting recipe bound, degenerate budget, oversize blobs) and reports issues to the server log and to ops in chat, so silent misconfig surfaces instead of "nothing generates".

## Adding custom ores — one file, no datapack required (0.1.2+)

Since 0.1.2 a deposit type can describe its **vein placement**, **drilling output**, and (0.1.4+) **fluid output** inline in `config/coedeposits/deposits.json`. The mod's `BundledRecipePack` virtual datapack auto-generates the COE recipes from the inline spec at server start and on every `/reload` — admins no longer need to author a separate datapack with `data/<ns>/recipe/*.json` files for new ores.

Minimal example: add a new platinum ore that drops alongside cobblestone slag.

```json
"mymod:platinum": {
  "vein": {
    "display_name": "Platinum Vein",
    "amount_multiplier_min": 2.0,
    "amount_multiplier_max": 25.0,
    "icon": "mymod:raw_platinum"
  },
  "drilling": {
    "outputs": [
      {"item": "mymod:raw_platinum"},
      {"item": "minecraft:cobblestone", "chance": 0.25}
    ],
    "ticks": 120,
    "stress": 320
  },
  "dimensions": "minecraft:overworld",
  "distance": {"min": 5000, "max": 2147483647},
  "size_chunks": {"min": 2, "max": 8},
  "per_chunk_units": {"min": 3000, "max": 25000},
  "weight": 12,
  "map_color": 14803425,
  "biome_filter": ["c:is_mountain"]
}
```

Save, run `/reload` — the new ore spawns with a synthetic `mymod:platinum_vein` + `mymod:platinum_drilling` recipe pair. **No datapack file needed**, no `pack.mcmeta`, no `data/` directory.

### How it works

1. `BundledRecipePackProvider` registers a virtual server-data pack named `coedeposits-config` via NeoForge's `AddPackFindersEvent`.
2. On every recipe-manager reload (server start, `/reload`), the pack reads `deposits.json` and emits in-memory JSON for each entry that has an inline `vein:`, `drilling:`, and/or `fluid:` block.
3. COE's drilling machine, its extractor, and our picker see these synthesised recipes exactly like bundled or datapack ones — they're indistinguishable at runtime.
4. The virtual pack loads at the TOP position, so any conflicting recipe id in a user datapack or bundled mod jar **wins** over our synthesis.

### Inline `vein` schema

| Field | Type | Default | Purpose |
|---|---|---|---|
| `display_name` | string | `"Deposit"` | Vein-finder + JEI label |
| `amount_multiplier_min` | float | `2.0` | COE per-chunk randomMul lower bound |
| `amount_multiplier_max` | float | `25.0` | COE per-chunk randomMul upper bound |
| `icon` | item id | first `drilling.outputs[0].item` | Vein-finder hover icon |
| `icon_count` | int | `1` | Icon stack size |
| `placement` | `{salt, separation, spacing}` | derived from entry hash | COE spread placement (ignored by MANAGED types) |
| `finite` | `"always"` / `"never"` | `"always"` | Vein depletes after `per_chunk_units` are drilled |

### Inline `drilling` schema

| Field | Type | Default | Purpose |
|---|---|---|---|
| `outputs` | list of `{item, count?, chance?}` | required | Items the drill can yield per cycle |
| `outputs[].item` | item id | required | Item id |
| `outputs[].count` | int | `1` | Stack size per drop |
| `outputs[].chance` | float | `1.0` | Independent per-cycle drop probability |
| `ticks` | int | `100` | Drill cycle duration |
| `stress` | int | `256` | Create stress units required |
| `drill_tag` | item-tag id | `createoreexcavation:drills` | Drill ingredient |
| `fluid_input` | `{fluid \| tag, amount}` | _none_ | **0.2+** — optional INPUT fluid the drill consumes per cycle (COE `drillingFluid`). One of `fluid` (id) or `tag` (fluid tag) + `amount` in mB (default 1000). |

### Inline `fluid` schema (0.1.4+)

A deposit can yield a **fluid** instead of (or alongside) items — harvested with COE's **Extractor** machine rather than the Drill. Add a `fluid:` block; the mod synthesises a `createoreexcavation:extracting` recipe bound to the same vein. The vein placement, distance, biome filter and `per_chunk_units` budget all work exactly as for a solid ore — COE's Extractor and Drill share the same vein-consumption logic — so a fluid deposit is configured the same way, only the output differs.

| Field | Type | Default | Purpose |
|---|---|---|---|
| `fluid` | fluid id | required | Source fluid produced, e.g. `minecraft:water`, `minecraft:lava` (in the editor, pick the fluid's **bucket** in the same item picker as Icon) |
| `amount` | int | `500` | Millibuckets yielded per extraction cycle |
| `ticks` | int | `20` | Extraction cycle duration |
| `stress` | int | `256` | Create stress units the extractor consumes |
| `drill_tag` | item-tag id | `createoreexcavation:drills` | Drill-head ingredient the extractor accepts |
| `fluid_input` | `{fluid \| tag, amount}` | _none_ | **0.2+** — optional INPUT fluid the extractor consumes per cycle (COE `drillingFluid`), separate from the fluid it outputs. One of `fluid` (id) or `tag` + `amount` in mB (default 1000). |

When a `fluid:` block is present and the vein has no explicit `icon`, the vein-finder icon defaults to the fluid's bucket (mirroring COE's own water extractor). A deposit may carry **both** `drilling:` and `fluid:` — the vein can then be drilled for items *or* pumped for fluid.

Example — an underground water spring, drilled in swamp biomes:

```json
"mymod:spring_water": {
  "vein": {
    "display_name": "Underground Spring",
    "amount_multiplier_min": 2.0,
    "amount_multiplier_max": 25.0
  },
  "fluid": {
    "fluid": "minecraft:water",
    "amount": 500,
    "ticks": 20,
    "stress": 256
  },
  "dimensions": "minecraft:overworld",
  "size_chunks": {"min": 4, "max": 12},
  "per_chunk_units": {"min": 20000, "max": 120000},
  "weight": 60,
  "map_color": 4159204,
  "biome_filter": ["c:is_swamp"]
}
```

(For a modded fluid, pick its bucket in the editor's item picker rather than guessing the id. `minecraft:lava` is the other vanilla source fluid.)

> **Bundled example.** The mod ships a ready `coedeposits:water` deposit — a finite "Underground Spring" of `minecraft:water` extracted with COE's Extractor — so the fluid path works the moment you install the mod. It generates in overworld swamp/plains biomes at `weight: 50`; turn it off from the in-game editor (or a `{"enabled": false}` overlay) if you don't want it. Note it ships as plain **datapack** files (`deposit_type/water.json` + `recipe/water_{vein,extracting}.json`), *not* an inline `fluid:` block — a datapack-supplied type must reference real recipes, since inline synthesis only runs for the config overlay.

### Recipe id derivation

- **Vein recipe id**: from `vein_recipe` (legacy) or first entry of `vein_recipes`, or auto-derived as `<typeNs>:<typePath>_vein`.
- **Drilling recipe id**: vein id with trailing `_vein` swapped for `_drilling`, or `_drilling` appended when no `_vein` suffix.
- **Extracting (fluid) recipe id**: vein id with trailing `_vein` swapped for `_extracting`, or `_extracting` appended when no `_vein` suffix.

Admins who prefer to author recipes externally (e.g. via KubeJS, a hand-crafted datapack, or another mod's bundled recipes) can still use `vein_recipe: "ns:my_external_vein"` and omit the inline blocks. The mod doesn't care which source provided the recipe.

## Multi-ore deposits, fillers, and self-replenishment (0.1.2+)

A single deposit type can now contain multiple ore recipes and "tailings" chunks, and can optionally regenerate its drained units over time. All three features are opt-in — bundled defaults still use the simple single-recipe schema and don't regenerate.

**Multi-ore (`vein_recipes`)**: each chunk of a placed deposit blob deterministically rolls one of the type's weighted recipes (or a filler — see below). The roll is seeded on `(depositSeed, chunkX, chunkZ)` so the same chunk always picks the same recipe across restarts. Backward-compatible: the legacy singular `vein_recipe: "X"` field still parses and is folded into a single-entry list with weight 1.

**Fillers (`fillers`)**: filler entries occupy slices of the same weighted pool as recipes. Chunks that roll a filler get no OreData — drills find nothing, but the chunk is still tracked in `DepositSavedData` and rendered on the world map as warm-grey "tailings" so the deposit footprint looks patchy and realistic rather than uniformly rich.

**Self-replenishment (`replenish_rate_per_hour`)**: positive values turn on a per-tick refill that subtracts from each loaded ore chunk's `extractedAmount` at the configured rate, distributed evenly across the deposit's loaded ore chunks. Capped at the chunk's initial yield — a deposit can recover from drilling, but never grow beyond what it originally held. Sub-unit-per-tick rates are accumulated via a per-deposit fractional debt map so even very slow rates (e.g. 100 units/hour over 30 chunks ≈ 0.001 units/sec per chunk) eventually move the needle.

Example schema:

```json
"coedeposits:rich_complex": {
  "vein_recipes": [
    {"recipe": "coedeposits:iron_vein",   "weight": 50},
    {"recipe": "coedeposits:copper_vein", "weight": 30},
    {"recipe": "coedeposits:gold_vein",   "weight": 20}
  ],
  "fillers": [
    {"weight": 40}
  ],
  "replenish_rate_per_hour": 1000,
  "per_chunk_units": {"min": 20000, "max": 200000},
  "size_chunks": {"min": 4, "max": 20},
  "weight": 30,
  "distance": {"min": 2000, "max": 99999},
  "biome_filter": ["c:is_mountain"]
}
```

Per-deposit replenish overrides via `/coedeposits replenish <rate>` (or `replenish all <rate>` for the whole dimension) — `rate=0` clears the override and reverts to the type default.

## Compatibility

| Mod | Status | Notes |
|---|---|---|
| **Create Ore Excavation** (`createoreexcavation`) | **Required** | We subclass COE's `RandomSpreadGenerator` and swap the static `OreVeinGenerator.picker` via reflection (the field is opened by an AccessTransformer). |
| **Xaero's World Map** (`xaeroworldmap`) | Soft | Mixin into `xaero.map.gui.GuiMap.render` (TAIL) draws the overlay + adds the toggle widget. Mixin is gated `client`-only via `coedeposits.mixins.json` so dedicated servers skip it. |
| **Xaero's Minimap** (`xaerominimap`) | Soft | `XaeroBridge` does a best-effort reflective waypoint add on deposit discovery; if Xaero's API drifts it falls back silently to the click-to-suggest `/tp` chat message. |
| **YACL** (`yet_another_config_lib_v3`) | Soft | When present, powers the config screen + in-game deposit editor; absent, the mod cedes to an auto-screen mod or NeoForge's native config screen. Client-only. |
| **KubeJS** (`kubejs`) | Soft | Optional `CoeDeposits` script binding to define deposit types from `kubejs/startup_scripts` (see [KubeJS](#kubejs)). Absent, the mod is unaffected. |
| **COE add-ons** — Create Ore Excavation Plus, CoE × Mekanism, OreCompatCreate, … | Auto | Any COE vein recipe they ship is **auto-adopted** onto the world map (toggle: `auto_adopt_coe_veins`). Their fluid-gated drilling recipes are supported via `fluid_input`. |

## Commands

All `/coedeposits` subcommands require permission level 2 (standard OP — granted by `/op <player>`).

| Command | Description |
|---|---|
| `/coedeposits tier` | Show distance-from-spawn and tier fraction at the current position. |
| `/coedeposits list` | Top-10 nearest known deposits in the current dimension. |
| `/coedeposits types` | Roster of deposit **types** in the current dimension — declared (managed/coe) plus auto-**adopted** foreign COE veins — with placed counts. The audit view for what `auto_adopt_coe_veins` pulled onto the map. |
| `/coedeposits here` | Info about the deposit owning the player's current chunk. |
| `/coedeposits scan` | Re-run the prospect-scan around the player at the configured `prospect_radius`. Useful after editing `deposits.json` + `/reload`. |
| `/coedeposits regenerate [seed]` | Wipe the current dimension's deposits and run a fresh prospect-scan. Pass an optional seed to lock placement to a specific RNG and reproduce the pattern elsewhere. |
| `/coedeposits seed` | Print the effective deposit seed for the current dimension and whether it's an override or the inherited world seed. |
| `/coedeposits refill` | Restore the deposit at the current chunk (resets extracted amount on every loaded chunk). |
| `/coedeposits refill all` | Same but for every deposit in the current dimension. |
| `/coedeposits delete here` | Remove the entire deposit owning the current chunk. |
| `/coedeposits delete chunk` | Remove just the current chunk from its deposit (shrinks the blob; if it was the last chunk the deposit is removed). |
| `/coedeposits place [<type> [<pos> [<amount> [<chunks>]]]]` | Admin-place a deposit. `amount<0` → infinite vein (requires `vein_recipe_infinite`); `amount>0` → exact unit budget; `chunks` overrides natural blob size (default 5). |
| `/coedeposits replenish <rate>` | Set per-deposit replenishment override on the deposit at the player's chunk. `rate` is units/hour; `0` clears the override (deposit reverts to its type's default). |
| `/coedeposits replenish all <rate>` | Same but applied to every deposit in the current dimension. |

## Deposit types — datapack + config overlay

Deposit types load from two layers. **Datapack layer:** every `data/<ns>/deposit_type/<id>.json` in the active server-data packs (namespace-root folder, like vanilla `recipe/`) — the mod ships the 14 standard ores in its own jar, and other datapacks / KubeJS / CraftTweaker can add new types or override the bundled ones by pack priority. **Config overlay (optional):** `config/coedeposits/deposits.json`, applied last so a hand-edit always wins over a datapack type of the same id. The file is no longer auto-created — a fresh install runs on the datapack defaults; copy the jar's `coedeposits-default-deposits.json` to that path for an editable starting point (it also carries the inline-recipe example entries).

> **Inline `vein:` / `drilling:` synthesis works only in the config overlay.** A datapack-supplied type must reference an existing recipe via `vein_recipe` / `vein_recipes`, because the on-the-fly recipe synthesiser (`BundledRecipePack`) reads the config file from disk and can't see other datapacks during a reload.

**Format:** a top-level JSON object whose keys are deposit ids (`namespace:path`) and whose values are deposit-type bodies. Keys starting with `_` are skipped (use them for inline comments).

```json
{
  "_comment": "Edit and run /reload to refresh in-game. Delete to restore defaults.",

  "coedeposits:iron": {
    "vein_recipe": "coedeposits:iron_vein",
    "vein_recipe_infinite": "coedeposits:iron_vein_infinite",
    "items": [{"item": "minecraft:raw_iron", "weight": 1, "min": 1, "max": 1}],
    "placement": "managed",
    "dimensions": "minecraft:overworld",
    "distance": {"min": 0, "max": 99999},
    "size_chunks": {"min": 4, "max": 30},
    "per_chunk_units": {"min": 20000, "max": 200000},
    "weight": 150,
    "map_color": 12895428,
    "biome_filter": ["c:is_mountain", "minecraft:is_taiga", "minecraft:is_hill"],
    "reveal": "always"
  }
}
```

After editing, run `/reload` on the server — `DepositTypeLoader` re-reads the file. Per-entry parse errors are logged but don't abort the rest of the load.

| Field | Type | Purpose |
|---|---|---|
| `vein_recipe` | ResourceLocation | **Legacy** — single COE VeinRecipe id. Auto-converted to a one-entry `vein_recipes` list with weight 1. Still accepted for back-compat. |
| `vein_recipes` | `[{recipe, weight}]` or single object | **0.1.2+** — weighted pool of recipes. Each chunk of a deposit blob rolls one entry deterministically. Use this instead of `vein_recipe` for multi-ore deposits. |
| `fillers` | `[{weight}]` | **0.1.2+** — weighted "tailings" entries in the same pool. Chunks that roll a filler get no OreData (drill yields nothing) and render as warm-grey on the map. Default: empty list (no fillers). |
| `replenish_rate_per_hour` | optional double | **0.1.2+** — per-deposit ore restoration rate in units/hour. Default 0 (disabled). Distributed evenly across the deposit's loaded ore chunks; capped at each chunk's initial yield. |
| `vein_recipe_infinite` | optional RL | Alternate recipe with `finite=never` (used by `/place ... -1`). |
| `items` | List<ItemEntry> | Reserved for future drilling-output synthesis. Currently unused at runtime. |
| `placement` | optional enum | Where the deposit is placed — both variants are fully owned + map-tracked by us (0.2+): `managed` (default) — our multi-chunk blob generator; `coe` — Create Ore Excavation's own spread placement (prospect-scanned + findable like managed). |
| `dimensions` | `string \| [string]` | Optional allow-list of dimension ids this type may spawn in. Empty = any enabled dimension. |
| `distance` | optional `{min, max}` | Block distance window from spawn (managed only; ignored for `coe`). |
| `size_chunks` | optional `{min, max}` | Blob size range in chunks (managed only). Tier lerps within. |
| `per_chunk_units` | optional `{min, max?}` | Per-chunk unit budget (managed only). When `max` is absent the budget grows open-endedly: `min × (1 + tier × unbounded_growth)`. |
| `weight` | optional int | Weight in the managed weighted pick. `0` (default) means the type is inert in the managed roll. Ignored for `coe`. |
| `map_color` | optional int | Packed RGB for the world-map overlay. Without one the renderer hashes `typeId`. |
| `biome_filter` | `string \| [string]` | Any-of biome tag list (managed only). Empty = any biome. |
| `reveal` | optional enum | Per-type override of the global `reveal_mode`. One of `always`, `on_discovery`, `on_proximity`, `on_prospect`. |

### Common patterns

**1) Keep vanilla COE infinite-vein behaviour, just put markers on the map.**

```json
"mymod:legacy_coal": {
  "vein_recipe": "createoreexcavation:coal_vein",
  "placement": "coe",
  "map_color": 2236962,
  "reveal": "on_prospect"
}
```

The blob generator doesn't touch this type; COE places it as usual. When the picker delegates to COE and gets this recipe back, it records a single-chunk deposit in SavedData so the chunk shows up on the map per the `reveal` rule.

**2) Vanilla generation AND ours side-by-side.** Leave managed entries as-is and add `placement: "coe"` entries beside them. The picker tries the managed blob first; if no managed deposit lands, it delegates to COE — tracked recipes get persisted, untracked spawn as plain COE veins.

**3) Deposit must be found before it appears on the map.** Set the global `reveal_mode = on_discovery` (walk within `discovery_radius_blocks`, default 24) or `on_prospect` (use the Vein Finder on it). Or override per type with the `reveal` field. Reveal state is per-player and persisted in SavedData.

**4) Deposit visible only while the player is nearby.** Use `reveal: "on_proximity"` — the marker shows/hides as the player crosses `proximity_reveal_blocks` (default 256). Pure client-side filter, no persistent state.

**5) Restrict an ore to specific dimensions.**

```json
"coedeposits:nether_quartz": {
  "vein_recipe": "coedeposits:nether_quartz_vein",
  "dimensions": "minecraft:the_nether",
  "per_chunk_units": {"min": 25000, "max": 220000},
  "weight": 220,
  "map_color": 15790824,
  "biome_filter": ["minecraft:is_nether"]
}
```

The type is only eligible when the chunk's dimension is in this list **and** in the global `enabled_dimensions` allow-list. Single-string and array forms are both accepted.

### Companion COE recipes — `data/<ns>/recipe/<name>_vein.json`

Standard COE format; we just reference the id from `deposit_type.vein_recipe`:

```json
{
  "type": "createoreexcavation:vein",
  "name": "{\"text\":\"Iron Deposit\"}",
  "priority": 0,
  "finite": "always",
  "amountMultiplierMin": 2.0,
  "amountMultiplierMax": 25.0,
  "placement": {"salt": 1762100002, "separation": 8, "spacing": 256},
  "icon": {"count": 1, "id": "minecraft:raw_iron"}
}
```

`amountMultiplierMin/Max` bound the per-chunk `randomMul` COE writes into `OreData`; we invert that to convert `per_chunk_units` into the right value.

And the drilling recipe — `data/<ns>/recipe/<name>_drilling.json`:

```json
{
  "type": "createoreexcavation:drilling",
  "drill": {"tag": "createoreexcavation:drills"},
  "output": [{"id": "minecraft:raw_iron"}],
  "priority": 0,
  "stress": 256,
  "ticks": 100,
  "veinId": "coedeposits:iron_vein"
}
```

## KubeJS

With **KubeJS** installed, define deposit types from a script instead of a JSON file — the `CoeDeposits` binding mirrors how Create Ore Excavation exposes its recipes to KubeJS. Put this in `kubejs/startup_scripts/` (runs once at launch):

```js
CoeDeposits.add('mypack:ruby', {
  vein_recipes: [{ recipe: 'mypack:ruby_vein', weight: 1 }],
  placement: 'managed',
  dimensions: 'minecraft:overworld',
  distance: { min: 2000, max: 99999 },
  size_chunks: { min: 4, max: 12 },
  per_chunk_units: { min: 20000, max: 120000 },
  weight: 80,
  map_color: 14689625,
  biome_filter: ['c:is_mountain']
})
```

The object is the exact `deposit_type` schema documented above. Scripted types merge **between** the datapack defaults and the `deposits.json` overlay (a hand-edited overlay still wins). Like datapack types, a scripted type must reference a real `vein_recipe` — inline `vein:` / `drilling:` / `fluid:` synthesis only runs for the on-disk config overlay (build the recipe with COE's own KubeJS API if you need one, then reference it). `CoeDeposits.remove('mypack:ruby')` drops one. KubeJS is an optional dependency — without it the binding simply isn't present and the mod is unaffected.

## Mod config

### Server-side — `coedeposits-common.toml`

| Key | Default | Purpose |
|---|---:|---|
| `base_radius` | 1000 | Block radius where the log curve transitions (tier ≈ 0.5). |
| `max_radius` | 25000 | Block distance at which tier saturates to ~1.0. |
| `core_spawn_probability` | 0.0005 | Per-chunk roll for a deposit-core candidate. 1 roll per ~2000 chunks; after biome+distance filters + 4-30-chunk blob expansion → ~1 placed deposit per 5-10k chunks (~1 chunk in 300-500 belongs to a deposit). |
| `edge_amount_mul` | 0.3 | Per-chunk gradient floor — edges get this fraction of the core amount. |
| `prospect_radius` | 2000 | Block radius pre-scanned on server start (0 = disabled, max 16000). |
| `unbounded_growth` | 50.0 | Growth coefficient when `per_chunk_units.max` is absent. |
| `reveal_mode` | `ALWAYS` | Global default reveal policy: `ALWAYS` / `ON_DISCOVERY` / `ON_PROXIMITY` / `ON_PROSPECT`. Per-type override via `reveal` in `deposits.json`. |
| `proximity_reveal_blocks` | 256 | Visibility radius for `ON_PROXIMITY` (client-side filter). |
| `discovery_radius_blocks` | 24 | Trigger radius for `ON_DISCOVERY` — the player must come this close to any chunk of the deposit. |
| `enabled_dimensions` | `[overworld, the_nether, the_end]` | Dimensions where the mod is active. Others fall through to vanilla COE behaviour. |
| `auto_adopt_coe_veins` | `true` | Adopt COE vein recipes that have no coedeposits `deposit_type` (from add-ons / datapacks) onto the world map: we own their OreData, track and render them like declared deposits. Off = they stay pure vanilla COE (generate but invisible to our map/finder). |
| `disabled_veins` | `[]` | COE vein recipe ids to **disable** explicitly — they won't generate or be adopted. Managed by the in-game editor (Deposits → vein → Disable). (Managed coedeposits ores are turned off by disabling their `deposit_type` instead.) |
| `coe_veins_disabled_by_default` | `true` | Disable the **base** Create Ore Excavation mod's bundled veins (`createoreexcavation:*`) by default — this mod's managed deposits replace them, so both generating would duplicate every ore. Add-on/datapack veins (other namespaces) are unaffected and still auto-adopt. A promoted (declared) type referencing a base vein overrides the disable. |
| `enabled_veins` | `[]` | Per-vein exceptions to `coe_veins_disabled_by_default` — base-COE vein ids listed here are active again. Managed by the editor's **Enable** button on a default-disabled vein. |

### Client-side — `coedeposits-client.toml`

| Key | Default | Purpose |
|---|---:|---|
| `map_button_enabled` | `true` | Show the compact toggle widget on Xaero's world map. Disable if it overlaps another mod's UI. The keybind still works. |
| `map_button_anchor` | `"LEFT"` | Edge to anchor the widget to: `LEFT` or `RIGHT`. |
| `map_button_x_offset` | 5 | Pixels from the anchored edge to the widget. |
| `map_button_y_offset` | 50 | Pixels from the top edge. Default 50 sits the widget below Xaero's corner button cluster — every Xaero corner has its own buttons (settings, layers, zoom, waypoints), so the mid-edge area is the only reliably-clear strip. |

The `key.coedeposits.toggle_overlay` keybind (Options → Controls → Coedeposits) is the always-available fallback when the widget is disabled or covered.

## Multi-dimension

By default the mod runs in all three vanilla dimensions (`enabled_dimensions = [overworld, the_nether, the_end]`). Each enabled dimension keeps its own `DepositSavedData` (in `<world>/<dim>/data/coedeposits_deposits.dat`) with independent deposit set, reveal history, and deposit seed override.

**Per-dimension picker behaviour:**
- The picker runs managed/COE placement, prospect-scan, and depletion sweep.
- `PlayerJoinSyncListener` sends one filtered sync per enabled dimension on player login.
- The world-map overlay filters snapshots by `mc.level.dimension()` so overworld deposits don't draw on the nether map at the same `(x, z)`.

**Dimensions outside the list** fall through to vanilla COE: the picker early-returns `null`, and `locate()` delegates to COE's default so the Vein Finder still works for vanilla veins.

**Combine the global allow-list with per-type `dimensions`** to target ores. The bundled defaults ship:

| Dimension | Ores |
|---|---|
| `minecraft:overworld` | coal, iron, copper, zinc, redstone, lapis, gold, emerald, diamond, quartz, glowstone |
| `minecraft:the_nether` | nether_quartz, nether_gold, ancient_debris |
| `minecraft:the_end` | _none — add your own_ |

### Reproducing a deposit pattern across worlds

The placement RNG is fully deterministic on `(depositSeed, chunkX, chunkZ)` + biome + distance-from-spawn. To copy a pattern from one world to another:

```
# In world A:
/coedeposits seed
  → deposit seed: 4823651729 (world seed) | dim: minecraft:overworld

# In world B, with the same deposits.json:
/coedeposits regenerate 4823651729
  → regenerated: wiped 0 deposits, placed 47 new in 2000-block radius
  → deposit seed is now 4823651729
```

This works if both worlds also share spawn position and biomes at the relevant chunks (the placement also depends on those). The seed override is persisted per-dimension in SavedData and survives restarts.

## Repository layout

The repository is organised as one **subproject per loader** so multiple Minecraft versions and loaders coexist without cross-polluting classpaths. Branches map to Minecraft versions; subfolders within a branch map to loaders Create Ore Excavation supports on that Minecraft version.

```
create-ore-excavation-deposits/
├── README.md / CHANGELOG.md / LICENSE     ← repo-wide meta
├── .github/workflows/publish.yml          ← multi-loader CI
├── NeoForge/                              ← independent Gradle project
│   ├── src/main/java/...
│   ├── build.gradle
│   └── gradlew
└── Fabric/   or   Forge/                  ← added per-branch as COE coverage permits
    └── ...
```

Branch / loader matrix (mirrors COE's own coverage):

| Branch | NeoForge | Fabric | Forge |
|---|---|---|---|
| `main` (1.21.1) | ✅ | — | — |
| `1.20.1` | — | _planned_ | _planned_ |
| `1.19.2` | — | _planned_ | _planned_ |
| `1.19.1`, `1.19`, `1.18.2` | — | — | _planned_ |

## Building

```bash
cd NeoForge          # or Fabric/ / Forge/ — pick the loader you want
./gradlew build      # → build/libs/coedeposits-<version>.jar
```

CI (`.github/workflows/publish.yml`) auto-detects which `<Loader>/build.gradle` files exist on the branch and builds + publishes each — no per-branch workflow tweaks needed.

## License

MIT — see `LICENSE`.
