# Changelog

All notable changes to this project will be documented in this file.

## 0.2.0

A unification + compatibility release: one ownership model for every deposit, automatic adoption of third-party COE veins onto the map, input-fluid recipes, and a KubeJS binding.

### Changed
- **Base COE veins are disabled by default.** This mod's managed deposits replace the role of the host mod's bundled veins (`createoreexcavation:*`) — letting both generate would duplicate every ore. They now start **disabled**: visible in the editor's vein list as `(disabled — base COE default)` with one-click **Enable** (writes the new `enabled_veins` config) or **Edit →** to promote one into a type you fully control. Veins from **add-ons/datapacks keep auto-adopting** — those were added intentionally, not as a dependency side-effect. Master switch: `coe_veins_disabled_by_default` (default on, also in the Generation config group).
- **Unified deposit model — one owner, two generation variants.** Every deposit coedeposits tracks is now owned by us identically: we write its `OreData`, persist it, prospect-scan it, render it, and find it. `placement` is no longer "who owns it" but only *where it's placed* — `managed` (our multi-chunk Perlin blob) or `coe` (Create Ore Excavation's own spread). This collapses the old dual-ownership split that caused COE-placement deposits to behave inconsistently (depletion/regenerate/finder edge cases).
- **COE-placement veins are now prospect-scanned and findable.** Declared `placement: coe` types (and adopted ones, below) are pre-populated on the world map ahead of the player and restored by `/coedeposits regenerate`, exactly like managed deposits — no more having to stand almost on top of a COE vein for it to appear. The picker faithfully replays COE's own spread placement (priority order, structure-chunk match, jittered biome whitelist/blacklist) off-thread.

### Added
- **Auto-adopt foreign COE veins (compat).** Any COE vein recipe that has no coedeposits `deposit_type` of its own — e.g. from **Create Ore Excavation Plus**, the **CoE × Mekanism** datapack, **OreCompatCreate**, or any datapack — is adopted onto the world map under an implicit type: we take ownership of its OreData, track it, render it and find it like a declared deposit. Toggle with the new `auto_adopt_coe_veins` config (default **on**). Tooltip names for adopted veins are made readable (`createoreexcavation:ore_vein_type/redstone` → "Redstone"). The in-game editor's Deposits tab lists adoptable veins, each with **Edit →** (promote to a `placement: coe` type you fully control — map colour, reveal, biome filter…) and **Disable** (suppress the vein entirely — it stops generating, stored in the new `disabled_veins` config). `/coedeposits types` shows declared vs adopted with placed counts.
- **Input / coolant fluid on recipes (like the host mod).** Inline `drilling:` and `fluid:` blocks accept an optional `fluid_input` (`{fluid|tag, amount}`) — the fluid the machine **consumes** per cycle (COE's `drillingFluid`, recipe key `"fluid"`). Lets you express fluid-gated recipes such as the CoE × Mekanism datapack's Brine / Sulfuric-Acid drilling bonuses, or a coolant requirement. Editable in the in-game editor (both the Drilling and Fluid groups).
- **KubeJS support — deposit types.** With KubeJS installed, define deposit types from `kubejs/startup_scripts` via the `CoeDeposits` binding: `CoeDeposits.add('mypack:ruby', { vein_recipes:[...], weight:80, distance:{min:2000,max:99999}, ... })`. The object is the standard `deposit_type` schema; scripted types merge between the datapack defaults and the `deposits.json` overlay. KubeJS is an optional dependency — the mod runs fine without it. (COE's own vein/drilling/extracting recipes remain scriptable through COE's KubeJS plugin.)

- **The bundled water deposit is renamed `coedeposits:example_water` → `coedeposits:water`** (its synthesised recipes likewise `example_water_vein/extracting` → `water_vein/extracting`). Existing worlds with placed `example_water` deposits will drop them on upgrade — re-run `/coedeposits regenerate` if you want the renamed one back.
- **`auto_adopt_coe_veins` is now in the in-game config** (Generation group), next to the new "Disable base COE veins" toggle — previously only editable in the toml.

### Fixed
- **A chunk whose vein recipe is set but unresolvable now reads "vein recipe not loaded"** on the map tooltip instead of a misleading "depleted" (the underlying never-applied-OreData self-heal shipped in 0.1.7).
- **Editor: an input fluid (`fluid_input`) set on a drilling/extracting block now persists** even when the block has no other content yet — picking the fluid marks its block active, so it's written to `deposits.json` instead of being dropped on save.
- **Editor: picking a fluid no longer bounces you back to the Core tab** — the editor reopens on the Inline-recipe tab you were on.

### Notes
- **Upgrading from 0.1.x:** base-COE veins switch off on upgrade (`coe_veins_disabled_by_default` arrives as `true`). Veins already placed in your world stay tracked and minable — only **new** placements stop; run `/coedeposits regenerate` for a clean slate, or flip the config / `enabled_veins` to keep them spawning.
- Auto-adopt and the unified model are most predictable on fresh chunks. Foreign COE veins already generated in old chunks (before this version) appear once their chunk loads (the self-heal applies them within ~1s) or after a re-prospect.

## 0.1.7

### Changed
- **Friendlier, configurable discovery chat message.** The old line printed the raw type id with a `[coedeposits]` prefix (`[coedeposits] discovered createoreexcavation:ore_vein_type/water at [...]`), which read like debug output (reported in #3). It now resolves a friendly name — the type's `display_name` from `deposits.json`, otherwise a prettified id (`createoreexcavation:ore_vein_type/water` → "Water") — and the whole line is a config template `discovery_message_format` (default `Discovered %name% at %pos%`). Placeholders: `%name%`, `%pos%` (clickable /tp), `%x% %y% %z%`, `%type%`, `%player%` (the discoverer), `%%`; `§` colour codes work.

### Fixed
- **Deposits placed over already-explored terrain no longer read "depleted".** COE's `OreData.populate` runs once per chunk, so a deposit placed by `/coedeposits regenerate` or the prospect scanner over a chunk generated *before* the deposit existed never got its OreData applied. The depletion sweep now self-heals such chunks (no recipe **and** nothing extracted → re-apply) while leaving genuinely mined-out chunks alone.
- **ON_DISCOVERY reveals are snappy now** — the walk-into sweep runs every second instead of every ten, so a deposit appears moments after you step onto it. The reveal-mode config option also finally documents what each of the four modes does.

### Added
- **ON_PROXIMITY now sends a personal "found it" chat line** the first time a player comes within the proximity radius (same `discovery_message_format`; once per player per deposit, persisted). Visibility is unchanged — still distance-based.
- **Discovery chat controls in the in-game config** — the **Reveal** group now has a **Discovery chat message** toggle (off keeps the map marker / Xaero waypoint, just no chat line) and a **Discovery message** template field, alongside the `discovery_chat` / `discovery_message_format` toml options (#3).
- **`reveal_scope` config — per-player, team, or shared discoveries (#3).** For the per-player reveal modes (`ON_DISCOVERY` / `ON_PROSPECT`): `PER_PLAYER` (default) keeps each player's discoveries private (COE's own behaviour); `TEAM` shares discoveries with the discoverer's party/team — **Open Parties and Claims**, else **FTB Teams**, else the vanilla scoreboard team (both mod integrations are reflection-based, no hard dependency). Team visibility is **live**: you see everything anyone *currently* on your team has discovered — joining a team shares past finds both ways, leaving un-shares them (your own finds always stay). `GLOBAL` makes the first player's discovery reveal the deposit for everyone. Online recipients get the marker + a chat line crediting the finder via the new `%player%` placeholder. Persisted in SavedData; surfaced in the **Reveal** config group. (No effect on `ALWAYS` / `ON_PROXIMITY`.) Per-type override is not included yet — it's a mechanical follow-up.
- **Deposit sharing (#3).** Players can share discoveries explicitly, in three ways: **`/coedeposits share`** — posts a clickable **[✚ Add to map]** offer to chat (everyone in the dimension can click it to add the deposit to their map + Xaero waypoint); **`/coedeposits share <player>`** / **`share all <player>`** — share the deposit you stand in (or everything you've discovered) directly with one player; **world-map keybind** — bind *"Share hovered deposit to chat"* in Options → Controls, hover a deposit on the Xaero world map and press it to post the same clickable offer (the tooltip shows the hint once the key is bound). You can only share deposits you can see; the share/accept commands work without OP (admin subcommands remain OP-gated).

## 0.1.6-1

### Changed
- **Lowered the required NeoForge floor to `21.1.211`** (was effectively `21.1.230`). The mod still builds and runs against `21.1.230`, but the published dependency `versionRange` in `neoforge.mods.toml` now declares `[21.1.211,)` to match the host mod **Create Ore Excavation**'s floor. Every NeoForge API the code touches already exists in `21.1.211`, so a `230`-built jar stays `211`-compatible. Resolves #2.

> No code or generation changes — `0.1.6-1` is identical to `0.1.6` apart from the wider NeoForge compatibility range.

## 0.1.6

### Changed
- **In-game editor & config screen reworked for clarity.** The config screen is now two tabs — **General** (generation, reveal, logging, client) and **Deposits** (the deposit list, inline). Each deposit is one row with **Edit → / Disable / Delete** (Delete behind a confirmation; pristine bundled defaults show only Edit + Disable). The per-deposit editor is two tabs — **Core** (with recipes & filters folded in) and **Inline recipe** — with clearer field help.
- **Compact pickers.** Vein recipes, Biome filter, Dimensions and the global Enabled-dimensions list are now one-line rows; you add an entry by picking from a dropdown of the still-available values. Drilling outputs use a searchable visual item picker (icon + name) per output, with Count and Chance-% number fields.

### Fixed
- **Add deposit** now always assigns a unique id, so creating several in a row (or after deletes) can no longer produce two deposits sharing a `coedeposits:new_N` key — which previously collided silently in `deposits.json`.

### Internal
- The editor/config UI was split out of two monolithic classes into small per-tab / per-group files (`client/config/general/*`, `client/config/deposit/*`) with explicit option declarations. No change to the `deposits.json` schema or generation behaviour.

## 0.1.5

### Added
- **Fluid deposits (COE Extractor).** A deposit can now yield a **fluid** instead of (or alongside) items, harvested with Create Ore Excavation's Extractor rather than the Drill. Add an inline `fluid:` block to a `config/coedeposits/deposits.json` entry (`fluid`, `amount` mB/cycle, `ticks`, `stress`, `drill_tag`) and the `BundledRecipePack` synthesises a `createoreexcavation:extracting` recipe bound to the type's vein. Placement, distance, biome filter and the `per_chunk_units` budget all work exactly as for a solid ore — COE's Extractor and Drill share the same vein-consumption logic.
- **In-game fluid picker.** The editor's Fluid field opens a picker that lists registered **source** fluids by their own texture (tinted sprite) + name, with search and click-to-select — not bucket items. (YACL has no fluid controller, so this is a small dedicated screen.)
- **Bundled example.** Ships a ready `coedeposits:example_water` deposit — a finite "Underground Spring" of `minecraft:water` extracted with the Extractor — so the fluid path works out of the box. Generates in overworld swamp/plains at `weight: 50`; disable it from the editor (or an `{"enabled": false}` overlay) if unwanted.

### Changed
- Config validation now counts an `extracting` recipe as making a vein harvestable, so a fluid-only deposit no longer false-warns "can't be mined".

### Internal
- `DepositType` codec split into two flattened `MapCodec` halves joined with `Codec.mapPair` (DataFixerUpper's `group(...)` caps at 16 fields; the type now has 17). The on-disk JSON schema is unchanged.

## 0.1.4

### Fixed
- **Disabling a deposit in the in-game editor no longer erases its content.** Turning a type off used to collapse its `deposits.json` entry to a bare `{"enabled": false}`, so the full definition was lost on the next reload: a custom deposit could not be recovered, and a re-enabled bundled ore was overwritten with an empty shell (weight 0, no recipe) that shadowed and broke the real default. Now:
  - A **custom** type — or a customised default — is written in full with `enabled: false` appended, so its fields survive a disable → reopen → re-enable round-trip. The loader still drops it from generation (it checks the flag before decoding).
  - An **unchanged bundled/datapack default** keeps writing the lightweight `{"enabled": false}` switch (no frozen copy); the editor restores its fields from the live default on reload, and re-enabling drops the overlay entry so the datapack default takes back over.
  - Bundled ores disabled by the 0.1.3 build show their real values again on reopen. A default already corrupted into an empty shell by the old bug is repaired with a single **Remove** in the editor (the live default returns).

## 0.1.3

### Added
- **In-game deposit editor** (YACL) — a master/detail screen to add and edit deposit types written to `config/coedeposits/deposits.json`, with structured controls (dropdowns, item picker, sliders) instead of hand-edited JSON. Reached from the mod's config screen.
- **Edit / disable bundled ores** — the 14 bundled defaults are listed in the editor (read from the mod jar, so they appear even in the main menu) and can be tuned or turned off without shipping a datapack.
- **Datapack `deposit_type` registry + config overlay** — types load from `data/<ns>/deposit_type/*.json` across packs, with `deposits.json` applied last as an override layer.
- **Overlap resolution** — overlapping deposits of the same type merge; across types the rarer one wins.

### Changed
- Project renamed to **Create Ore Excavation Deposits**; Xaero's Map dependencies declared; README overhauled.

### Fixed
- Config-screen number fields parsing under a comma locale; drill-yields tooltip column alignment; overlay inline-vein binding, `deposit_type` scan path, and config validation.

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
