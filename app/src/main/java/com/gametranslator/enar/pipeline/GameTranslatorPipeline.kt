package com.gametranslator.enar.pipeline

import android.util.Log
import com.gametranslator.enar.engine.TranslationEngine
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.OcrResult
import com.gametranslator.enar.model.TranslationResult
import com.gametranslator.enar.render.CaptureGeometry
import com.gametranslator.enar.render.CoordinateMapper
import com.gametranslator.enar.render.OverlayRect
import com.gametranslator.enar.render.OverlayRegion
import com.gametranslator.enar.render.OverlaySink
import com.gametranslator.enar.render.OverlayStyle
import com.gametranslator.enar.translation.TextNormalizer
import com.gametranslator.enar.translation.TokenProtector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.Executors as JExecutors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Integrated async translation pipeline (Phase 7): stabilized OCR results in,
 * translated overlay regions out — without ever blocking capture.
 *
 * ```
 * ocrResults: Flow<List<OcrResult>> (stabilized, trackId-keyed, from OcrPipelineCoordinator)
 *   │  worker: single owned thread (ALL state lives here)
 *   ├─ normalize → revision check (latest-state per region)
 *   ├─ maybeLaunch: engine warm? → semaphore slot? → translate on bounded pool
 *   ├─ onComplete (hopped back to worker): revision match? → publish gate → overlay
 *   └─ disappearance: drop state + remove overlay
 * ```
 *
 * ## Backpressure & supersession (Parts H+I — the critical design)
 * - At most [maxConcurrentTranslations] inferences run concurrently (default 1:
 *   the ONNX engine's concurrent-`translate()` safety is unverified, and one
 *   flight keeps thermals flat). No FIFO, no queue object at all: a launch that
 *   finds no free permit is SKIPPED (`skippedBusy`), and the next stable OCR
 *   cycle retries the latest text — self-healing, zero accumulation.
 * - Every region carries a monotonic `revision`, bumped on each new stable text
 *   (and on unstable arrivals, to poison in-flight work). A completion whose
 *   revision mismatches is DROPPED (`staleDropped`) and immediately triggers a
 *   launch of the latest text — newer text always supersedes; stale Arabic can
 *   never overwrite or outlive it.
 *
 * ## Publish gate (Part J)
 * Overlay updates ONLY for results that are verified-restored OR span-free
 * (same rule as the cache admission policy — a result the cache would refuse
 * is not shown either); failures and unverified outputs keep the previous
 * overlay (or nothing), never blank/crash the pipeline. Vanished regions are
 * removed from the overlay on the first cycle they disappear (the coordinator
 * already applied disappearance grace upstream).
 *
 * ## Threading (Part K)
 * - Capture thread: untouched (existing service flow, conflated latest-only).
 * - Coordinator worker: untouched (existing; sequential recognize).
 * - Pipeline worker: owned single thread — SOLE owner of revisions, published
 *   map, overlay mirror, metrics mutation.
 * - Translation pool: owned fixed pool (`maxConcurrentTranslations` threads).
 * - Overlay sink: marshals to main internally (existing controller contract).
 * Override [workerDispatcher]/[translationDispatcher] ONLY for tests; owned
 * executors are created in [start] and closed in [stop] (no leaks; rapid
 * start/stop safe). Late completions after stop hit a cancelled scope and are
 * dropped (no post-stop overlay writes, ever). A cancelled in-flight inference
 * finishes its current native call (tens of ms) before its thread goes idle —
 * documented, bounded, uninterruptible-by-design (no JNI interruption).
 *
 * ## Memory (Part N)
 * No frames (metadata-only flow, as before), no bitmaps queued, no text
 * retained beyond active tracks (bounded by detector output), translations
 * bounded by semaphore permits, overlay mirror bounded by live regions, LRU
 * bounded in the engine decorator. In-flight coroutines ≤ permits by
 * construction (a permit is held from launch until the hopped completion
 * handler starts, which then runs serialized on the worker).
 *
 * @param maxConcurrentTranslations Inference parallelism bound (≥ 1; 1 default).
 * @param moveSnapPx Box-edge tolerance below which overlay bounds freeze
 *   (prevents layout rebuilds on ±pixel detector jitter).
 */
class GameTranslatorPipeline(
    private val translationEngine: TranslationEngine,
    private val overlaySink: OverlaySink,
    private val normalizer: TextNormalizer = TextNormalizer(),
    private val tokenProtector: TokenProtector = TokenProtector(),
    viewport: Viewport = Viewport(1080, 2400, densityScale = 1f),
    private val defaultStyle: OverlayStyle = OverlayStyle(),
    private val maxConcurrentTranslations: Int = 1,
    private val workerDispatcher: CoroutineDispatcher? = null,
    private val translationDispatcher: CoroutineDispatcher? = null,
    private val moveSnapPx: Float = 4f
) : Closeable {

    companion object {
        private const val TAG = "GameTranslatorPipeline"

        /**
         * Retry-pump period. Bounds recovery latency for shed/cold/failed work
         * independent of future emissions (which StateFlow conflation may
         * suppress for static content). Matches the ~5 Hz OCR cadence so the
         * pump never out-paces fresh cycles; each tick is a map scan, ~µs.
         */
        const val RETRY_POLL_MS = 200L
    }

    init {
        require(maxConcurrentTranslations >= 1) {
            "maxConcurrentTranslations must be >= 1, got $maxConcurrentTranslations"
        }
        require(moveSnapPx >= 0f && moveSnapPx.isFinite()) {
            "moveSnapPx must be finite >= 0, got $moveSnapPx"
        }
    }

    val metrics = PipelineMetrics()

    @Volatile
    private var mapper: CoordinateMapper = mapperFor(viewport)

    @Volatile
    private var currentViewport: Viewport = viewport

    // ---- worker-confined state (touched ONLY on worker, incl. hopped completions) ----
    private data class RegionState(
        var revision: Long = 0L,
        var text: String = "",
        var bounds: BoundingBox = BoundingBox(0, 0, 0, 0),
        var pending: Boolean = false,
        var needsRetry: Boolean = false,
        var firstStableNs: Long = 0L,
        /** Stability of the LAST receipt (pump may only launch proven text). */
        var stable: Boolean = false
    )

    private data class Published(
        val arabic: String,
        val captureBounds: BoundingBox,
        val emitted: OverlayRegion
    )

    private val states = mutableMapOf<String, RegionState>()
    private val published = mutableMapOf<String, Published>()
    private var overlayMirror: List<OverlayRegion> = emptyList()

    // ---- lifecycle ----
    private val running = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var workerExec: ExecutorCoroutineDispatcher? = null
    private var translationExec: ExecutorCoroutineDispatcher? = null
    private var activeWorker: CoroutineDispatcher = Dispatchers.Default
    private var activeTranslation: CoroutineDispatcher = Dispatchers.Default
    private val translationSlots = Semaphore(maxConcurrentTranslations)

    /**
     * Refreshes display/capture geometry (rotation, resize, density). Atomic
     * swap — in-flight cycles finish on the old mapper, new cycles use the new
     * one. Safe from any thread.
     */
    fun updateViewport(viewport: Viewport) {
        currentViewport = viewport
        mapper = mapperFor(viewport)
    }

    fun isRunning(): Boolean = running.get()

    /**
     * Starts collecting [ocrResults] (e.g. `OcrPipelineCoordinator.ocrResults`).
     * Idempotent-guarded: returns false when already running. Owned executors
     * are (re)created here so every start/stop cycle is leak-free.
     */
    fun start(ocrResults: Flow<List<OcrResult>>): Boolean {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "start() ignored: already running.")
            return false
        }
        activeWorker = workerDispatcher ?: JExecutors.newSingleThreadExecutor { r ->
            Thread(r, "TranslatorPipeline")
        }.asCoroutineDispatcher().also { workerExec = it }
        activeTranslation = translationDispatcher ?: JExecutors
            .newFixedThreadPool(maxConcurrentTranslations) { r ->
                Thread(r, "TranslatorNMT")
            }.asCoroutineDispatcher().also { translationExec = it }

        val sc = CoroutineScope(SupervisorJob() + activeWorker)
        scope = sc
        sc.launch {
            try {
                ocrResults.collect { onCycle(it) }
            } catch (t: Throwable) {
                if (running.get()) {
                    Log.w(TAG, "OCR results collection failed: ${t.message}")
                }
            }
        }
        sc.launch {
            try {
                while (running.get()) {
                    kotlinx.coroutines.delay(RETRY_POLL_MS)
                    onRetryTick()
                }
            } catch (t: Throwable) {
                if (running.get()) {
                    Log.w(TAG, "Retry pump failed: ${t.message}")
                }
            }
        }
        Log.i(TAG, "Pipeline started (permits=$maxConcurrentTranslations).")
        return true
    }

    /**
     * Stops everything in order: reject new cycles → cancel in-flight work →
     * close owned executors → clear state → detach overlay. Late completions
     * are dropped by the cancelled scope (never touch the overlay). Safe to
     * call repeatedly and from any thread (overlay detach may block briefly
     * on the main-thread marshal — lifecycle path, not hot path).
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            scope?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Error cancelling pipeline scope: ${t.message}")
        } finally {
            scope = null
        }
        try {
            workerExec?.close()
            translationExec?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing pipeline executors: ${t.message}")
        } finally {
            workerExec = null
            translationExec = null
            activeWorker = Dispatchers.Default
            activeTranslation = Dispatchers.Default
        }
        states.clear()
        published.clear()
        overlayMirror = emptyList()
        metrics.noteInFlight(0)
        metrics.noteTracked(0)
        metrics.noteOverlay(0)
        try {
            if (overlaySink.hide()) {
                metrics.incOverlayHide()
            }
        } catch (t: Throwable) {
            metrics.incOverlayFailure()
            Log.w(TAG, "Overlay detach on stop failed: ${t.message}")
        }
        Log.i(TAG, "Pipeline stopped.")
    }

    override fun close() = stop()

    // ---- worker body ----

    /**
     * Retry pump (runs on the worker every [RETRY_POLL_MS]): launches whatever
     * is pending-and-wanted, independent of future emissions. This is REQUIRED
     * for progress, not an optimization — the upstream flow is conflated, so
     * static content may never produce another emission after the one that set
     * `needsRetry` (cold engine, shed permits, transient failures, or a first
     * observation that arrived unstable). Permits still bound concurrency;
     * [maybeLaunch] re-arms `needsRetry` whenever it sheds.
     */
    private fun onRetryTick() {
        var launchedAny = false
        for ((id, st) in states) {
            // The pump may ONLY launch last-known-STABLE text: translating on
            // unstable receipts would violate the stabilize-before-translate
            // rule (Part F). Unstable arrivals merely arm needsRetry; a later
            // stable receipt (or none) decides.
            if (!st.pending && st.needsRetry && st.stable) {
                st.needsRetry = false
                if (maybeLaunch(id, st)) {
                    launchedAny = true
                }
            }
        }
        if (launchedAny) {
            metrics.incRetryLaunch()
        }
    }

    private fun onCycle(results: List<OcrResult>) {
        metrics.incCycles()
        val nowNs = System.nanoTime()
        val seen = HashSet<String>(results.size)

        for (r in results) {
            if (r.regionId.isEmpty()) {
                metrics.incBlank()
                continue
            }
            val canonical = normalizer.normalize(r.rawText)
            if (canonical.isEmpty()) {
                metrics.incBlank()
                continue
            }
            seen.add(r.regionId)
            val st = states.getOrPut(r.regionId) { RegionState() }
            st.bounds = r.bounds
            st.stable = r.isStable
            // A moved box refreshes the overlay position even when the text is
            // unchanged (or currently unstable) — positions must track motion.
            if (published.containsKey(r.regionId)) {
                refreshEmitted(r.regionId, st)
            }
            if (!r.isStable) {
                metrics.incUnstable()
                if (canonical != st.text) {
                    // Record the latest seen text and flag it for translation
                    // once stable. The revision bumps (and counts) ONLY when a
                    // flight is actually poisoned — otherwise this is just the
                    // normal pre-stable observation every track starts with.
                    st.text = canonical
                    st.needsRetry = true
                    if (st.pending) {
                        st.revision++
                        metrics.incSuperseded()
                    }
                }
                continue
            }
            metrics.incStable()
            val changed = canonical != st.text
            if (changed) {
                // A pending flight for the previous text is superseded here
                // (counted even on the stable path — only the metric cares
                // which branch marked it; the revision bump does the work).
                if (st.pending) {
                    metrics.incSuperseded()
                }
                st.text = canonical
                st.revision++
                st.firstStableNs = nowNs
                st.needsRetry = false
            } else if (st.firstStableNs == 0L) {
                // Stable text first observed via an unstable cycle earlier:
                // anchor end-to-end latency now (0 would corrupt the metric).
                st.firstStableNs = nowNs
            }
            if (!st.pending && (changed || st.needsRetry)) {
                st.needsRetry = false
                maybeLaunch(r.regionId, st)
            }
        }

        // Disappearance: the coordinator already applied grace upstream, so a
        // missing id means REMOVED — drop state and unpublish immediately.
        states.keys.retainAll(seen)
        val removedPublished = published.keys.filter { it !in seen }
        for (id in removedPublished) {
            published.remove(id)
            metrics.incRemoved()
        }
        metrics.noteTracked(states.size)
        emitOverlay()
    }

    /**
     * Attempts one translation flight. Returns true when launched; false when
     * shed (cold engine or no free permit — `needsRetry` is re-armed so the
     * retry pump or the next stable cycle picks it up).
     */
    private fun maybeLaunch(id: String, st: RegionState): Boolean {
        if (!translationEngine.isWarm()) {
            metrics.incColdSkip()
            st.needsRetry = true
            return false
        }
        if (!translationSlots.tryAcquire()) {
            // Bounded shedding: no queue forms; the retry pump (or the next
            // stable OCR cycle) retries the (unchanged) latest text.
            metrics.incBusySkip()
            st.needsRetry = true
            return false
        }
        val revision = st.revision
        val text = st.text
        val bounds = st.bounds
        val sc = scope
        if (sc == null) {
            translationSlots.release()
            return false
        }
        st.pending = true
        metrics.incLaunched()
        metrics.noteInFlight(maxConcurrentTranslations - translationSlots.availablePermits())
        sc.launch(activeTranslation) {
            val result: TranslationResult? = try {
                translationEngine.translate(text, bounds, "en", "ar")
            } catch (t: Throwable) {
                Log.w(TAG, "Translation failed for '$id': ${t.message}")
                null
            }
            postToWorker(id, revision, result)
        }
        return true
    }

    /**
     * Marshals a translation outcome back to the worker. The permit is
     * released FIRST inside the hopped coroutine: everything below runs
     * serialized on the worker, so no second flight can interleave — and
     * follow-up launches inside the handler (supersession) acquire
     * immediately instead of deferring a cycle. Dispatch failure (stopped
     * pipeline) releases inline instead. Exactly once either way.
     */
    private fun postToWorker(id: String, revision: Long, result: TranslationResult?) {
        val sc = scope
        if (sc == null) {
            translationSlots.release()
            return
        }
        try {
            sc.launch(activeWorker) {
                translationSlots.release()
                metrics.noteInFlight(maxConcurrentTranslations - translationSlots.availablePermits())
                try {
                    if (result == null) onFailure(id, revision) else onComplete(id, revision, result)
                } catch (t: Throwable) {
                    Log.w(TAG, "Completion handler failed for '$id': ${t.message}")
                }
            }
        } catch (t: Throwable) {
            // Scope/dispatcher gone (stop raced completion): permit back, result dropped.
            translationSlots.release()
            Log.w(TAG, "Dropping late translation for '$id' (pipeline stopped).")
        }
    }

    private fun onComplete(id: String, revision: Long, result: TranslationResult) {
        val st = states[id]
        if (st == null) {
            metrics.incStale() // region removed meanwhile
            return
        }
        st.pending = false
        if (revision != st.revision) {
            // Superseded while in flight: drop, then translate the latest text now.
            metrics.incStale()
            maybeLaunch(id, st)
            return
        }
        metrics.incCompleted()
        if (result.fromCache) metrics.incHit() else metrics.incMiss()

        // Publish gate (mirrors the cache admission rule): a span-bearing but
        // unverified result is never shown — hold previous (or nothing).
        val hasSpans = tokenProtector.protect(st.text).tokenMap.isNotEmpty()
        if (!result.protectedTokensRestored && hasSpans) {
            metrics.incSuppressed()
            return
        }
        published[id] = Published(
            result.translatedArabicText,
            st.bounds,
            emittedFor(id, st.bounds, result.translatedArabicText)
        )
        metrics.noteEndToEnd(System.nanoTime() - st.firstStableNs)
        emitOverlay()
    }

    private fun onFailure(id: String, revision: Long) {
        metrics.incFailure()
        val st = states[id] ?: return
        st.pending = false
        if (revision != st.revision) {
            maybeLaunch(id, st) // newer text already waiting
        } else {
            st.needsRetry = true // transient: next stable cycle retries
        }
    }

    /**
     * Rebuilds the emitted overlay region when the box moved beyond the snap
     * tolerance (pure motion — no revision bump, no re-translation). Compared
     * in OVERLAY pixels against the last emitted rect.
     */
    private fun refreshEmitted(id: String, st: RegionState) {
        val prev = published[id] ?: return
        val rect = mapper.toOverlay(st.bounds)
        if (!withinSnap(prev.emitted.boundsPx, rect)) {
            published[id] = prev.copy(
                captureBounds = st.bounds,
                emitted = OverlayRegion(
                    id = id,
                    boundsPx = rect,
                    text = prev.arabic,
                    style = defaultStyle
                )
            )
            emitOverlay()
        }
    }

    private fun emittedFor(id: String, bounds: BoundingBox, arabic: String): OverlayRegion =
        OverlayRegion(
            id = id,
            boundsPx = mapper.toOverlay(bounds),
            text = arabic,
            style = defaultStyle
        )

    private fun withinSnap(a: OverlayRect, b: OverlayRect): Boolean {
        return kotlin.math.abs(a.left - b.left) <= moveSnapPx &&
            kotlin.math.abs(a.top - b.top) <= moveSnapPx &&
            kotlin.math.abs(a.right - b.right) <= moveSnapPx &&
            kotlin.math.abs(a.bottom - b.bottom) <= moveSnapPx
    }

    private fun emitOverlay() {
        val desired = published.values.map { it.emitted }.sortedBy { it.id }
        if (desired == overlayMirror) return
        // Mirror updates ONLY on sink success: a failed update keeps the old
        // mirror, so the next cycle retries the same desired set instead of
        // going blind.
        try {
            if (desired.isEmpty()) {
                if (overlaySink.hide()) {
                    overlayMirror = desired
                    metrics.noteOverlay(0)
                    metrics.incOverlayHide()
                }
            } else {
                if (overlaySink.update(desired, currentViewport.densityScale)) {
                    overlayMirror = desired
                    metrics.noteOverlay(desired.size)
                    metrics.incOverlayUpdate()
                } else {
                    metrics.incOverlayFailure()
                }
            }
        } catch (t: Throwable) {
            metrics.incOverlayFailure()
            Log.w(TAG, "Overlay update failed (isolated): ${t.message}")
        }
    }

    private fun mapperFor(viewport: Viewport): CoordinateMapper = CoordinateMapper(
        CaptureGeometry(
            frameWidth = viewport.captureWidthPx,
            frameHeight = viewport.captureHeightPx,
            displayWidth = viewport.displayWidthPx,
            displayHeight = viewport.displayHeightPx,
            rotationClockwiseDeg = viewport.rotationClockwiseDeg
        )
    )
}
