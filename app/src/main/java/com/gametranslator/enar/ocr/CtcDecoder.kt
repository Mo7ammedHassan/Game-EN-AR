package com.gametranslator.enar.ocr

/**
 * CTC greedy decoder for the PP-OCR recognizer (Phase 9).
 *
 * Exact port of reference `CTCLabelDecode`: the character table is
 * `['blank'] + dict_lines + [' ']` (the appended space is load-bearing — it
 * makes the table 97 entries for `en_dict.txt`'s 95 lines, matching the
 * model's 97-class output). Decode = per-step argmax (first-max on ties, like
 * `np.argmax`), skip blank (index 0), collapse consecutive duplicates where
 * the RAW previous step (blanks included) breaks runs, then join.
 *
 * Confidence mirrors the reference quirk exactly: `mean(emitted_probs +
 * [1e-50])`, i.e. `(sum + eps) / (n + 1)` — NOT the plain mean. Empty text
 * therefore scores ≈ 0. The parity golden (`rec_logits_*`, score 0.9371)
 * arbitrates the formula.
 *
 * Pure JVM. Logits layout is batch-major `[batch][step][class]`.
 */
class CtcDecoder(dictLines: List<String>) {

    data class CtcResult(val text: String, val score: Float)

    /** Full table with blank prefix + DUPLICATED-trailing-space suffix. */
    val characters: List<String> = listOf(BLANK) + dictLines + listOf(" ")

    /** Model class count this table decodes (assert against ORT output). */
    val classCount: Int get() = characters.size

    /**
     * @param logits Flat batch-major logits/probs, `batch * steps * classes`.
     * @throws IllegalArgumentException when [classes] mismatches the table
     *   (fail-closed: the engine converts this to an empty result).
     */
    fun decode(
        logits: FloatArray,
        batch: Int,
        steps: Int,
        classes: Int
    ): List<CtcResult> {
        require(batch >= 0 && steps >= 0) { "batch/steps must be >= 0" }
        require(classes == classCount) {
            "logit classes ($classes) != decoder table ($classCount)"
        }
        require(logits.size == batch * steps * classes) { "logits size mismatch" }
        val out = ArrayList<CtcResult>(batch)
        for (b in 0 until batch) {
            val chars = StringBuilder()
            var confSum = 0.0
            var confCount = 0
            var prevRaw = -1
            for (t in 0 until steps) {
                val base = (b * steps + t) * classes
                var best = 0
                var bestV = logits[base]
                for (c in 1 until classes) {
                    val v = logits[base + c]
                    if (v > bestV) {
                        bestV = v
                        best = c
                    }
                }
                val raw = best
                if (raw == BLANK_INDEX) {
                    prevRaw = raw
                    continue
                }
                if (raw == prevRaw) {
                    prevRaw = raw
                    continue
                }
                prevRaw = raw
                chars.append(characters[raw])
                confSum += bestV.toDouble()
                confCount++
            }
            val score = ((confSum + EPS) / (confCount + 1)).toFloat()
            out.add(CtcResult(chars.toString(), score))
        }
        return out
    }

    companion object {
        const val BLANK = "blank"
        const val BLANK_INDEX = 0

        /** Reference `1e-50` epsilon inside the confidence mean. */
        const val EPS = 1e-50
    }
}
