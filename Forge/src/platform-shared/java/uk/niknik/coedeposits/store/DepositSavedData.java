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
 * Per-level persistent registry of placed deposits, backed by vanilla
 * {@link SavedData} → {@code <world>/data/coedeposits_deposits.dat}.
 *
 * <p><b>1.20.1 line:</b> uses the 1.20.1 SavedData API —
 * {@code computeIfAbsent(loadFn, factory, name)} (no {@code Factory} record),
 * {@code load(CompoundTag)} / {@code save(CompoundTag)} without the
 * {@code HolderLookup.Provider} the 1.21 API threads through.
 */
public class DepositSavedData extends SavedData {
    /** SavedData file name (.dat appended by the framework). */
    public static final String NAME = "coedeposits_deposits";

    private final Map<UUID, Deposit> deposits = new HashMap<>();
    private final Map<Long, UUID> chunkIndex = new HashMap<>();
    private final Map<UUID, Set<UUID>> revealed = new HashMap<>();
    /** Deposit ids revealed for EVERYONE — a GLOBAL-scope reveal trigger fired for them. */
    private final Set<UUID> globallyRevealed = new HashSet<>();
    private Optional<Long> depositSeed = Optional.empty();

    /** Get-or-create the level's data instance. Always call on the server thread. */
    public static DepositSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(DepositSavedData::load, DepositSavedData::new, NAME);
    }

    /** Wire wrapper for codec encoding. */
    private record Storage(List<Deposit> all, List<RevealEntry> revealedEntries,
                           List<UUID> globallyRevealed, Optional<Long> depositSeed) {
        static final Codec<Storage> CODEC = RecordCodecBuilder.create(b -> b.group(
                Codec.list(Deposit.CODEC).fieldOf("deposits").forGetter(Storage::all),
                Codec.list(RevealEntry.CODEC).optionalFieldOf("revealed", List.of())
                        .forGetter(Storage::revealedEntries),
                Codec.list(UUIDUtil.CODEC).optionalFieldOf("globally_revealed", List.of())
                        .forGetter(Storage::globallyRevealed),
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

    /** Deserialization factory — 1.20.1 signature {@code Function<CompoundTag, T>} (no HolderLookup). */
    private static DepositSavedData load(CompoundTag tag) {
        DepositSavedData out = new DepositSavedData();
        Storage.CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(err -> Coedeposits.LOGGER.error(
                        "[coedeposits] failed to load SavedData: {}", err))
                .ifPresent(s -> {
                    s.all().forEach(out::addInternal);
                    for (RevealEntry e : s.revealedEntries()) {
                        out.revealed.put(e.player(), new HashSet<>(e.deposits()));
                    }
                    out.globallyRevealed.addAll(s.globallyRevealed());
                    out.depositSeed = s.depositSeed();
                });
        return out;
    }

    /** Codec-based serialization to NBT — 1.20.1 signature {@code save(CompoundTag)} (no HolderLookup). */
    @Override
    public CompoundTag save(CompoundTag tag) {
        List<RevealEntry> revealedFlat = new ArrayList<>(revealed.size());
        for (var e : revealed.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            revealedFlat.add(new RevealEntry(e.getKey(), new ArrayList<>(e.getValue())));
        }

        Storage payload = new Storage(new ArrayList<>(deposits.values()), revealedFlat,
                new ArrayList<>(globallyRevealed), depositSeed);
        Tag encoded = Storage.CODEC.encodeStart(NbtOps.INSTANCE, payload)
                .getOrThrow(false, err -> Coedeposits.LOGGER.error(
                        "[coedeposits] failed to encode SavedData: {}", err));
        if (encoded instanceof CompoundTag c) {
            tag.merge(c);
        }
        return tag;
    }

    /** Find the deposit owning this chunk, or {@code null}. */
    public Deposit lookup(ChunkPos cp) {
        UUID id = chunkIndex.get(cp.toLong());
        return id != null ? deposits.get(id) : null;
    }

    /** O(1) check: does any deposit claim this chunk? */
    public boolean isOccupied(ChunkPos cp) {
        return chunkIndex.containsKey(cp.toLong());
    }

    /** Persist a new deposit and index all its chunks. Marks dirty. */
    public Deposit add(Deposit d) {
        addInternal(d);
        setDirty();
        return d;
    }

    /** Outcome of {@link #addResolvingOverlap}. */
    public record OverlapResult(Deposit placed, Set<UUID> removed, Set<UUID> changed, Set<ChunkPos> trimmedChunks) {}

    /**
     * Add a candidate deposit, resolving overlaps: same-type merges; across types
     * the lower-weight (rarer) one wins each contested chunk.
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
                mergeInto.add(owner.id());
            } else if (newWeight < weightOf.applyAsInt(owner.typeId())) {
                claim.add(cp);
                trim.computeIfAbsent(owner.id(), k -> new HashSet<>()).add(cp);
            }
        }

        Set<UUID> removed = new HashSet<>();
        Set<UUID> changed = new HashSet<>();
        Set<ChunkPos> trimmedChunks = new HashSet<>();

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

    /** Internal add without setDirty — used by load(). */
    private void addInternal(Deposit d) {
        deposits.put(d.id(), d);
        for (ChunkPos cp : d.chunks()) {
            chunkIndex.put(cp.toLong(), d.id());
        }
    }

    /** Drop a deposit entirely. Returns the removed deposit (or null if unknown). */
    public Deposit remove(UUID depositId) {
        Deposit removed = deposits.remove(depositId);
        if (removed == null) return null;
        for (ChunkPos cp : removed.chunks()) {
            chunkIndex.computeIfPresent(cp.toLong(),
                    (k, owner) -> owner.equals(depositId) ? null : owner);
        }
        globallyRevealed.remove(depositId);
        setDirty();
        return removed;
    }

    /** Replace an existing deposit in place (same id), re-indexing its chunk set. */
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

    /** Wipe every deposit. Returns a snapshot of removed ids. */
    public java.util.List<UUID> removeAll() {
        if (deposits.isEmpty()) return java.util.List.of();
        java.util.List<UUID> ids = new ArrayList<>(deposits.keySet());
        deposits.clear();
        chunkIndex.clear();
        globallyRevealed.clear();
        setDirty();
        return ids;
    }

    /** Full UUID → deposit map — read-only view for iteration. */
    public Map<UUID, Deposit> all() {
        return deposits;
    }

    /** Mark a deposit revealed for a player. Returns true when newly revealed. */
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

    /**
     * Mark this deposit revealed for EVERYONE (a GLOBAL-scope reveal trigger).
     * Returns true when it wasn't already globally revealed, so the caller can
     * drive a one-shot broadcast. Marks the SavedData dirty on change.
     */
    public boolean revealGlobal(UUID depositId) {
        boolean added = globallyRevealed.add(depositId);
        if (added) setDirty();
        return added;
    }

    /** Is this deposit revealed for everyone (a GLOBAL-scope reveal fired)? */
    public boolean isGloballyRevealed(UUID depositId) {
        return globallyRevealed.contains(depositId);
    }

    /** Unmodifiable view of the player's revealed-deposit set. */
    public Set<UUID> revealedFor(UUID playerId) {
        Set<UUID> set = revealed.get(playerId);
        return set == null ? Set.of() : Collections.unmodifiableSet(set);
    }

    /** Optional override of the placement seed (absent = use world seed). */
    public Optional<Long> depositSeed() {
        return depositSeed;
    }

    /** Resolve the effective seed: override if set, otherwise the level's world seed. */
    public long effectiveSeed(ServerLevel level) {
        return depositSeed.orElseGet(level::getSeed);
    }

    /** Persist a seed override (or clear it with {@code null}). */
    public void setDepositSeed(Long seed) {
        this.depositSeed = Optional.ofNullable(seed);
        setDirty();
    }
}
