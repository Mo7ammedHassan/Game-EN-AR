package com.gametranslator.enar

import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.render.CaptureGeometry
import com.gametranslator.enar.render.CoordinateMapper
import com.gametranslator.enar.render.DefaultOverlayTierRenderer
import com.gametranslator.enar.render.ExplicitOverlayRenderPolicy
import com.gametranslator.enar.render.OverlayRect
import com.gametranslator.enar.render.OverlayRegion
import com.gametranslator.enar.render.OverlayRenderPolicy
import com.gametranslator.enar.render.OverlayRenderTier
import com.gametranslator.enar.render.OverlaySink
import com.gametranslator.enar.render.OverlayStyle
import com.gametranslator.enar.render.OverlayTierRenderer
import com.gametranslator.enar.render.TierRenderOutcome
import com.gametranslator.enar.render.TieredOverlaySink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 8 JVM tests: tier selection, explicit policy config, per-tier styling,
 * fallback/diagnostic behavior, failure isolation, and coordinate preservation.
 *
 * Everything here is pure-JVM (no Android runtime): the tier layer is a style
 * transform only. Existing Phase 6 tests are untouched.
 */
class OverlayRenderTiersTest {

    private fun region(
        id: String = "r1",
        text: String = "مرحبا بك في اللعبة",
        rect: OverlayRect = OverlayRect(10f, 20f, 410f, 120f),
        style: OverlayStyle = OverlayStyle()
    ) = OverlayRegion(id, rect, text, style)

    /** Recording sink: captures the last forwarded update. */
    private class FakeSink(
        var throwOnUpdate: Boolean = false,
        var updateResult: Boolean = true
    ) : OverlaySink {
        var lastRegions: List<OverlayRegion>? = null
        var lastDensity: Float = Float.NaN
        var updates = 0
        var clears = 0
        var hides = 0

        override fun update(regions: List<OverlayRegion>, densityScale: Float): Boolean {
            updates++
            if (throwOnUpdate) throw RuntimeException("boom-update")
            lastRegions = regions.toList()
            lastDensity = densityScale
            return updateResult
        }

        override fun clear(): Boolean {
            clears++
            return true
        }

        override fun hide(): Boolean {
            hides++
            return true
        }
    }

    // ---- 1. Tier selection ----

    @Test
    fun `default policy selects its configured default tier`() {
        val policy = ExplicitOverlayRenderPolicy(OverlayRenderTier.TIER_1_COVER)
        assertEquals(OverlayRenderTier.TIER_1_COVER, policy.tierFor("anything"))
        assertEquals(OverlayRenderTier.TIER_1_COVER, policy.tierFor(""))
        assertEquals(OverlayRenderTier.TIER_1_COVER, policy.tierFor("unknown-track-9"))
    }

    @Test
    fun `per-region override wins over the default`() {
        val policy = ExplicitOverlayRenderPolicy(
            OverlayRenderTier.TIER_3_TRANSPARENT,
            mapOf("dialogue-1" to OverlayRenderTier.TIER_1_COVER)
        )
        assertEquals(OverlayRenderTier.TIER_1_COVER, policy.tierFor("dialogue-1"))
        assertEquals(OverlayRenderTier.TIER_3_TRANSPARENT, policy.tierFor("hud-hp"))
    }

    // ---- 2. Explicit policy configuration ----

    @Test
    fun `policy exposes its configuration for diagnostics`() {
        val policy = ExplicitOverlayRenderPolicy(
            OverlayRenderTier.TIER_2_MASK,
            mapOf("a" to OverlayRenderTier.TIER_1_COVER, "b" to OverlayRenderTier.TIER_3_TRANSPARENT)
        )
        assertEquals(OverlayRenderTier.TIER_2_MASK, policy.default())
        assertEquals(setOf("a", "b"), policy.overriddenIds())
    }

    @Test
    fun `policy overrides are immune to caller mutation`() {
        val mutable = mutableMapOf("a" to OverlayRenderTier.TIER_1_COVER)
        val policy = ExplicitOverlayRenderPolicy(
            OverlayRenderTier.TIER_3_TRANSPARENT, mutable
        )
        mutable["a"] = OverlayRenderTier.TIER_2_MASK
        mutable["evil"] = OverlayRenderTier.TIER_1_COVER
        assertEquals(OverlayRenderTier.TIER_1_COVER, policy.tierFor("a"))
        assertEquals(OverlayRenderTier.TIER_3_TRANSPARENT, policy.tierFor("evil"))
    }

    @Test
    fun `blank override ids are ignored at construction`() {
        val policy = ExplicitOverlayRenderPolicy(
            OverlayRenderTier.TIER_3_TRANSPARENT,
            mapOf("" to OverlayRenderTier.TIER_1_COVER)
        )
        assertTrue(policy.overriddenIds().isEmpty())
        assertEquals(OverlayRenderTier.TIER_3_TRANSPARENT, policy.tierFor(""))
    }

    // ---- 3. Tier 1 rendering ----

    @Test
    fun `tier1 applies an opaque cover in the same bounds with identical text`() {
        val renderer = DefaultOverlayTierRenderer()
        val src = region()
        val outcome = renderer.render(src, OverlayRenderTier.TIER_1_COVER)
        assertTrue(outcome is TierRenderOutcome.Rendered)
        val out = (outcome as TierRenderOutcome.Rendered).region
        assertEquals(OverlayRenderTier.TIER_1_COVER, outcome.appliedTier)
        assertEquals(src.id, out.id)
        assertEquals(src.boundsPx, out.boundsPx)
        assertEquals(src.text, out.text) // byte-identical logical order
        assertNotNull(out.style.backgroundColor)
        assertEquals(0xFF, (out.style.backgroundColor!! ushr 24) and 0xFF)
        // Non-background style fields untouched.
        assertEquals(src.style.textSizeSp, out.style.textSizeSp, 0f)
        assertEquals(src.style.textColor, out.style.textColor)
        assertEquals(src.style.alpha, out.style.alpha, 0f)
        assertEquals(src.style.maxLines, out.style.maxLines)
        assertEquals(src.style.ellipsize, out.style.ellipsize)
    }

    @Test
    fun `tier1 keeps an already-opaque author background`() {
        val renderer = DefaultOverlayTierRenderer()
        val style = OverlayStyle(backgroundColor = 0xFF000080.toInt())
        val out = (renderer.render(
            region(style = style),
            OverlayRenderTier.TIER_1_COVER
        ) as TierRenderOutcome.Rendered).region
        assertEquals(0xFF000080.toInt(), out.style.backgroundColor)
    }

    @Test
    fun `tier1 upgrades a translucent background to opaque (no english show-through)`() {
        val renderer = DefaultOverlayTierRenderer()
        val style = OverlayStyle(backgroundColor = 0x80141414.toInt())
        val out = (renderer.render(
            region(style = style),
            OverlayRenderTier.TIER_1_COVER
        ) as TierRenderOutcome.Rendered).region
        assertEquals(0xFF, (out.style.backgroundColor!! ushr 24) and 0xFF)
    }

    // ---- 4. Tier 2 rendering ----

    @Test
    fun `tier2 applies a translucent mask with identical geometry and text`() {
        val renderer = DefaultOverlayTierRenderer()
        val src = region()
        val out = (renderer.render(src, OverlayRenderTier.TIER_2_MASK)
            as TierRenderOutcome.Rendered).region
        assertEquals(src.id, out.id)
        assertEquals(src.boundsPx, out.boundsPx)
        assertEquals(src.text, out.text)
        val bg = out.style.backgroundColor
        assertNotNull(bg)
        val alpha = (bg!! ushr 24) and 0xFF
        assertTrue("Tier 2 mask must be translucent, got alpha=$alpha", alpha in 1..0xFE)
        assertEquals(src.style.textSizeSp, out.style.textSizeSp, 0f)
        assertEquals(src.style.textColor, out.style.textColor)
    }

    // ---- 5. Tier 3 rendering ----

    @Test
    fun `tier3 is transparent and preserves text and bounds`() {
        val renderer = DefaultOverlayTierRenderer()
        val src = region(style = OverlayStyle(backgroundColor = 0xFF141414.toInt()))
        val out = (renderer.render(src, OverlayRenderTier.TIER_3_TRANSPARENT)
            as TierRenderOutcome.Rendered).region
        assertEquals(src.id, out.id)
        assertEquals(src.boundsPx, out.boundsPx)
        assertEquals(src.text, out.text)
        assertNull(out.style.backgroundColor) // game stays visible
    }

    @Test
    fun `tier3 keeps author outline-shadow styles byte-identical`() {
        val renderer = DefaultOverlayTierRenderer()
        val src = region() // default style: outline on, no background
        val out = (renderer.render(src, OverlayRenderTier.TIER_3_TRANSPARENT)
            as TierRenderOutcome.Rendered).region
        assertEquals(src.style, out.style) // identity transform at steady state
    }

    @Test
    fun `tier3 adds a readability outline only when style has neither outline nor shadow`() {
        val renderer = DefaultOverlayTierRenderer()
        val bare = OverlayStyle(outlineColor = null, outlineWidthPx = 0f, shadowRadiusPx = 0f)
        assertFalse(bare.hasOutline())
        assertFalse(bare.hasShadow())
        val out = (renderer.render(
            region(style = bare),
            OverlayRenderTier.TIER_3_TRANSPARENT
        ) as TierRenderOutcome.Rendered).region
        assertTrue(out.style.hasOutline())
        assertNull(out.style.backgroundColor)
    }

    @Test
    fun `renderer rejects invalid constructor configuration`() {
        try {
            DefaultOverlayTierRenderer(tier1Background = 0x80141414.toInt())
            fail("translucent Tier 1 background must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            DefaultOverlayTierRenderer(tier2Background = 0xFF141414.toInt())
            fail("opaque Tier 2 mask must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ---- 6. Fallback behavior ----

    @Test
    fun `tier1 renderer failure falls back to tier3 preserving translated text`() {
        val failing = object : OverlayTierRenderer {
            override fun render(
                source: OverlayRegion,
                tier: OverlayRenderTier
            ): TierRenderOutcome {
                if (tier != OverlayRenderTier.TIER_3_TRANSPARENT) {
                    throw RuntimeException("simulated Tier 1 GPU fault")
                }
                return TierRenderOutcome.Rendered(
                    source.copy(style = source.style.copy(backgroundColor = null)),
                    tier
                )
            }
        }
        val sink = FakeSink()
        val tiered = TieredOverlaySink(
            sink,
            ExplicitOverlayRenderPolicy(OverlayRenderTier.TIER_1_COVER),
            failing
        )
        val src = region(text = "لديك 50 HP باق")
        assertTrue(tiered.update(listOf(src), 3.5f))
        val forwarded = sink.lastRegions
        assertNotNull(forwarded)
        assertEquals(1, forwarded!!.size)
        assertEquals(src.text, forwarded[0].text) // text preserved across fallback
        assertNull(forwarded[0].style.backgroundColor) // Tier 3 transparent
        val snap = tiered.metrics.snapshot()
        assertEquals(1L, snap.tier1Requested)
        assertEquals(1L, snap.fallbacksToTier3)
        assertEquals(1L, snap.forwardedRegions)
    }

    @Test
    fun `policy failure falls back to tier3 instead of crashing`() {
        val badPolicy = object : OverlayRenderPolicy {
            override fun tierFor(regionId: String): OverlayRenderTier {
                throw RuntimeException("simulated policy fault")
            }
        }
        val sink = FakeSink()
        val tiered = TieredOverlaySink(sink, badPolicy, DefaultOverlayTierRenderer())
        val src = region()
        assertTrue(tiered.update(listOf(src), 1f))
        assertEquals(1, sink.lastRegions!!.size)
        assertEquals(src.text, sink.lastRegions!![0].text)
        val snap = tiered.metrics.snapshot()
        assertEquals(1L, snap.policyFallbacks)
        assertEquals(1L, snap.tier3Requested)
    }

    // ---- 7. Invalid / unsupported tier configuration ----

    @Test
    fun `tier parsing accepts aliases and rejects garbage`() {
        assertEquals(OverlayRenderTier.TIER_1_COVER, OverlayRenderTier.parseOrNull("TIER_1_COVER"))
        assertEquals(OverlayRenderTier.TIER_1_COVER, OverlayRenderTier.parseOrNull("cover"))
        assertEquals(OverlayRenderTier.TIER_1_COVER, OverlayRenderTier.parseOrNull(" 1 "))
        assertEquals(OverlayRenderTier.TIER_2_MASK, OverlayRenderTier.parseOrNull("mask"))
        assertEquals(OverlayRenderTier.TIER_2_MASK, OverlayRenderTier.parseOrNull("T2"))
        assertEquals(
            OverlayRenderTier.TIER_3_TRANSPARENT,
            OverlayRenderTier.parseOrNull("transparent")
        )
        assertNull(OverlayRenderTier.parseOrNull("INPAINT"))
        assertNull(OverlayRenderTier.parseOrNull(""))
        assertNull(OverlayRenderTier.parseOrNull(null))
        assertNull(OverlayRenderTier.parseOrNull("tier 4"))
    }

    @Test
    fun `parseOrFallback never returns null and defaults to tier3`() {
        assertEquals(
            OverlayRenderTier.TIER_3_TRANSPARENT,
            OverlayRenderTier.parseOrFallback("NO_SUCH_TIER")
        )
        assertEquals(
            OverlayRenderTier.TIER_3_TRANSPARENT,
            OverlayRenderTier.parseOrFallback(null)
        )
        assertEquals(
            OverlayRenderTier.TIER_1_COVER,
            OverlayRenderTier.parseOrFallback("bogus", OverlayRenderTier.TIER_1_COVER)
        )
    }

    @Test
    fun `fromConfig falls back per-entry and reports the count`() {
        val (policy, fallbacks) = ExplicitOverlayRenderPolicy.fromConfig(
            defaultRaw = "BOGUS_DEFAULT",
            overridesRaw = mapOf(
                "dialogue-1" to "TIER_1_COVER",
                "hud-hp" to "AI_INPAINT",
                "" to "TIER_2_MASK"
            )
        )
        assertEquals(3, fallbacks) // bad default + bad entry + blank id
        assertEquals(OverlayRenderTier.TIER_3_TRANSPARENT, policy.default())
        assertEquals(OverlayRenderTier.TIER_1_COVER, policy.tierFor("dialogue-1"))
        assertEquals(OverlayRenderTier.TIER_3_TRANSPARENT, policy.tierFor("hud-hp"))
    }

    // ---- 8. Mixed Arabic / English ----

    @Test
    fun `all tiers preserve mixed-direction text byte-identical`() {
        val renderer = DefaultOverlayTierRenderer()
        val cases = listOf(
            "اضغط E للفتح",
            "لديك 50 HP باق",
            "التكلفة: \$100 أو 50%",
            "مرحباً يا {playerName} مرحباً بكم",
            "Score: 12500 pts (100%)",
            "Press E to open the door."
        )
        for (tier in OverlayRenderTier.values()) {
            for (text in cases) {
                val out = renderer.render(region(text = text), tier)
                assertTrue("$tier must render '$text'", out is TierRenderOutcome.Rendered)
                assertEquals(
                    "Tier $tier mutated text '$text'",
                    text,
                    (out as TierRenderOutcome.Rendered).region.text
                )
            }
        }
    }

    // ---- 9. Long text ----

    @Test
    fun `long multiline text passes through tiers untruncated`() {
        val renderer = DefaultOverlayTierRenderer()
        val long = ("هذه جملة طويلة في حوار اللعبة. " .repeat(20) + "\nLine two\nLine three").trim()
        val style = OverlayStyle(maxLines = 4, ellipsize = true)
        for (tier in OverlayRenderTier.values()) {
            val out = (renderer.render(region(text = long, style = style), tier)
                as TierRenderOutcome.Rendered).region
            assertEquals(long, out.text) // tier layer never truncates; layout owns that
            assertEquals(4, out.style.maxLines)
            assertTrue(out.style.ellipsize)
        }
    }

    // ---- 10. Empty text ----

    @Test
    fun `blank or degenerate regions are dropped on every tier`() {
        val renderer = DefaultOverlayTierRenderer()
        val blanks = listOf("", "   ", "\n\t ")
        for (tier in OverlayRenderTier.values()) {
            for (text in blanks) {
                val out = renderer.render(region(text = text), tier)
                assertTrue("Tier $tier must drop blank text", out is TierRenderOutcome.Dropped)
            }
            val degenerate = renderer.render(
                region(text = "مرحبا", rect = OverlayRect(5f, 5f, 5f, 50f)),
                tier
            )
            assertTrue("Tier $tier must drop zero-area bounds", degenerate is TierRenderOutcome.Dropped)
        }
    }

    @Test
    fun `sink drops empty regions without touching the delegate content`() {
        val sink = FakeSink()
        val tiered = TieredOverlaySink(
            sink,
            ExplicitOverlayRenderPolicy(OverlayRenderTier.TIER_1_COVER),
            DefaultOverlayTierRenderer()
        )
        val good = region(id = "good", text = "مرحبا")
        val bad = region(id = "bad", text = "   ")
        assertTrue(tiered.update(listOf(good, bad), 2f))
        assertEquals(listOf("good"), sink.lastRegions!!.map { it.id })
        assertEquals(2f, sink.lastDensity, 0f)
        val snap = tiered.metrics.snapshot()
        assertEquals(1L, snap.droppedRegions)
        assertEquals(1L, snap.forwardedRegions)
    }

    // ---- 11. Multiple regions ----

    @Test
    fun `mixed tiers resolve per region in a single update`() {
        val sink = FakeSink()
        val tiered = TieredOverlaySink(
            sink,
            ExplicitOverlayRenderPolicy(
                OverlayRenderTier.TIER_3_TRANSPARENT,
                mapOf("dlg" to OverlayRenderTier.TIER_1_COVER, "hud" to OverlayRenderTier.TIER_2_MASK)
            ),
            DefaultOverlayTierRenderer()
        )
        tiered.update(
            listOf(
                region(id = "dlg", text = "مرحبا"),
                region(id = "hud", text = "HP 50"),
                region(id = "world", text = "العدو يقترب")
            ),
            1f
        )
        val forwarded = sink.lastRegions!!.associateBy { it.id }
        assertEquals(3, forwarded.size)
        assertEquals(0xFF, (forwarded["dlg"]!!.style.backgroundColor!! ushr 24) and 0xFF)
        val hudAlpha = (forwarded["hud"]!!.style.backgroundColor!! ushr 24) and 0xFF
        assertTrue(hudAlpha in 1..0xFE)
        assertNull(forwarded["world"]!!.style.backgroundColor)
        val snap = tiered.metrics.snapshot()
        assertEquals(1L, snap.tier1Requested)
        assertEquals(1L, snap.tier2Requested)
        assertEquals(1L, snap.tier3Requested)
        assertEquals(3L, snap.forwardedRegions)
        assertEquals(1L, snap.updatesForwarded)
    }

    // ---- 12. Region failure isolation ----

    @Test
    fun `one region failing even tier3 does not block its siblings`() {
        val selective = object : OverlayTierRenderer {
            private val inner = DefaultOverlayTierRenderer()
            override fun render(
                source: OverlayRegion,
                tier: OverlayRenderTier
            ): TierRenderOutcome {
                if (source.id == "poison") throw RuntimeException("poison region")
                return inner.render(source, tier)
            }
        }
        val sink = FakeSink()
        val tiered = TieredOverlaySink(
            sink,
            ExplicitOverlayRenderPolicy(OverlayRenderTier.TIER_1_COVER),
            selective
        )
        assertTrue(
            tiered.update(
                listOf(
                    region(id = "a", text = "واحد"),
                    region(id = "poison", text = "مسموم"),
                    region(id = "b", text = "اثنان")
                ),
                1f
            )
        )
        assertEquals(listOf("a", "b"), sink.lastRegions!!.map { it.id })
        val snap = tiered.metrics.snapshot()
        assertEquals(1L, snap.fallbacksToTier3) // Tier 1 throw retried as Tier 3…
        assertEquals(1L, snap.droppedRegions) // …then dropped when Tier 3 also threw
        assertEquals(2L, snap.forwardedRegions)
    }

    @Test
    fun `delegate failure is counted and never rethrown`() {
        val sink = FakeSink(throwOnUpdate = true)
        val tiered = TieredOverlaySink(
            sink,
            ExplicitOverlayRenderPolicy(OverlayRenderTier.TIER_3_TRANSPARENT),
            DefaultOverlayTierRenderer()
        )
        assertFalse(tiered.update(listOf(region()), 1f))
        assertEquals(1L, tiered.metrics.snapshot().delegateFailures)
    }

    @Test
    fun `delegate refusal is counted as failure without throwing`() {
        val sink = FakeSink(updateResult = false)
        val tiered = TieredOverlaySink(sink)
        assertFalse(tiered.update(listOf(region()), 1f))
        assertEquals(1L, tiered.metrics.snapshot().delegateFailures)
    }

    @Test
    fun `clear and hide pass straight through to the delegate`() {
        val sink = FakeSink()
        val tiered = TieredOverlaySink(sink)
        assertTrue(tiered.clear())
        assertTrue(tiered.hide())
        assertEquals(1, sink.clears)
        assertEquals(1, sink.hides)
    }

    // ---- 13. Coordinate mapping preservation ----

    @Test
    fun `tier layer reuses the single canonical mapper — bounds pass through untouched`() {
        // Build overlay-space rects through the REAL production mapper, then
        // prove the tier layer never perturbs them on any tier.
        val mapper = CoordinateMapper(
            CaptureGeometry(
                frameWidth = 540,
                frameHeight = 1200,
                displayWidth = 1080,
                displayHeight = 2400
            )
        )
        val captureBox = BoundingBox(10, 200, 300, 320)
        val mapped = mapper.toOverlay(captureBox)
        val renderer = DefaultOverlayTierRenderer()
        for (tier in OverlayRenderTier.values()) {
            val out = (renderer.render(region(rect = mapped), tier)
                as TierRenderOutcome.Rendered).region
            assertEquals("Tier $tier moved the rect", mapped, out.boundsPx)
        }
    }

    @Test
    fun `sink forwards mapper-derived bounds and density unchanged`() {
        val mapper = CoordinateMapper(
            CaptureGeometry(
                frameWidth = 540,
                frameHeight = 1200,
                displayWidth = 1080,
                displayHeight = 2400
            )
        )
        val mapped = mapper.toOverlay(BoundingBox(0, 0, 540, 100))
        val sink = FakeSink()
        val tiered = TieredOverlaySink(sink)
        tiered.update(listOf(region(rect = mapped)), 3.5f)
        assertEquals(mapped, sink.lastRegions!![0].boundsPx)
        assertEquals(3.5f, sink.lastDensity, 0f)
    }
}
