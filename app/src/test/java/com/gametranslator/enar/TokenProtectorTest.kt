package com.gametranslator.enar

import com.gametranslator.enar.translation.TokenProtector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TokenProtector] with the numeric-sentinel strategy (ISSUE-004).
 *
 * The retired `VARn` scheme tokenizes as ordinary translatable subwords
 * (`VAR0` -> `▁V/AR/0`) and is mangled or dropped by the model. Sentinels are
 * short digit strings the model copies verbatim (validated against the real INT8
 * model; see `docs/research.md` section 5C). These tests pin the protector-side
 * contract: sentinel shape, collision avoidance, exact round-trips for every
 * required span class, and verifiable restoration via [TokenProtector.missingPlaceholders].
 */
class TokenProtectorTest {

    private lateinit var protector: TokenProtector

    @Before
    fun setUp() {
        protector = TokenProtector()
    }

    // Fake "model" note: the restore tests below feed masked text with translated
    // surroundings, mirroring the measured real-model behavior (digit sentinels are
    // copied verbatim while surrounding words translate). This keeps unit tests
    // hermetic — no ONNX runtime on the JVM test classpath.

    @Test
    fun `placeholders are numeric sentinels, not VAR tokens`() {
        val protected = protector.protect("Welcome {playerName} to the game!")

        assertEquals(1, protected.tokenMap.size)
        val sentinel = protected.tokenMap.keys.single()
        assertTrue("Sentinel must be a short digit string, got '$sentinel'",
            sentinel.matches(Regex("\\d{3,6}")))
        assertTrue("Masked text must not contain VAR-style placeholders",
            !protected.maskedText.contains("VAR"))
        assertEquals("{playerName}", protected.tokenMap[sentinel])
    }

    @Test
    fun `sentinel avoids collision with numbers already in text`() {
        // 987650 (the sentinel base) occurs as a REAL value here: the protector must
        // bump past it so restore can never alias the real number with a placeholder.
        // Validated end-to-end on the model: "Score 987650 is 987652" copies both.
        val protected = protector.protect("Score 987650 is high")

        assertEquals("Score 987651 is high", protected.maskedText)
        assertEquals(mapOf("987651" to "987650"), protected.tokenMap)

        // Round-trip: the real value restores exactly.
        val restored = protector.restore(protected.maskedText, protected.tokenMap)
        assertEquals("Score 987650 is high", restored)
        assertTrue(protector.missingPlaceholders(protected.maskedText, protected.tokenMap).isEmpty())
    }

    @Test
    fun `sentinel hidden inside a longer run is skipped`() {
        // 19876509 contains the base sentinel as a substring: issuing 987650 here
        // would corrupt the longer number at restore time.
        val protected = protector.protect("ID 19876509 ok")

        assertEquals(mapOf("987651" to "19876509"), protected.tokenMap)
        assertEquals("ID 987651 ok", protected.maskedText)
    }

    @Test
    fun `protect curly bracket variable`() {
        val input = "Welcome {playerName} to the game!"
        val protected = protector.protect(input)

        val sentinel = protected.tokenMap.keys.single()
        assertEquals("{playerName}", protected.tokenMap[sentinel])

        val translatedMock = "مرحباً بك يا $sentinel في اللعبة!"
        val restored = protector.restore(translatedMock, protected.tokenMap)

        assertEquals("مرحباً بك يا {playerName} في اللعبة!", restored)
        assertTrue(protector.missingPlaceholders(translatedMock, protected.tokenMap).isEmpty())
    }

    @Test
    fun `protect format specifiers`() {
        val input = "Gained %d XP and %s gold"
        val protected = protector.protect(input)

        assertEquals(2, protected.tokenMap.size)
        assertTrue(protected.tokenMap.values.contains("%d"))
        assertTrue(protected.tokenMap.values.contains("%s"))

        val keys = protected.tokenMap.keys.toList()
        val translatedMock = "حصلت على ${keys[0]} و ${keys[1]}"
        val restored = protector.restore(translatedMock, protected.tokenMap)

        assertTrue(restored.contains("%d"))
        assertTrue(restored.contains("%s"))
        assertTrue(protector.missingPlaceholders(translatedMock, protected.tokenMap).isEmpty())
    }

    @Test
    fun `protect float format specifier`() {
        val protected = protector.protect("Progress %.2f complete")

        assertEquals(1, protected.tokenMap.size)
        assertTrue(protected.tokenMap.values.contains("%.2f"))
        val sentinel = protected.tokenMap.keys.single()
        assertEquals("Progress %.2f complete",
            protector.restore("Progress $sentinel complete", protected.tokenMap))
    }

    @Test
    fun `protect currency and numbers`() {
        val input = "Cost: $100 or 50G"
        val protected = protector.protect(input)

        assertTrue(protected.tokenMap.values.contains("\$100"))
        assertTrue(protected.tokenMap.values.contains("50G"))

        val keys = protected.tokenMap.keys.toList()
        val mockTrans = "التكلفة: ${keys[0]} أو ${keys[1]}"
        val restored = protector.restore(mockTrans, protected.tokenMap)

        assertTrue(restored.contains("\$100"))
        assertTrue(restored.contains("50G"))
    }

    @Test
    fun `protect percentages and game stats`() {
        // NOTE: "50 XP" masks as "50" (the unit letters stay in the sentence — the
        // validated model shape is "Gained 987651 XP ...", copied verbatim).
        val protected = protector.protect("Player {playerName} has 100% accuracy and 50 XP!")

        assertTrue(protected.tokenMap.values.contains("{playerName}"))
        assertTrue(protected.tokenMap.values.contains("100%"))
        assertTrue(protected.tokenMap.values.contains("50"))

        // Simulate the verified model behavior: sentinels copied verbatim.
        val keys = protected.tokenMap.keys.toList()
        val mockArabic = "الـ بلايـر ${keys[0]} لديهـا دقة ${keys[1]} و ${keys[2]} XP"
        val restored = protector.restore(mockArabic, protected.tokenMap)
        assertTrue(restored.contains("{playerName}"))
        assertTrue(restored.contains("100%"))
        assertTrue(restored.contains("50"))
        assertTrue(protector.missingPlaceholders(mockArabic, protected.tokenMap).isEmpty())
    }

    @Test
    fun `protect ratio as a single span`() {
        // HP bars show "80/100": masking the pair as ONE sentinel copies verbatim
        // ("HP 987650" -> "HP 987650"), while split sentinels around "/" degenerate.
        val protected = protector.protect("HP 80/100")

        assertEquals(1, protected.tokenMap.size)
        assertEquals("80/100", protected.tokenMap.values.single())
        val sentinel = protected.tokenMap.keys.single()
        assertEquals("HP 80/100", protector.restore("HP $sentinel", protected.tokenMap))
    }

    @Test
    fun `protect html and color tags`() {
        val input = "<color=#FF0000>Warning!</color>"
        val protected = protector.protect(input)

        assertEquals(2, protected.tokenMap.size)
        val keys = protected.tokenMap.keys.toList()
        val mockTrans = "${keys[0]}تحذير!${keys[1]}"
        val restored = protector.restore(mockTrans, protected.tokenMap)

        assertEquals("<color=#FF0000>تحذير!</color>", restored)
    }

    @Test
    fun `mixed text with multiple distinct placeholders round-trips`() {
        val input = "Give {playerName} $100 and %d potions"
        val protected = protector.protect(input)

        assertEquals(3, protected.tokenMap.size)
        // Every sentinel is space-separated in masked text (clean tokenization).
        for (key in protected.tokenMap.keys) {
            assertTrue("Sentinel $key must stand alone: '${protected.maskedText}'",
                protected.maskedText.contains(" $key ") ||
                    protected.maskedText.startsWith("$key ") ||
                    protected.maskedText.endsWith(" $key") ||
                    protected.maskedText == key)
        }
        // Simulate verbatim sentinel copy through translation.
        var mockTrans = protected.maskedText
        val restored = protector.restore(mockTrans, protected.tokenMap)
        for (original in protected.tokenMap.values) {
            assertTrue("Restored text must contain '$original': '$restored'",
                restored.contains(original))
        }
        assertTrue(protector.missingPlaceholders(mockTrans, protected.tokenMap).isEmpty())
    }

    @Test
    fun `repeated placeholder restores at every occurrence`() {
        val protected = protector.protect("50 and 50")

        assertEquals(2, protected.tokenMap.size)
        val keys = protected.tokenMap.keys.toList()
        // Verified model behavior: repeated sentinels are each copied ("999 و999").
        val restored = protector.restore("${keys[0]} و ${keys[1]}", protected.tokenMap)
        assertEquals("50 و 50", restored)
    }

    @Test
    fun `missingPlaceholders detects model mangling — ISSUE-004 regression`() {
        val protected = protector.protect("You have 50 HP remaining.")

        // Simulate the OLD failure: model transliterates/drops the placeholder
        // ("لديك في آربيلا المتبقية." / "لديك \"فارلو\" باقي") so restore is a no-op.
        val mangledModelOutput = "لديك في آربيلا المتبقية."
        val missing = protector.missingPlaceholders(mangledModelOutput, protected.tokenMap)
        assertEquals("All sentinels must be reported missing on mangled output",
            protected.tokenMap.keys, missing)

        // And restore must NOT claim success by inventing text: output is unchanged.
        val restored = protector.restore(mangledModelOutput, protected.tokenMap)
        assertEquals(mangledModelOutput, restored)
    }

    @Test
    fun `plain text without spans passes through untouched`() {
        val inputs = listOf(
            "The enemy is approaching from the north.",
            "Save Game",
            "Press E to open the door."
        )
        for (input in inputs) {
            val protected = protector.protect(input)
            assertTrue("No spans expected in '$input': ${protected.tokenMap}",
                protected.tokenMap.isEmpty())
            assertEquals(input, protected.maskedText)
        }
    }

    @Test
    fun `text normalization trims spaces and normalizes quotes`() {
        val input = "  “Quest”   ‘Reward’   "
        val protected = protector.protect(input)

        assertEquals("\"Quest\" 'Reward'", protected.maskedText)
    }

    @Test
    fun `empty and blank text handling`() {
        val empty = protector.protect("")
        assertEquals("", empty.maskedText)
        assertTrue(empty.tokenMap.isEmpty())

        val blank = protector.protect("   ")
        assertEquals("", blank.maskedText)
    }
}
