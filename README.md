# Create Ore Excavation Deposits

A NeoForge add-on for **Create Ore Excavation** (COE) that replaces the flat one-chunk-per-vein model with proper **multi-chunk ore deposits** — connected blobs of a single ore type, with distance-from-spawn richness scaling, biome gating, a Xaero world-map overlay, and per-player reveal policies.

- **Minecraft**: 1.21.1
- **NeoForge**: 21.1.230
- **Required**: Create Ore Excavation
- **Soft compat**: Xaero's World Map (overlay), Xaero's Minimap (waypoints)

---

## What it does

Vanilla COE assigns vein recipes to random chunks independently — ore types and amounts have no relationship to location. This mod layers a richer placement model on top:

1. **Multi-chunk deposits** — chunks are grouped into irregular Perlin-shaped blobs (2–40 chunks), all sharing one ore type.
2. **Distance gradient (log curve)** — the further from spawn, the larger and richer the deposits, and the more exotic the eligible ore types.
3. **Per-chunk gradient** — the core chunk yields the deposit's peak amount; outer chunks fade down to `edge_amount_mul × core` (default 30 %).
4. **Per-type budget** — each ore declares its own `per_chunk_units` range, independent of COE's global `finiteAmountBase`.
5. **Biome tag filtering** — ores can be restricted to biome tags (e.g. iron only in `c:is_mountain` / `minecraft:is_taiga`).
6. **Per-type reveal policy** — `always`, `on_discovery` (walk near it), `on_proximity` (visible while close), or `on_prospect` (Vein Finder must reveal it). Per-player tracking.
7. **Per-type placement mode** — `managed` (our blob algorithm) or `coe` (let COE's `RandomSpreadGenerator` place it; we just track it for the map). Mix and match per ore.
8. **Xaero world-map overlay** — coloured diagonal-striped chunks with a hover tooltip showing deposit name, location, and per-chunk `remaining / initial` units. Compact in-map toggle button + keybind.
9. **Multi-dimension support** — `enabled_dimensions` config selects which dimensions the picker runs in; each dimension has its own SavedData and reproducible seed.
10. **Reproducible patterns** — per-dimension `depositSeed` override lets you copy a deposit layout from one world to another (`/coedeposits seed` to read, `/coedeposits regenerate <seed>` to apply).

Plus auto-clearing of depleted chunks (no Vein Finder false positives), prospect pre-scan around spawn on server start, and incremental roving scans as players explore.

## Compatibility

| Mod | Status | Notes |
|---|---|---|
| **Create Ore Excavation** (`createoreexcavation`) | **Required** | We subclass COE's `RandomSpreadGenerator` and swap the static `OreVeinGenerator.picker` via reflection (the field is opened by an AccessTransformer). |
| **Xaero's World Map** (`xaeroworldmap`) | Soft | Mixin into `xaero.map.gui.GuiMap.render` (TAIL) draws the overlay + adds the toggle widget. Mixin is gated `client`-only via `coedeposits.mixins.json` so dedicated servers skip it. |
| **Xaero's Minimap** (`xaerominimap`) | Soft | `XaeroBridge` does a best-effort reflective waypoint add on deposit discovery; if Xaero's API drifts it falls back silently to the click-to-suggest `/tp` chat message. |

## Commands

All `/coedeposits` subcommands require permission level 2 (standard OP — granted by `/op <player>`).

| Command | Description |
|---|---|
| `/coedeposits tier` | Show distance-from-spawn and tier fraction at the current position. |
| `/coedeposits list` | Top-10 nearest known deposits in the current dimension. |
| `/coedeposits here` | Info about the deposit owning the player's current chunk. |
| `/coedeposits scan` | Re-run the prospect-scan around the player at the configured `prospect_radius`. Useful after editing `deposits.json` + `/reload`. |
| `/coedeposits regenerate [seed]` | Wipe the current dimension's deposits and run a fresh prospect-scan. Pass an optional seed to lock placement to a specific RNG and reproduce the pattern elsewhere. |
| `/coedeposits seed` | Print the effective deposit seed for the current dimension and whether it's an override or the inherited world seed. |
| `/coedeposits refill` | Restore the deposit at the current chunk (resets extracted amount on every loaded chunk). |
| `/coedeposits refill all` | Same but for every deposit in the current dimension. |
| `/coedeposits delete here` | Remove the entire deposit owning the current chunk. |
| `/coedeposits delete chunk` | Remove just the current chunk from its deposit (shrinks the blob; if it was the last chunk the deposit is removed). |
| `/coedeposits place [<type> [<pos> [<amount> [<chunks>]]]]` | Admin-place a deposit. `amount<0` → infinite vein (requires `vein_recipe_infinite`); `amount>0` → exact unit budget; `chunks` overrides natural blob size (default 5). |

## Settings — `config/coedeposits/deposits.json`

All deposit types live in one external file under the server's config directory. On first run the mod copies the bundled defaults (`coedeposits-default-deposits.json` from the jar) to that path; thereafter the on-disk file is the only source of truth — datapacks no longer contribute. To restore the defaults, delete the file and restart the server.

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
| `vein_recipe` | ResourceLocation | id of the COE VeinRecipe applied via `OreData.setRecipe`. |
| `vein_recipe_infinite` | optional RL | Alternate recipe with `finite=never` (used by `/place ... -1`). |
| `items` | List<ItemEntry> | Reserved for future drilling-output synthesis. Currently unused at runtime. |
| `placement` | optional enum | `managed` (default) — our blob generator places this. `coe` — vanilla COE's `RandomSpreadGenerator` places it; we observe and persist for the map only. |
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

## Mod config

### Server-side — `coedeposits-common.toml`

| Key | Default | Purpose |
|---|---:|---|
| `base_radius` | 1000 | Block radius where the log curve transitions (tier ≈ 0.5). |
| `max_radius` | 25000 | Block distance at which tier saturates to ~1.0. |
| `core_spawn_probability` | 0.02 | Per-chunk roll to become a deposit core. |
| `edge_amount_mul` | 0.3 | Per-chunk gradient floor — edges get this fraction of the core amount. |
| `prospect_radius` | 2000 | Block radius pre-scanned on server start (0 = disabled, max 16000). |
| `unbounded_growth` | 50.0 | Growth coefficient when `per_chunk_units.max` is absent. |
| `reveal_mode` | `ALWAYS` | Global default reveal policy: `ALWAYS` / `ON_DISCOVERY` / `ON_PROXIMITY` / `ON_PROSPECT`. Per-type override via `reveal` in `deposits.json`. |
| `proximity_reveal_blocks` | 256 | Visibility radius for `ON_PROXIMITY` (client-side filter). |
| `discovery_radius_blocks` | 24 | Trigger radius for `ON_DISCOVERY` — the player must come this close to any chunk of the deposit. |
| `enabled_dimensions` | `[overworld, the_nether, the_end]` | Dimensions where the mod is active. Others fall through to vanilla COE behaviour. |

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
