package uk.niknik.coedeposits.command;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import com.tom.createores.recipe.VeinRecipe;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.Config;
import uk.niknik.coedeposits.deposit.Deposit;
import uk.niknik.coedeposits.deposit.DepositType;
import uk.niknik.coedeposits.gen.CoedepositsPicker;
import uk.niknik.coedeposits.gen.DepositPlacer;
import uk.niknik.coedeposits.gen.DistanceGradient;
import uk.niknik.coedeposits.gen.ProspectScanQueue;
import uk.niknik.coedeposits.gen.ProspectScanner;
import uk.niknik.coedeposits.network.CoedepositsNetwork;
import uk.niknik.coedeposits.store.DepositSavedData;

import com.tom.createores.OreData;
import com.tom.createores.OreDataAttachment;

/**
 * Brigadier tree for {@code /coedeposits}. All subcommands require permission
 * level 2 (OP / cheat-mode).
 *
 * <ul>
 *   <li>{@code tier} — print distance + tier at the player's position</li>
 *   <li>{@code list} — top-10 nearest known deposits</li>
 *   <li>{@code types} — roster of declared + auto-adopted deposit types with placed counts</li>
 *   <li>{@code here} — info about the deposit at the player's chunk</li>
 *   <li>{@code scan} — prospect-scan around the player (no wipe)</li>
 *   <li>{@code regenerate [seed]} — wipe the current dim + rescan, with an
 *       optional seed override for reproducing a pattern in another world</li>
 *   <li>{@code seed} — print the effective deposit seed for the current dim</li>
 *   <li>{@code refill [all]} — restore extractedAmount on depleted chunks</li>
 *   <li>{@code delete here|chunk} — remove a deposit (entire or single chunk)</li>
 *   <li>{@code place [type [pos [amount [chunks]]]]} — admin-place a deposit</li>
 * </ul>
 */
public final class CoedepositsCommand {
    private CoedepositsCommand() {}

    /** Register the full subcommand tree onto the server's brigadier dispatcher. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Brigadier-style fluent build: each .then() adds a child node. The
        // tree's `requires(hasPermission(2))` at the root means every subcommand
        // inherits the OP/cheat-mode gate — players without permission don't
        // even see the command in tab-complete.
        dispatcher.register(Commands.literal("coedeposits")
                .requires(s -> s.hasPermission(2))

                // ── Read-only inspection subcommands ────────────────────────
                // No-arg commands that print info to the invoker's chat. Cheap,
                // no state mutation, useful for debugging deposit layout.
                .then(Commands.literal("tier").executes(CoedepositsCommand::cmdTier))
                .then(Commands.literal("list").executes(CoedepositsCommand::cmdList))
                .then(Commands.literal("types").executes(CoedepositsCommand::cmdTypes))
                .then(Commands.literal("here").executes(CoedepositsCommand::cmdHere))
                .then(Commands.literal("seed").executes(CoedepositsCommand::cmdSeed))

                // ── Scan / refill (re-create OreData without wiping) ────────
                // scan: extend the prospect search around the player.
                // refill: restore extractedAmount for loaded chunks (one
                //   deposit by default, all with the `all` suffix).
                .then(Commands.literal("scan").executes(CoedepositsCommand::cmdScan))
                .then(Commands.literal("refill")
                        .executes(c -> cmdRefill(c, false))
                        .then(Commands.literal("all").executes(c -> cmdRefill(c, true))))

                // ── Destructive subcommands ─────────────────────────────────
                // delete here  — drop the whole deposit at the player's chunk.
                // delete chunk — drop just one chunk (auto-deletes the parent
                //                deposit if it was the last chunk).
                // regenerate   — wipe the dimension and re-prospect.
                //                Optional `<seed>` arg locks the placement RNG.
                .then(Commands.literal("delete")
                        .then(Commands.literal("here").executes(CoedepositsCommand::cmdDeleteHere))
                        .then(Commands.literal("chunk").executes(CoedepositsCommand::cmdDeleteChunk)))
                .then(Commands.literal("regenerate")
                        .executes(c -> cmdRegenerate(c, null))
                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                .executes(c -> cmdRegenerate(c, LongArgumentType.getLong(c, "seed")))))

                // ── Replenish per-deposit override ──────────────────────────
                // /coedeposits replenish <rate>          — set on the deposit at the player's chunk
                // /coedeposits replenish all <rate>      — set on every deposit in the current dim
                // rate=0 clears the override (deposit falls back to type default)
                .then(Commands.literal("replenish")
                        .then(Commands.argument("rate", DoubleArgumentType.doubleArg(0.0))
                                .executes(c -> cmdReplenish(c,
                                        DoubleArgumentType.getDouble(c, "rate"), false)))
                        .then(Commands.literal("all")
                                .then(Commands.argument("rate", DoubleArgumentType.doubleArg(0.0))
                                        .executes(c -> cmdReplenish(c,
                                                DoubleArgumentType.getDouble(c, "rate"), true)))))

                // ── Admin-place a deposit (the most complex subtree) ────────
                // Chained optional-args pattern via nested .then() — every
                // .executes() on the outer level is the entry point for that
                // arity. Five legal forms:
                //   /coedeposits place
                //   /coedeposits place <type>
                //   /coedeposits place <type> <pos>
                //   /coedeposits place <type> <pos> <amount>
                //   /coedeposits place <type> <pos> <amount> <chunks>
                // Brigadier dispatches to the deepest matching .executes(),
                // so each call shape lands in the same doPlace() with the
                // appropriate zero-defaults for missing args.
                .then(Commands.literal("place")
                        .executes(c -> doPlace(c, null, null, 0, 0))
                        .then(Commands.argument("type", ResourceLocationArgument.id())
                                // Tab-complete: suggest known deposit type ids.
                                .suggests((c, b) -> SharedSuggestionProvider.suggestResource(
                                        Coedeposits.DEPOSIT_TYPES.all().keySet(), b))
                                .executes(c -> doPlace(c,
                                        ResourceLocationArgument.getId(c, "type"), null, 0, 0))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(c -> doPlace(c,
                                                ResourceLocationArgument.getId(c, "type"),
                                                BlockPosArgument.getBlockPos(c, "pos"), 0, 0))
                                        // amount accepts negatives — that's the infinite-vein opt-in.
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(Integer.MIN_VALUE))
                                                .executes(c -> doPlace(c,
                                                        ResourceLocationArgument.getId(c, "type"),
                                                        BlockPosArgument.getBlockPos(c, "pos"),
                                                        IntegerArgumentType.getInteger(c, "amount"),
                                                        0))
                                                // chunks: enforce ≥1 here so doPlace doesn't have to defend.
                                                .then(Commands.argument("chunks", IntegerArgumentType.integer(1))
                                                        .executes(c -> doPlace(c,
                                                                ResourceLocationArgument.getId(c, "type"),
                                                                BlockPosArgument.getBlockPos(c, "pos"),
                                                                IntegerArgumentType.getInteger(c, "amount"),
                                                                IntegerArgumentType.getInteger(c, "chunks"))))))))
        );
    }

    /**
     * /coedeposits place [<type> [<pos> [<amount> [<chunks>]]]]
     *
     * <p>Semantics:
     * <ul>
     *   <li><b>no args / lower-arity</b>: tier-based size + tier-based amount (natural-like)</li>
     *   <li><b>amount provided, chunks=0</b>: use 5 chunks default</li>
     *   <li><b>amount + chunks</b>: use exactly the specified chunks</li>
     *   <li><b>amount &lt; 0</b>: infinite vein (uses {@code vein_recipe_infinite} from the
     *       DepositType; fails if not declared). amount magnitude is ignored.</li>
     * </ul>
     * No caps on randomMul or chunk count — player asked for it, player gets it.
     * Floor randomMul at 0 to avoid negative-unit COE arithmetic.
     */
    private static int doPlace(CommandContext<CommandSourceStack> ctx,
                                ResourceLocation forcedType,
                                BlockPos forcedPos,
                                int totalAmount,
                                int chunksOverride) {
        // ── Phase 1: validate caller + target chunk ─────────────────────────
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        ServerLevel lvl = p.serverLevel();
        BlockPos targetPos = forcedPos != null ? forcedPos : p.blockPosition();
        ChunkPos cp = new ChunkPos(targetPos);

        var store = DepositSavedData.get(lvl);
        if (store.isOccupied(cp)) {
            src.sendFailure(Component.literal(
                    "chunk " + cp.x + "," + cp.z + " already belongs to a deposit"));
            return 0;
        }

        // Interpret the totalAmount arg: <0 is the infinite-vein opt-in,
        // 0 means "user didn't supply amount" (use type's natural budget),
        // >0 is an exact unit budget the user wants enforced.
        boolean infinite = totalAmount < 0;
        boolean amountGiven = totalAmount != 0;

        // ── Phase 2: initial placement decision ─────────────────────────────
        // Two paths: forced type (admin explicitly picked the ore) vs the
        // natural picker (let tryPick decide based on biome/distance/weights).
        // The natural path uses probability=1.0 to bypass the core_spawn_probability
        // gate — the admin wants this chunk to spawn something regardless of
        // density settings.
        DepositPlacer.Result placed;
        if (forcedType != null) {
            DepositType t = Coedeposits.DEPOSIT_TYPES.get(forcedType);
            if (t == null) {
                src.sendFailure(Component.literal("unknown deposit type: " + forcedType));
                return 0;
            }
            placed = DepositPlacer.forceType(cp, lvl.getSharedSpawnPos(), targetPos.getY(),
                    DepositSavedData.get(lvl).effectiveSeed(lvl), forcedType, t,
                    Config.BASE_RADIUS.get().floatValue(),
                    Config.MAX_RADIUS.get().floatValue());
        } else {
            placed = DepositPlacer.tryPick(
                    cp, lvl.getSharedSpawnPos(), DepositSavedData.get(lvl).effectiveSeed(lvl), Coedeposits.DEPOSIT_TYPES,
                    Config.BASE_RADIUS.get().floatValue(),
                    Config.MAX_RADIUS.get().floatValue(),
                    1.0f,  // probability=1.0 — force a pick rather than rolling the gate
                    lvl.getBiome(new BlockPos(cp.getMiddleBlockX(), 64, cp.getMiddleBlockZ())),
                    lvl.dimension().location());
            if (placed == null) {
                src.sendFailure(Component.literal(
                        "no eligible deposit type for this position (check distance.min/max)"));
                return 0;
            }
        }

        // ── Phase 3: resolve which recipe variant to apply ──────────────────
        // Infinite mode forces every chunk to use vein_recipe_infinite (single
        // recipe, no per-chunk roll, no fillers). Normal mode uses the type's
        // first recipe as a reference for amountMul math; individual chunks
        // still get their own recipe via per-chunk roll at apply time below.
        DepositType type = Coedeposits.DEPOSIT_TYPES.get(placed.typeId());
        ResourceLocation referenceRecipeId;
        if (infinite) {
            var maybeInf = type.veinRecipeInfinite();
            if (maybeInf.isEmpty()) {
                src.sendFailure(Component.literal(
                        "DepositType " + placed.typeId() + " has no vein_recipe_infinite — "
                        + "add it to the deposit_type JSON to allow infinite veins"));
                return 0;
            }
            referenceRecipeId = maybeInf.get();
        } else {
            if (placed.type().veinRecipes().isEmpty()) {
                src.sendFailure(Component.literal(
                        "DepositType " + placed.typeId() + " has no vein_recipes — "
                        + "add at least one recipe entry to deposits.json"));
                return 0;
            }
            referenceRecipeId = placed.type().veinRecipes().get(0).recipe();
        }
        VeinRecipe vr = resolveVein(lvl, referenceRecipeId);
        if (vr == null) {
            src.sendFailure(Component.literal(
                    "vein_recipe " + referenceRecipeId + " not loaded — check data/<ns>/recipe/"));
            return 0;
        }

        // ── Phase 4: optionally resize the blob ─────────────────────────────
        // Default sizing comes from the type's size_chunks range × tier. The
        // command lets the admin override:
        //   - explicit chunks arg: always wins
        //   - amount supplied but no chunks: default to 5 (compact-ish blob)
        //   - neither: keep the natural size from Phase 2
        // forceWith rebuilds the blob deterministically — same chunk + same
        // seed + same target size always produces the same Perlin shape.
        int targetChunks = placed.chunks().size();
        if (chunksOverride > 0) targetChunks = chunksOverride;
        else if (amountGiven)  targetChunks = 5;

        if (targetChunks != placed.chunks().size()) {
            placed = DepositPlacer.forceWith(cp, DepositSavedData.get(lvl).effectiveSeed(lvl), placed.typeId(),
                    type, placed.tierFraction(), targetChunks);
        }

        // ── Phase 5: compute amountMul (COE randomMul) ──────────────────────
        // Three branches:
        //   - infinite: formula irrelevant (recipe.finite=never bypasses it),
        //     but we still feed 1.0 so tooltips show a sane value.
        //   - amount supplied: divide totalAmount by chunks to derive per-chunk
        //     units, then invert COE's formula via amountMulForTarget.
        //   - neither: derive from the type's per_chunk_units budget — same
        //     logic the natural picker would use.
        float amountMul;
        int base = com.tom.createores.Config.finiteAmountBase;
        if (infinite) {
            amountMul = 1.0f;
        } else if (amountGiven) {
            int chunks = placed.chunks().size();
            double perChunkUnits = totalAmount / (double) chunks;
            amountMul = DepositPlacer.amountMulForTarget(
                    perChunkUnits, vr.getMinAmount(), vr.getMaxAmount(), base);
        } else {
            double target = type.perChunkUnits().computeTarget(
                    placed.tierFraction(),
                    Config.UNBOUNDED_GROWTH.get());
            amountMul = DepositPlacer.amountMulForTarget(
                    target, vr.getMinAmount(), vr.getMaxAmount(), base);
        }

        // ── Phase 6: persist the deposit ────────────────────────────────────
        // Final-locals are required so the lambda chat-summary captures below
        // can reference these values from within sendSuccess() suppliers.
        final float finalAmountMul = amountMul;
        final ResourceLocation finalReferenceRecipeId = referenceRecipeId;
        Deposit dep = new Deposit(
                UUID.randomUUID(),
                placed.typeId(),
                placed.typeId().getPath() + "@" + (cp.x * 16) + "," + (cp.z * 16),
                cp,
                placed.chunks(),
                finalAmountMul,
                placed.tierFraction(),
                uk.niknik.coedeposits.deposit.DepositType.Placement.MANAGED,
                0.0);  // replenishRateOverride: defer to type's default
        store.add(dep);

        // ── Phase 7: materialize OreData on currently-loaded blob chunks ────
        // Unloaded chunks pick up OreData via the picker's Phase 1 (saved-
        // deposit lookup) when they next load — no need to force-load here.
        // Two paths:
        //   - infinite: every chunk gets the same infinite recipe and same
        //     amountMul (no edge fade, no fillers — admin explicitly wants
        //     this to be a uniform infinite vein)
        //   - normal: per-chunk roll selects each chunk's recipe (could be a
        //     filler — those skip OreData entirely)
        int materialized = 0;
        int filled = 0;
        float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();
        long depositSeed = DepositSavedData.get(lvl).effectiveSeed(lvl);
        for (ChunkPos cc : placed.chunks()) {
            LevelChunk lc = lvl.getChunkSource().getChunk(cc.x, cc.z, false);
            if (lc == null) continue;

            ResourceLocation chunkRecipe;
            float perChunkMul;
            if (infinite) {
                chunkRecipe = finalReferenceRecipeId;
                perChunkMul = finalAmountMul;
            } else {
                var rolled = DepositPlacer.rollChunkRecipe(type, depositSeed, cc);
                if (rolled.isEmpty()) {
                    // filler — leave OreData empty, count for the summary
                    filled++;
                    continue;
                }
                chunkRecipe = rolled.get();
                perChunkMul = dep.amountMulFor(cc, edgeMul);
            }
            CoedepositsPicker.applyToOreData(lc, chunkRecipe, perChunkMul);
            lc.setUnsaved(true);
            materialized++;
        }
        CoedepositsPicker.broadcastDiscovery(lvl, dep);

        // ── Phase 8: chat summary to the invoking admin ─────────────────────
        // perChunkUnits replays COE's amount formula (the inverse of what
        // amountMulForTarget computed) so the displayed number matches what
        // a Drilling Machine will actually yield per chunk.
        long perChunkUnits = Math.round(
                ((vr.getMaxAmount() - vr.getMinAmount()) * finalAmountMul + vr.getMinAmount()) * base);
        long totalUnits = perChunkUnits * placed.chunks().size();
        final int matFinal = materialized;
        final int totalChunks = placed.chunks().size();
        final DepositPlacer.Result placedF = placed;
        final boolean isInfinite = infinite;
        String typeName = vr.getName().getString();

        src.sendSuccess(() -> Component.literal("placed ")
                .append(Component.literal(typeName).withStyle(net.minecraft.ChatFormatting.AQUA)),
                false);
        if (isInfinite) {
            src.sendSuccess(() -> Component.literal(String.format(
                    "  INFINITE across %d chunks (never depletes)", placedF.chunks().size()))
                    .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE), false);
        } else {
            src.sendSuccess(() -> Component.literal(String.format(
                    "  %,d units across %d chunks (~%,d per chunk)",
                    totalUnits, placedF.chunks().size(), perChunkUnits))
                    .withStyle(net.minecraft.ChatFormatting.GRAY), false);
        }
        if (matFinal < totalChunks) {
            src.sendSuccess(() -> Component.literal(String.format(
                    "  applied to %d/%d loaded chunks (rest activate on next chunk-load)",
                    matFinal, totalChunks)).withStyle(net.minecraft.ChatFormatting.DARK_GRAY),
                    false);
        }
        return 1;
    }

    /** Look up a COE vein recipe by id; returns {@code null} if missing or wrong type. */
    @SuppressWarnings("unchecked")
    private static VeinRecipe resolveVein(ServerLevel lvl, ResourceLocation id) {
        return lvl.getRecipeManager().byKey(id)
                .filter(r -> r.value() instanceof VeinRecipe)
                .map(r -> (RecipeHolder<VeinRecipe>) r)
                .map(RecipeHolder::value)
                .orElse(null);
    }

    /**
     * Refill OreData for the deposit at the player's chunk (or all deposits
     * if {@code all == true}). Sets {@code extractedAmount=0} and restores
     * recipe + amountMul for every loaded chunk. Unloaded chunks are skipped
     * (they'll naturally refresh when our picker fires on next load).
     */
    private static int cmdRefill(CommandContext<CommandSourceStack> ctx, boolean all) {
        // ── Phase 1: validate caller ─────────────────────────────────────────
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        ServerLevel lvl = p.serverLevel();
        var store = DepositSavedData.get(lvl);
        float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();

        // ── Phase 2: collect targets ────────────────────────────────────────
        // Two modes: `all` walks every deposit in the dimension (admin "reset
        // everything" path); otherwise we refill just the deposit owning the
        // player's current chunk. If the player isn't standing on any known
        // deposit, the non-all path errors out so the user gets feedback
        // rather than silent no-op.
        java.util.Collection<Deposit> targets;
        if (all) {
            // Defensive copy — store.all() returns the live map and we don't
            // want to iterate it while the picker is potentially mutating it
            // on chunk-load events.
            targets = new java.util.ArrayList<>(store.all().values());
        } else {
            ChunkPos cp = new ChunkPos(p.blockPosition());
            Deposit d = store.lookup(cp);
            if (d == null) {
                src.sendFailure(Component.literal(
                        "not in a known deposit — use /coedeposits refill all to refill globally"));
                return 0;
            }
            targets = java.util.List.of(d);
        }

        // ── Phase 3: re-apply OreData on every loaded chunk of every target ─
        // Per-chunk recipe roll for MANAGED deposits (so each chunk gets the
        // same recipe it had originally — rolls are deterministic on chunk
        // pos + deposit seed). Filler chunks are skipped (nothing to refill,
        // they have no OreData by design). COE-mode deposits use the type's
        // first recipe as a single-recipe fallback.
        //
        // skipped counter combines: type-missing (whole deposit), unloaded
        // chunk, filler chunk — all three are "no apply happened" cases.
        int chunksRefilled = 0;
        int chunksSkipped = 0;
        long depositSeed = store.effectiveSeed(lvl);
        for (Deposit dep : targets) {
            var type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
            if (type == null || type.veinRecipes().isEmpty()) {
                chunksSkipped += dep.chunks().size();
                continue;
            }
            for (ChunkPos cc : dep.chunks()) {
                LevelChunk lc = lvl.getChunkSource().getChunk(cc.x, cc.z, false);
                if (lc == null) { chunksSkipped++; continue; }

                ResourceLocation chunkRecipe;
                if (dep.placement() == uk.niknik.coedeposits.deposit.DepositType.Placement.COE) {
                    chunkRecipe = type.veinRecipes().get(0).recipe();
                } else {
                    var rolled = DepositPlacer.rollChunkRecipe(type, depositSeed, cc);
                    if (rolled.isEmpty()) { chunksSkipped++; continue; }  // filler
                    chunkRecipe = rolled.get();
                }
                // applyToOreData also resets extractedAmount to 0 — that's the
                // actual "refill" semantic. Recipe + amountMul are restored
                // alongside in case the chunk's OreData was wiped.
                float perChunk = dep.amountMulFor(cc, edgeMul);
                CoedepositsPicker.applyToOreData(lc, chunkRecipe, perChunk);
                lc.setUnsaved(true);
                chunksRefilled++;
            }
        }

        // ── Phase 4: chat summary ───────────────────────────────────────────
        final int refF = chunksRefilled, skipF = chunksSkipped, depF = targets.size();
        src.sendSuccess(() -> Component.literal(String.format(
                "refilled %d chunks across %d deposits (skipped %d unloaded/missing)",
                refF, depF, skipF)).withStyle(net.minecraft.ChatFormatting.GREEN), false);
        return chunksRefilled;
    }

    /** Print deposit name, type, core, chunk count and bounding box for the player's current chunk. */
    private static int cmdHere(CommandContext<CommandSourceStack> ctx) {
        // ── Phase 1: caller validation + lookup ─────────────────────────────
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        ServerLevel lvl = p.serverLevel();
        ChunkPos cp = new ChunkPos(p.blockPosition());
        Deposit d = DepositSavedData.get(lvl).lookup(cp);
        if (d == null) {
            // sendSuccess (not sendFailure) — "no deposit" is information, not
            // a command error. Players use this to confirm they ARE in clear
            // ground after a delete operation.
            src.sendSuccess(() -> Component.literal(
                    "chunk " + cp.x + "," + cp.z + " — NOT in a known deposit"), false);
            return 0;
        }

        // ── Phase 2: compute bounding box of all blob chunks ────────────────
        // O(chunks) min/max sweep — cheap, blobs are at most ~40 chunks.
        // Used purely for the human-readable size + box-corner display below.
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ChunkPos c : d.chunks()) {
            if (c.x < minX) minX = c.x; if (c.x > maxX) maxX = c.x;
            if (c.z < minZ) minZ = c.z; if (c.z > maxZ) maxZ = c.z;
        }
        int widthBlocks = (maxX - minX + 1) * 16;
        int depthBlocks = (maxZ - minZ + 1) * 16;
        // Lambda capture demands effectively-final locals.
        final int fMinX = minX, fMaxX = maxX, fMinZ = minZ, fMaxZ = maxZ;
        final int fW = widthBlocks, fD = depthBlocks;

        // ── Phase 3: emit three info lines to the invoker ───────────────────
        // Block coords (core.x * 16, etc.) rather than chunk coords because
        // players think in blocks. "+15" on the box bottom-right turns chunk
        // coords into the inclusive block bound.
        src.sendSuccess(() -> Component.literal(String.format(
                "deposit: %s", d.name())), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  type=%s | core=%d,%d | %d chunks | amountMul=%.2f | tier=%.2f",
                d.typeId(), d.core().x * 16, d.core().z * 16,
                d.chunks().size(), d.amountMul(), d.tierFraction())), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  bounding box: %dx%d blocks | from %d,%d to %d,%d",
                fW, fD, fMinX * 16, fMinZ * 16, fMaxX * 16 + 15, fMaxZ * 16 + 15)), false);
        return 1;
    }

    /** Top-10 nearest deposits by core distance to the player. */
    private static int cmdList(CommandContext<CommandSourceStack> ctx) {
        // ── Phase 1: caller validation ──────────────────────────────────────
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        ServerLevel lvl = p.serverLevel();
        var store = DepositSavedData.get(lvl);
        var pos = p.blockPosition();

        // ── Phase 2: sort by squared distance, take top 10 ──────────────────
        // chunkDistSq is the squared-distance comparator (cheaper than sqrt
        // per element). limit(10) caps the output so a world with thousands
        // of deposits doesn't spam chat.
        List<Deposit> sorted = store.all().values().stream()
                .sorted(Comparator.comparingDouble(d -> chunkDistSq(d.core(), pos)))
                .limit(10)
                .toList();

        if (sorted.isEmpty()) {
            src.sendSuccess(() -> Component.literal("no deposits recorded yet"), false);
            return 0;
        }

        // ── Phase 3: emit header + one line per deposit ─────────────────────
        // sqrt only in the display path — we used squared distance for the
        // sort to avoid N square roots, here we want the human-readable value.
        src.sendSuccess(() -> Component.literal("nearest deposits:"), false);
        for (Deposit d : sorted) {
            double dist = Math.sqrt(chunkDistSq(d.core(), pos));
            src.sendSuccess(() -> Component.literal(String.format(
                    "  %s | core=%d,%d | %d chunks | tier=%.2f | dist=%.0f",
                    d.name(), d.core().x * 16, d.core().z * 16,
                    d.chunks().size(), d.tierFraction(), dist)), false);
        }
        return sorted.size();
    }

    /**
     * Roster of deposit <b>types</b> in the current dimension — declared (managed
     * / coe) plus auto-<b>adopted</b> foreign COE veins — with how many of each
     * are placed. This is the audit view for "what did auto_adopt_coe_veins pull
     * onto my map": adopted entries are COE veins from add-ons / datapacks that
     * have no deposit_type of their own. (Per-dimension, like {@code list}/{@code here}.)
     */
    private static int cmdTypes(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        ServerLevel lvl = p.serverLevel();
        ResourceLocation dim = lvl.dimension().location();
        DepositSavedData store = DepositSavedData.get(lvl);

        // Count placed deposits per type id in this dimension. Ordered by id for a
        // stable, scannable roster.
        java.util.Comparator<ResourceLocation> byId = java.util.Comparator.comparing(ResourceLocation::toString);
        java.util.Map<ResourceLocation, Integer> counts = new java.util.TreeMap<>(byId);
        for (Deposit d : store.all().values()) {
            counts.merge(d.typeId(), 1, Integer::sum);
        }
        // Union placed-type ids with declared types so configured-but-unplaced
        // types still appear. A type is "adopted" when it's placed (or resolvable)
        // but NOT in the declared registry.
        var declared = Coedeposits.DEPOSIT_TYPES.all().keySet();
        java.util.Set<ResourceLocation> all = new java.util.TreeSet<>(byId);
        all.addAll(counts.keySet());  // anything placed in this dim is always relevant
        // Declared types: only those eligible in THIS dimension — don't list
        // nether-only ores under the overworld, etc.
        for (ResourceLocation id : declared) {
            DepositType dt = Coedeposits.DEPOSIT_TYPES.get(id);
            if (dt != null && dt.matchesDimension(dim)) all.add(id);
        }

        if (all.isEmpty()) {
            src.sendSuccess(() -> Component.literal("no deposit types loaded"), false);
            return 0;
        }

        int declaredCount = 0, adoptedCount = 0, placedTotal = 0;
        for (int v : counts.values()) placedTotal += v;
        for (ResourceLocation id : all) {
            if (declared.contains(id)) declaredCount++; else adoptedCount++;
        }

        final int dc = declaredCount, ac = adoptedCount, pt = placedTotal;
        src.sendSuccess(() -> Component.literal(String.format(
                "deposit types in %s — %d declared, %d adopted | %d placed",
                dim, dc, ac, pt)).withStyle(net.minecraft.ChatFormatting.AQUA), false);

        int shown = 0;
        final int limit = 40;
        for (ResourceLocation id : all) {
            if (shown >= limit) {
                final int more = all.size() - shown;
                src.sendSuccess(() -> Component.literal("  …and " + more + " more (raise/lower density or check the map)")
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY), false);
                break;
            }
            boolean isDeclared = declared.contains(id);
            DepositType t = Coedeposits.DEPOSIT_TYPES.get(id);
            String tag = isDeclared
                    ? (t != null ? t.placement().getSerializedName() : "?")
                    : "adopted";
            int n = counts.getOrDefault(id, 0);
            net.minecraft.ChatFormatting color = isDeclared
                    ? net.minecraft.ChatFormatting.GRAY
                    : net.minecraft.ChatFormatting.GOLD;
            final String flabel = shortTypeLabel(id);
            final String ftag = tag;
            final int fn = n;
            src.sendSuccess(() -> Component.literal(String.format(
                    "  %s  [%s]  %d placed", flabel, ftag, fn)).withStyle(color), false);
            shown++;
        }
        if (adoptedCount > 0) {
            src.sendSuccess(() -> Component.literal(
                    "  adopted = COE veins from other mods/datapacks, shown on the world map")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY), false);
        }
        return all.size();
    }

    /**
     * Compact display label for a deposit type in the {@code types} roster:
     * keeps the namespace (the source mod — the point of the audit) but strips
     * COE's verbose {@code ore_vein_type/} folder and trailing {@code _vein} so
     * lines don't wrap. {@code createoreexcavation:ore_vein_type/coal} →
     * {@code createoreexcavation:coal}; {@code kubejs:test_adopt_vein} →
     * {@code kubejs:test_adopt}.
     */
    private static String shortTypeLabel(ResourceLocation id) {
        String path = id.getPath();
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        if (path.endsWith("_vein")) path = path.substring(0, path.length() - "_vein".length());
        return id.getNamespace() + ":" + path;
    }

    /** XZ-plane squared distance from a chunk centre to a block pos; used for sort comparator. */
    private static double chunkDistSq(ChunkPos cp, BlockPos pos) {
        double dx = cp.getMiddleBlockX() - pos.getX();
        double dz = cp.getMiddleBlockZ() - pos.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Re-run the prospect scan around the player's current chunk in the
     * configured {@link Config#PROSPECT_RADIUS}. Useful after editing
     * {@code deposits.json} and running {@code /reload} — the loader picks up
     * the new types, but only chunks loaded *after* the reload would
     * normally see them. This forces a dry-run sweep so new types appear on
     * the world map without players having to walk new ground.
     *
     * <p>Refuses on dimensions not in {@link Config#ENABLED_DIMENSIONS} —
     * scanning there would do nothing because the picker is dimension-gated.
     */
    private static int cmdScan(CommandContext<CommandSourceStack> ctx) {
        // ── Phase 1: caller validation + sanity gates ───────────────────────
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        ServerLevel lvl = p.serverLevel();
        ResourceLocation dim = lvl.dimension().location();
        // Refuse on dimensions the picker doesn't manage — the scan would
        // dry-run successfully but its placements would be ignored by the
        // chunk-load path. Better to tell the admin upfront.
        if (!Config.isDimensionEnabled(dim)) {
            src.sendFailure(Component.literal(
                    "dimension " + dim + " is not in enabled_dimensions"));
            return 0;
        }
        int radius = Config.PROSPECT_RADIUS.get();
        if (radius <= 0) {
            src.sendFailure(Component.literal(
                    "prospect_radius is 0 in coedeposits-common.toml — nothing to do"));
            return 0;
        }

        // ── Phase 2: run a synchronous sweep and time it ────────────────────
        // Stays synchronous (not the async queue path) because the admin
        // expects an immediate "placed X" number in chat — async would lie.
        // The freeze is acceptable for a manual admin command.
        BlockPos pos = p.blockPosition();
        int before = DepositSavedData.get(lvl).all().size();
        long t0 = System.currentTimeMillis();
        ProspectScanner.scanAround(lvl, pos, radius);
        long elapsed = System.currentTimeMillis() - t0;
        int placed = DepositSavedData.get(lvl).all().size() - before;

        // ── Phase 3: chat report ────────────────────────────────────────────
        final int fPlaced = placed;
        final long fElapsed = elapsed;
        src.sendSuccess(() -> Component.literal(String.format(
                "scan: placed %d new deposits in %d-block radius (%d ms)",
                fPlaced, radius, fElapsed))
                .withStyle(net.minecraft.ChatFormatting.GREEN), false);
        return 1;
    }

    /**
     * Delete the entire deposit owning the player's current chunk —
     * removes every {@link ChunkPos} from {@link DepositSavedData}, clears
     * OreData on all loaded member chunks, and broadcasts a removal packet
     * so the world-map overlay drops it immediately.
     */
    private static int cmdDeleteHere(CommandContext<CommandSourceStack> ctx) {
        // ── Phase 1: caller validation + locate the deposit ─────────────────
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        ServerLevel lvl = p.serverLevel();
        ChunkPos cp = new ChunkPos(p.blockPosition());
        DepositSavedData store = DepositSavedData.get(lvl);
        Deposit dep = store.lookup(cp);
        if (dep == null) {
            src.sendFailure(Component.literal(
                    "chunk " + cp.x + "," + cp.z + " is not part of a known deposit"));
            return 0;
        }
        int chunks = dep.chunks().size();

        // ── Phase 2: wipe OreData on loaded blob chunks, drop SavedData ─────
        // Order matters: clear OreData FIRST so a chunk that gets re-walked
        // between the two operations doesn't get a stale vein from the picker
        // (the picker checks SavedData before placing). Unloaded chunks keep
        // their on-disk OreData; the picker re-runs on next load and finds no
        // SavedData entry, so it either rolls a new deposit or leaves blank.
        int cleared = clearDepositOreData(lvl, dep);
        store.remove(dep.id());

        // ── Phase 3: broadcast removal + chat confirmation ──────────────────
        // Sends a DepositRemovePayload to every player on this dimension so
        // their world-map overlay drops the marker immediately.
        CoedepositsNetwork.broadcastRemoval(lvl, List.of(dep.id()));
        final String name = dep.name();
        src.sendSuccess(() -> Component.literal(String.format(
                "deleted deposit %s (%d chunks, OreData cleared on %d loaded)",
                name, chunks, cleared))
                .withStyle(net.minecraft.ChatFormatting.YELLOW), false);
        return 1;
    }

    /**
     * Delete a single chunk from the deposit it belongs to. If this was the
     * last chunk, the deposit itself is removed. Useful for hand-trimming
     * a deposit that grew into territory you'd rather keep clean.
     */
    private static int cmdDeleteChunk(CommandContext<CommandSourceStack> ctx) {
        // ── Phase 1: validate caller + find the owning deposit ──────────────
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        ServerLevel lvl = p.serverLevel();
        ChunkPos cp = new ChunkPos(p.blockPosition());
        DepositSavedData store = DepositSavedData.get(lvl);
        Deposit dep = store.lookup(cp);
        if (dep == null) {
            src.sendFailure(Component.literal(
                    "chunk " + cp.x + "," + cp.z + " is not part of a known deposit"));
            return 0;
        }

        // ── Phase 2: clear OreData on the leaving chunk ─────────────────────
        // Must happen before we mutate SavedData — if the player walks out and
        // back in after the SavedData drop, the picker would otherwise re-roll
        // and possibly assign a new vein. With OreData cleared, COE sees a
        // blank chunk and behaves like the chunk was never visited.
        clearChunkOreData(lvl, cp);

        // ── Phase 3a: last-chunk branch — drop the whole deposit ────────────
        // Build the new chunk set first to decide which path to take.
        java.util.Set<ChunkPos> remaining = new java.util.HashSet<>(dep.chunks());
        remaining.remove(cp);
        if (remaining.isEmpty()) {
            store.remove(dep.id());
            // Removal packet drops the marker on every client's overlay.
            CoedepositsNetwork.broadcastRemoval(lvl, List.of(dep.id()));
            final String name = dep.name();
            src.sendSuccess(() -> Component.literal(String.format(
                    "deleted last chunk of deposit %s — deposit removed entirely", name))
                    .withStyle(net.minecraft.ChatFormatting.YELLOW), false);
            return 1;
        }

        // ── Phase 3b: shrink branch — rebuild the record with fewer chunks ──
        // Same id, same metadata; only the chunk set changes. broadcastSync-
        // Filtered pushes the new snapshot to clients so the overlay updates
        // its footprint immediately rather than waiting for the periodic sync.
        Deposit replaced = new Deposit(
                dep.id(), dep.typeId(), dep.name(), dep.core(),
                remaining, dep.amountMul(), dep.tierFraction(), dep.placement(),
                dep.replenishRateOverride());  // preserve per-deposit replenish override
        store.replace(replaced);
        CoedepositsNetwork.broadcastSyncFiltered(lvl.getServer(), lvl, List.of(replaced));
        final String name = dep.name();
        final int rem = remaining.size();
        src.sendSuccess(() -> Component.literal(String.format(
                "removed chunk %d,%d from %s — %d chunks remain",
                cp.x, cp.z, name, rem))
                .withStyle(net.minecraft.ChatFormatting.YELLOW), false);
        return 1;
    }

    /**
     * Wipe the current dimension and re-prospect around the player with the
     * current (or freshly-overridden) deposit seed. Pipeline:
     * <ol>
     *   <li>Optionally set a new {@code depositSeed} override in SavedData</li>
     *   <li>Clear OreData on every loaded chunk of every existing deposit</li>
     *   <li>{@link DepositSavedData#removeAll()} + broadcast removal packet</li>
     *   <li>{@link ProspectScanner#scanAround} at the player's position with
     *       {@link Config#PROSPECT_RADIUS}; the scanner itself now applies
     *       OreData to loaded chunks of freshly-placed deposits</li>
     * </ol>
     *
     * <p>Passing a seed makes generation reproducible across worlds — same
     * {@code deposits.json}, same seed, similar biome layout → same pattern.
     *
     * @param newSeed  optional seed override (null = keep current)
     */
    private static int cmdRegenerate(CommandContext<CommandSourceStack> ctx, Long newSeed) {
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        ServerLevel lvl = p.serverLevel();
        ResourceLocation dim = lvl.dimension().location();
        if (!Config.isDimensionEnabled(dim)) {
            src.sendFailure(Component.literal(
                    "dimension " + dim + " is not in enabled_dimensions"));
            return 0;
        }
        int radius = Config.PROSPECT_RADIUS.get();
        if (radius <= 0) {
            src.sendFailure(Component.literal(
                    "prospect_radius is 0 in coedeposits-common.toml — nothing to regenerate"));
            return 0;
        }
        DepositSavedData store = DepositSavedData.get(lvl);

        if (newSeed != null) store.setDepositSeed(newSeed);
        long effectiveSeed = store.effectiveSeed(lvl);

        // Phase 0: cancel any in-flight async scan for this level so a worker
        // batch in progress doesn't materialize stale placements (built against
        // the pre-regenerate snapshot — possibly old seed, definitely old
        // SavedData) into the freshly-wiped state below.
        ProspectScanQueue.INSTANCE.clear(lvl);

        // Phase 1: clear OreData on every loaded chunk of every existing deposit.
        int wipedDeposits = store.all().size();
        int chunksCleared = 0;
        for (Deposit dep : store.all().values()) {
            chunksCleared += clearDepositOreData(lvl, dep);
        }
        java.util.List<java.util.UUID> wipedIds = store.removeAll();
        CoedepositsNetwork.broadcastRemoval(lvl, wipedIds);

        // Phase 2: re-prospect around the player. ProspectScanner applies OreData
        // to loaded chunks of newly-placed deposits so the result is visible
        // immediately without waiting for chunk reload.
        int sizeBefore = 0;  // store was just wiped
        long t0 = System.currentTimeMillis();
        ProspectScanner.scanAround(lvl, p.blockPosition(), radius);
        long elapsed = System.currentTimeMillis() - t0;
        int placed = DepositSavedData.get(lvl).all().size() - sizeBefore;

        // Re-broadcast the freshly-placed snapshots so the client map fills in
        // without waiting for the periodic sync tick.
        if (placed > 0) {
            CoedepositsNetwork.broadcastSyncFiltered(lvl.getServer(), lvl,
                    DepositSavedData.get(lvl).all().values());
        }

        final int wD = wipedDeposits;
        final int cC = chunksCleared;
        final int pl = placed;
        final long el = elapsed;
        src.sendSuccess(() -> Component.literal(String.format(
                "regenerated: wiped %d deposits (%d loaded chunks cleared), placed %d new in %d-block radius (%d ms)",
                wD, cC, pl, radius, el))
                .withStyle(net.minecraft.ChatFormatting.GOLD), false);
        if (newSeed != null) {
            src.sendSuccess(() -> Component.literal(String.format(
                    "  deposit seed is now %d (was %s)",
                    effectiveSeed,
                    store.depositSeed().isPresent() ? "set to " + effectiveSeed : "world seed"))
                    .withStyle(net.minecraft.ChatFormatting.AQUA), false);
        }
        return placed;
    }

    /**
     * Set the per-deposit replenishment override. The deposit's effective
     * rate is {@code replenishRateOverride > 0 ? override : type.replenishRatePerHour}.
     * Passing {@code rate=0} clears the override (deposit reverts to type default).
     *
     * @param rate  units/hour to restore (0 disables override)
     * @param all   when true, apply to every deposit in the current dimension;
     *               otherwise only the deposit owning the player's current chunk
     */
    private static int cmdReplenish(CommandContext<CommandSourceStack> ctx, double rate, boolean all) {
        // ── Phase 1: caller validation ──────────────────────────────────────
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        ServerLevel lvl = p.serverLevel();
        ResourceLocation dim = lvl.dimension().location();
        if (!Config.isDimensionEnabled(dim)) {
            src.sendFailure(Component.literal(
                    "dimension " + dim + " is not in enabled_dimensions"));
            return 0;
        }
        DepositSavedData store = DepositSavedData.get(lvl);

        // ── Phase 2: collect targets ────────────────────────────────────────
        // all-mode walks the whole dim's deposits. Single-mode looks up the
        // deposit owning the player's current chunk (errors if no deposit).
        java.util.Collection<Deposit> targets;
        if (all) {
            // Defensive copy — we'll be calling store.replace() in the loop
            // which mutates the underlying map.
            targets = new java.util.ArrayList<>(store.all().values());
        } else {
            ChunkPos cp = new ChunkPos(p.blockPosition());
            Deposit d = store.lookup(cp);
            if (d == null) {
                src.sendFailure(Component.literal(
                        "not in a known deposit — stand on one, or use /coedeposits replenish all <rate>"));
                return 0;
            }
            targets = java.util.List.of(d);
        }
        if (targets.isEmpty()) {
            src.sendSuccess(() -> Component.literal("no deposits to update"), false);
            return 0;
        }

        // ── Phase 3: replace each deposit with an updated record ────────────
        // Records are immutable — replenishRateOverride is set by building a
        // new Deposit with the same id and feeding it through store.replace().
        // Preserves the chunkIndex thanks to the id-equality path inside
        // SavedData.replace.
        int updated = 0;
        for (Deposit d : targets) {
            // Skip no-op replacements — useful when /replenish all is called
            // and most deposits already have the same rate.
            if (Math.abs(d.replenishRateOverride() - rate) < 1e-9) continue;
            Deposit updatedDep = new Deposit(
                    d.id(), d.typeId(), d.name(), d.core(),
                    d.chunks(), d.amountMul(), d.tierFraction(), d.placement(),
                    rate);
            store.replace(updatedDep);
            updated++;
        }

        // ── Phase 4: chat summary ───────────────────────────────────────────
        final int upd = updated;
        final int total = targets.size();
        String scope = all ? "all deposits" : "current deposit";
        if (rate <= 0.0) {
            src.sendSuccess(() -> Component.literal(String.format(
                    "cleared replenish override on %d of %d %s — type defaults now in effect",
                    upd, total, scope))
                    .withStyle(net.minecraft.ChatFormatting.GOLD), false);
        } else {
            src.sendSuccess(() -> Component.literal(String.format(
                    "set replenish to %.1f units/hour on %d of %d %s",
                    rate, upd, total, scope))
                    .withStyle(net.minecraft.ChatFormatting.GREEN), false);
        }
        return updated;
    }

    /**
     * Print the active deposit seed for the player's current dimension and
     * whether it's an override or the inherited world seed. Useful when
     * sharing a pattern with another world via
     * {@code /coedeposits regenerate <seed>}.
     */
    private static int cmdSeed(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        // Two pieces of info: the effective seed value (override or world)
        // and its origin so admin knows whether /regenerate ever set it.
        ServerLevel lvl = p.serverLevel();
        DepositSavedData store = DepositSavedData.get(lvl);
        long effective = store.effectiveSeed(lvl);
        String origin = store.depositSeed().isPresent() ? "override" : "world seed (no override)";
        src.sendSuccess(() -> Component.literal(String.format(
                "deposit seed: %d (%s) | dim: %s",
                effective, origin, lvl.dimension().location()))
                .withStyle(net.minecraft.ChatFormatting.AQUA), false);
        // Second line is a usage hint — discoverability for the copy-pattern
        // workflow. Dark gray so it reads as documentation, not data.
        src.sendSuccess(() -> Component.literal(
                "  /coedeposits regenerate <seed> to copy a pattern from another world")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    /**
     * Clear OreData on every loaded chunk of a deposit. Unloaded chunks are
     * skipped — their on-disk OreData stays as it was; players will see
     * COE's "no vein" state once they walk in (our picker no longer claims
     * the chunk because SavedData was wiped).
     *
     * @return  number of chunks where OreData was actually cleared
     */
    private static int clearDepositOreData(ServerLevel lvl, Deposit dep) {
        int cleared = 0;
        for (ChunkPos cp : dep.chunks()) {
            if (clearChunkOreData(lvl, cp)) cleared++;
        }
        return cleared;
    }

    /** Set the chunk's OreData to (recipe=null, randomMul=0). Returns true if the chunk was loaded. */
    private static boolean clearChunkOreData(ServerLevel lvl, ChunkPos cp) {
        // load=false — we only clear chunks that are already in memory.
        // Forcing a load just to clear would burn IO and possibly trigger
        // the picker as part of population, which we explicitly don't want
        // here (deletion semantics).
        LevelChunk chunk = lvl.getChunkSource().getChunk(cp.x, cp.z, false);
        if (chunk == null) return false;
        // getData (NOT the reflected applyToOreData path) is safe here because
        // we're outside the picker callstack — no recursion risk.
        OreData od = OreDataAttachment.getData(chunk);
        od.setRecipe(null);
        od.setRandomMul(0f);
        od.setExtractedAmount(0);
        // Mark dirty so the cleared state survives the next world save.
        chunk.setUnsaved(true);
        return true;
    }

    /** Print distance-from-spawn and tier-fraction at the player's current position. */
    private static int cmdTier(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        // Diagnostic command — runs the same tier formula the picker uses so
        // admins tuning base_radius / max_radius can see exactly what the
        // current spot resolves to. Both distance and tier on one line for
        // quick scanning while running across the world.
        ServerLevel lvl = p.serverLevel();
        var spawn = lvl.getSharedSpawnPos();
        float tier = DistanceGradient.tierFraction(
                p.blockPosition(), spawn,
                Config.BASE_RADIUS.get().floatValue(),
                Config.MAX_RADIUS.get().floatValue());
        double dist = DistanceGradient.distance(p.blockPosition(), spawn);
        src.sendSuccess(() -> Component.literal(
                String.format("dist=%.0f tier=%.3f", dist, tier)), false);
        return 1;
    }

}
