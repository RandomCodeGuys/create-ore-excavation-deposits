# Changelog

All notable changes to this project will be documented in this file.

## 0.2.0

The 1.20.1 line (Forge + Fabric) catches up to the NeoForge **0.2.0** build, folding the NeoForge **0.1.7** and **0.2.0** spans into one release: per-player / team / shared discoveries, deposit sharing, one unified ownership model for every deposit, automatic adoption of third-party COE veins onto the map, input-fluid recipes, and a KubeJS binding.

### Added
- **`reveal_scope` — per-player, team, or shared discoveries.** For the per-player reveal modes (`ON_DISCOVERY` / `ON_PROSPECT`): `PER_PLAYER` (default) keeps each player's discoveries private (COE's own behaviour); `TEAM` shares them with the discoverer's party/team — **Open Parties and Claims**, else **FTB Teams**, else the vanilla scoreboard team (all integrations are reflection-based, no hard dependency). Team visibility is **live**: you see everything anyone *currently* on your team has discovered — joining shares past finds both ways, leaving un-shares them (your own finds always stay). `GLOBAL` makes the first player's discovery reveal the deposit for everyone. Online recipients get the marker + a chat line crediting the finder. Persisted in SavedData; surfaced in the **Reveal** config group. (No effect on `ALWAYS` / `ON_PROXIMITY`.)
- **Deposit sharing.** Share discoveries explicitly, three ways: **`/coedeposits share`** posts a clickable **[✚ Add to map]** offer to chat (anyone in the dimension can click it to add the deposit + an Xaero waypoint); **`/coedeposits share <player>`** / **`share all <player>`** share the deposit you stand in (or everything you've found) directly with one player; **world-map keybind** — bind *"Share hovered deposit to chat"* in Options → Controls, hover a deposit on the Xaero world map and press it to post the same offer (the tooltip shows the hint once bound). You can only share what you can see; share/accept work without OP (admin subcommands stay OP-gated).
- **Auto-adopt foreign COE veins (compat).** Any COE vein recipe with no coedeposits `deposit_type` of its own — e.g. from **Create Ore Excavation Plus**, the **CoE × Mekanism** datapack, **OreCompatCreate**, or any datapack — is adopted onto the world map under an implicit type: we take ownership of its OreData, track, render and find it like a declared deposit. Toggle with `auto_adopt_coe_veins` (default **on**). Adopted veins get readable tooltip names (`createoreexcavation:ore_vein_type/redstone` → "Redstone"). The editor's Deposits tab lists adoptable veins, each with **Edit →** (promote to a `placement: coe` type you fully control) and **Disable** (suppress the vein — stored in `disabled_veins`). `/coedeposits types` shows declared vs adopted with placed counts.
- **Input / coolant fluid on recipes (like the host mod).** Inline `drilling:` and `fluid:` blocks accept an optional `fluid_input` (`{fluid|tag, amount}`) — the fluid the machine **consumes** per cycle (COE's `drillingFluid`, recipe key `"fluid"`). Lets you express fluid-gated recipes (the CoE × Mekanism Brine / Sulfuric-Acid drilling bonuses, a coolant requirement…). Editable in the in-game editor (both the Drilling and Fluid groups).
- **KubeJS support — deposit types.** With KubeJS installed, define deposit types from `kubejs/startup_scripts` via the `CoeDeposits` binding: `CoeDeposits.add('mypack:ruby', { vein_recipes:[...], weight:80, distance:{min:2000,max:99999}, ... })` (and `CoeDeposits.remove('id')`). The object is the standard `deposit_type` schema; scripted types merge between the datapack defaults and the `deposits.json` overlay. KubeJS is an **optional** dependency — the mod runs fine without it. (COE's own vein/drilling/extracting recipes remain scriptable through COE's own KubeJS plugin.)
- **`ON_PROXIMITY` sends a personal "found it" chat line** the first time a player comes within the proximity radius (same `discovery_message_format`; once per player per deposit, persisted). Visibility is unchanged — still distance-based.
- **Discovery chat controls in the in-game config** — the **Reveal** group gains a **Discovery chat message** toggle (off keeps the map marker / Xaero waypoint, just no chat line) and a **Discovery message** template field, alongside the `discovery_chat` / `discovery_message_format` toml options.

### Changed
- **Base COE veins are disabled by default.** This mod's managed deposits replace the role of the host mod's bundled veins (`createoreexcavation:*`) — letting both generate would duplicate every ore. They now start **disabled**: shown in the editor's vein list as `(disabled — base COE default)` with one-click **Enable** (writes the new `enabled_veins` config) or **Edit →** to promote one into a type you fully control. Veins from **add-ons / datapacks keep auto-adopting** — those were added intentionally, not as a dependency side-effect. Master switch: `coe_veins_disabled_by_default` (default on, also in the Generation config group).
- **Unified deposit model — one owner, two generation variants.** Every deposit coedeposits tracks is now owned by us identically: we write its `OreData`, persist it, prospect-scan it, render it and find it. `placement` is no longer "who owns it" but only *where it's placed* — `managed` (our multi-chunk Perlin blob) or `coe` (Create Ore Excavation's own spread). This collapses the old dual-ownership split that caused COE-placement deposits to behave inconsistently (depletion / regenerate / finder edge cases).
- **COE-placement veins are now prospect-scanned and findable.** Declared `placement: coe` types (and adopted ones) are pre-populated on the world map ahead of the player and restored by `/coedeposits regenerate`, exactly like managed deposits — no more standing almost on top of a COE vein for it to appear. The picker faithfully replays COE's own spread placement (priority order, structure-chunk match, jittered biome whitelist / blacklist) off-thread.
- **Friendlier, configurable discovery chat message.** The discovery line now resolves a friendly name — the type's `display_name`, else a prettified id (`…/water` → "Water") — and the whole line is a config template `discovery_message_format` (default `Discovered %name% at %pos%`). Placeholders: `%name%`, `%pos%` (clickable /tp), `%x% %y% %z%`, `%type%`, `%player%` (the discoverer), `%%`; `§` colour codes work.
- **The bundled water deposit is renamed `coedeposits:example_water` → `coedeposits:water`** (its synthesised recipes likewise `example_water_vein/extracting` → `water_vein/extracting`). Worlds with placed `example_water` deposits drop them on upgrade — re-run `/coedeposits regenerate` for the renamed one.

### Fixed
- **Deposits placed over already-explored terrain no longer read "depleted".** COE's `OreData.populate` runs once per chunk, so a deposit placed by `/coedeposits regenerate` or the prospect scanner over a chunk generated *before* the deposit existed never got its OreData applied. The depletion sweep now self-heals such chunks (no recipe **and** nothing extracted → re-apply) while leaving genuinely mined-out chunks alone.
- **A chunk whose vein recipe is set but unresolvable now reads "vein recipe not loaded"** on the map tooltip instead of a misleading "depleted".
- **`ON_DISCOVERY` reveals are snappy** — the walk-into sweep runs every second instead of every ten, so a deposit appears moments after you step onto it.
- **Editor: an input fluid (`fluid_input`) set on a drilling / extracting block now persists** even when the block has no other content yet — picking the fluid marks its block active, so it's written to `deposits.json` instead of dropped on save.
- **Editor: picking a fluid no longer bounces you back to the Core tab** — the editor reopens on the Inline-recipe tab you were on.

### Notes
- **Upgrading from 0.1.x:** base-COE veins switch off on upgrade (`coe_veins_disabled_by_default` arrives as `true`). Veins already placed in your world stay tracked and minable — only **new** placements stop; run `/coedeposits regenerate` for a clean slate, or flip the config / `enabled_veins` to keep them spawning.
- Auto-adopt and the unified model are most predictable on fresh chunks. Foreign COE veins already generated in old chunks appear once their chunk loads (the self-heal applies them within ~1s) or after a re-prospect.
- Both loaders ship from one codebase — the `reveal_scope`, sharing, KubeJS and auto-adopt logic lives in `platform-shared/`; only the networking + keybind glue differs between Forge and Fabric.

## 0.1.6

### Changed
- **The in-game editor and config screen, reworked** — the 1.20.1 line (Forge + Fabric) catches up to the NeoForge 0.1.6 build. The config screen is now two tabs: **General** (generation / reveal / logging / client settings) and **Deposits** (an inline list, one row per deposit with **Edit → / Disable / Delete**, Delete behind a confirmation). Vein recipes, biome filter and dimensions are compact dropdown pickers; drilling outputs use a searchable visual item picker (icon + name) with Count and Chance-% number fields. The old monolithic editor/config classes were split into small per-tab / per-group files.

### Fixed
- **Add deposit** now assigns a unique id, so creating several in a row (or after deletes) can no longer make two deposits share a `coedeposits:new_N` key — which previously collided silently in `deposits.json`.
- **Biome filter is usable from the main menu** — with no world loaded the biome registry (and thus the real tag list) isn't available, so the picker now offers the common vanilla biome tags instead of greying out. Pack-specific / modded tags still need the editor opened in a world.

## 0.1.5-1

### Fixed
- **Crash on load** (`NoSuchMethodError: net.minecraft.resources.FileToIdConverter.json`). The 0.1.5 jar was published **un-reobfuscated**: the release pipeline ran `publishMods` as a separate Gradle invocation, which re-ran the `jar` task without `reobfJar` and uploaded a jar calling Mojang-mapped method names that don't exist at runtime → crash during mod construction on every 1.20.1 instance. The build now forces `reobfJar` before any publish task. No content or behaviour changes versus 0.1.5 — same code, correctly obfuscated.

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
