package com.gametranslator.enar.engine

import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.TranslationResult
import com.gametranslator.enar.translation.TokenProtector

/**
 * Deterministic in-memory dictionary-based translation engine for standard game UI vocabulary.
 *
 * Uses neutral Modern Standard Arabic (MSA / Fusha) tailored specifically for gaming contexts.
 * Serves as:
 * 1. An ultra-fast, zero-overhead baseline translation engine (< 1 ms latency).
 * 2. An offline fallback when neural models are loading or under extreme thermal throttling.
 * 3. An instant translation provider for common high-frequency menu and HUD strings.
 */
class DictionaryTranslationEngine(
    private val tokenProtector: TokenProtector = TokenProtector()
) : TranslationEngine {

    companion object {
        // High-frequency game UI glossary (Neutral Modern Standard Arabic / Fusha)
        private val GLOSSARY = mapOf(
            // Menu / Navigation
            "start" to "ابدأ",
            "start game" to "بدء اللعبة",
            "new game" to "لعبة جديدة",
            "continue" to "متابعة",
            "resume" to "استئناف",
            "restart" to "إعادة البدء",
            "retry" to "إعادة المحاولة",
            "options" to "الخيارات",
            "settings" to "الإعدادات",
            "audio" to "الصوت",
            "video" to "الفيديو",
            "graphics" to "الرسومات",
            "controls" to "التحكم",
            "quit" to "خروج",
            "exit" to "خروج",
            "back" to "رجوع",
            "cancel" to "إلغاء",
            "confirm" to "تأكيد",
            "accept" to "قبول",
            "decline" to "رفض",
            "apply" to "تطبيق",
            "save" to "حفظ",
            "load" to "تحميل",
            "save game" to "حفظ اللعبة",
            "load game" to "تحميل اللعبة",
            "pause" to "إيقاف مؤقت",
            "main menu" to "القائمة الرئيسية",

            // Gameplay Actions
            "attack" to "هجوم",
            "defend" to "دفاع",
            "jump" to "قفز",
            "crouch" to "انحناء",
            "run" to "ركض",
            "sprint" to "عدو سريع",
            "walk" to "مشي",
            "interact" to "تفاعل",
            "open" to "فتح",
            "close" to "إغلاق",
            "talk" to "تحدث",
            "search" to "بحث",
            "loot" to "نهب",
            "equip" to "تجهيز",
            "unequip" to "إلغاء التجهيز",
            "use" to "استخدام",
            "craft" to "صناعة",
            "buy" to "شراء",
            "sell" to "بيع",
            "upgrade" to "ترقية",
            "repair" to "إصلاح",

            // Stats / Status
            "health" to "الصحة",
            "mana" to "المانا",
            "stamina" to "التحمل",
            "energy" to "الطاقة",
            "level" to "المستوى",
            "score" to "النقاط",
            "experience" to "الخبرة",
            "xp" to "الخبرة",
            "gold" to "ذهب",
            "coins" to "عملات",
            "inventory" to "الحقيبة",
            "quests" to "المهام",
            "map" to "الخريطة",
            "skills" to "المهارات",

            // Game States / Messages
            "game over" to "انتهت اللعبة",
            "victory" to "نصر",
            "defeat" to "هزيمة",
            "you died" to "لقد متّ",
            "mission accomplished" to "تمت المهمة بنجاح",
            "mission failed" to "فشلت المهمة",
            "loading" to "جارٍ التحميل...",
            "loading..." to "جارٍ التحميل...",
            "press any key to continue" to "اضغط على أي زر للمتابعة",
            "press any button to start" to "اضغط على أي زر للبدء"
        )
    }

    override suspend fun translate(
        text: String,
        targetBounds: BoundingBox,
        sourceLang: String,
        targetLang: String
    ): TranslationResult {
        val startNs = System.nanoTime()

        // 1. Protect variables
        val protected = tokenProtector.protect(text)
        val cleanKey = protected.maskedText.lowercase().trim()

        // 2. Lookup in glossary
        val directMatch = GLOSSARY[cleanKey]

        val translatedMasked = if (directMatch != null) {
            directMatch
        } else {
            // Partial word-by-word replacement for simple multi-word strings
            val words = protected.maskedText.split(" ")
            val translatedWords = words.map { word ->
                val cleanWord = word.lowercase().trim(' ', '.', '!', '?', ',', ':')
                GLOSSARY[cleanWord] ?: word
            }
            translatedWords.joinToString(" ")
        }

        // 3. Restore variables (honest flag: only true if every sentinel survived).
        val finalArabic = tokenProtector.restore(translatedMasked, protected.tokenMap)
        val fullyRestored = protected.tokenMap.isNotEmpty() &&
            tokenProtector.missingPlaceholders(translatedMasked, protected.tokenMap).isEmpty()
        val latencyNs = System.nanoTime() - startNs

        return TranslationResult(
            originalText = text,
            translatedArabicText = finalArabic,
            bounds = targetBounds,
            fromCache = false,
            latencyMs = latencyNs / 1_000_000L,
            latencyNs = latencyNs,
            sourceLang = sourceLang,
            targetLang = targetLang,
            engineMetadata = getEngineMetadata(),
            protectedTokensRestored = fullyRestored
        )
    }

    override fun isWarm(): Boolean = true

    override fun getEngineMetadata(): EngineMetadata = EngineMetadata(
        engineName = "DictionaryTranslationEngine",
        modelVersion = "1.0.0-MSA",
        executionBackend = "CPU_MEMORY_DICTIONARY",
        isQuantized = false
    )
}
