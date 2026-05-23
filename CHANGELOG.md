# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

### Balance
- **`core_spawn_probability` default lowered from `0.02` to `0.0005`** (40× sparser). Playtesting at the original density made deposits feel ubiquitous; new default lands ~1 placed deposit per 5–10k chunks once biome/distance filters apply, which suits the exploration-driven progression the mod is built around. Existing worlds keep their saved value — only fresh `coedeposits-common.toml` files get the new default.

### Internal
- Multi-loader/multi-MC repo layout: per-loader subprojects under `NeoForge/`, future `Fabric/` / `Forge/`. CI auto-detects subprojects via `hashFiles`.
- Map toggle widget now anchored top-left of Xaero map (was top-right) — Xaero corners are all crowded; `map_button_anchor` config lets users flip back.
- Dev runtime gained Create: Steam 'n' Rails + Architectury + FTB Chunks stack for cross-mod coexistence testing.

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
