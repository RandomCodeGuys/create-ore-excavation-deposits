# Changelog

All notable changes to this project will be documented in this file.

## 0.1.2

### Performance
- **World map renderer rewritten** — diagonal stripes dropped, fills merged across contiguous chunks per row, outline collapsed to the deposit's external perimeter. Cuts draw calls per chunk from ~35-55 down to a handful per row; large surveyed maps no longer stutter when many deposits are on-screen at once.
- **Async prospect scan** — Vein Finder / proximity reveal scans now run off-thread (dry-run) and materialise on the main thread in budgeted slices (`PROSPECT_CHUNKS_PER_TICK`, default 4). Eliminates the 1+ second tick freezes that used to happen when a player roamed into a region with many unrevealed deposits.

### Schema
- **Multi-recipe deposits** — a deposit can list `vein_recipes: [{recipe, weight}, …]` for per-chunk weighted picks across multiple ores.
- **Per-block mixing via COE drilling outputs** — primary ore + secondary trace items + slag are rolled independently per drill cycle (using Create's native chance-per-output mechanic). Yield ratios "float" naturally.
- **Inline `vein` + `drilling` blocks** in `deposits.json` — define a new deposit type without shipping a separate datapack. Recipes are synthesised at load time by a virtual `BundledRecipePack`. Single source of truth: edit one file, `/reload`, done.
- **Self-replenishment** — `replenish_rate_per_hour` restores units/hour to drilled chunks while loaded (capped at each chunk's original yield). Per-deposit override via `/coedeposits replenish`.
- **Fillers (tailings chunks)** — optional `fillers: [{weight}]` adds patchy empty chunks visible as warm-grey on the map. Off by default everywhere except the `example_kitchen_sink` showcase entry.
- **`distance.max` default bumped to `Integer.MAX_VALUE`** — deposits now spawn at any distance from spawn by default, matching the "explore further → richer" log curve already in place.

### Bundled defaults
- All 14 vanilla ore types rewritten as **primary ore + secondary ore traces + slag** (per-block weighted drilling outputs). E.g. iron drills mostly raw iron with cobblestone/coal trace; redstone yields occasional lapis flakes; nether gold drills raw gold + nether gold ore + quartz fragments.
- Vanilla types are clean — no fillers, no replenish — so a fresh server feels predictable.
- 6 example deposit types added under `coedeposits:example_*` showcasing every feature (per-block mix, primary+slag, replenish-only, kitchen-sink with all features). Set `weight: 0` to disable.

### UI
- Hover tooltip extended: **deposit composition** (when multi-recipe), **drill yields** for the hovered chunk's recipe (with per-output percentages), **replenish rate** line.
- Per-chunk remaining line shows the chunk's specific ore name for multi-recipe deposits ("This chunk: iron — 12,345 / 50,000 units").
- All tooltip labels capitalised (`At`, `Chunks:`, `Ores:`, `Drill yields:`, `Replenishing:`, `This chunk:`).
- Filler chunks render as a distinct warm-grey to read as "rock" on the map.

### Commands
- `/coedeposits replenish [<rate>|all <rate>]` — set the replenish rate for the deposit under the player, or all deposits at once.

### Dev / observability
- 8 log-category toggles in `coedeposits-common.toml` (placement, prospect, replenish, network, etc.) — silence the categories you don't care about without losing the others.
- Spark profiler added to `localRuntime` for diagnosing tick stalls in dev.

### Documentation
- README updated for multi-recipe, fillers, replenish, and the inline `vein` + `drilling` schema.

### Compatibility
- COE Ore Vein Atlas, Sample Drill, and Vein Finder all read the synthesised vein recipes correctly — no special handling needed for inline-schema deposits.

## 0.1.1

### Balance
- **`core_spawn_probability` default lowered from `0.02` to `0.0005`** (40× sparser). Playtesting at the original density made deposits feel ubiquitous; new default lands ~1 placed deposit per 5–10k chunks once biome/distance filters apply, which suits the exploration-driven progression the mod is built around. Existing worlds keep their saved value — only fresh `coedeposits-common.toml` files get the new default.

### UI
- Map toggle widget now anchored top-left of Xaero's world map (was top-right). Every Xaero corner is occupied by its own button cluster, so the new default sits the widget on the left edge ~50px down — clear of Xaero's own controls and of Create: Steam 'n' Rails' Train Routes toggle.
- New `map_button_anchor` config (`LEFT` / `RIGHT`) lets users flip the widget back to the right edge if preferred. `map_button_x_offset` semantics changed from "pixels from right edge" to "pixels from anchored edge".

### Repository / CI
- Multi-loader / multi-MC repo layout: per-loader subprojects under `NeoForge/`, future `Fabric/` and `Forge/` slot in beside it without polluting each other's classpaths.
- `.github/workflows/publish.yml` auto-detects which `<Loader>/build.gradle` files exist on the branch and builds + publishes each, so a branch with only `NeoForge/` ships only the NeoForge jar.
- Release type pinned to `ALPHA` until the SavedData codec stabilises.

### Documentation
- TOML comment + README field table for `core_spawn_probability` now spell out the full chain: 1 candidate roll → biome+distance filter → 4-30-chunk blob expansion → ~1 placed deposit per 5-10k chunks.

## 0.1.0 — initial release

First public release. Multi-chunk ore deposits add-on for Create Ore Excavation on NeoForge 1.21.1.

### Generation
- Multi-chunk Perlin-shaped deposits (2–40 chunks per blob).
- Distance-from-spawn log curve drives blob size, per-chunk units, and type eligibility.
- Per-chunk gradient: core peak fades to `edge_amount_mul × peak` at the edges.
- Per-type budget via `per_chunk_units` (independent of COE's global `finiteAmountBase`).
- Biome-tag filtering per type.
- Two placement modes per type: `managed` (our blob algorithm) or `coe` (vanilla COE places, we track for the map).

### Visibility
- Xaero World Map overlay: coloured diagonal-striped chunks with hover tooltip (name, location, per-chunk `remaining / initial`).
- Compact 16×16 toggle widget on the map + Options → Controls keybind.
- Four reveal policies: `always` / `on_discovery` (walk near) / `on_proximity` (visible while close) / `on_prospect` (Vein Finder must reveal). Per-player tracking.

### Multi-dimension
- Default `enabled_dimensions = [overworld, the_nether, the_end]`.
- Per-dimension SavedData + per-dimension `depositSeed` override for reproducible patterns across worlds.
- Bundled overworld (11) + nether (3) ore defaults; end ready for user-defined entries.

### Admin commands (OP-only)
- `/coedeposits tier` / `list` / `here` / `scan` / `regenerate [seed]` / `seed`.
- `/coedeposits refill [all]` / `delete here|chunk`.
- `/coedeposits place [<type> [<pos> [<amount> [<chunks>]]]]`.

### Compatibility
- Required: Create Ore Excavation.
- Soft: Xaero's World Map (overlay), Xaero's Minimap (waypoints).
- No conflict with Create: Steam 'n' Rails (button position configurable to avoid UI overlap).
- Third-party COE veins coexist via `placement: "coe"` delegation path.
