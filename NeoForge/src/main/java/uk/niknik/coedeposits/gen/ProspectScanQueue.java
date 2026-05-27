package uk.niknik.coedeposits.gen;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import uk.niknik.coedeposits.Coedeposits;

/**
 * Two-phase prospect-scan pipeline that keeps the server tick free of the
 * O((radius/16)²) work the original synchronous {@link ProspectScanner}
 * incurred (≈1s freeze at {@code prospect_radius=2000} on a typical CPU).
 *
 * <h2>Phases</h2>
 * <ol>
 *   <li><b>Dry run (worker thread)</b> — {@link ProspectScanner#dryRunChunk}
 *       runs on a single dedicated daemon thread, processing the scan grid in
 *       batches of {@link #WORKER_BATCH_SIZE}. Successful placements are
 *       pushed into a per-level {@link ConcurrentLinkedQueue}. No level state
 *       is mutated here — biome sampling goes through the {@link net.minecraft.world.level.biome.BiomeSource}
 *       directly so no chunk loads are triggered.</li>
 *   <li><b>Materialize (server thread)</b> — {@link #tickMaterialize} drains
 *       at most {@code prospect_chunks_per_tick} placements per tick, calling
 *       {@link ProspectScanner#materialize} which writes SavedData and patches
 *       OreData on currently-loaded blob chunks.</li>
 * </ol>
 *
 * <h2>Concurrency model</h2>
 * <ul>
 *   <li>One job at a time per level — keeps the design free of fine-grain
 *       locking. Subsequent enqueues chain onto the same level's deque.</li>
 *   <li>{@link uk.niknik.coedeposits.store.DepositSavedData} is touched only
 *       on the server thread (snapshot capture at {@link #enqueue}, lookups +
 *       writes during {@link #tickMaterialize}). The worker thread reads only
 *       the immutable {@link ProspectScanner.ScanSnapshot}.</li>
 *   <li>{@link LevelState#claimedChunks} stops the worker from emitting two
 *       overlapping blobs across batches. Authoritative dedup still happens at
 *       materialize via {@code store.isOccupied}.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * Singleton bound to the process. {@link #shutdown} flushes any outstanding
 * placements synchronously on the calling thread and tears down the worker
 * executor — call from {@code ServerStoppingEvent} so deposits picked but not
 * yet materialized still make it into the saved world.
 */
public final class ProspectScanQueue {
    /** Process singleton. */
    public static final ProspectScanQueue INSTANCE = new ProspectScanQueue();

    /**
     * Off-thread dry-run executor. Lazily created on first {@link #enqueue}
     * and re-created if a previous server cycle shut it down — keeps the same
     * static instance usable across restart cycles in a singleplayer client.
     */
    private volatile ExecutorService worker;

    /** Per-level state, keyed by dimension. */
    private final Map<ResourceKey<Level>, LevelState> states = new ConcurrentHashMap<>();

    /**
     * Chunks the worker chews per submitted task before re-submitting itself.
     * 1024 chunks ≈ ~10ms of pure CPU on the worker. Keeps any single job from
     * starving other jobs at the executor head and bounds shutdown latency.
     */
    private static final int WORKER_BATCH_SIZE = 1024;

    /**
     * Bucket size used by {@link #packCenter} — two enqueue centres in the
     * same {@value} ×{@value} block bucket count as duplicates and the
     * later one is dropped. The {@link uk.niknik.coedeposits.event.PlayerRoamProspectListener}
     * already gates re-enqueues at {@code prospect_radius/2} so this only
     * catches multi-player same-area spam and command-driven duplicates.
     */
    private static final int DEDUP_BUCKET_BLOCKS = 16;

    private ProspectScanQueue() {}

    /** Per-level mutable state. Mutated only under {@code synchronized (state)} except where noted. */
    private static final class LevelState {
        /** Jobs waiting their turn at the worker. */
        final Deque<ScanJob> pendingJobs = new ArrayDeque<>();
        /** Job currently owned by the worker. {@code null} when no work in flight. */
        ScanJob runningJob;
        /** Placements emitted by the worker, awaiting main-thread materialize. */
        final ConcurrentLinkedQueue<ProspectScanner.PendingPlacement> pendingPlacements = new ConcurrentLinkedQueue<>();
        /** Long-packed ChunkPos already covered by an emitted-or-saved deposit. */
        final Set<Long> claimedChunks = ConcurrentHashMap.newKeySet();
        /** Packed (cx/16, cz/16) buckets of in-flight job centres, for {@link #enqueue} dedup. */
        final Set<Long> recentCenters = ConcurrentHashMap.newKeySet();
        /** Total placements pushed onto the materialize queue across the level's lifetime. Diagnostic only. */
        final AtomicLong totalPlacementsEmitted = new AtomicLong();
    }

    /** One scheduled scan. Worker advances {@code dx}/{@code dz} until the grid is exhausted. */
    private static final class ScanJob {
        final BlockPos center;
        final int blockRadius;
        final int chunkRadius;
        final ChunkPos centerCp;
        final ProspectScanner.ScanSnapshot snapshot;
        final long enqueuedAtMs = System.currentTimeMillis();

        /** Cursor: next chunk-relative offset to scan. */
        int dx;
        int dz;
        // Stats accumulated by the worker (single-thread access, no atomics needed).
        int scanned;
        int skipped;
        int candidates;

        ScanJob(BlockPos center, int blockRadius, ProspectScanner.ScanSnapshot snapshot) {
            this.center = center;
            this.blockRadius = blockRadius;
            this.chunkRadius = blockRadius / 16;
            this.centerCp = new ChunkPos(center);
            this.snapshot = snapshot;
            this.dx = -chunkRadius;
            this.dz = -chunkRadius;
        }

        boolean hasMore() {
            return dz <= chunkRadius;
        }

        ChunkPos next() {
            ChunkPos cp = new ChunkPos(centerCp.x + dx, centerCp.z + dz);
            dx++;
            if (dx > chunkRadius) {
                dx = -chunkRadius;
                dz++;
            }
            return cp;
        }
    }

    /**
     * Schedule a prospect scan around {@code center} for {@code blockRadius}.
     * Captures a {@link ProspectScanner.ScanSnapshot} on the calling thread
     * (must be the server thread — the snapshot reads {@link uk.niknik.coedeposits.store.DepositSavedData}
     * and the chunk source) and queues the job. The worker is spun up on
     * first enqueue.
     *
     * <p>Coarse dedup: centres in the same {@link #DEDUP_BUCKET_BLOCKS} bucket
     * as an in-flight job are dropped — protects against the per-player tick
     * loop and the {@code /coedeposits scan} command piling up redundant work
     * while the previous scan is still draining.
     *
     * @param lvl          level to scan; caller should gate on
     *                      {@link uk.niknik.coedeposits.Config#isDimensionEnabled}
     * @param center       centre of the scan window in world blocks
     * @param blockRadius  scan radius in blocks; {@code <= 0} skips silently
     */
    public void enqueue(ServerLevel lvl, BlockPos center, int blockRadius) {
        if (blockRadius <= 0) return;
        LevelState state = states.computeIfAbsent(lvl.dimension(), k -> new LevelState());
        long centerKey = packCenter(center);
        // Capture snapshot on the server thread first — must happen before we
        // commit to enqueueing because DepositSavedData.get insists on the
        // server thread.
        ProspectScanner.ScanSnapshot snap = ProspectScanner.ScanSnapshot.capture(lvl);
        synchronized (state) {
            if (!state.recentCenters.add(centerKey)) return;  // dup; let the previous one drain
            ScanJob job = new ScanJob(center, blockRadius, snap);
            state.pendingJobs.add(job);
            if (state.runningJob == null) {
                state.runningJob = state.pendingJobs.pollFirst();
                submitBatch(state, state.runningJob);
            }
        }
    }

    /**
     * Drain up to {@code budget} pending placements for this level. Call once
     * per server tick from a {@link net.neoforged.neoforge.event.tick.ServerTickEvent.Post}
     * handler. Cheap when the queue is empty — early-returns without touching
     * SavedData.
     *
     * @param lvl     level whose queue to drain
     * @param budget  maximum placements to materialize this tick
     * @return        number actually materialized (≤ {@code budget})
     */
    public int tickMaterialize(ServerLevel lvl, int budget) {
        LevelState state = states.get(lvl.dimension());
        if (state == null) return 0;
        if (state.pendingPlacements.isEmpty()) return 0;
        int done = 0;
        // Allow up to budget placements (not budget calls) — a poll that returns
        // a placement materialize()=false (race lost to picker) still counts as
        // useful budget consumed because we polled the queue.
        for (int polled = 0; polled < budget; polled++) {
            ProspectScanner.PendingPlacement p = state.pendingPlacements.poll();
            if (p == null) break;
            if (ProspectScanner.materialize(lvl, p)) done++;
            // We don't release claimedChunks here — those chunks are now in
            // the SavedData chunkIndex (or were already there, losing the race),
            // which is the authoritative source for the picker. Keeping them
            // claimed in our set is a no-op cost.
        }
        return done;
    }

    /**
     * Cancel all pending and in-flight work for the level. Used by
     * {@code /coedeposits regenerate} so a previously-running scan's
     * placements can't bleed into the freshly-wiped SavedData.
     *
     * <p>The current worker batch will run to completion (it's a few ms),
     * but its emitted placements will land in the cleared queue and be
     * discarded. Subsequent enqueues start fresh.
     */
    public void clear(ServerLevel lvl) {
        LevelState state = states.get(lvl.dimension());
        if (state == null) return;
        synchronized (state) {
            state.pendingJobs.clear();
            state.runningJob = null;
            state.pendingPlacements.clear();
            state.claimedChunks.clear();
            state.recentCenters.clear();
        }
    }

    /**
     * Server-shutdown hook — flushes outstanding placements synchronously on
     * the calling thread and stops the worker. Best-effort; if the worker
     * doesn't terminate within 2 s we abandon it (daemon thread, JVM reaps it).
     *
     * @param liveLevels  the levels whose pending materializations to flush.
     *                     Use {@link PickerInstaller#enabledLevels} so disabled
     *                     dimensions don't get touched.
     */
    public void shutdown(Iterable<ServerLevel> liveLevels) {
        ExecutorService w = worker;
        if (w != null) {
            w.shutdown();
            try {
                w.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        // Drain remaining placements with unlimited budget — server is stopping,
        // tick spikes don't matter and we'd rather persist than discard.
        for (ServerLevel lvl : liveLevels) {
            int drained = tickMaterialize(lvl, Integer.MAX_VALUE);
            if (drained > 0 && uk.niknik.coedeposits.Config.LOG_LIFECYCLE.get()) {
                Coedeposits.LOGGER.info(
                        "[coedeposits] shutdown drained {} pending placements for {}",
                        drained, lvl.dimension().location());
            }
        }
        states.clear();
    }

    /**
     * Submit one batch of dry-run work for the given job. The batch processes
     * up to {@link #WORKER_BATCH_SIZE} chunks then re-submits itself if the
     * job has more chunks, or advances to the next pending job otherwise.
     */
    private void submitBatch(LevelState state, ScanJob job) {
        ExecutorService w = workerOrInit();
        w.submit(() -> runBatch(state, job));
    }

    /** One off-thread chunk-batch. Catches all throwables so a single bad input doesn't kill the worker. */
    private void runBatch(LevelState state, ScanJob job) {
        try {
            int budget = WORKER_BATCH_SIZE;
            while (budget > 0 && job.hasMore()) {
                ChunkPos cp = job.next();
                job.scanned++;
                budget--;

                long packed = cp.toLong();
                if (state.claimedChunks.contains(packed)) {
                    job.skipped++;
                    continue;
                }

                ProspectScanner.PendingPlacement p = ProspectScanner.dryRunChunk(job.snapshot, cp);
                if (p == null) continue;

                // Claim every chunk in the blob so concurrent batches (or this
                // job in a later iteration) don't emit overlapping placements.
                // Authoritative dedup still happens at materialize against
                // SavedData.chunkIndex.
                for (ChunkPos cc : p.chunks()) {
                    state.claimedChunks.add(cc.toLong());
                }
                state.pendingPlacements.add(p);
                state.totalPlacementsEmitted.incrementAndGet();
                job.candidates++;
            }
        } catch (Throwable t) {
            Coedeposits.LOGGER.error(
                    "[coedeposits] scan worker crashed at cursor=({},{}) center=({},{})",
                    job.dx, job.dz, job.center.getX(), job.center.getZ(), t);
        }

        // Decide what to do next: continue this job, or move to the next.
        synchronized (state) {
            if (job.hasMore()) {
                submitBatch(state, job);
                return;
            }
            // Job finished its dry-run. Log + advance.
            long elapsedMs = System.currentTimeMillis() - job.enqueuedAtMs;
            if ((job.candidates > 0 || elapsedMs > 500) && uk.niknik.coedeposits.Config.LOG_SCAN_SUMMARY.get()) {
                Coedeposits.LOGGER.info(
                        "[coedeposits] prospect scan dry-run at ({},{}): radius={} blocks, " +
                                "scanned={} chunks, skipped={} (claimed), candidates={} placements queued in {} ms",
                        job.center.getX(), job.center.getZ(), job.blockRadius,
                        job.scanned, job.skipped, job.candidates, elapsedMs);
            }
            // Allow the same centre to be re-enqueued again now.
            state.recentCenters.remove(packCenter(job.center));
            state.runningJob = state.pendingJobs.pollFirst();
            if (state.runningJob != null) {
                submitBatch(state, state.runningJob);
            }
        }
    }

    /** Lazily create the worker on first use. Daemon thread so it never blocks JVM exit. */
    private ExecutorService workerOrInit() {
        ExecutorService w = worker;
        if (w != null && !w.isShutdown()) return w;
        synchronized (this) {
            if (worker == null || worker.isShutdown()) {
                worker = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "coedeposits-scan");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
            }
            return worker;
        }
    }

    /**
     * Coarse spatial hash for the dedup set — anything in the same
     * {@link #DEDUP_BUCKET_BLOCKS}-wide bucket as an in-flight job centre is
     * considered a duplicate. Cheap and good enough — the
     * {@link uk.niknik.coedeposits.event.PlayerRoamProspectListener} already
     * gates on a much coarser per-player movement threshold.
     */
    private static long packCenter(BlockPos pos) {
        int qx = Math.floorDiv(pos.getX(), DEDUP_BUCKET_BLOCKS);
        int qz = Math.floorDiv(pos.getZ(), DEDUP_BUCKET_BLOCKS);
        return ((long) qx << 32) | (qz & 0xFFFFFFFFL);
    }
}
