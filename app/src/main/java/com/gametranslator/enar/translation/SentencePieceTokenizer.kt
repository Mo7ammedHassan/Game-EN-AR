package com.gametranslator.enar.translation

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

/**
 * Faithful Kotlin re-implementation of the HuggingFace `MarianTokenizer` segmentation
 * for `Helsinki-NLP/opus-mt-en-ar`.
 *
 * Reference behavior (verified against `transformers==4.57.6`):
 * - Segmentation comes from the `source.spm` SentencePiece **unigram** model
 *   (Viterbi max-sum over per-piece log-probability scores, `nmt_nfkc` normalization,
 *   extra-whitespace collapsing, unknown chars fall back to `<unk>` one char at a time).
 * - Token IDs come from the shared 62,802-entry `vocab.json` (spm-internal IDs are
 *   DIFFERENT — 31,993 of 32,000 do not match — so spm IDs must NEVER be used directly).
 * - EOS (`</s>`, id 0) is appended; no BOS is added; blank input yields `[EOS]`.
 */
class SentencePieceTokenizer private constructor(
    private val pieces: List<ScoredPiece>,
    private val pieceIndex: Map<String, List<Int>>,
    private val vocab: Map<String, Int>,
    private val idToToken: Map<Int, String>
) : TranslationTokenizer {

    data class ScoredPiece(val text: String, val score: Double, val type: Int)

    companion object {
        const val EOS_TOKEN_ID = 0
        const val UNK_TOKEN_ID = 1
        const val PAD_TOKEN_ID = 62801
        const val DECODER_START_TOKEN_ID = 62801

        private const val TYPE_NORMAL = 1
        private const val TYPE_USER_DEFINED = 4
        private const val TYPE_BYTE = 5

        private const val MAX_PIECE_LEN = 32
        private const val UNK_FALLBACK_SCORE = -1e9

        fun load(spmFile: File, vocabFile: File): SentencePieceTokenizer {
            val spmPieces = parseSpm(spmFile.readBytes())
            val usable = spmPieces.filter {
                it.type == TYPE_NORMAL || it.type == TYPE_USER_DEFINED || it.type == TYPE_BYTE
            }
            val index = mutableMapOf<String, MutableList<Int>>()
            for ((idx, p) in usable.withIndex()) {
                index.getOrPut(p.text) { mutableListOf() }.add(idx)
            }

            val vocabText = vocabFile.readText()
            val vocab = mutableMapOf<String, Int>()
            val idToToken = mutableMapOf<Int, String>()
            parseJson(vocabText, vocab, idToToken)
            return SentencePieceTokenizer(usable, index, vocab, idToToken)
        }

        private fun parseSpm(bytes: ByteArray): List<ScoredPiece> {
            val pieces = mutableListOf<ScoredPiece>()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            while (buf.hasRemaining()) {
                val tag = buf.get().toInt() and 0xFF
                val fieldNumber = tag ushr 3
                val wireType = tag and 0x07
                if (fieldNumber == 1 && wireType == 2) {
                    val length = readVarint(buf)
                    val pieceBytes = ByteArray(length)
                    buf.get(pieceBytes)
                    pieces.add(parseSpmPiece(ByteBuffer.wrap(pieceBytes).order(ByteOrder.LITTLE_ENDIAN)))
                } else {
                    skipField(buf, wireType)
                }
            }
            return pieces
        }

        private fun parseSpmPiece(buf: ByteBuffer): ScoredPiece {
            var text = ""
            var score = 0.0
            var type = TYPE_NORMAL
            while (buf.hasRemaining()) {
                val tag = buf.get().toInt() and 0xFF
                val fieldNumber = tag ushr 3
                val wireType = tag and 0x07
                when {
                    fieldNumber == 1 && wireType == 2 -> {
                        val length = readVarint(buf)
                        val strBytes = ByteArray(length)
                        buf.get(strBytes)
                        text = String(strBytes, Charsets.UTF_8)
                    }
                    fieldNumber == 2 && wireType == 5 -> {
                        score = buf.float.toDouble()
                    }
                    fieldNumber == 3 && wireType == 0 -> {
                        type = readVarint(buf)
                    }
                    else -> skipField(buf, wireType)
                }
            }
            return ScoredPiece(text, score, type)
        }

        private fun readVarint(buf: ByteBuffer): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = buf.get().toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }

        private fun skipField(buf: ByteBuffer, wireType: Int) {
            when (wireType) {
                0 -> readVarint(buf)
                1 -> buf.position(buf.position() + 8)
                2 -> {
                    val length = readVarint(buf)
                    buf.position(buf.position() + length)
                }
                5 -> buf.position(buf.position() + 4)
                else -> throw IllegalStateException("Unknown wire type: $wireType")
            }
        }

        private fun parseJson(
            text: String,
            vocab: MutableMap<String, Int>,
            idToToken: MutableMap<Int, String>
        ) {
            var i = 0
            while (i < text.length) {
                if (text[i] == '"') {
                    val keyEnd = findClosingQuote(text, i)
                    if (keyEnd == -1) break
                    val key = unescapeJsonString(text.substring(i + 1, keyEnd))
                    var j = keyEnd + 1
                    while (j < text.length && (text[j] == ' ' || text[j] == ':')) j++
                    if (j >= text.length) break
                    if (text[j] == '"') {
                        val valEnd = findClosingQuote(text, j)
                        if (valEnd == -1) break
                        i = valEnd + 1
                    } else {
                        var valEnd = j
                        while (valEnd < text.length && text[valEnd] != ',' && text[valEnd] != '}') valEnd++
                        val numStr = text.substring(j, valEnd).trim()
                        try {
                            val id = numStr.toInt()
                            vocab[key] = id
                            idToToken[id] = key
                        } catch (_: NumberFormatException) {
                        }
                        i = valEnd
                    }
                } else {
                    i++
                }
            }
        }

        private fun findClosingQuote(text: String, openIndex: Int): Int {
            var k = openIndex + 1
            while (k < text.length) {
                val c = text[k]
                if (c == '\\') {
                    k += 2
                    continue
                }
                if (c == '"') return k
                k++
            }
            return -1
        }

        private fun unescapeJsonString(s: String): String {
            if (s.indexOf('\\') == -1) return s
            val sb = StringBuilder(s.length)
            var k = 0
            while (k < s.length) {
                val c = s[k]
                if (c != '\\' || k + 1 >= s.length) {
                    sb.append(c)
                    k++
                    continue
                }
                when (val e = s[k + 1]) {
                    '"', '\\', '/' -> {
                        sb.append(e)
                        k += 2
                    }
                    'b' -> {
                        sb.append('\b')
                        k += 2
                    }
                    'f' -> {
                        sb.append('')
                        k += 2
                    }
                    'n' -> {
                        sb.append('\n')
                        k += 2
                    }
                    'r' -> {
                        sb.append('\r')
                        k += 2
                    }
                    't' -> {
                        sb.append('\t')
                        k += 2
                    }
                    'u' -> {
                        if (k + 5 < s.length) {
                            val hex = s.substring(k + 2, k + 6)
                            try {
                                sb.append(hex.toInt(16).toChar())
                                k += 6
                            } catch (_: NumberFormatException) {
                                sb.append(c)
                                k++
                            }
                        } else {
                            sb.append(c)
                            k++
                        }
                    }
                    else -> {
                        sb.append(c)
                        k++
                    }
                }
            }
            return sb.toString()
        }
    }

    override fun encode(text: String): IntArray {
        if (text.isBlank()) return intArrayOf(EOS_TOKEN_ID)

        // nmt_nfkc-style normalization: NFKC, collapse whitespace, trim,
        // then a leading space so the first word takes its '▁'-prefixed piece.
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC)
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return intArrayOf(EOS_TOKEN_ID)
        s = "▁" + s.replace(' ', '▁')

        val chars = s.toCharArray()
        val n = chars.size
        val dp = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val back = IntArray(n + 1) { -1 }
        val backLen = IntArray(n + 1) { 0 }
        val backUnk = BooleanArray(n + 1)
        dp[0] = 0.0

        for (i in 0 until n) {
            if (dp[i] == Double.NEGATIVE_INFINITY) continue
            var matched = false
            for (len in 1..minOf(MAX_PIECE_LEN, n - i)) {
                val sub = String(chars, i, len)
                val candidates = pieceIndex[sub] ?: continue
                for (pIdx in candidates) {
                    val newScore = dp[i] + pieces[pIdx].score
                    if (newScore > dp[i + len]) {
                        dp[i + len] = newScore
                        back[i + len] = pIdx
                        backLen[i + len] = len
                        backUnk[i + len] = false
                    }
                    matched = true
                }
            }
            // SentencePiece unknown-char fallback: consume one char as <unk>.
            val unkScore = dp[i] + UNK_FALLBACK_SCORE
            if (!matched || unkScore > dp[i + 1]) {
                if (!matched) {
                    dp[i + 1] = unkScore
                    back[i + 1] = -2
                    backLen[i + 1] = 1
                    backUnk[i + 1] = true
                }
            }
        }

        if (dp[n] == Double.NEGATIVE_INFINITY) {
            return intArrayOf(UNK_TOKEN_ID, EOS_TOKEN_ID)
        }

        val tokenIds = mutableListOf<Int>()
        var pos = n
        while (pos > 0) {
            if (backUnk[pos]) {
                tokenIds.add(UNK_TOKEN_ID)
            } else {
                val piece = pieces[back[pos]].text
                tokenIds.add(vocab[piece] ?: UNK_TOKEN_ID)
            }
            pos -= backLen[pos]
        }
        tokenIds.reverse()
        tokenIds.add(EOS_TOKEN_ID)
        return tokenIds.toIntArray()
    }

    override fun decode(tokenIds: IntArray): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            if (id == EOS_TOKEN_ID || id == UNK_TOKEN_ID || id == PAD_TOKEN_ID) continue
            val token = idToToken[id] ?: continue
            sb.append(token)
        }
        return sb.toString().replace('▁', ' ').trim()
    }

    // HuggingFace bos_token_id is None for this tokenizer.
    override fun getBosTokenId(): Int = -1

    override fun getEosTokenId(): Int = EOS_TOKEN_ID

    override fun getPadTokenId(): Int = PAD_TOKEN_ID

    override fun getVocabSize(): Int = vocab.size
}
