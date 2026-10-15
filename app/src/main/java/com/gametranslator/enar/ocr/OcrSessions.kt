package com.gametranslator.enar.ocr

/**
 * Replaceable OCR runtime seam (Phase 9, ADR-014 philosophy).
 *
 * WHY interfaces instead of ORT types: JVM unit tests run without native
 * libraries (`UnsatisfiedLinkError` on any real session use), so inference is
 * scripted through fakes there, while `androidTest`/production use the ORT
 * implementation. The engine only ever sees these shapes — swapping runtimes
 * (LiteRT, NNAPI, QNN) means a new provider, zero engine changes.
 */
interface DetSession {
    /** Runs the DB detector. Input CHW float32 `[batch, 3, h, w]`. */
    fun run(input: FloatArray, batch: Int, channels: Int, height: Int, width: Int): DetOutput

    /** Releases native session resources. Idempotent. */
    fun close()
}

/** Single DB probability map, row-major `[mapH, mapW]`. */
data class DetOutput(val prob: FloatArray, val mapH: Int, val mapW: Int)

interface RecSession {
    /** Runs the CTC recognizer. Input CHW float32 `[batch, 3, 48, w]`. */
    fun run(input: FloatArray, batch: Int, channels: Int, height: Int, width: Int): RecOutput

    /** Releases native session resources. Idempotent. */
    fun close()
}

/** Batch-major logits `[batch, steps, classes]`. */
data class RecOutput(val logits: FloatArray, val batch: Int, val steps: Int, val classes: Int)

/**
 * Lifecycle owner for both OCR sessions plus the CTC character table.
 *
 * Contract: [load] once off the main thread (throws on missing/corrupt
 * assets — caller degrades to cold/engine-absent); [isLoaded] gates
 * inference; [release] is idempotent and safe pre-load; [characters] returns
 * the dict lines (WITHOUT the decoder's blank/space affixes) once loaded,
 * empty before.
 */
interface OcrSessionProvider {
    /** Loads sessions + dict off the caller's thread (throws on failure). */
    fun load()
    fun isLoaded(): Boolean
    fun detector(): DetSession
    fun recognizer(): RecSession
    fun characters(): List<String>
    fun release()
}
