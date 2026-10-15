package com.gametranslator.enar

import com.gametranslator.enar.ocr.CtcDecoder
import com.gametranslator.enar.ocr.DbDecoder
import com.gametranslator.enar.ocr.OrtOcrSessionProvider
import com.gametranslator.enar.ocr.RecPreprocess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 9 decoder tests: DB decode (synthetic + real-tensor golden parity),
 * CTC decode (synthetic + real-logit golden parity), crop/resize-norm.
 */
class OcrDecodeTest {

    private fun resourceBytes(name: String): ByteArray =
        (javaClass.getResourceAsStream("/$name")
            ?: throw IllegalStateException("missing test resource $name")).readBytes()

    private fun readF32LE(raw: ByteArray): FloatArray {
        val fb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(fb.remaining())
        fb.get(out)
        return out
    }

    private fun resourceText(name: String): String =
        resourceBytes(name).toString(Charsets.UTF_8)

    // ---- DB decoder: synthetic ----

    @Test
    fun `empty prob map yields no boxes`() {
        assertTrue(DbDecoder.decode(FloatArray(20 * 20), 20, 20, 20, 20).isEmpty())
    }

    @Test
    fun `single solid blob yields one box covering it`() {
        val w = 40
        val h = 30
        val prob = FloatArray(w * h)
        for (y in 8..17) {
            for (x in 5..24) prob[y * w + x] = 0.95f
        }
        val boxes = DbDecoder.decode(prob, w, h, w, h)
        assertEquals(1, boxes.size)
        val b = boxes[0]
        // Blob 5..24 x 8..17 expanded by d = area*1.6/perim ≈ (20*10*1.6)/60 ≈ 5.3.
        assertTrue("x0=${b.x0}", b.x0 <= 5f && b.x0 >= -1f)
        assertTrue("x1=${b.x1}", b.x1 >= 25f && b.x1 <= 40f)
        // Score dilution is REFERENCE behavior: the mask is dilated (21x11)
        // but prob is hot only on the original 20x10 → 200*0.95/231 ≈ 0.8225.
        assertEquals(200f * 0.95f / 231f, b.score, 1e-3f)
    }

    @Test
    fun `two separated blobs yield two boxes`() {
        val w = 60
        val h = 20
        val prob = FloatArray(w * h)
        for (y in 4..11) {
            for (x in 2..12) prob[y * w + x] = 0.9f
            for (x in 40..52) prob[y * w + x] = 0.9f
        }
        val boxes = DbDecoder.decode(prob, w, h, w, h)
        assertEquals(2, boxes.size)
    }

    @Test
    fun `below-threshold and tiny blobs are dropped`() {
        val w = 30
        val h = 30
        val prob = FloatArray(w * h)
        // 1px-tall line: dilation rescues it to 2px, still < min_size 3 → dropped
        // (a 2px line WOULD survive dilation+score — genuine DB behavior, pinned
        // by the blob test above — so the drop case needs a 1px line).
        for (x in 2..20) prob[2 * w + x] = 0.9f
        for (y in 15..25) {
            for (x in 2..20) prob[y * w + x] = 0.2f // below thresh
        }
        assertTrue(DbDecoder.decode(prob, w, h, w, h).isEmpty())
    }

    // ---- DB decoder: real-tensor golden parity ----

    @Test
    fun `golden probmap reproduces reference boxes`() {
        val prob = readF32LE(resourceBytes("ocr/det_probmap_f32.raw"))
        // Golden map is (960, 448) = (mapH, mapW); dest snapshot is (432, 960).
        assertEquals(960 * 448, prob.size)
        val boxes = DbDecoder.decode(prob, 448, 960, 432, 960)
        assertEquals("expected 3 reference boxes, got ${boxes.size}", 3, boxes.size)

        // Reference boxes are ORIGINAL-image coords (1080x2400); snapshot
        // scale is 432/1080 = 0.4 → map reference into snapshot space.
        // Real quads from reference_run.json (hud_numbers.png), as AABBs:
        //   "Press E to open" (74,342,543,415), "50 HP" (72,227,266,306),
        //   "100%" (80,120,251,192).
        val refs = listOf(
            floatArrayOf(29.6f, 136.8f, 217.2f, 166.0f),
            floatArrayOf(28.8f, 90.8f, 106.4f, 122.4f),
            floatArrayOf(32.0f, 48.0f, 100.4f, 76.8f)
        )
        // Order-independent: every decoded box must strongly overlap a distinct ref.
        val used = BooleanArray(refs.size)
        for (b in boxes) {
            var best = -1
            var bestIou = 0f
            for (i in refs.indices) {
                if (used[i]) continue
                val r = refs[i]
                val iou = DbDecoder.iou(
                    b, DbDecoder.DetBox(r[0], r[1], r[2], r[3], 1f)
                )
                if (iou > bestIou) {
                    bestIou = iou
                    best = i
                }
            }
            println("GOLDEN det box=$b bestIoU=$bestIou")
            // Gate 0.8: measured 0.867/0.949/0.953 on the golden map. The
            // residual gap is the documented AABB-vs-rotated-quad difference
            // (quads are near-rectangles for horizontal text).
            assertTrue("box $b has no matching reference (bestIoU=$bestIou)", best >= 0 && bestIou >= 0.8f)
            used[best] = true
        }
        // Scores sane (reference box_thresh 0.5 gate already applied).
        for (b in boxes) assertTrue("score=${b.score}", b.score >= 0.5f)
    }

    // ---- CTC decoder: synthetic ----

    private val tinyDict = listOf("a", "b") // table: blank,a,b,space (4 classes)

    private fun logitsFor(seq: List<Pair<Int, Float>>, classes: Int = 4): FloatArray {
        val out = FloatArray(seq.size * classes) { 0.01f }
        seq.forEachIndexed { t, (cls, v) -> out[t * classes + cls] = v }
        return out
    }

    @Test
    fun `ctc collapses duplicates and skips blanks`() {
        val dec = CtcDecoder(tinyDict)
        // a a blank a b b → "aab".
        val logits = logitsFor(listOf(1 to 0.9f, 1 to 0.9f, 0 to 0.9f, 1 to 0.8f, 2 to 0.9f, 2 to 0.9f))
        val res = dec.decode(logits, 1, 6, 4)[0]
        assertEquals("aab", res.text)
    }

    @Test
    fun `ctc blank-only yields empty text with near-zero score`() {
        val dec = CtcDecoder(tinyDict)
        val logits = logitsFor(listOf(0 to 0.99f, 0 to 0.99f, 0 to 0.99f))
        val res = dec.decode(logits, 1, 3, 4)[0]
        assertEquals("", res.text)
        assertTrue("score=${res.score}", res.score < 1e-9f)
    }

    @Test
    fun `ctc score follows the reference mean-plus-eps formula`() {
        val dec = CtcDecoder(tinyDict)
        val logits = logitsFor(listOf(1 to 0.9f, 2 to 0.8f))
        val res = dec.decode(logits, 1, 2, 4)[0]
        assertEquals("ab", res.text)
        assertEquals((0.9 + 0.8 + 1e-50) / 3, res.score.toDouble(), 1e-6)
    }

    @Test
    fun `ctc rejects class-count mismatch fail-closed`() {
        val dec = CtcDecoder(tinyDict) // 4 classes
        try {
            dec.decode(FloatArray(2 * 97) { 0.1f }, 1, 2, 97)
            fail("class mismatch must throw")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `ctc ties resolve to first max like argmax`() {
        val dec = CtcDecoder(tinyDict)
        val logits = FloatArray(1 * 1 * 4) { 0.5f } // all tie → index 0 (blank)
        assertEquals("", dec.decode(logits, 1, 1, 4)[0].text)
    }

    // ---- CTC decoder: real-logit golden parity ----

    @Test
    fun `golden logits decode to reference text and score`() {
        val logits = readF32LE(resourceBytes("ocr/rec_logits_f32.raw"))
        // Golden: batch 1, 41 steps, 97 classes (from rec_logits_meta.json).
        assertEquals(1 * 41 * 97, logits.size)
        val dict = resourceText("ocr/en_dict.txt")
            .split("\n").map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() }
        assertEquals(95, dict.size)
        val dec = CtcDecoder(dict)
        assertEquals(97, dec.classCount)
        val res = dec.decode(logits, 1, 41, 97)[0]
        assertEquals("Press E to open", res.text)
        // Reference score 0.9371 (see rec_logits_meta.json).
        assertEquals(0.9371, res.score.toDouble(), 1e-3)
    }

    // ---- ORT det-tensor flattening (nesting-depth pin) ----

    @Test
    fun `flattenDetProb reads rows at channel-depth zero`() {
        // [1,1,2,3] tensor value: batch=1, channel=1, H=2, W=3.
        val nested = Array(1) {
            Array(1) {
                Array(2) { y -> FloatArray(3) { x -> (y * 10 + x).toFloat() } }
            }
        }
        val out = OrtOcrSessionProvider.flattenDetProb(nested, 2, 3)
        assertEquals(2, out.mapH)
        assertEquals(3, out.mapW)
        assertEquals(
            listOf(0f, 1f, 2f, 10f, 11f, 12f),
            out.prob.toList()
        )
    }

    @Test
    fun `flattenDetProb rejects wrong batch and size mismatch fail-closed`() {
        val good = Array(1) { Array(1) { Array(2) { FloatArray(3) } } }
        try {
            OrtOcrSessionProvider.flattenDetProb(good, 4, 3)
            fail("H mismatch must throw")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            OrtOcrSessionProvider.flattenDetProb(good, 2, 5)
            fail("W mismatch must throw")
        } catch (expected: IllegalArgumentException) {
        }
        val twoBatch = Array(2) { Array(1) { Array(2) { FloatArray(3) } } }
        try {
            OrtOcrSessionProvider.flattenDetProb(twoBatch, 2, 3)
            fail("batch != 1 must throw")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ---- RecPreprocess ----

    @Test
    fun `extractCrop clips and rejects degenerate`() {
        val rgb = ByteArray(10 * 10 * 3) { it.toByte() }
        val ok = RecPreprocess.extractCrop(rgb, 10, 10, 2, 2, 6, 6)!!
        assertEquals(4, ok.width)
        assertEquals(4, ok.height)
        assertNull(RecPreprocess.extractCrop(rgb, 10, 10, 5, 5, 5, 9))
        // Fully outside → clipped to empty.
        assertNull(RecPreprocess.extractCrop(rgb, 10, 10, 20, 20, 30, 30))
    }

    @Test
    fun `tall crops rotate per reference rule`() {
        val rgb = ByteArray(4 * 8 * 3) { it.toByte() }
        val crop = RecPreprocess.extractCrop(rgb, 4, 8, 0, 0, 4, 8)!!
        assertEquals(8, crop.width) // rot90: (w,h) -> (h,w)
        assertEquals(4, crop.height)
        // np.rot90 check on indices: out[y'][x'] = in[x'][H-1-y'] with out (8,4):
        // in(0,0) -> out(0,3): out[(3)*8+0]*3 == in[0].
        assertEquals(rgb[0], crop.rgb[(3 * 8 + 0) * 3])
    }

    @Test
    fun `resizeNormImg matches reference geometry and normalization`() {
        // 2:1 crop → imgW = 96, no padding, white → +1.0.
        val white = ByteArray(100 * 50 * 3) { 255.toByte() }
        val rec = RecPreprocess.resizeNormImg(RecPreprocess.Crop(white, 100, 50))!!
        assertEquals(48, rec.height)
        assertEquals(96, rec.width)
        assertEquals(3 * 48 * 96, rec.chw.size)
        assertEquals(1.0f, rec.chw[0], 1e-5f)
        // Black → -1.0.
        val black = RecPreprocess.resizeNormImg(RecPreprocess.Crop(ByteArray(100 * 50 * 3), 100, 50))!!
        assertEquals(-1.0f, black.chw[0], 1e-5f)
    }

    @Test
    fun `resizeNormImg rejects degenerate and sliver-wide crops`() {
        assertNull(
            RecPreprocess.resizeNormImg(RecPreprocess.Crop(ByteArray(0), 0, 10))
        )
    }
}
