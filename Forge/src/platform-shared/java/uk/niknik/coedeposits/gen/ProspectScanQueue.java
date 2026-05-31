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
 * Two-phase prospect-scan pipeline (dry-run on a worker thread → materialize on
 * the server tick) so a large {@code prospect_radius} sweep doesn't freeze the
 * server tick.
 *
 * <p>Loader-agnostic (platform-shared); ports verbatim — pure Java concurrency +
 * vanilla MC types. The materialize budget is drained from a loader-specific
 * server-tick handler (Forge {@code TickEvent.ServerTickEvent} in
 * {@link PickerInstaller}).
 */
public final class ProspectScanQueue {
    /** Process singleton. */
    public static final ProspectScanQueue INSTANCE = new ProspectScanQueue();

    private volatile ExecutorService worker;

    private final Map<ResourceKey<Level>, LevelState> states = new ConcurrentHashMap<>();

    private static final int WORKER_BATCH_SIZE = 1024;
    private static final int DEDUP_BUCKET_BLOCKS = 16;

    private ProspectScanQueue() {}

    private static final class LevelState {
        final Deque<ScanJob> pendingJobs = new ArrayDeque<>();
        ScanJob runningJob;
        final ConcurrentLinkedQueue<ProspectScanner.PendingPlacement> pendingPlacements = new ConcurrentLinkedQueue<>();
        final Set<Long> claimedChunks = ConcurrentHashMap.newKeySet();
        final Set<Long> recentCenters = ConcurrentHashMap.newKeySet();
        final AtomicLong totalPlacementsEmitted = new AtomicLong();
    }

    private static final class ScanJob {
        final BlockPos center;
        final int blockRadius;
        final int chunkRadius;
        final ChunkPos centerCp;
        final ProspectScanner.ScanSnapshot snapshot;
        final long enqueuedAtMs = System.currentTimeMillis();

        int dx;
        int dz;
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

    /** Schedule a prospect scan around {@code center}. Must be called on the server thread (snapshot capture). */
    public void enqueue(ServerLevel lvl, BlockPos center, int blockRadius) {
        if (blockRadius <= 0) return;
        LevelState state = states.computeIfAbsent(lvl.dimension(), k -> new LevelState());
        long centerKey = packCenter(center);
        ProspectScanner.ScanSnapshot snap = ProspectScanner.ScanSnapshot.capture(lvl);
        synchronized (state) {
            if (!state.recentCenters.add(centerKey)) return;
            ScanJob job = new ScanJob(center, blockRadius, snap);
            state.pendingJobs.add(job);
            if (state.runningJob == null) {
                state.runningJob = state.pendingJobs.pollFirst();
                submitBatch(state, state.runningJob);
            }
        }
    }

    /** Drain up to {@code budget} pending placements for this level. Call once per server tick. */
    public int tickMaterialize(ServerLevel lvl, int budget) {
        LevelState state = states.get(lvl.dimension());
        if (state == null) return 0;
        if (state.pendingPlacements.isEmpty()) return 0;
        int done = 0;
        for (int polled = 0; polled < budget; polled++) {
            ProspectScanner.PendingPlacement p = state.pendingPlacements.poll();
            if (p == null) break;
            if (ProspectScanner.materialize(lvl, p)) done++;
        }
        return done;
    }

    /** Cancel all pending + in-flight work for the level. Used by /coedeposits regenerate. */
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

    /** Server-shutdown hook — flushes outstanding placements synchronously and stops the worker. */
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

    private void submitBatch(LevelState state, ScanJob job) {
        ExecutorService w = workerOrInit();
        w.submit(() -> runBatch(state, job));
    }

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

        synchronized (state) {
            if (job.hasMore()) {
                submitBatch(state, job);
                return;
            }
            long elapsedMs = System.currentTimeMillis() - job.enqueuedAtMs;
            if ((job.candidates > 0 || elapsedMs > 500) && uk.niknik.coedeposits.Config.LOG_SCAN_SUMMARY.get()) {
                Coedeposits.LOGGER.info(
                        "[coedeposits] prospect scan dry-run at ({},{}): radius={} blocks, " +
                                "scanned={} chunks, skipped={} (claimed), candidates={} placements queued in {} ms",
                        job.center.getX(), job.center.getZ(), job.blockRadius,
                        job.scanned, job.skipped, job.candidates, elapsedMs);
            }
            state.recentCenters.remove(packCenter(job.center));
            state.runningJob = state.pendingJobs.pollFirst();
            if (state.runningJob != null) {
                submitBatch(state, state.runningJob);
            }
        }
    }

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

    private static long packCenter(BlockPos pos) {
        int qx = Math.floorDiv(pos.getX(), DEDUP_BUCKET_BLOCKS);
        int qz = Math.floorDiv(pos.getZ(), DEDUP_BUCKET_BLOCKS);
        return ((long) qx << 32) | (qz & 0xFFFFFFFFL);
    }
}
