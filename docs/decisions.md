# Architecture Decision Records (ADRs)

This file logs all major architectural and technical decisions made for the project.

---

## ADR-014: Real OCR via ONNX Runtime — RapidOCR PP-OCR EN Graphs, Paddle-Faithful Kotlin Pre/Post

* **Status**: ACCEPTED (2026-10-05; JVM-verified 275/275 incl. real-tensor goldens; VERIFIED ON DEVICE same day — 1 nesting defect found+fixed, ENTRY 010; ISSUE-007 CLOSED)
* **Date**: 2026-10-05
* **Context**: Phase 7 left a metadata-only seam (`FrameBuffer` carries no pixels by design). Phase 9 must feed REAL pixels to REAL models without breaking memory bounds, the `OcrEngine` contract, or the tracker/stabilizer state machines.
* **Decisions**:
  1. *Models*: `en_PP-OCRv3_det_infer.onnx` (2.4 MB, DB prob-map) + `en_PP-OCRv4_rec_mobile.onnx` (7.7 MB, CTC-97) + `en_dict.txt` (95 lines), SHA-pinned, Apache-2.0, validated 16/16 on a game-like corpus via the reference runtime (`research.md` §5C). EN-only (no `ch_*` bloat), FP32 (no pinned INT8 EN artifacts; 10 MB needs no quantization), no angle-cls (upright scope; rot90 rule kept).
  2. *Runtime*: ONNX Runtime CPU 4-thread (same options proven for NMT), behind a replaceable seam (`DetSession`/`RecSession`/`OcrSessionProvider` interfaces; ORT impl + scripted fakes). LiteRT not pursued (no pre-built PP-OCR `.tflite`; Flex-op risk, zero benefit); delegates unevaluated without hardware (CPU retained as production path, not just fallback).
  3. *Paddle-faithful Kotlin pre/post*: det resize max/960 + /32 banker's rounding + BGR-swapped normalize; simplified DB decode (AABB for rotated quads — downstream `BoundingBox` can't represent rotation anyway; golden IoU 0.867–0.953); CTC greedy decode incl. the `(sum+eps)/(n+1)` confidence quirk; rec `resize_norm_img` single-batch form; reference sort/filter/gate constants unchanged. `OcrEngine` and `OcrPipelineCoordinator` signatures untouched — the engine pulls snapshots through a provider lambda.
  4. *Wiring*: `MainActivity` constructs `OnnxOcrEngine(store::acquire, OrtOcrSessionProvider)`, loads off-main on capture start, releases on stop; tracker enabled with ADR-009 paired grace 3/3; rescan stays 1 (tuning awaits device numbers).
* **Alternatives rejected**: LiteRT conversion (above); INT8-quantized OCR (no pinned artifacts); PP-OCRv5/`ch_*` (size/vocab cost, no measured need); interface-breaking snapshot params (contract preservation); translating unstable text (unchanged rule); per-frame full-res retention (bounded slot instead — ADR-013).
* **Measured (dev-machine x64, informational)**: det cold 75–131 ms / warm 73–90 ms; rec ≈10–58 ms/region; tier-style JVM OCR path unmeasured on ART (ISSUE-007).
---

## ADR-015: Pixel Downscale Hoisting, NNAPI Evaluation, and Realistic Rescan Gating

* **Status**: ACCEPTED (2026-10-15; JVM-verified 278/278; device-verified 23/23 on VKJ-NX9, ENTRY 011)
* **Date**: 2026-10-15
* **Context**: Phase 10 optimization targeting ISSUE-008 latency bottlenecks: full-frame snapshot downscaling consuming ~385 ms, live OCR recognition not exhibiting expected rescan skips (~590 ms P50), and unverified NNAPI delegate performance.
* **Decisions**:
  1. *Downscaling Optimization (Opt A)*: In `SnapshotDownscaler`, hoist packed 32-bit pixel reads and inline channel blending (`blendChannel`). Verified on VKJ-NX9: P50 latency dropped from 385.2 ms to 115.2 ms (3.34× speedup, bit-identical output asserted on hardware).
  2. *NNAPI Candidate Evaluation (Negative Result)*: Evaluated NNAPI execution provider via `OcrNnapiProbeDeviceTest` on Snapdragon 7 Gen 4. NNAPI Det P50 = 146.8 ms vs ORT CPU-4T P50 102–142 ms. NNAPI yields no speedup on this SoC; CPU multi-threading retained as production path.
  3. *Instrumentation Defect Fix*: In `OcrPipelineCoordinator`, `recognizedRegionCount` recorded `rawResults.size` (total regions including skips) instead of actual model-recognized count. Corrected to `rawResults.size - skippedIds.size` and added `skipped` count to `OCR cycle:` logcat.
  4. *Rescan Gating Tolerance*: Relax `RESCAN_STABLE_IOU` default from 0.98 to 0.90 (configurable via `rescanStableIou`). Real DBNet detector outputs under game ambient rendering jitter by 1–2 pixels across consecutive frames, reducing IoU on typical UI boxes (e.g. 80×30 px) to 0.91–0.95 and preventing skips under 0.98. Threshold 0.90 absorbs detector jitter while strictly re-recognizing genuinely moved regions. Re-verification remains bounded by `ocrRescanIntervalCycles`.
* **Consequences**: Downscaler latency cut by ~270 ms; NNAPI dismissed with measured evidence; rescan skips can trigger on physical detector outputs; instrumentation accurately reflects skipped work.

---

## ADR-013: Bounded Latest-Only Pixel Snapshots (SnapshotStore)

* **Status**: ACCEPTED (2026-10-05; JVM-verified)
* **Date**: 2026-10-05
* **Context**: The coordinator consumes metadata-only `FrameBuffer`s from a conflated `StateFlow` (ADR-011 — byte arrays must never enter that flow or collectors pin megabytes). Real OCR still needs pixels, but full-frame retention, bitmap queues, and capture-thread blocking are all banned by the Phase 9 brief.
* **Decisions**:
  1. *Single lock-free slot*: `SnapshotStore` holds ≤ 1 immutable `OcrSnapshot` (pre-scaled RGB888, ≤ 960 side, ~1.3 MB); publish CAS-swaps (CaptureThread never blocks); newer replaces older (`overwritten` counted — latest-wins by construction, no queue can form).
  2. *Throttle by fake-clock*: publish refuses inside `SNAPSHOT_MIN_INTERVAL_MS = 200` (aligned with `maxOcrFps = 5`); the clock is caller-passed (`elapsedRealtime`, monotonic required) so the store is deterministic under test; `isDue()` lets the producer skip wasted downscales.
  3. *Immutable payload, no close protocol*: `OcrSnapshot` bytes are never mutated → producer/consumer can't race; "release" = drop the reference (GC). Live pixel bound ≈ 2 × 1.3 MB (store slot + engine active cycle). No native handles exist, so no deterministic-close machinery is warranted.
  4. *Staleness is bounded, not gated*: the engine consumes the latest available snapshot (≤ 200 ms old by throttle alignment); only start/stop `clear()`s the slot (a restarted session can never read pre-restart pixels). No-snapshot degrades to empty results (NoOp-shaped), which the coordinator already handles without state damage.
  5. *Mid-cycle pinning*: the engine holds the detection snapshot across that cycle's recognitions — a newer publish can never swap pixels under an in-flight crop batch.
* **Alternatives rejected**: `StateFlow<ByteArray>` pixels (pins memory across collectors); unbounded/2+-slot queues (complexity without benefit — OCR is slower than capture, so depth only adds staleness); explicit ref-counted buffers (no native resources to manage); capture-thread blocking acquire (violates non-blocking capture); arming protocol (standing ≤ 5 Hz publish is simpler and race-free).
* **Consequences**: `ScreenCaptureService` publishes changed+due frames only; `MainActivity`/coordinator unchanged structurally; `store.stats()` exposes published/overwritten/throttled for diagnostics.

## ADR-001: Multi-Rate Staged Pipeline Architecture

* **Status**: ACCEPTED
* **Date**: 2026-09-24
* **Context**: Real-time games render at 30 to 120 FPS. Deep neural networks (Text Detection, OCR Recognition, Neural Machine Translation) require 20ms–150ms per execution on mobile chips. Running deep models on every frame causes extreme frame drops, high power draw, and thermal throttling.
* **Options Evaluated**:
  1. *Per-frame synchronous pipeline*: Capture → OCR → Translate → Draw on every frame.
  2. *Single background thread pipeline*: Push frames synchronously to a single background worker.
  3. *Multi-rate staged asynchronous pipeline*: Decoupled rates for Capture (30 FPS), Change Detection (15 FPS), OCR (2–5 FPS), NMT (Async queue), and Overlay (On state change).
* **Selected Approach**: Option 3 — Multi-rate staged asynchronous pipeline.
* **Why**: Allows game rendering to remain completely smooth while AI inference runs only when screen content changes.
* **Advantages**: Zero impact on game FPS, dramatic battery savings, thermal stability.
* **Disadvantages**: Slightly higher architecture complexity (requires spatial tracking and request queues).
* **Rejected Alternatives**: Options 1 and 2 cause severe UI freezing and thermal throttling within seconds.
* **Consequences**: Internal interfaces must be strictly asynchronous using Kotlin Coroutines / Channels or RxJava.

---

## ADR-002: Android MediaProjection & Foreground Service Lifecycle

* **Status**: ACCEPTED
* **Date**: 2026-09-24
* **Context**: Android 14 (API 34) and Android 15 (API 35) introduced strict security enforcement around `MediaProjection` and foreground services. Failure to comply results in immediate `SecurityException` runtime crashes.
* **Options Evaluated**:
  1. *Legacy Service*: Standard foreground service starting capture token on boot or background.
  2. *Android 14/15 Compliant Foreground Service*: Declaring `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` in manifest, requesting user consent via `createScreenCaptureIntent()` prior to `startForeground()`, and handling non-persistent session tokens.
* **Selected Approach**: Option 2 — Android 14/15 Compliant Foreground Service.
* **Why**: Mandatory for API 34+ device support and Google Play policy compliance.
* **Advantages**: Clean system lifecycle compliance, robust session cleanup, no unexpected crashes.
* **Disadvantages**: User must grant consent once per session start. Token cannot be cached permanently.
* **Rejected Alternatives**: Option 1 crashes on Android 14+ devices.
* **Consequences**: App must implement clear UX for initiating capture sessions.

---

## ADR-003: Pluggable ML Runtimes & Acceleration Candidate Selection

* **Status**: ACCEPTED (Primary: LiteRT, Secondary: ONNX Runtime Mobile, Baseline Fallback: CPU)
* **Date**: 2026-09-24
* **Context**: The translation and OCR pipeline requires lightweight, efficient local inference engines with hardware acceleration candidates (GPU/NPU delegates) and a guaranteed multi-threaded CPU baseline fallback.
* **Options Evaluated**:
  1. *LiteRT (formerly TensorFlow Lite)*: Primary Android inference framework with candidate GPU and QNN delegates.
  2. *ONNX Runtime Mobile*: Framework-neutral runtime with candidate Execution Providers.
  3. *ExecuTorch*: PyTorch edge deployment runtime.
* **Selected Approach**: LiteRT as Primary Runtime, ONNX Runtime Mobile as Secondary Candidate, CPU Multi-threaded XNNPACK as Baseline Fallback. GPU (OpenCL) and NPU (QNN) delegates are candidate acceleration backends to be evaluated via benchmarks on target physical hardware before final production selection.
* **Why**: LiteRT provides native Android integration, small library footprint, and native `.tflite` model compatibility, while CPU fallback guarantees 100% hardware compatibility.
* **Advantages**: Zero risk of device lock-up; empirical benchmark selects final production backend.
* **Consequences**: All model wrappers implement abstract interfaces (`OcrEngine`, `TranslationEngine`).

---

## ADR-004: Native Android Text Layout & BIDI HarfBuzz Shaping

* **Status**: ACCEPTED
* **Date**: 2026-09-24
* **Context**: Arabic text requires complex visual shaping (joining initial/medial/final letter forms) and Unicode Bidirectional (BIDI) string rendering when mixed with English keys (`"Press E to open الباب"`).
* **Options Evaluated**:
  1. *Custom string reversal / basic mapping*: Reversing character arrays manually in Kotlin.
  2. *Native Android Layout Engine (`android.text.StaticLayout`, `BidiFormatter`, HarfBuzz)*: Standard Android text layout stack.
* **Selected Approach**: Option 2 — Native Android Layout Engine.
* **Why**: Custom string reversal fails catastrophically on ligatures, mixed numbers, and English substrings. Android's native text stack handles HarfBuzz shaping, RTL alignment, and BIDI rules out of the box.
* **Advantages**: 100% Arabic typography correctness, native support for custom Arabic fonts (Amiri, Cairo).
* **Disadvantages**: Requires configuring `TextPaint` and `StaticLayout` parameters carefully for overlay canvases.
* **Rejected Alternatives**: Option 1 leads to broken, disconnected Arabic letters.
* **Consequences**: All overlay drawing operations must use `StaticLayout` rather than raw `Canvas.drawText()`.

---

## ADR-005: Temporal OCR Stabilization & Bounded LRU Cache

* **Status**: ACCEPTED
* **Date**: 2026-09-24
* **Context**: Moving game frames introduce pixel noise, font aliasing, and background animation, causing raw OCR outputs to fluctuate frame-by-frame (e.g. `"Open"` vs `"Open!"`). Passing unstable strings to translation wastes model cycles and flickers UI.
* **Options Evaluated**:
  1. *Immediate Translation*: Send every detected OCR text directly to translation.
  2. *Temporal Voting + LRU Translation Cache*: Accumulate spatial bounding box tracking ($IoU > 0.6$), calculate normalized Levenshtein distance ($D_{norm} < 0.15$) over 3 frames before stabilizing, and cache normalized English → Arabic mappings in a bounded LRU cache (capacity = 500 entries).
* **Selected Approach**: Option 2 — Temporal Voting + Bounded LRU Cache.
* **Why**: Eliminates translation jitter, reduces NMT workload by 70–90% in static UI screens.
* **Advantages**: Extremely low latency for repeated UI text (cache hits < 1ms), rock-solid visual stability.
* **Disadvantages**: Introduces a minor 3-frame (~100ms) delay before initial translation of new text appears.
* **Rejected Alternatives**: Option 1 causes translation flickering and high power usage.
* **Consequences**: Implementation must include a clean spatial tracking component and LRU cache datastructure.

---

## ADR-012: Tiered Overlay Rendering — Explicit Policy + Pure Style Renderer

* **Status**: ACCEPTED (2026-10-04; JVM-verified 227/227; on-device visual gate pending)
* **Date**: 2026-10-04
* **Context**: Translated Arabic must stay readable over diverse game backgrounds (flat dialogue boxes vs busy 3D scenes) without heavy image processing. Phase 6 proved transparent outlined rendering; Phase 7 delivers regions to a sink. The missing piece is a visual-strategy layer that covers simple UI, masks medium regions, and floats transparently over complex scenes — without touching coordinates, shaping, or pipeline flow.
* **Decisions**:
  1. *Policy/renderer split*: `OverlayRenderTier` enum + `OverlayRenderPolicy` interface (decision, O(1), Android-free) vs `OverlayTierRenderer` (implementation). Same decoupling rationale as ADR-006 (`OcrEngine`/`TranslationEngine` seams).
  2. *Explicit selection only*: `ExplicitOverlayRenderPolicy` (default + per-region overrides, string-config parsing with Tier 3 fallback + fallback count). Automatic background-complexity detection is explicitly deferred — building it blind (no real game corpus yet, Phase 11) would be speculative CV work against the Phase 8 constraints.
  3. *Style-only tiers*: `DefaultOverlayTierRenderer` changes ONLY background/outline/shadow fields (Tier 1: opaque cover, upgrading translucent author boxes; Tier 2: fixed translucent mask `0xB3141414`; Tier 3: strip background, add fallback outline only to bare styles). No bitmaps, no sampling, no second coordinate system, id/bounds/text byte-identical by unit-tested contract.
  4. *Sink decorator*: `TieredOverlaySink` wraps any `OverlaySink` (production: `OverlayController`), applying policy+render per region with Tier 3 fallback, safe-drop, and per-region isolation; `clear()`/`hide()` pass through. `MainActivity` wires it with the all-Tier-3 default = identity transform (zero visual change vs Phase 7).
* **Alternatives rejected**: tier logic inside `OverlayController` (breaks the tested controller contract + mixes decision with window management); AI/heavy inpainting (banned by constraint); per-tier view subclasses (unnecessary — one `drawRect` path already handles all three); auto-detection now (no corpus, no validated signal).
* **Measured (JVM)**: policy+styling over 20 synthetic regions p50 2.8 µs / p95 15.9 µs per update (ENTRY 008) — negligible next to the 768 µs on-device layout build (ENTRY 006).
* **Consequences**: Phase 6 guarantees preserved (29 pre-existing overlay tests untouched, all green); device visual gate for Tiers 1/2 still required (ISSUE-006); Phase 11 selects per-game tiers explicitly until a detector exists.

---

## ADR-011: Integrated Async Pipeline — Bounded Latest-State Design

* **Status**: ACCEPTED (2026-10-04; JVM-verified 197/197; device E2E pending real OCR models)
* **Date**: 2026-10-04
* **Context**: Phase 7 wires capture→overlay with async translation. Dangers: unbounded queues, stale results overwriting fresh text, translating before stabilization, caching/overlaying unsafe outputs, blocking capture, leaking executors, depending on repeated emissions that conflated flows suppress.
* **Decisions**:
  1. *No queues, latest-state only*: one `Semaphore(permits)` (default 1 — ONNX concurrent safety unverified) guards inference; a launch finding no permit is SHED (`skippedBusy`), retried later. No FIFO of obsolete states can form by construction.
  2. *Revisions + worker-marshaled completions*: per-region monotonic revisions; completions run on the single pipeline worker; mismatched revisions drop (`staleDropped`) and immediately launch the latest text. Stale Arabic can never overwrite or outlive fresh text.
  3. *Retry pump (200 ms on the worker)*: REQUIRED, not optimization — `StateFlow` conflation between coordinator and pipeline means static content may never re-emit, so shed/cold/failed/unstable-first work would stall forever waiting for "the next cycle" (proven by stalled integration tests pre-pump). The pump launches only last-known-STABLE text (never violates stabilize-before-translate).
  4. *Publish gate*: overlay updates only for verified-restored OR span-free results (cache-admission mirror); failures/unverified hold previous (or nothing); vanished regions removed on first absence; pure box motion refreshes position without re-translation (4 px snap).
  5. *Coordinator delta (minimal)*: optional `RegionTracker` (downstream ids become trackIds) + `ocrRescanIntervalCycles` (skip re-OCR for stable+static+fresh tracks, bounded re-verify; default 1 = legacy-exact). No coordinator rewrite; `MainActivity` untouched by signature changes.
  6. *Wiring*: `MainActivity` connects coordinator → `GameTranslatorPipeline` → `OverlayController`, engine = `CachedTranslationEngine(DictionaryTranslationEngine())` (instant, safe-idle with NoOp OCR). ONNX swaps in at Phase 9 with its loadModels lifecycle — the seam is one constructor argument.
  7. *Threading*: capture thread + coordinator worker (existing) → pipeline worker (owned single thread, sole state owner) → translation pool (owned, permits-sized) → completions hop to worker → sink marshals to main. Owned executors created in `start`, closed in `stop` (rapid start/stop safe); late completions die on the cancelled scope (never touch overlay post-stop).
  8. *Viewport*: explicit display/capture/density/rotation config, atomic swap; steady-state capture==display (verified in service code); rotation refresh is owner-driven.
* **Alternatives rejected**: FIFO + cancellation tokens (complexity without benefit given latest-state sufficiency); relying on re-emissions for retries (disproven by conflation stall); translating unstable text speculatively (violates Part F); caching/overlaying flag-false span-bearing outputs (ADR-008).
* **Measured (JVM)**: synthetic E2E frame→overlay p50 ~515 ms (harness-paced, NOT a component claim); 10-region burst: 10 launched / 10 completed / 54 shed / 0 stale / max-concurrent 1 (ENTRY 007).
* **Known audit gap (NOT fixed here)**: no pixel transport capture→OCR (`FrameBuffer` is metadata-only by design) — real detectors need a bounded snapshot path, gated on Phase 9 models (no plumbing without models to validate against).
* **Consequences**: `fromCache=true` still implies safe output; `protectedTokensRestored=false` + spans suppresses overlay (metrics-visible); NMT-workload-reduction claims await production traffic (Phase 11).

---

## ADR-010: Overlay Rendering Layer (Phase 6, Stage A)

* **Status**: ACCEPTED (2026-10-04; JVM-verified; device validation pending)
* **Date**: 2026-10-04
* **Context**: Phase 6 needs Arabic shaping + bidi + transparent non-interactive overlay rendering. Constraints: no manual string reversal, no custom shaping unless native proves insufficient, `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCHABLE` + `FLAG_NOT_FOCUSABLE`, no full-screen bitmaps, no AI inpainting.
* **Decisions**:
  1. *Native shaping only* (extends ADR-004): `StaticLayout` + `FIRSTSTRONG_LTR` paragraph direction + HIGH_QUALITY breaking + NO hyphenation. Alignment maps logically (START→NORMAL, END→OPPOSITE, CENTER→CENTER) so direction follows the resolved paragraph natively. Outline = second stroke-paint layout pass (2× build, cached, zero bidi risk — manual per-run drawing would bypass platform runs); shadow = single pass.
  2. *Layering*: pure models (`OverlayStyle`, `OverlayRegion`/`OverlayRect`, `CoordinateMapper`, `BidiUtils` via `java.text.Bidi`) are Android-free and JVM-tested; Android classes (`ArabicTextLayout`, `OverlayRegionView`, `OverlayController`, `OverlayService`) are thin and device-tested. Capture (Int) vs overlay (Float) coordinates are distinct types; canonical system = physical display pixels, current orientation, scale + offset + {0,90,180,270} rotation support.
  3. *Controller/service split*: `OverlayController` (blocking-marshaled, idempotent, failure-isolated, permission-gated) owns the window; `OverlayService` is a thin lifecycle host. No static-held Context (service-scoped controller reached via `instance?.controller` — avoids StaticFieldLeak by construction). No regions through intents (same-process design).
  4. *No Robolectric*: its `android-all` runtime is absent offline (verified: no jar on disk), so JVM tests stay Android-free and all `StaticLayout`/window behavior goes to `androidTest` on the HONOR device.
  5. *Touch*: belt-and-braces (window flags + non-clickable/non-focusable view + `onTouchEvent=false`) verified by DISPATCHED-event tests, not flag inspection — plus a documented manual game-behind-overlay gate that stays pending until run on hardware.
* **Alternatives rejected**: custom shaping/reversal (banned by constraint); fragment re-layout per frame (layouts rebuild only on content change); Espresso/UIAutomator (not in deps, offline); Parcelable region intents (binder limits, unnecessary same-process).
* **Measured (JVM)**: mapper+bidi over 20 synthetic regions p50 ~12 µs. Device layout-build timing is logged by the device test for Stage B.
* **Consequences**: `originalText`-style raw preservation continues (`OverlayRegion.text` is logical-order, never mutated); Phase 7 wires OCR→translation→controller; visual tiering (solid/gradient/outline backgrounds) builds on `OverlayStyle.backgroundColor` + outline/shadow already present.

---

## ADR-009: Spatial ROI Tracking Rules + Stabilizer State Machine

* **Status**: ACCEPTED (2026-10-04; JVM-verified; coordinator wiring deferred to Phase 7)
* **Date**: 2026-10-04
* **Context**: ADR-005 requires IoU>0.6 spatial tracking plus Levenshtein voting over 3 frames, but leaves matching ambiguity, track lifecycle, and disappearance semantics undefined. The Phase 3 stabilizer (id-keyed ring buffer, no states, immediate prune) cannot distinguish new/changed/disappeared regions.
* **Decisions**:
  1. *Matching*: greedy bipartite assignment on IoU strictly > 0.6 (boundary == 0.6 does NOT match), ordered by IoU desc → older track → earlier detection. Each track/detection used once; losers open new tracks (detections) or age (tracks). A check-then-add discipline (not short-circuit set-add) is REQUIRED — the naive form crashes on ambiguity (found by unit test, fixed).
  2. *Lifecycle*: NEW → OBSERVING → STABLE ⇄ CHANGED, with DISAPPEARED (frozen window, ≤ grace absences) → REMOVED (> grace). Reappearance within grace resumes with no re-observation debt; after removal a look-alike starts at NEW (stale text cannot resurrect). Tracker and stabilizer grace windows MUST be paired to the same `maxMissedFrames` so both layers expire a track on the same cycle.
  3. *Evidence*: full window (default 3) mutually consistent within edit distance (default 1) of the window reference. Beyond-tolerance change restarts the window at the new text (CHANGED if ever stable). Within-tolerance drift keeps STABLE and advances `stableText` (score counters replace without round-trip). Empty and below-`minConfidence` observations are DROPPED (freeze, don't corrupt) — trade-off documented: a box permanently cleared but still detected holds last stable text until the track disappears (detectors normally drop empty boxes).
  4. *Scope*: `RegionTracker` + hardened `OcrResultStabilizer` are independently correct/tested; `OcrPipelineCoordinator` is NOT rewired this task (constructor-compatible defaults preserve its exact behavior). Phase 7 composition: detections → `tracker.update()` → `prune(activeTrackIds)` → `stabilize()` per track with `regionId = trackId`.
* **Alternatives rejected**: immediate prune on any absence (flickers through single-frame detector misses); holding last-stable output text in the result (changes `OcrResult` semantics — gating stays flag-based); appearance-based re-ID (out of scope for static game UI).
* **Measured**: full tracker+stabilizer cycle over synthetic 12-region frames (JVM): p50 16.7 µs, p95 92.8 µs — inside the 5 ms hot-path budget (ENTRY 004). Pure integer geometry; only transient is the O(tracks×detections) candidate list (pooling deferred to profiler evidence).
* **Consequences**: downstream (cache/translation) keys on `trackId`; `getState()`/`getStableText()` available for Phase 7 hold-last-stable overlay policy.

---

## ADR-008: Bounded LRU Translation Cache + Text Normalizer Semantics

* **Status**: ACCEPTED (2026-10-04; JVM-verified; on-device cache numbers pending)
* **Date**: 2026-10-04
* **Context**: Phase 5 requires a deterministic normalization layer and a bounded (500-entry) LRU translation cache (ADR-005 design), plus a defined policy for `protectedTokensRestored=false` results (open question left by ISSUE-004).
* **Options Evaluated**:
  1. *Aggressive normalization* (case folding, punctuation stripping, stemming) to maximize hit rate. REJECTED: changes meaning; a false hit serves a wrong translation with full confidence — worse than a miss.
  2. *Cache everything including degraded outputs* (flag=false, cold-engine echoes). REJECTED: cements potentially-unsafe outputs until eviction and poisons future lookups after the engine warms; re-translation is deterministic anyway, so caching buys latency but risks correctness.
  3. *Conservative normalizer + admission-gated LRU* (SELECTED): NFKC (matches the model's own `nmt_nfkc`), whitespace/line-break/quote canonicalization, control-char removal; case/punctuation/spans untouched. `CachedTranslationEngine` decorator (existing `TranslationCache` String→String interface, no parallel architecture) caches ONLY warm-engine results that are verified-restored OR span-free; cold echoes and flag=false span-bearing outputs are served but never stored. Hits therefore carry no restoration concern and report `protectedTokensRestored=true`.
* **Selected Approach**: Option 3 — `TextNormalizer` (pure, idempotent), `LruTranslationCache` (access-ordered `LinkedHashMap`, single-lock synchronized, O(1) expected, zero hot-path allocation beyond map internals), `CachedTranslationEngine` decorator preserving raw `originalText`.
* **Why**: Hit-rate chasing must never trade correctness; the admission rule makes "cached" synonymous with "safe", so downstream layers can trust `fromCache=true` unconditionally.
* **Measured**: engine-level hit latency (real API incl. normalize+lookup+result build, JVM dev machine): single-key p50 4.8µs / p95 12.6µs; 500-key cycling p50 4.1µs / p95 5.3µs — ~200× inside the 1 ms target (ENTRY 003).
* **Disadvantages / limits**: duplicate concurrent misses may translate twice (benign: deterministic); cache key is normalized-text-only (en→ar scope); pipeline wiring belongs to Phase 7; ART/JVM numbers will differ — device measurement outstanding.
* **Consequences**: `fromCache=true` implies admission-passed output; Phase 7 wires this decorator around the production engine; any normalizer extension must re-prove span byte-identity in unit tests.

---

## ADR-007: Numeric-Sentinel Token Protection (ISSUE-004 Resolution)

* **Status**: ACCEPTED (2026-10-04; on-device re-validation pending)
* **Date**: 2026-10-04
* **Context**: `TokenProtector` masked game variables with `VARn` placeholders. On-device, Opus-MT transliterated/mangled them (`فارلو`) or dropped them, and the `protectedTokensRestored` flag still reported success — silent corruption. See `issues.md` ISSUE-004 and `research.md` §5C.
* **Options Evaluated**:
  1. *Another alphabetic placeholder format* (`__VAR_0__`, `<ph0>`, …). REJECTED with evidence: the real 62,802-token vocabulary contains no such token; every variant fragments into translatable subwords (`VAR0` → `▁V/AR/0`) and is mangled by the real INT8 model.
  2. *Segment-and-rejoin* (translate text fragments around spans, never feeding spans to the model). REJECTED with evidence: isolated short fragments degenerate (`" and "` → `-----------`, `" "` → empty).
  3. *Short numeric sentinels* (`987650`+) with collision avoidance, verified restoration, and unmasked-retry fallback. SELECTED: copied verbatim in ~25/30 measured contexts across all required span classes; failure modes are detectable (`missingPlaceholders()`) instead of silent.
* **Selected Approach**: Option 3. Spans are masked with space-padded 6-digit sentinels (base 987650, bumped past any source digit-run collision; `80/100`-style ratios masked as one span; `100%`-style spans kept atomic). Engines report `protectedTokensRestored=true` only when every sentinel is found verbatim pre-restore; `OnnxTranslationEngine` retries once unmasked on loss; fallbacks return original text, never sentinel internals.
* **Why**: It is the only evaluated strategy validated against the actual tokenizer AND model rather than assumed. Tokenizer parity is preserved (untouched code path, +3 pinned cases) and the ONNX loop is byte-identical (extracted, not rewritten).
* **Advantages**: Value preservation is exact across numbers, `%`-specifiers, `{vars}`, currency, units, tags, ratios, multi/repeated spans; corruption becomes a flagged, recoverable event.
* **Disadvantages / Honest limits**: pathological model contexts still degrade with or without masking (catalogued in `research.md` §5C); fallback output then carries `protectedTokensRestored=false` and downstream layers (Phases 5–7) must define behavior for it; on-device ARM64 re-validation outstanding.
* **Rejected Alternatives**: Options 1 and 2 (measured failures above); leaving `VARn` in place (proven corruption); masking nothing (drops `{vars}`/`#ids`/`$`/tags, mistranslates `%d`).
* **Consequences**: `TokenProtector` placeholder format is a measured model-interface contract — any future change requires re-running the §5C probe matrix, not just unit tests.

---

## ADR-006: Verification-Driven Model Abstraction & Fact-Checked Benchmarks

* **Status**: ACCEPTED
* **Date**: 2026-09-24
* **Context**: Preliminary model size estimates and latencies were unverified hypotheses. In reality, PP-OCRv4 Mobile det+rec combined is ~15 MB FP32 (~5-8 MB INT8), and Opus-MT unquantized is ~298 MB FP32 (requiring custom PyTorch → ONNX → LiteRT conversion). Furthermore, non-interactive overlay pass-through via `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` is an intended design that requires validation across Android versions, and GPU/NPU delegates are candidate backends requiring empirical benchmarking.
* **Options Evaluated**:
  1. *Hard-code pipeline around specific model file paths, fixed acceleration backends, and hard-coded performance claims*.
  2. *Encapsulate all ML components behind generic Kotlin interfaces (`OcrEngine`, `TranslationEngine`)*, separate target performance budgets into "Benchmark Required" categories, treat delegates as candidates, and allow model/runtime swapping post-benchmarking.
* **Selected Approach**: Option 2 — Encapsulate behind generic Kotlin interfaces.
* **Why**: Guarantees zero architecture rewrite regardless of which candidate model or backend wins empirical benchmarks on physical devices.
* **Advantages**: Clean modularity, robust fallback mechanisms, zero speculative lock-in.
* **Consequences**: All pipeline components interact strictly with `OcrEngine` and `TranslationEngine` abstractions.
