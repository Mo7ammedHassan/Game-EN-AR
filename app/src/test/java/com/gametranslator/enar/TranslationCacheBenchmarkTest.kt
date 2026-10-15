package com.gametranslator.enar

import com.gametranslator.enar.engine.CachedTranslationEngine
import com.gametranslator.enar.engine.TranslationEngine
import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.TranslationResult
import com.gametranslator.enar.translation.LruTranslationCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cache-hit latency benchmark through the REAL [CachedTranslationEngine] API
 * (normalize → cache lookup → result construction), not an isolated map probe.
 *
 * Target (implementation-plan.md Phase 5): cache hit < 1 ms. Treated as a target:
 * numbers are measured and reported; the assertion uses the full 1 ms budget with
 * large margin. JVM dev-machine measurement — on-device (ART) numbers will differ
 * and are recorded separately when taken.
 */
class TranslationCacheBenchmarkTest {

    private val bounds = BoundingBox(0, 0, 100, 20)

    private class InstantEngine : TranslationEngine {
        override suspend fun translate(
            text: String,
            targetBounds: BoundingBox,
            sourceLang: String,
            targetLang: String
        ): TranslationResult = TranslationResult(
            originalText = text,
            translatedArabicText = "AR[$text]",
            bounds = targetBounds,
            fromCache = false,
            latencyMs = 0L,
            engineMetadata = getEngineMetadata(),
            protectedTokensRestored = true
        )

        override fun isWarm(): Boolean = true

        override fun getEngineMetadata(): EngineMetadata = EngineMetadata(
            engineName = "InstantBenchmark",
            modelVersion = "0",
            executionBackend = "TEST",
            isQuantized = false
        )
    }

    private data class Stats(val n: Int, val meanNs: Double, val p50Ns: Long, val p95Ns: Long)

    private fun summarize(name: String, samplesNs: LongArray): Stats {
        samplesNs.sort()
        val mean = samplesNs.average()
        val p50 = samplesNs[(samplesNs.size * 0.50).toInt()]
        val p95 = samplesNs[(samplesNs.size * 0.95).toInt().coerceAtMost(samplesNs.size - 1)]
        println(
            "CACHE_BENCH[$name] n=${samplesNs.size} " +
                "mean_ns=${"%.1f".format(mean)} mean_us=${"%.3f".format(mean / 1_000.0)} " +
                "p50_ns=$p50 p95_ns=$p95 p50_us=${"%.3f".format(p50 / 1_000.0)}"
        )
        return Stats(samplesNs.size, mean, p50, p95)
    }

    @Test
    fun `single-key hot hit latency`() = runBlocking {
        val engine = CachedTranslationEngine(InstantEngine(), LruTranslationCache())
        val input = "You have 50 HP remaining."
        engine.translate(input, bounds) // miss: populates the entry

        val n = 2000
        val samples = LongArray(n)
        for (i in 0 until n) {
            val start = System.nanoTime()
            val result = engine.translate(input, bounds)
            samples[i] = System.nanoTime() - start
            assertTrue(result.fromCache)
        }
        val stats = summarize("hot-hit-single-key", samples)
        assertTrue(
            "Median hot-hit latency ${stats.p50Ns} ns exceeds the 1 ms target",
            stats.p50Ns < 1_000_000L
        )
    }

    @Test
    fun `cycling hit latency over full 500-entry cache`() = runBlocking {
        val engine = CachedTranslationEngine(InstantEngine(), LruTranslationCache())
        val keys = List(500) { i -> "Game string number $i HP!" }
        for (key in keys) {
            engine.translate(key, bounds) // 500 misses fill the cache to bound
        }

        val n = 2000
        val samples = LongArray(n)
        for (i in 0 until n) {
            val key = keys[i % keys.size] // round-robin: every access is a recency-moving hit
            val start = System.nanoTime()
            val result = engine.translate(key, bounds)
            samples[i] = System.nanoTime() - start
            assertTrue(result.fromCache)
        }
        val stats = summarize("cycling-hit-500-keys", samples)
        assertTrue(
            "Median cycling-hit latency ${stats.p50Ns} ns exceeds the 1 ms target",
            stats.p50Ns < 1_000_000L
        )
    }
}
