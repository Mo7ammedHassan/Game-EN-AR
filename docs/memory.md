# High-Level Persistent Project Memory

## 1. PROJECT OVERVIEW
The goal of this project is to build an autonomous, production-quality Android application capable of translating English text in real-time from Android games into Arabic without user intervention or network access.

### Core User Experience:
Game → MediaProjection Screen Capture → Cheap Frame Change Detection → English Text Detection → English Recognition (OCR) → Temporal Stabilization → Text Normalization & Token Protection → Bounded Translation Cache → Offline Neural Machine Translation (NMT) Model → Arabic BIDI Shaping & Layout → Non-interactive System Overlay.

---

## 2. KEY GOALS & NON-GOALS

### Goals:
* 100% offline real-time translation pipeline during gameplay.
* Multi-rate architecture ensuring zero frame-drop impact on game rendering (30/60/90/120 FPS).
* Temporal OCR stabilization to eliminate text flickering across consecutive frames.
* Correct Arabic RTL rendering, bidirectional text handling (e.g. mixed English/Arabic/numbers/placeholders), proper character shaping.
* Tiered background replacement/overlay to maintain readability over complex game screens.
* Bounded memory consumption, low battery impact, and sustained performance via thermal throttling awareness.

### Non-Goals:
* Real-time generative image inpainting of game backgrounds.
* Large general-purpose LLMs (600M+ parameters) as the primary translation engine.
* Root access, code injection, accessibility hacks, or touch hijacking.
* Cloud API dependencies during gameplay.
* Translating all world languages (scope focused on English → Arabic).

---

## 3. ARCHITECTURE SUMMARY
- **Capture Engine**: `MediaProjection` API producing `HardwareBuffer`/`ImageReader` surfaces inside an isolated `ForegroundService` with `mediaProjection` service type (compliant with Android 14/15 restrictions).
- **Staged Pipeline**:
  - *L1*: Downsampled luminance frame difference / hash check.
  - *L2*: Region-Of-Interest (ROI) text detection model (`OcrEngine.detectRegions()`).
  - *L3*: English OCR recognition (`OcrEngine.recognizeRegion()`) & Temporal Stabilizer.
  - *L4*: Token Protection (`{player}`, `%d`, `$100`), Text Normalizer, and LRU Cache.
  - *L5*: Offline NMT Engine (`TranslationEngine.translate()`).
  - *L6*: BIDI Arabic Shaping (`android.text.TextPaint`, `StaticLayout`, HarfBuzz/Bidi) & Tiered Canvas Overlay.

---

## 4. FINAL HANDOFF SECTION

```yaml
CURRENT PHASE: PHASE 10 IN PROGRESS (PARTIAL) — JVM 278/278 PASS, Device 23/23 PASS on VKJ-NX9; Opt A downscaler proven 3.34x (115 ms vs 385 ms P50); NNAPI probed & rejected (slower than CPU); rescan gating loosened to 0.90 with instrumentation defect fixed; ENTRY 011 recorded.
CURRENT STATUS: Real OCR live E2E on hardware with Phase 10 optimizations: Opt A packed-read downscale in SnapshotDownscaler, NNAPI evaluated via OcrNnapiProbeDeviceTest, rescan gating repaired in OcrPipelineCoordinator (IoU 0.90 tolerance + honest unskipped count). App installed on VKJ-NX9 with SYSTEM_ALERT_WINDOW active. ENTRY 009, 010, and 011 committed to benchmarks.md.
LAST VERIFIED: 2026-10-15 (VKJ-NX9, Snapdragon 7 Gen 4) — testDebugUnitTest 278/278 PASSED; connectedDebugAndroidTest 23/23 PASSED (10 layout + 1 NNAPI + 1 OCR + 1 stage bench + 2 NMT + 5 service + 3 touch); Opt A downscaler 115.2 ms P50 (vs 385.2 ms old); NNAPI det 146.8 ms P50 (vs CPU 102-142 ms); assembleDebug + installDebug + appops SYSTEM_ALERT_WINDOW allow VERIFIED.
CURRENT BLOCKER: None. Open Phase 10 follow-up: sustained thermal run & memory stability profile.
NEXT TASK: Phase 10 sustained thermal run / profiling & Phase 11 game validation.
NEXT TASK: (Done 2026-10-04) On-device inference re-measured with sentinel path — cold-load 2909 ms, warm 38–83 ms, PSS 543/544 MB (ENTRY 002). Model-delivery decision (~230 MB) still open for Phase 9/12.
IMPORTANT CONSTRAINTS:
  - All translation inference runs asynchronously off the main thread behind TranslationEngine.
  - Preserve CPU fallback path (DictionaryTranslationEngine / CPU multi-threading).
  - Do not bundle ~230 MB INT8 models in the APK before hardware benchmarks and a model-delivery decision.
  - Target Arabic output is natural, neutral Modern Standard Arabic (MSA / Fusha), not dialectal.
  - Latency, RAM, and quantization size claims remain classified as BENCHMARK REQUIRED until measured on physical devices.
  - Android tokenizer MUST use vocab.json (62,802 tokens) + EOS, never raw 32k source.spm; JSON parser must unescape \uXXXX.
  - "Save Game" -> empty output is genuine model behavior (immediate EOS, confirmed on PyTorch); cover via dictionary fallback.
IMPORTANT RECENT DECISIONS:
  - ADR-015 (2026-10-15): Pixel downscale hoisting (Opt A, 3.34x speedup to 115 ms P50 on VKJ-NX9), NNAPI evaluated and dismissed (slower than CPU-4T), rescan gating relaxed to 0.90 to absorb detector jitter + recognizedRegionCount instrumentation repair.
  - ADR-014 (2026-10-05): Real OCR on ORT CPU — pinned PP-OCR EN det+rec (FP32, 10 MB, Apache-2.0, 16/16 reference) + replaceable session seam + Paddle-faithful Kotlin pre/post (AABB DB simplification, golden-pinned) behind the UNCHANGED OcrEngine contract; LiteRT/INT8/delegates rejected-or-deferred with evidence.
  - ADR-013 (2026-10-05): Bounded latest-only pixel snapshots — single lock-free slot (~1.3 MB), 200 ms fake-clock throttle, immutable payload (drop-to-release), bounded staleness, clear-on-stop/start, mid-cycle pinning.
  - ADR-012 (2026-10-04): Tiered overlay rendering — explicit policy (default+overrides, Tier-3 config fallback) + pure style renderer (opaque cover / fixed translucent mask / transparent+readability, byte-identical id/bounds/text) + TieredOverlaySink decorator (Tier-3 fallback, safe drop, per-region isolation); auto-detection deferred (ISSUE-006).
  - ADR-011 (2026-10-04): Integrated async pipeline — bounded latest-state (semaphore+shed, no queues), revisions + worker-marshaled completions, 200 ms retry pump (StateFlow conflation!), publish gate mirroring cache admission, coordinator tracker+rescan with legacy defaults, MainActivity wiring (dictionary engine, ONNX swap = 1 arg in Phase 9).
  - ADR-001: Multi-rate staged pipeline architecture.
  - ADR-007 (2026-10-04): Numeric-sentinel token protection (ISSUE-004) — placeholder format is a measured model-interface contract; changes require re-running the research.md S5C probe matrix.
  - ADR-008 (2026-10-04): Bounded LRU cache + normalizer semantics — cache ONLY warm, verified-restored or span-free outputs; flag=false span-bearing and cold echoes are served but never stored; hits report flag true.
  - ADR-009 (2026-10-04): Spatial tracking rules + stabilizer state machine — strict IoU>0.6 greedy matching with deterministic ties; OBSERVING/STABLE/CHANGED/DISAPPEARED lifecycle; paired tracker/stabilizer grace; coordinator wiring deferred to Phase 7.
  - ADR-010 (2026-10-04): Overlay rendering layer — native StaticLayout/FIRSTSTRONG only (no reversal/shaping); controller/service split with no static-held Context; Robolectric rejected (no offline runtime); touch verified by dispatched events + manual gate.
  - ADR-011 (2026-10-04): Integrated async pipeline — bounded latest-state (semaphore+shed, no queues), revisions + worker-marshaled completions, 200 ms retry pump (StateFlow conflation!), publish gate mirroring cache admission, coordinator tracker+rescan with legacy defaults, MainActivity wiring (dictionary engine, ONNX swap = 1 arg in Phase 9).
  - ADR-012 (2026-10-04): Tiered overlay rendering — explicit policy (default+overrides, Tier-3 config fallback) + pure style renderer (opaque cover / fixed translucent mask / transparent+readability, byte-identical id/bounds/text) + TieredOverlaySink decorator (Tier-3 fallback, safe drop, per-region isolation); auto-detection deferred (ISSUE-006).
  - ADR-013 (2026-10-05): Bounded latest-only pixel snapshots — single lock-free slot (~1.3 MB), 200 ms fake-clock throttle, immutable payload (drop-to-release), bounded staleness, clear-on-stop/start, mid-cycle pinning.
  - ADR-014 (2026-10-05): Real OCR on ORT CPU — pinned PP-OCR EN det+rec (FP32, 10 MB, Apache-2.0, 16/16 reference) + replaceable session seam + Paddle-faithful Kotlin pre/post (AABB DB simplification, golden-pinned) behind the UNCHANGED OcrEngine contract; LiteRT/INT8/delegates rejected-or-deferred with evidence.
  - Phase 4.1: ONNX Runtime selected over LiteRT for the NMT path (Opus-MT exports 3 standard ONNX graphs; ORT Mobile 1.17.0 integrated).
  - Phase 4: TokenProtector uses single-pass non-overlapping unified regex to avoid recursive placeholder replacements.
  - Phase 5 (2026-10-04): TokenProtector uses space-padded 6-digit sentinels (base 987650, collision-avoided), single-span ratios, atomic %-spans, verifiable restore + unmasked-retry fallback; fallbacks return original text.
  - Phase 4: DictionaryTranslationEngine provides deterministic, instant (< 1 ms), 100% offline baseline and fallback for game UI.
  - Phase 4: Primary NMT candidate is Helsinki-NLP/opus-mt-en-ar (CC-BY-4.0, 6 encoder / 6 decoder MarianMT, ~74.4M params).
IMPORTANT FAILED APPROACHES:
  - Multi-pass regex for TokenProtector (causes recursive matching of digits inside placeholder tokens like __VAR_0__).
  - Claiming Opus-MT is available as pre-built TFLite (requires PyTorch -> ONNX -> LiteRT conversion pipeline).
  - Raw source.spm (32k) tokenization on Android (produces different IDs than HF 62,802-token vocab; must use vocab.json).
  - VARn / any alphabetic placeholder for NMT masking (fragments into translatable subwords, mangled/dropped by the model — proven on real INT8, 2026-10-04).
  - Naive segment-and-rejoin translation of fragments around spans (isolated short fragments degenerate).
  - 9-digit numeric sentinels (dropped by the model; cap is 6 digits).
  - Naive JSON string parsing without \uXXXX unescaping (silently degrades tokenization to <unk>).
  - Type-grouped past_key_values index mapping (must be layer-major: dec_out[1 + i*4]).
RELEVANT BENCHMARKS:
  - PP-OCRv4 Mobile Det ONNX: ~4.6 MB FP32 (~2.5 MB INT8) [VERIFIED].
  - PP-OCRv4 Mobile Rec ONNX: ~10.4 MB FP32 (~5.0 MB INT8) [VERIFIED].
  - Opus-MT en-ar PyTorch FP32: ~298 MB [VERIFIED].
  - Opus-MT INT8 ONNX on disk: encoder 51.6 MB + decoder 90.6 MB + decoder-with-past 87.4 MB (~229.6 MB total) [VERIFIED].
  - Opus-MT warm latency ON DEVICE (VKJ-NX9, Snapdragon 7 Gen 4, ORT 1.17 CPU 4 threads, greedy): 34–47 ms short UI strings, 10 ms immediate-EOS input [VERIFIED — benchmarks.md ENTRY 001].
  - Opus-MT cold load ON DEVICE: 2441 ms incl. first-install 232 MB asset copy [VERIFIED — ENTRY 001].
  - Opus-MT session footprint ON DEVICE: +241 MB PSS [VERIFIED — exceeds <150 MB budget, see ISSUE-004/next-phase work].
  - Opus-MT PyTorch warm latency, dev machine (greedy): 119–255 ms short UI strings, 34 ms immediate-EOS input [VERIFIED — dev machine only].
  - Opus-MT INT8 warm latency, dev machine (ORT 1.30 CPU, greedy): 40–210 ms incl. sentinel-masked inputs; 6-digit sentinels copied verbatim ~25/30 contexts [VERIFIED — dev machine only, research.md S5C].
  - DictionaryTranslationEngine latency: < 1 ms [VERIFIED].
  - APK size: 212 MB with proof-bundled INT8 assets (59.91 MB without models) [VERIFIED].
  - Sentinel-masking path on-device: VERIFIED (VKJ-NX9 2026-10-04 — ENTRY 002: 50 HP + {playerName} restored, flags honest, warm 38–83 ms).
  - Cache-hit latency, real engine API, JVM: p50 ~4–5 us, p95 ~5–13 us (ENTRY 003) — inside < 1 ms target; ART numbers outstanding.
  - Render-model cost (mapper+bidi, 20 regions), JVM: p50 ~12 us (ENTRY 005); StaticLayout build on-device p50 768 us (ENTRY 006).
  - Tier policy+styling (20 regions), JVM: p50 2.8 us / p95 15.9 us per update (ENTRY 008).
  - PP-OCR reference, dev-machine x64 CPU (ORT 1.30): 16/16 corpus exact; det cold 75–131 ms / warm 73–90 ms; rec 10–232 ms by region count (ENTRY 009, INFORMATIONAL — ART outstanding).
  - Multi-region overlay update (1–2 regions), VKJ-NX9: mean 2.2 ms quiet / 9.9 ms loaded, event-driven only (ENTRY 006).
  - Synthetic integrated E2E (fake instant models), JVM: p50 ~515 ms harness-paced, liveness proof only (ENTRY 007); 10-region burst 10/10 completed, max-concurrency 1.
FILES/COMPONENTS CURRENTLY BEING MODIFIED:
  - None (Phase 9 COMPLETE on VKJ-NX9; production fix = det-tensor flattening + stack logging; no contract changes).
```


