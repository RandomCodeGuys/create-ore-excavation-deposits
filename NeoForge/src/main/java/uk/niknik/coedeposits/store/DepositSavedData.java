package uk.niknik.coedeposits.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import uk.niknik.coedeposits.Coedeposits;
import uk.niknik.coedeposits.deposit.Deposit;

/**
 * Per-level persistent registry of placed deposits. Backed by NeoForge's
 * {@link SavedData} so the data lives in
 * {@code <world>/data/coedeposits_deposits.dat} alongside vanilla world data.
 *
 * <p>Maintains:
 * <ul>
 *   <li>{@code deposits}: UUID → {@link Deposit} primary store</li>
 *   <li>{@code chunkIndex}: packed-long ChunkPos → owning deposit UUID; used
 *       for O(1) "is this chunk in some deposit?" queries by the picker</li>
 *   <li>{@code revealed}: per-player set of deposit UUIDs that player has
 *       discovered/prospected. Required for reveal modes ON_DISCOVERY and
 *       ON_PROSPECT, which gate map visibility per player</li>
 *   <li>{@code depositSeed}: optional override for the seed used in the
 *       placement RNG. Absent → use {@link ServerLevel#getSeed} (default).
 *       Set explicitly via {@code /coedeposits regenerate <seed>} so a
 *       pattern can be reproduced across worlds with the same deposits.json
 *       and biome layout.</li>
 * </ul>
 */
public class DepositSavedData extends SavedData {
    /** SavedData file name (NeoForge appends .dat). */
    public static final String NAME = "coedeposits_deposits";

    private final Map<UUID, Deposit> deposits = new HashMap<>();
    private final Map<Long, UUID> chunkIndex = new HashMap<>();
    /** Per-player set of deposit ids the player has revealed (ON_DISCOVERY / ON_PROSPECT). */
    private final Map<UUID, Set<UUID>> revealed = new HashMap<>();
    /** Optional override for the placement seed; absent means "use the world seed". */
    private Optional<Long> depositSeed = Optional.empty();

    /** Get-or-create the level's data instance. Always call on the server thread. */
    public static DepositSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(DepositSavedData::new, DepositSavedData::load, null),
                NAME);
    }

    /**
     * Wire wrapper for codec encoding — keeps the field structure stable across
     * versions. The {@code revealed} and {@code deposit_seed} blocks are
     * optional so SavedData files predating per-player tracking and the
     * seed override still load.
     */
    private record Storage(List<Deposit> all, List<RevealEntry> revealedEntries, Optional<Long> depositSeed) {
        static final Codec<Storage> CODEC = RecordCodecBuilder.create(b -> b.group(
                Codec.list(Deposit.CODEC).fieldOf("deposits").forGetter(Storage::all),
                Codec.list(RevealEntry.CODEC).optionalFieldOf("revealed", List.of())
                        .forGetter(Storage::revealedEntries),
                Codec.LONG.optionalFieldOf("deposit_seed").forGetter(Storage::depositSeed)
        ).apply(b, Storage::new));
    }

    /** Wire record for one player's revealed-deposit set. */
    private record RevealEntry(UUID player, List<UUID> deposits) {
        static final Codec<RevealEntry> CODEC = RecordCodecBuilder.create(b -> b.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(RevealEntry::player),
                Codec.list(UUIDUtil.CODEC).fieldOf("deposits").forGetter(RevealEntry::deposits)
        ).apply(b, RevealEntry::new));
    }

    /** Deserialization factory — called by NeoForge when the .dat file exists. */
    private static DepositSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        // Build a fresh instance — caller (NeoForge SavedData factory) wants
        // an instance even on parse failure (will be empty rather than null,
        // mod still functions with no deposits rather than crashing).
        DepositSavedData out = new DepositSavedData();
        Storage.CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(err -> Coedeposits.LOGGER.error(
                        "[coedeposits] failed to load SavedData: {}", err))
                .ifPresent(s -> {
                    // ── Step 1: re-index every deposit via addInternal ──────
                    // addInternal (not add) — we don't want setDirty during
                    // load, NeoForge handles the clean/dirty bit for us. Also
                    // populates chunkIndex which is what powers lookup().
                    s.all().forEach(out::addInternal);
                    // ── Step 2: rebuild the per-player reveal map ───────────
                    // HashSet copy: the codec returns lists, we want O(1)
                    // membership lookups in isRevealed/reveal.
                    for (RevealEntry e : s.revealedEntries()) {
                        out.revealed.put(e.player(), new HashSet<>(e.deposits()));
                    }
                    // ── Step 3: restore the optional deposit-seed override ──
                    out.depositSeed = s.depositSeed();
                });
        return out;
    }

    /** Codec-based serialization to NBT — called by NeoForge during world save. */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        // ── Phase 1: flatten the per-player reveal map into a list ──────────
        // Storage codec wants a list of records, not a map of UUID→Set. Skip
        // empty entries — a player who hasn't revealed anything (or had their
        // reveals wiped) shouldn't take up bytes on disk.
        List<RevealEntry> revealedFlat = new ArrayList<>(revealed.size());
        for (var e : revealed.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            revealedFlat.add(new RevealEntry(e.getKey(), new ArrayList<>(e.getValue())));
        }

        // ── Phase 2: assemble + encode the wire payload ─────────────────────
        // Defensive copy of deposits.values() — Codec.list iterates it, and
        // the picker might be mutating concurrently in pathological races
        // (chunk-load on another thread under c2me). Cheap enough to not bother
        // with a read lock.
        Storage payload = new Storage(new ArrayList<>(deposits.values()), revealedFlat, depositSeed);
        Tag encoded = Storage.CODEC.encodeStart(NbtOps.INSTANCE, payload).getOrThrow();
        // tag.merge so NeoForge's outer wrapper fields (typically nothing for
        // SavedData but defensive against future framework additions) survive.
        if (encoded instanceof CompoundTag c) {
            tag.merge(c);
        }
        return tag;
    }

    /** Find the deposit owning this chunk, or {@code null} if the chunk is not part of any. */
    public Deposit lookup(ChunkPos cp) {
        UUID id = chunkIndex.get(cp.toLong());
        return id != null ? deposits.get(id) : null;
    }

    /** O(1) check: does any deposit claim this chunk? Used by the prospect scanner for idempotency. */
    public boolean isOccupied(ChunkPos cp) {
        return chunkIndex.containsKey(cp.toLong());
    }

    /** Persist a new deposit and index all its chunks. Marks the SavedData dirty. */
    public Deposit add(Deposit d) {
        addInternal(d);
        setDirty();
        return d;
    }

    /**
     * Outcome of {@link #addResolvingOverlap}: {@code placed} is the resulting
     * deposit — a fresh one, or the same-type deposit the candidate merged into
     * ({@code null} when the candidate kept no chunks). {@code removed} are
     * deposits fully absorbed (merged away or trimmed to empty); {@code changed}
     * are deposits whose chunk set shrank or grew; {@code trimmedChunks} are
     * chunks taken from a more-common deposit by the rarer candidate (their
     * OreData should be re-applied for the new owner).
     */
    public record OverlapResult(Deposit placed, Set<UUID> removed, Set<UUID> changed, Set<ChunkPos> trimmedChunks) {}

    /**
     * Add a candidate deposit, resolving overlaps with already-placed deposits:
     * <ul>
     *   <li><b>Same type</b> — merge: the candidate's chunks join the existing
     *       same-type deposit(s) into one (keeping the first one's id, core and
     *       amountMul/reveal state).</li>
     *   <li><b>Different type</b> — the lower-weight (rarer) type wins each
     *       contested chunk. If the candidate is rarer it claims the chunk and
     *       the more-common owner is trimmed (removed if it ends up empty);
     *       otherwise the candidate yields that chunk.</li>
     * </ul>
     * Returns what changed so the caller can sync clients and re-apply OreData.
     * (COE allows one vein per chunk, so different types can't truly co-occupy a
     * chunk — the rarer just claims it, forming a mixed region.)
     */
    public OverlapResult addResolvingOverlap(Deposit candidate, ToIntFunction<ResourceLocation> weightOf) {
        int newWeight = weightOf.applyAsInt(candidate.typeId());
        Set<ChunkPos> claim = new HashSet<>();
        Set<UUID> mergeInto = new LinkedHashSet<>();
        Map<UUID, Set<ChunkPos>> trim = new HashMap<>();

        for (ChunkPos cp : candidate.chunks()) {
            UUID ownerId = chunkIndex.get(cp.toLong());
            Deposit owner = ownerId != null ? deposits.get(ownerId) : null;
            if (owner == null) {
                claim.add(cp);
            } else if (owner.typeId().equals(candidate.typeId())) {
                mergeInto.add(owner.id());                              // same type → merge
            } else if (newWeight < weightOf.applyAsInt(owner.typeId())) {
                claim.add(cp);                                          // candidate rarer → wins
                trim.computeIfAbsent(owner.id(), k -> new HashSet<>()).add(cp);
            }
            // else: the more-common candidate yields this chunk to the rarer owner.
        }

        Set<UUID> removed = new HashSet<>();
        Set<UUID> changed = new HashSet<>();
        Set<ChunkPos> trimmedChunks = new HashSet<>();

        // ── Trim the more-common owners the rarer candidate took chunks from ──
        for (Map.Entry<UUID, Set<ChunkPos>> e : trim.entrySet()) {
            Deposit owner = deposits.get(e.getKey());
            if (owner == null) continue;
            Set<ChunkPos> remaining = new HashSet<>(owner.chunks());
            remaining.removeAll(e.getValue());
            trimmedChunks.addAll(e.getValue());
            if (remaining.isEmpty()) {
                remove(owner.id());
                removed.add(owner.id());
            } else {
                replace(owner.withChunks(remaining));
                changed.add(owner.id());
            }
        }

        // ── Merge into an existing same-type deposit, or create a new one ─────
        Deposit placed;
        if (mergeInto.isEmpty()) {
            if (claim.isEmpty()) {
                return new OverlapResult(null, removed, changed, trimmedChunks);
            }
            placed = candidate.withChunks(claim);
            add(placed);
        } else {
            UUID targetId = mergeInto.iterator().next();
            Deposit target = deposits.get(targetId);
            Set<ChunkPos> merged = new HashSet<>(target.chunks());
            merged.addAll(claim);
            for (UUID otherId : mergeInto) {
                if (otherId.equals(targetId)) continue;
                Deposit other = deposits.get(otherId);
                if (other != null) {
                    merged.addAll(other.chunks());
                    remove(otherId);
                    removed.add(otherId);
                }
            }
            placed = target.withChunks(merged);
            replace(placed);
            changed.add(targetId);
        }
        return new OverlapResult(placed, removed, changed, trimmedChunks);
    }

    /** Internal add without setDirty — used by load() during deserialization. */
    private void addInternal(Deposit d) {
        deposits.put(d.id(), d);
        for (ChunkPos cp : d.chunks()) {
            chunkIndex.put(cp.toLong(), d.id());
        }
    }

    /**
     * Drop a deposit entirely — removes the entry from the primary store
     * and clears its chunkIndex entries. Returns the removed deposit so
     * the caller can iterate its chunks for OreData cleanup, or null when
     * the id was unknown.
     */
    public Deposit remove(UUID depositId) {
        // ── Step 1: remove from the primary store ───────────────────────────
        // Bail with null if the id was already gone — defensive against
        // callers that don't pre-check. Most callsites (cmdDeleteHere, etc.)
        // already looked up via id, so this rarely triggers.
        Deposit removed = deposits.remove(depositId);
        if (removed == null) return null;

        // ── Step 2: clear matching chunkIndex entries ───────────────────────
        // computeIfPresent + owner-equality is a defensive pattern: if another
        // deposit somehow grabbed one of our chunks between our store.put and
        // our store.remove (only possible via a hand-crafted replace race),
        // we leave that other deposit's index untouched rather than orphaning
        // its chunks.
        for (ChunkPos cp : removed.chunks()) {
            chunkIndex.computeIfPresent(cp.toLong(),
                    (k, owner) -> owner.equals(depositId) ? null : owner);
        }
        setDirty();
        return removed;
    }

    /**
     * Replace an existing deposit in place (same id). Cleans the previous
     * chunkIndex entries that no longer belong, then re-adds for the new
     * chunk set. Used by per-chunk deletion which shrinks {@code chunks}.
     */
    public void replace(Deposit replacement) {
        // ── Step 1: swap the primary record ─────────────────────────────────
        // Map.put returns the previous value or null. We need the previous
        // chunk set to clean stale index entries.
        Deposit prev = deposits.put(replacement.id(), replacement);

        // ── Step 2: clean stale chunkIndex entries from the old chunk set ──
        // Only entries whose owner is THIS deposit get cleared — protects
        // against the same race as remove(). If `prev` is null this is a
        // fresh insert and there's nothing to clean.
        if (prev != null) {
            for (ChunkPos cp : prev.chunks()) {
                chunkIndex.computeIfPresent(cp.toLong(),
                        (k, owner) -> owner.equals(replacement.id()) ? null : owner);
            }
        }

        // ── Step 3: re-index the new chunk set ──────────────────────────────
        // Plain put is fine here — overwrites whatever was at this chunk,
        // which is the intended semantic for "this deposit now owns this chunk".
        for (ChunkPos cp : replacement.chunks()) {
            chunkIndex.put(cp.toLong(), replacement.id());
        }
        setDirty();
    }

    /**
     * Wipe every deposit. Returns a snapshot of the removed ids so callers
     * can broadcast a removal packet + clear OreData.
     */
    public java.util.List<UUID> removeAll() {
        // Empty short-circuit — avoid the dirty-marking work entirely when
        // there's nothing to wipe. Lets callers fire this unconditionally
        // (e.g. /coedeposits regenerate at the very start of the world).
        if (deposits.isEmpty()) return java.util.List.of();
        // Snapshot ids BEFORE clearing so the caller can broadcast a removal
        // packet for these uuids (clients drop them from their cache).
        java.util.List<UUID> ids = new ArrayList<>(deposits.keySet());
        deposits.clear();
        chunkIndex.clear();
        setDirty();
        return ids;
    }

    /** Full UUID → deposit map — read-only view for iteration (e.g., refill, sync). */
    public Map<UUID, Deposit> all() {
        return deposits;
    }

    /**
     * Mark this deposit as revealed for the given player. Returns true when
     * the deposit was not already revealed (so callers can drive a one-shot
     * sync packet + chat line). Marks the SavedData dirty when state changes.
     */
    public boolean reveal(UUID playerId, UUID depositId) {
        Set<UUID> set = revealed.computeIfAbsent(playerId, k -> new HashSet<>());
        boolean added = set.add(depositId);
        if (added) setDirty();
        return added;
    }

    /** Is this deposit currently revealed for the player? */
    public boolean isRevealed(UUID playerId, UUID depositId) {
        Set<UUID> set = revealed.get(playerId);
        return set != null && set.contains(depositId);
    }

    /** Unmodifiable view of the player's revealed-deposit set (empty if never revealed anything). */
    public Set<UUID> revealedFor(UUID playerId) {
        Set<UUID> set = revealed.get(playerId);
        return set == null ? Set.of() : Collections.unmodifiableSet(set);
    }

    /**
     * Optional override of the placement seed. Absent ({@link Optional#empty()})
     * means generation uses the world seed; present means every chunk in
     * this dimension rolls its RNG against the override.
     */
    public Optional<Long> depositSeed() {
        return depositSeed;
    }

    /**
     * Resolve the effective seed: override if set, otherwise the level's
     * world seed. Use this in every callsite that feeds
     * {@link uk.niknik.coedeposits.gen.DepositPlacer#tryPick} or the
     * deterministic randomMul roll.
     */
    public long effectiveSeed(ServerLevel level) {
        return depositSeed.orElseGet(level::getSeed);
    }

    /**
     * Persist a seed override (or clear it with {@code null}). Caller is
     * expected to also wipe and re-prospect — changing the seed without
     * regeneration would desync SavedData from what the picker would now
     * roll, leaving stale deposits at chunks the new seed wouldn't pick.
     */
    public void setDepositSeed(Long seed) {
        this.depositSeed = Optional.ofNullable(seed);
        setDirty();
    }
}
