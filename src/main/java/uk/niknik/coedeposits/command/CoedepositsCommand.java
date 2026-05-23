package uk.niknik.coedeposits.command;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
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
        dispatcher.register(Commands.literal("coedeposits")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("tier").executes(CoedepositsCommand::cmdTier))
                .then(Commands.literal("list").executes(CoedepositsCommand::cmdList))
                .then(Commands.literal("here").executes(CoedepositsCommand::cmdHere))
                .then(Commands.literal("scan").executes(CoedepositsCommand::cmdScan))
                .then(Commands.literal("refill")
                        .executes(c -> cmdRefill(c, false))
                        .then(Commands.literal("all").executes(c -> cmdRefill(c, true))))
                .then(Commands.literal("delete")
                        .then(Commands.literal("here").executes(CoedepositsCommand::cmdDeleteHere))
                        .then(Commands.literal("chunk").executes(CoedepositsCommand::cmdDeleteChunk)))
                .then(Commands.literal("regenerate")
                        .executes(c -> cmdRegenerate(c, null))
                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                .executes(c -> cmdRegenerate(c, LongArgumentType.getLong(c, "seed")))))
                .then(Commands.literal("seed").executes(CoedepositsCommand::cmdSeed))
                .then(Commands.literal("place")
                        .executes(c -> doPlace(c, null, null, 0, 0))
                        .then(Commands.argument("type", ResourceLocationArgument.id())
                                .suggests((c, b) -> SharedSuggestionProvider.suggestResource(
                                        Coedeposits.DEPOSIT_TYPES.all().keySet(), b))
                                .executes(c -> doPlace(c,
                                        ResourceLocationArgument.getId(c, "type"), null, 0, 0))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(c -> doPlace(c,
                                                ResourceLocationArgument.getId(c, "type"),
                                                BlockPosArgument.getBlockPos(c, "pos"), 0, 0))
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(Integer.MIN_VALUE))
                                                .executes(c -> doPlace(c,
                                                        ResourceLocationArgument.getId(c, "type"),
                                                        BlockPosArgument.getBlockPos(c, "pos"),
                                                        IntegerArgumentType.getInteger(c, "amount"),
                                                        0))
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

        boolean infinite = totalAmount < 0;
        boolean amountGiven = totalAmount != 0;

        // Initial placement — tier-based size/amountMul as starting point.
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
                    1.0f,
                    lvl.getBiome(new BlockPos(cp.getMiddleBlockX(), 64, cp.getMiddleBlockZ())),
                    lvl.dimension().location());
            if (placed == null) {
                src.sendFailure(Component.literal(
                        "no eligible deposit type for this position (check distance.min/max)"));
                return 0;
            }
        }

        // Decide which recipe to apply — finite or infinite — and resolve it.
        DepositType type = Coedeposits.DEPOSIT_TYPES.get(placed.typeId());
        ResourceLocation recipeId;
        if (infinite) {
            var maybeInf = type.veinRecipeInfinite();
            if (maybeInf.isEmpty()) {
                src.sendFailure(Component.literal(
                        "DepositType " + placed.typeId() + " has no vein_recipe_infinite — "
                        + "add it to the deposit_type JSON to allow infinite veins"));
                return 0;
            }
            recipeId = maybeInf.get();
        } else {
            recipeId = placed.type().veinRecipe();
        }
        VeinRecipe vr = resolveVein(lvl, recipeId);
        if (vr == null) {
            src.sendFailure(Component.literal(
                    "vein_recipe " + recipeId + " not loaded — check data/<ns>/recipe/"));
            return 0;
        }

        // Resize chunks if user provided amount and/or chunks override.
        // Defaults: amount-given → 5 chunks; chunks override always wins.
        int targetChunks = placed.chunks().size();
        if (chunksOverride > 0) targetChunks = chunksOverride;
        else if (amountGiven)  targetChunks = 5;

        if (targetChunks != placed.chunks().size()) {
            placed = DepositPlacer.forceWith(cp, DepositSavedData.get(lvl).effectiveSeed(lvl), placed.typeId(),
                    type, placed.tierFraction(), targetChunks);
        }

        // amountMul — for finite: derive from totalAmount or from type's per_chunk_units.
        // For infinite: irrelevant (formula bypassed) — use 1.0 for tooltip aesthetics.
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
            // Use the deposit_type's per_chunk_units budget for natural-style amount.
            double target = type.perChunkUnits().computeTarget(
                    placed.tierFraction(),
                    Config.UNBOUNDED_GROWTH.get());
            amountMul = DepositPlacer.amountMulForTarget(
                    target, vr.getMinAmount(), vr.getMaxAmount(), base);
        }

        final float finalAmountMul = amountMul;
        final ResourceLocation finalRecipeId = recipeId;
        Deposit dep = new Deposit(
                UUID.randomUUID(),
                placed.typeId(),
                placed.typeId().getPath() + "@" + (cp.x * 16) + "," + (cp.z * 16),
                cp,
                placed.chunks(),
                finalAmountMul,
                placed.tierFraction(),
                uk.niknik.coedeposits.deposit.DepositType.Placement.MANAGED);
        store.add(dep);

        int materialized = 0;
        float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();
        for (ChunkPos cc : placed.chunks()) {
            LevelChunk lc = lvl.getChunkSource().getChunk(cc.x, cc.z, false);
            if (lc != null) {
                float perChunkMul = infinite
                        ? finalAmountMul  // recipe.finite=never bypasses the formula anyway
                        : dep.amountMulFor(cc, edgeMul);
                CoedepositsPicker.applyToOreData(lc, finalRecipeId, perChunkMul);
                lc.setUnsaved(true);
                materialized++;
            }
        }
        CoedepositsPicker.broadcastDiscovery(lvl, dep);

        // Chat summary.
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
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        ServerLevel lvl = p.serverLevel();
        var store = DepositSavedData.get(lvl);
        float edgeMul = Config.EDGE_AMOUNT_MUL.get().floatValue();

        java.util.Collection<Deposit> targets;
        if (all) {
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

        int chunksRefilled = 0;
        int chunksSkipped = 0;
        for (Deposit dep : targets) {
            var type = Coedeposits.DEPOSIT_TYPES.get(dep.typeId());
            if (type == null) { chunksSkipped += dep.chunks().size(); continue; }
            for (ChunkPos cc : dep.chunks()) {
                LevelChunk lc = lvl.getChunkSource().getChunk(cc.x, cc.z, false);
                if (lc == null) { chunksSkipped++; continue; }
                float perChunk = dep.amountMulFor(cc, edgeMul);
                CoedepositsPicker.applyToOreData(lc, type.veinRecipe(), perChunk);
                lc.setUnsaved(true);
                chunksRefilled++;
            }
        }

        final int refF = chunksRefilled, skipF = chunksSkipped, depF = targets.size();
        src.sendSuccess(() -> Component.literal(String.format(
                "refilled %d chunks across %d deposits (skipped %d unloaded/missing)",
                refF, depF, skipF)).withStyle(net.minecraft.ChatFormatting.GREEN), false);
        return chunksRefilled;
    }

    /** Print deposit name, type, core, chunk count and bounding box for the player's current chunk. */
    private static int cmdHere(CommandContext<CommandSourceStack> ctx) {
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
            src.sendSuccess(() -> Component.literal(
                    "chunk " + cp.x + "," + cp.z + " — NOT in a known deposit"), false);
            return 0;
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ChunkPos c : d.chunks()) {
            if (c.x < minX) minX = c.x; if (c.x > maxX) maxX = c.x;
            if (c.z < minZ) minZ = c.z; if (c.z > maxZ) maxZ = c.z;
        }
        int widthBlocks = (maxX - minX + 1) * 16;
        int depthBlocks = (maxZ - minZ + 1) * 16;
        final int fMinX = minX, fMaxX = maxX, fMinZ = minZ, fMaxZ = maxZ;
        final int fW = widthBlocks, fD = depthBlocks;

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
        var src = ctx.getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("must be a player"));
            return 0;
        }
        ServerLevel lvl = p.serverLevel();
        var store = DepositSavedData.get(lvl);
        var pos = p.blockPosition();

        List<Deposit> sorted = store.all().values().stream()
                .sorted(Comparator.comparingDouble(d -> chunkDistSq(d.core(), pos)))
                .limit(10)
                .toList();

        if (sorted.isEmpty()) {
            src.sendSuccess(() -> Component.literal("no deposits recorded yet"), false);
            return 0;
        }
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
                    "prospect_radius is 0 in coedeposits-common.toml — nothing to do"));
            return 0;
        }
        BlockPos pos = p.blockPosition();
        int before = DepositSavedData.get(lvl).all().size();
        long t0 = System.currentTimeMillis();
        ProspectScanner.scanAround(lvl, pos, radius);
        long elapsed = System.currentTimeMillis() - t0;
        int placed = DepositSavedData.get(lvl).all().size() - before;
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
        int cleared = clearDepositOreData(lvl, dep);
        store.remove(dep.id());
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

        clearChunkOreData(lvl, cp);

        java.util.Set<ChunkPos> remaining = new java.util.HashSet<>(dep.chunks());
        remaining.remove(cp);
        if (remaining.isEmpty()) {
            // Last chunk — drop the deposit entirely + broadcast removal.
            store.remove(dep.id());
            CoedepositsNetwork.broadcastRemoval(lvl, List.of(dep.id()));
            final String name = dep.name();
            src.sendSuccess(() -> Component.literal(String.format(
                    "deleted last chunk of deposit %s — deposit removed entirely", name))
                    .withStyle(net.minecraft.ChatFormatting.YELLOW), false);
            return 1;
        }

        // Shrink in place — rebuild the record with the smaller chunk set and
        // re-sync that single deposit so clients see the updated footprint.
        Deposit replaced = new Deposit(
                dep.id(), dep.typeId(), dep.name(), dep.core(),
                remaining, dep.amountMul(), dep.tierFraction(), dep.placement());
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
        ServerLevel lvl = p.serverLevel();
        DepositSavedData store = DepositSavedData.get(lvl);
        long effective = store.effectiveSeed(lvl);
        String origin = store.depositSeed().isPresent() ? "override" : "world seed (no override)";
        src.sendSuccess(() -> Component.literal(String.format(
                "deposit seed: %d (%s) | dim: %s",
                effective, origin, lvl.dimension().location()))
                .withStyle(net.minecraft.ChatFormatting.AQUA), false);
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
        LevelChunk chunk = lvl.getChunkSource().getChunk(cp.x, cp.z, false);
        if (chunk == null) return false;
        OreData od = OreDataAttachment.getData(chunk);
        od.setRecipe(null);
        od.setRandomMul(0f);
        od.setExtractedAmount(0);
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
