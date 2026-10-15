# Phased Implementation Plan

This document outlines the step-by-step phases for developing, benchmarking, and validating the Offline Real-Time Android Game Translator.

---

## ROADMAP OVERVIEW

```
Phase 0: Research & Architecture (COMPLETED)
  ↓
Phase 1: Android Foundation & Project Skeleton (COMPLETED)
  ↓
Phase 2: Screen Capture (MediaProjection Service) (COMPLETED & VERIFIED)
  ↓
Phase 3: OCR Prototype & Change Detection (COMPLETED & VERIFIED)
  ↓
Phase 4: Modular Offline Translation Engine (COMPLETED & VERIFIED)
  ↓
Phase 4.1: Real Translation Inference Proof (COMPLETE & VERIFIED on VKJ-NX9 2026-10-03)
  ↓
Phase 5: Stabilization, Token Protection & LRU Cache (NEXT ACTIVE — note ISSUE-004 token strategy first)
  ↓
Phase 6: Arabic BIDI Layout & Overlay Renderer
  ↓
Phase 7: Integrated Async Pipeline
  ↓
Phase 8: Visual Tiered Overlay Replacement
  ↓
Phase 9: Real OCR Integration — bounded pixel transport + PP-OCR EN on ORT CPU (COMPLETE 2026-10-05)
  ↓
Phase 10: Performance, Thermal & Memory Optimization
  ↓
Phase 11: Real-Game Validation & Edge Case Handling
  ↓
Phase 12: Final Benchmarking & Release Candidate
```

---

## PHASE DETAILS & ACCEPTANCE CRITERIA

### PHASE 0: Research & Architecture [COMPLETED]
- [x] Inspect repository and initialize persistent `/docs/` structure.
- [x] Write `/AGENTS.md` operating instructions and AI protocol.
- [x] Research Android 14/15 MediaProjection requirements, LiteRT/ONNX runtimes, OCR, and NMT candidates.
- [x] Perform Phase 0 Verification Pass over all research and architecture claims.
- [x] Create core architecture blueprint, multi-rate data pipeline, coordinate mapping, and ADRs.
- [x] Define benchmark plan and test dataset structure.
* **Verification Criteria**: All documentation files created, audited, and consistent.

---

### PHASE 1: Android Foundation & Project Skeleton [COMPLETED]
- [x] Initialize Android Studio project (`com.gametranslator.enar`, Kotlin, Min SDK 29, Target SDK 35).
- [x] Configure Gradle version catalog (`libs.versions.toml`), root `build.gradle.kts`, `settings.gradle.kts`, and `app/build.gradle.kts`.
- [x] Create `AndroidManifest.xml` with permissions (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`).
- [x] Define abstract interfaces `OcrEngine` and `TranslationEngine` alongside data models (`TextRegion`, `OcrResult`, `TranslationResult`).
- [x] Create `ScreenCaptureServiceSkeleton` with `foregroundServiceType="mediaProjection"`.
- [x] Implement `MainActivity` UI shell with overlay permission launcher handlers.
- [x] Implement unit test framework setup and `FoundationSanityTest`.
* **Verification Criteria**: Gradle build layout created, manifest permissions declared, abstract interfaces defined, sanity test passed.

---

### PHASE 2: Screen Capture (MediaProjection Service) [COMPLETED & VERIFIED]
- [x] Implement full `ScreenCaptureService` with `foregroundServiceType="mediaProjection"`.
- [x] Implement user consent launcher flow via `MediaProjectionManager.createScreenCaptureIntent()`.
- [x] Set up `ImageReader` / `HardwareBuffer` consumer surface on dedicated `HandlerThread`.
- [x] Implement screen orientation changes, screen resolution scaling, and lifecycle teardown.
- [x] Verify zero memory leaks and thread safety with atomic shutdown protection.
* **Verification Criteria**: Verified via `assembleDebug`, `testDebugUnitTest` (3/3), `lintDebug`, and 12-item lifecycle/memory code review.

---

### PHASE 3: OCR Prototype & Change Detection [COMPLETED & VERIFIED]
- [x] Implement Level 1 Change Detector (`FrameChangeDetector`: 4× downscaled luminance block hash & Hamming distance).
- [x] Implement pluggable `NoOpOcrEngine` stub for testing and pipeline wiring without model dependencies.
- [x] Implement `LiteRtOcrEngine` skeleton with memory-mapped model loading off the hot path.
- [x] Implement `OcrPipelineCoordinator` with two-level gating (`isChanged` + 5 FPS rate limiter) on `Dispatchers.Default`.
- [x] Implement minimal `OcrResultStabilizer` (3-frame Levenshtein ring buffer).
- [x] Implement diagnostic instrumentation (`OcrPipelineStats`, `ChangeDetectionResult`).
* **Verification Criteria**: All unit tests pass (25/25), `assembleDebug` passes, `lintDebug` clean, APK verified (24.35 MB). Benchmarking latency/accuracy deferred to Phase 9.

---

### PHASE 4: Modular Offline Translation Engine [COMPLETED & VERIFIED]
- [x] Perform Model Feasibility Audit for `Helsinki-NLP/opus-mt-en-ar` (MarianMT, ~74.4M params, SentencePiece SPM, CC-BY-4.0).
- [x] Implement `TokenProtector` with single-pass regex preserving game variables (`{player}`, `%d`, `$100`, `<color>`).
- [x] Implement `DictionaryTranslationEngine` for neutral Modern Standard Arabic game terminology and instant CPU fallback.
- [x] Implement `LiteRtTranslationEngine` prototype skeleton with separate encoder/decoder load step.
- [x] Implement `TranslationCache` abstraction and `NoOpTranslationCache`.
- [x] Implement `TranslationPipelineStats` latency instrumentation.
* **Verification Criteria**: All unit tests pass (41/41 across 9 suites), `assembleDebug` passes, `lintDebug` clean, APK verified (24.38 MB). Offline translation tested with dictionary engine. Model benchmarking on physical hardware deferred to Phase 9.

---

### PHASE 4.1: Real Translation Inference Proof [COMPLETE & VERIFIED ON PHYSICAL HARDWARE 2026-10-03]
- [x] Identify exact model artifact (`Helsinki-NLP/opus-mt-en-ar`, transformers 4.57.6, optimum ONNX export) and verify tokenizer files, architecture, and CC-BY-4.0 license.
- [x] Convert to ONNX FP32 + INT8 (QOperator): encoder 51.6 MB, decoder 90.6 MB, decoder-with-past 87.4 MB.
- [x] Prove real inference on dev machine: encoder execution, autoregressive greedy decoder, EOS handling, detokenization, real Arabic output for all 5 representative sentences (PyTorch AND quantized ONNX).
- [x] Implement `OnnxTranslationEngine` (3-session greedy decoder behind `TranslationEngine`) and `SentencePieceTokenizer` (62,802-token vocab.json + unescape + EOS).
- [x] Add `OnnxTranslationIntegrationTest` (4 tests) and `TranslationIntegrationTest` (3 tests).
- [x] Execute ONNX model on Android (VKJ-NX9, Snapdragon 7 Gen 4) — **DONE 2026-10-03**.
- [x] Measure Android cold-load time (2441 ms), warm latency (34–47 ms), peak RAM (+241 MB PSS) — see benchmarks.md ENTRY 001.
* **Verification Criteria**: 50/50 unit tests pass, 2/2 connected tests pass, `assembleDebug` passes, `lintDebug` clean. Dev-machine AND on-device NMT inference VERIFIED with recorded Arabic outputs. Phase 4.1 CLOSED.

---

### PHASE 5: Stabilization, Token Protection & LRU Cache
- [x] Implement Spatial ROI Tracker ($IoU > 0.6$). — DONE 2026-10-04 (JVM-verified 19/19; strict boundary, deterministic ties, grace expiry; ADR-009).
- [x] Implement Temporal OCR Stabilizer (Levenshtein distance voting buffer over 3 frames). — HARDENED 2026-10-04 (TrackState machine + grace + freeze rules; 12 legacy tests unchanged + 13 new; composition integration-tested ×7; coordinator wiring deferred to Phase 7).
- [x] Implement Token Protector for placeholders (`%d`, `{player}`, `$100`, `<color>`) — DONE 2026-10-04 via ISSUE-004: numeric sentinels, verified on VKJ-NX9 (ENTRY 002). ISSUE-004 CLOSED.
- [x] Implement Text Normalizer and Bounded LRU Translation Cache (capacity = 500). — DONE 2026-10-04 (JVM-verified 96/96; ADR-008 admission policy; pipeline wiring deferred to Phase 7).
- [x] Measure cache hit latency (< 1ms) and reduction in NMT workload. — HIT LATENCY DONE (JVM p50 ~4–5 µs, ENTRY 003); NMT-workload reduction requires Phase 7 pipeline integration (open).
* **Verification Criteria**: Moving text noise is filtered without visual flickering; repeated UI strings return instant cached translations.

---

### PHASE 6: Arabic BIDI Layout & Overlay Renderer [COMPLETE 2026-10-04 — VKJ-NX9]
- [x] STAGE A (JVM-verified 163/163): pure render models + Android layer; device suites written + compiling; lint clean (ADR-010).
- [x] STAGE B (VKJ-NX9): `connectedDebugAndroidTest` 20/20 (layout shaping/direction/wrapping/order/pixels, window params incl. alpha, dispatched-touch, lifecycle, rapid updates, build-cost log) + eye-verified render bitmap + tap/swipe-through-overlay on the real launcher + focus tracking. 1 real alpha-composition bug found+fixed. ENTRY 006.
- [x] Integrate Unicode BIDI formatter and HarfBuzz Arabic shaping. (Covered natively via StaticLayout/FIRSTSTRONG — verified on-device; no custom shaping.)
- [x] Implement `ArabicTextLayout` using Android `StaticLayout` and custom fonts. (Layout DONE + device-verified; custom fonts deferred to Phase 11 visual polish — system fonts in proof.)
- [x] Implement non-interactive `SystemOverlayService` (`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_TOUCHABLE`, `FLAG_NOT_FOCUSABLE`). (DONE as `OverlayService`+`OverlayController`; proven on VKJ-NX9 with exact-params assert.)
- [x] Verify touch events pass through cleanly to underlying games across target OS versions. (Verified on VKJ-NX9/Android 16: dispatched streams + tap-through-overlay app launch + swipe paging + focus tracking. Cross-game/OS breadth belongs to Phase 11.)
* **Verification Criteria**: Arabic text renders right-to-left with correct ligatures and line wrapping over target coordinates without capturing touches.

---

### PHASE 7: Integrated Async Pipeline
- [x] Connect Capture → Change Detection → Detection → Stabilization → Normalization → Cache → NMT → Overlay. — DONE 2026-10-04: `OcrPipelineCoordinator` (+tracker/ids, +rescan policy, legacy-exact defaults) → `GameTranslatorPipeline` (bounded revisions/supersession/stale-drop, retry pump, publish gate, lifecycle-safe executors) → `CachedTranslationEngine` → `OverlaySink`; wired in `MainActivity` (dictionary engine, safe-idle). 197/197 JVM tests; ENTRY 007. Known gap: no pixel transport (Phase 9); on-device E2E awaits real OCR models.
- [x] Implement bounded Coroutine Channel queues with priority cancellation. — DONE as semaphore+skip with latest-state supersession (no queues by design — strictly bounded; documented in ADR-011).
- [x] Handle stale translation cancellation when text disappears from screen. — DONE: revision mismatch drops + disappearance removes state/overlay on first absence; late post-stop completions dropped.
* **Verification Criteria**: Full end-to-end pipeline operates smoothly from screen capture to Arabic overlay presentation.

---

### PHASE 8: Visual Tiered Overlay Replacement [IMPLEMENTED & JVM-VERIFIED 2026-10-04 — on-device visual gate pending]
- [x] Implement Tier 1 (opaque dialogue-box cover: `DefaultOverlayTierRenderer` opaque `0xFF141414` cover, author-opaque kept, translucent upgraded).
- [x] Implement Tier 2 (translucent mask: fixed `0xB3141414` region-limited box, no sampling/blur/inpainting).
- [x] Implement Tier 3 (transparent + readability: background stripped, outline/shadow ensured; default style passes through identical).
- [x] Measure rendering frame times. — TIER PATH DONE (JVM p50 2.8 µs / p95 15.9 µs per 20-region update, ENTRY 008); `StaticLayout` on-device cost unchanged (ENTRY 006).
* **Verification Criteria**: Text is 100% readable across diverse game backgrounds without heavy image inpainting overhead. — JVM-verified (227/227 incl. 30 new tier tests); ON-DEVICE VISUAL GATE outstanding (see ISSUE-006).

---

### PHASE 9: Real OCR Integration [COMPLETE 2026-10-05 — VKJ-NX9, 275/275 JVM + 21/21 Android + live game E2E]
- [x] Bounded pixel transport — DONE: `SnapshotStore` (single lock-free latest-only slot, 200 ms throttle, clear-on-stop/start) + `SnapshotDownscaler` (in-place RGBA→RGB bilinear, ≤ 960 side) + service publish path (changed+due only, metadata-only fallback). ADR-013.
- [x] OCR model delivery — DONE: `en_PP-OCRv3_det_infer` (2.4 MB) + `en_PP-OCRv4_rec_mobile` (7.7 MB) + `en_dict.txt`, SHA-pinned, Apache-2.0, bundled under `assets/models/` (+10 MB APK). INT8/v5/`ch_*`/cls rejected with reasons (research §5C).
- [x] OCR runtime abstraction — DONE: `DetSession`/`RecSession`/`OcrSessionProvider` seams (ORT impl + scripted fakes); ORT CPU 4T + BASIC_OPT (NMT-proven options); delegates unevaluated (CPU is the production path until device numbers exist).
- [x] Pre/postprocessing with reference parity — DONE: Paddle-faithful det geometry/normalize, simplified DB decode (golden IoU 0.867–0.953), CTC decode (golden text+score exact), rec crop/norm; goldens in `app/src/test/resources/ocr/`.
- [x] Pipeline integration — DONE: `OnnxOcrEngine` behind the UNCHANGED `OcrEngine` contract (no coordinator/pipeline changes); tracker enabled with paired 3/3 grace; rescan stays 1; MainActivity loads off-main + releases on stop. 273/273 JVM (46 new).
- [x] Device validation gate — DONE 2026-10-05: `OcrOnDeviceTest` ×4 (cold 332–360 ms, exact sets, release lifecycle) + full 21/21 suite + live consented MLBB run (25 det / 13–14 stable / 18 published, co-located overlay screenshots, UI-stop teardown). ENTRY 010. ISSUE-007 CLOSED.
- [ ] Accelerator evaluation — DEFERRED (no hardware delta measured; CPU is the production path; GPU/NNAPI/QNN need dedicated Phase 10 runs — see ISSUE-008).
* **Verification Criteria**: real pixels → real OCR → stabilized text → translation → overlay on VKJ-NX9 with measured load/warm/PSS/stability — ALL MET (see testing.md Phase 9 device gate log).

---

### PHASE 10: Performance, Thermal & Memory Optimization
- [ ] Implement `PowerManager.OnThermalStatusChangedListener`.
- [ ] Implement dynamic workload throttling under `THERMAL_STATUS_MODERATE / SEVERE`.
- [ ] Optimize peak RAM usage (reusing direct `HardwareBuffer` memory).
- [ ] Benchmark sustained 30-minute gameplay run for battery drain and thermal stability.
* **Verification Criteria**: App maintains stable execution for > 30 minutes without memory leaks or severe thermal throttling.

---

### PHASE 11: Real-Game Validation
- [ ] Test across 5 representative games (RPG dialogue, FPS HUD, Strategy UI, Multiline Subtitles, Fast-moving UI).
- [ ] Document visual edge cases and tune parameters.
- [ ] Verify system stability across device orientation changes and screen resolution switches.
* **Verification Criteria**: All 5 test games translate cleanly with high readability and zero crash incidents.

---

### PHASE 12: Final Benchmarking & Release Candidate
- [ ] Run full benchmark suite and record findings in `/docs/benchmarks.md`.
- [ ] Audit license compliance for all bundled models and libraries.
- [ ] Create reproducible build APK script.
- [ ] Update final handoff section in `/docs/memory.md`.
* **Verification Criteria**: Release candidate APK built, verified offline, fully documented, and meeting all 50 prompt acceptance criteria.
