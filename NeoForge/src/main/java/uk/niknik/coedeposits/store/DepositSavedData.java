package uk.niknik.coedeposits.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
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
        DepositSavedData out = new DepositSavedData();
        Storage.CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(err -> Coedeposits.LOGGER.error(
                        "[coedeposits] failed to load SavedData: {}", err))
                .ifPresent(s -> {
                    s.all().forEach(out::addInternal);
                    for (RevealEntry e : s.revealedEntries()) {
                        out.revealed.put(e.player(), new HashSet<>(e.deposits()));
                    }
                    out.depositSeed = s.depositSeed();
                });
        return out;
    }

    /** Codec-based serialization to NBT — called by NeoForge during world save. */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        List<RevealEntry> revealedFlat = new ArrayList<>(revealed.size());
        for (var e : revealed.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            revealedFlat.add(new RevealEntry(e.getKey(), new ArrayList<>(e.getValue())));
        }
        Storage payload = new Storage(new ArrayList<>(deposits.values()), revealedFlat, depositSeed);
        Tag encoded = Storage.CODEC.encodeStart(NbtOps.INSTANCE, payload).getOrThrow();
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
        Deposit removed = deposits.remove(depositId);
        if (removed == null) return null;
        for (ChunkPos cp : removed.chunks()) {
            // Only clear the index entry if it still points to *this* deposit
            // — defends against an unlikely race where another deposit picked
            // up the same chunk via a hand-crafted replace.
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
        Deposit prev = deposits.put(replacement.id(), replacement);
        if (prev != null) {
            for (ChunkPos cp : prev.chunks()) {
                chunkIndex.computeIfPresent(cp.toLong(),
                        (k, owner) -> owner.equals(replacement.id()) ? null : owner);
            }
        }
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
        if (deposits.isEmpty()) return java.util.List.of();
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
