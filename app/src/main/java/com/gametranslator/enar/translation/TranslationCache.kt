package com.gametranslator.enar.translation

/**
 * Interface for translation caching.
 * Decouples translation engines and pipelines from specific caching implementations
 * (No-op, in-memory Map, Bounded LRU, or persistent SQLite/Room cache).
 */
interface TranslationCache {
    /**
     * Looks up cached Arabic translation for normalized source text.
     * Returns null if cache miss.
     */
    fun get(sourceText: String): String?

    /**
     * Stores a translation in the cache.
     */
    fun put(sourceText: String, translatedText: String)

    /**
     * Clears all cached entries.
     */
    fun clear()

    /**
     * Returns the current number of cached entries.
     */
    fun size(): Int
}

/**
 * No-operation cache implementation that always returns cache misses.
 */
class NoOpTranslationCache : TranslationCache {
    override fun get(sourceText: String): String? = null
    override fun put(sourceText: String, translatedText: String) {}
    override fun clear() {}
    override fun size(): Int = 0
}
