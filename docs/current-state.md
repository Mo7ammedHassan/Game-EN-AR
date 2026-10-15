# Current Project State (Verified Reality)

This document reflects the verified reality of the repository. It must be kept accurate after every major work milestone.

---

## 1. EXECUTIVE SUMMARY

* **Current Phase**: Phase 4.1 — Real Translation Inference Proof (**COMPLETE & VERIFIED on physical ARM64 hardware**) + **ISSUE-004 token-protection fix (FULLY VERIFIED ON DEVICE 2026-10-04 — CLOSED)**.
* **Overall Status**:
  - **Phase 4.1 Real Inference Proof**: **COMPLETE & VERIFIED**. `Helsinki-NLP/opus-mt-en-ar` INT8 ONNX executed end-to-end **on a physical Snapdragon 7 Gen 4 (VKJ-NX9, Android 16, 11.6 GB RAM)**: cold load 2441 ms (incl. first-install asset copy), warm 34–47 ms per short UI string (10 ms immediate-EOS), +241 MB PSS for 3 sessions, 0 crashes. Real Arabic output for 4/5 sentences (5th empty per genuine model behavior). Full numbers in `benchmarks.md` ENTRY 001.
  - **Token Protector & Placeholders**: **REWORKED & FULLY VERIFIED ON DEVICE**. `VARn` placeholders RETIRED (proven unfixable: `VAR0` → `▁V/AR/0`, mangled/dropped by the real model). `TokenProtector` masks with space-padded 6-digit numeric sentinels (base 987650, collision-avoided), masks `80/100`-style ratios as one span, keeps `100%`-style spans atomic, exposes `missingPlaceholders()`. On-device proof (VKJ-NX9, 2026-10-04): `You have 50 HP remaining.` → `لديك 50 HP باق.` (61.7 ms, flag true); `Hello {playerName}…` → `مرحباً يا {playerName} مرحباً بكم مرة أخرى` (82.8 ms, flag true); zero sentinel residue; 0 crashes. Full matrix `research.md` §5C, numbers ENTRY 002.
  - **Translation Engine Implementations**: **IMPLEMENTED & VERIFIED**:
    - `NoOpTranslationEngine`: Pass-through baseline for zero-dependency pipeline tests.
    - `DictionaryTranslationEngine`: Deterministic in-memory glossary of high-frequency game UI phrases translated into neutral Modern Standard Arabic (MSA / Fusha), providing a guaranteed fast (< 1 ms), 100% offline CPU fallback. Now reports the honest `protectedTokensRestored` flag.
    - `LiteRtTranslationEngine`: Modular skeleton for LiteRT Seq2Seq execution with separate encoder/decoder model loading off the hot path and CPU multi-threaded (4 threads) XNNPACK fallback.
    - `OnnxTranslationEngine`: Full 3-session (encoder + decoder + decoder-with-past) greedy autoregressive decoder behind the `TranslationEngine` contract (**executed on physical ARM64 (VKJ-NX9, Snapdragon 7 Gen 4): real Arabic on VKJ-NX9, 34–47 ms warm, +241 MB PSS, 0 crashes** (ENTRY 001; tensor ownership discipline per ISSUE-005). Greedy loop extracted byte-identical to `runGreedyInference()`; restore is now verified (`missingPlaceholders()` + one unmasked retry on sentinel loss); model-absent/exception fallbacks return ORIGINAL text, never sentinel internals.
  - **SentencePiece Tokenizer**: **IMPLEMENTED & VERIFIED** (unit tests). `SentencePieceTokenizer` implements the exact HF segmentation: source.spm unigram scores (Viterbi) + 62,802-token `vocab.json` ID remap (`\uXXXX` unescaping) + leading-`▁` + EOS, per-char `<unk>` fallback. **Parity audit: Android IDs == HF IDs on 16/16 inputs** (13 original + 3 sentinel-masked shapes).
  - **Translation Cache Abstraction**: **IMPLEMENTED & VERIFIED**. `TranslationCache` and `NoOpTranslationCache` interfaces.
  - **Text Normalizer**: **IMPLEMENTED & VERIFIED**. `TextNormalizer` (NFKC + whitespace/line-break/quote canonicalization; case/punctuation/spans untouched; idempotent). Cache key + model input use it; raw text preserved in `originalText`.
  - **Bounded LRU Translation Cache**: **IMPLEMENTED & VERIFIED (JVM)**. `LruTranslationCache` (capacity 500, deterministic eviction, O(1) expected, thread-safe) + `CachedTranslationEngine` decorator with ADR-008 admission (never caches flag=false span-bearing or cold-engine outputs; hits report flag true). Hit latency (real API, JVM): p50 ~4–5 µs (ENTRY 003). Pipeline wiring deferred to Phase 7; on-device cache numbers pending.
  - **Spatial ROI Tracker**: **IMPLEMENTED & VERIFIED (JVM)**. `RegionTracker` (IoU strictly > 0.6 greedy bipartite matching, deterministic tie-breaks, grace-aged stale cleanup, creation-order output). An ambiguity unit test exposed and fixed a real greedy-matching crash (check-then-add discipline).
  - **Temporal OCR Stabilizer (hardened)**: **IMPLEMENTED & VERIFIED (JVM)**. `OcrResultStabilizer` with explicit `TrackState` machine (OBSERVING/STABLE/CHANGED/DISAPPEARED), grace-counted disappearance, empty/low-confidence freeze, cross-region isolation. All 12 Phase 3 tests pass unchanged (compatible defaults). Tracker↔stabilizer composition integration-tested (7 scenarios); coordinator NOT rewired (Phase 7). Hot-path cost (12 regions, JVM): p50 16.7 µs (ENTRY 004).
  - **Overlay Rendering Foundation (Phase 6)**: **COMPLETE & VERIFIED ON DEVICE 2026-10-04 (VKJ-NX9)**. Pure models + Android layer (native shaping FIRSTSTRONG, non-interactive controller/service). 20/20 device tests (layout matrix incl. order/pixel/alpha asserts, dispatched-touch, lifecycle/rapid/refusal); eye-verified render bitmap (shaping/RTL/mixed/wrap/clip/ellipsis/styles/transparency); tap-through-overlay launched a real app, swipe paged the launcher, focus never stolen; layout build p50 768 µs. Accepted: server-side window alpha 0.8 (client 1.0 asserted; OEM policy). 1 real alpha-composition bug found+fixed by pixel gate.
  - **Build Status**: **PASS**. Debug APK compiled with Gradle 8.7 on **Eclipse Temurin JDK 21.0.12.1 LTS** (`BUILD SUCCESSFUL`).
  - **Unit Tests**: **PASS**. **60/60 unit tests passed** across 12 test suites (`testDebugUnitTest`, 2026-10-04).
  - **Lint / Static Analysis**: **PASS**. `lintDebug` completed without errors.
  - **APK Output**: **YES**. Verified at `d:\GamesEn-Ar\app\build\outputs\apk\debug\app-debug.apk` (212,446,757 bytes — includes proof-bundled INT8 ONNX assets in `app/src/main/assets/`, unchanged by this fix; cf. 59.91 MB without bundled models).
  - **Physical Device Validation**: **VERIFIED** (VKJ-NX9, Snapdragon 7 Gen 4, Android 16 — benchmarks.md ENTRY 001 (VAR path) + ENTRY 002 (sentinel path, ISSUE-004 closure)).
* **Last Verified Build**: 2026-10-15 (BUILD SUCCESSFUL, 278/278 unit tests, debug APK installed and verified on hardware).
* **Last Verified Test Run**: 2026-10-15 (`testDebugUnitTest` **278/278 PASSED**; `connectedDebugAndroidTest` **23/23 PASSED on VKJ-NX9** — full suite incl. OCR gate, NNAPI probe, A/B downscale benchmark: Opt A 3.34× faster at 115 ms vs 385 ms P50; rescan gating relaxed to 0.90 with instrumentation defect fixed; ENTRY 011).

---

## 2. COMPONENT STATUS MATRIX

| Component | Status | Implementation Details / Notes | Verified? |
| :--- | :--- | :--- | :--- |
| **AGENTS.md Rules** | COMPLETE | Operating instructions and agent protocol initialized. | YES |
| **Documentation Map** | COMPLETE | `/docs/` structure fully populated and audited. | YES |
| **Android Project Setup** | VERIFIED | Gradle 8.7 + Kotlin DSL (`build.gradle.kts`, `settings.gradle.kts`, `libs.versions.toml`). | YES |
| **JDK Environment** | VERIFIED | Eclipse Temurin JDK 21 LTS (`C:\Users\asus\.jdks\jdk-21.0.12.1+1`). | YES |
| **Screen Capture Engine** | REVIEWED & VERIFIED | `ScreenCaptureService` (MediaProjection + HandlerThread + atomic shutdown guard). | YES |
| **Frame Rate Limiter** | VERIFIED | Atomic nano-second `FrameRateLimiter` (30 FPS capture target). | YES |
| **Level 1 Change Detector** | IMPLEMENTED & VERIFIED | `FrameChangeDetector` (4× downscaled luminance block hash + Hamming distance on CaptureThread). | YES |
| **Pluggable OCR Contract** | COMPLETE | Abstract `OcrEngine` Kotlin interface. | YES |
| **OCR Pipeline Coordinator** | IMPLEMENTED & VERIFIED | `OcrPipelineCoordinator` on `Dispatchers.Default` with two-level gating (`isChanged` + 5 FPS cap). | YES |
| **OCR Stabilizer** | IMPLEMENTED & VERIFIED | `OcrResultStabilizer` (3-frame Levenshtein ring buffer). | YES |
| **Pluggable Translation Contract**| COMPLETE | Abstract `TranslationEngine` Kotlin interface. | YES |
| **Token Protector & Variables** | REWORKED & VERIFIED ON DEVICE | `TokenProtector` with numeric sentinels (987650+, collision-avoided), ratio spans, atomic `%`-spans, verifiable restore. Closed 2026-10-04 (ENTRY 002). | YES (16/16 tests + 2/2 device) |
| **Dictionary Translation Engine** | IMPLEMENTED & VERIFIED | `DictionaryTranslationEngine` with neutral MSA game glossary & instant CPU fallback; honest restore flag. | YES (5/5 tests) |
| **LiteRT Translation Engine Skeleton**| IMPLEMENTED & VERIFIED | `LiteRtTranslationEngine` for MarianMT Seq2Seq models with separate load step. Superseded by ONNX path for Phase 4.1. | YES (Compilation & Types) |
| **ONNX Translation Engine** | IMPLEMENTED & VERIFIED ON DEVICE | `OnnxTranslationEngine`: 3-session greedy decoder; verified restore + unmasked retry; original-text fallbacks. Sentinel path proven on VKJ-NX9 (ENTRY 002). | YES (2/2 connected instrumentation tests) |
| **SentencePiece Tokenizer** | IMPLEMENTED & VERIFIED | `SentencePieceTokenizer`: source.spm unigram-score Viterbi + 62,802-token `vocab.json` ID remap + leading-`▁` + EOS + per-char `<unk>` fallback. Parity: 16/16 exact ID match vs HF. | YES (3/3 tests) |
| **Translation Cache Abstraction** | IMPLEMENTED & VERIFIED | `TranslationCache` & `NoOpTranslationCache` interfaces. | YES (2/2 tests) |
| **Text Normalizer** | IMPLEMENTED & VERIFIED | `TextNormalizer`: NFKC + whitespace canonicalization; meaning-preserving. | YES (13/13 tests) |
| **Bounded LRU Cache + Decorator** | IMPLEMENTED & VERIFIED (JVM) | `LruTranslationCache` (500, deterministic LRU, thread-safe) + `CachedTranslationEngine` with admission policy. On-device numbers pending. | YES (24/24 tests: 13 LRU + 9 engine + 2 bench) |
| **Spatial ROI Tracker** | IMPLEMENTED & VERIFIED (JVM) | `RegionTracker`: IoU>0.6 greedy matching, deterministic ties, grace expiry. 1 real bug found+fixed by tests. | YES (19/19 tests) |
| **Temporal Stabilizer (hardened)** | IMPLEMENTED & VERIFIED (JVM) | `TrackState` machine + grace + freeze rules; 12 legacy tests unchanged. Composition integration-tested (7). Coordinator untouched. | YES (25/25 tests: 12 legacy + 13 new) |
| **Overlay Foundation (6A)** | IMPLEMENTED & VERIFIED (JVM+DEVICE) | Pure models + Android render layer; 20/20 device green, visual+touch+lifecycle gates pass. Server alpha 0.8 accepted. | YES (27 JVM + 20 device) |
| **Integrated Pipeline (7)** | IMPLEMENTED & VERIFIED (JVM) | Bounded revisions/supersession/stale-drop, 200 ms retry pump, publish gate, lifecycle-safe executors; coordinator tracker+rescan (legacy-exact defaults); MainActivity wired (dictionary engine, safe-idle). | YES (34 new: 19 pipeline + 8 coordinator + 5 integration + 2 bench) |
| **Tiered Overlay Rendering (8)** | IMPLEMENTED & VERIFIED (JVM; device visual gate pending — ISSUE-006) | `OverlayRenderTier` (cover/mask/transparent) + `ExplicitOverlayRenderPolicy` (default+overrides, config fallback) + `DefaultOverlayTierRenderer` (pure style transforms, byte-identical id/bounds/text) + `TieredOverlaySink` (Tier-3 fallback, safe drop, per-region isolation, counts-only metrics); wired in MainActivity (all-Tier-3 default = identity). No auto-detection (deferred). | YES (30 new: 29 tier + 1 bench) |
| **Bounded Pixel Transport (9A)** | IMPLEMENTED & VERIFIED (JVM) | `SnapshotStore` (1 lock-free latest-only slot, 200 ms throttle, clear-on-stop/start) + `SnapshotDownscaler` (in-place RGBA bilinear ≤960 side); service publishes changed+due only with metadata-only fallback. | YES (17: 8 store + 9 pixel-math incl. cv2 golden) |
| **Real OCR Path (9B–E)** | COMPLETE & VERIFIED ON DEVICE 2026-10-05 (VKJ-NX9) | Pinned PP-OCR EN det (2.4 MB) + rec (7.7 MB) + dict (Apache-2.0); ORT CPU-4T; 1 real nesting defect found+fixed by the gate (JVM-pinned). Device: cold 332–360 ms, det 223–396 ms, rec ~24–32 ms/region, corpus exact sets, live MLBB 25 det / 13–14 stable / 18 published with co-located overlay, UI-stop teardown (PSS 299→196 MB), 0 crashes. ENTRY 010; budgets→ISSUE-008. | YES (275 JVM incl. 2 pin; 21/21 device incl. OCR gate) |
| **Translation Pipeline Stats** | IMPLEMENTED & VERIFIED | `TranslationPipelineStats` latency breakdown data class. | YES (3/3 tests) |
| **Debug APK Output** | VERIFIED | `app-debug.apk` compiled and verified on disk (59.91 MB; growth from ONNX Runtime + LiteRT native libs, no models bundled). | YES |
| **Physical Device Verification**| VERIFIED | VKJ-NX9 (Snapdragon 7 Gen 4, Android 16). ENTRY 001. | YES |
| **Translation LRU Cache** | NOT STARTED | Scheduled for Phase 5. | NO |
| **Spatial ROI Tracker (IoU)** | NOT STARTED | Scheduled for Phase 5. | NO |
| **Arabic BIDI & Layout** | NOT STARTED | Scheduled for Phase 6. | NO |
| **System Overlay Service** | NOT STARTED | Scheduled for Phase 6. | NO |

---

## 3. VERIFIED vs BENCHMARK REQUIRED MATRIX (PHASE 4)

| Component / Claim | Classification | Ground Truth |
| :--- | :--- | :--- |
| `TokenProtector` variable masking/restoration | **VERIFIED** | Unit tests cover `{player}`, `%d`, `%s`, `%.2f`, `$100`, `50G`, `100%`, `50 XP`, `<color>` tags, `80/100` ratios, multi-/repeated-span round-trips, collision avoidance, and mangling detection; sentinel copy validated on the real INT8 model (`research.md` §5C). |
| `DictionaryTranslationEngine` MSA accuracy | **VERIFIED** | 5/5 unit tests verify correct neutral Modern Standard Arabic terms for game actions/menus. |
| `DictionaryTranslationEngine` latency (< 1 ms) | **VERIFIED** | In-memory hash lookup is sub-millisecond on JVM test runner. |
| `Helsinki-NLP/opus-mt-en-ar` parameters (~74.4M) | **VERIFIED** | Official HuggingFace / MarianMT architecture specification. |
| `Helsinki-NLP/opus-mt-en-ar` license (CC-BY-4.0) | **VERIFIED** | Official HuggingFace repository license card. |
| Opus-MT INT8 converted size (~40–75 MB) | **HYPOTHESIS / BENCHMARK REQUIRED** | Model conversion and quantization must be validated on disk. |
| Opus-MT INT8 inference latency (< 100 ms) | **BENCHMARK REQUIRED** | Speculative target budget; must be measured on target device with autoregressive decoder. |
| Peak RAM during NMT inference (< 150 MB) | **BENCHMARK REQUIRED** | Requires Android Profiler run on physical ARM64 hardware. |
