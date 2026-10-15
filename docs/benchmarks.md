# Empirical Benchmark Log & Target Budgets

This file contains historical benchmark measurements and target performance budgets. **Historical benchmark entries must NEVER be deleted or overwritten.** New benchmark runs must be appended chronologically with full hardware environment metadata.

---

## 1. BENCHMARK METHODOLOGY & METRICS DEFINITION

### Metrics Tracked:
* **Cold Start Latency**: Time from model load invocation to first initialized tensor state (ms).
* **Warm Latency (P50 & P95)**: Inference execution time on initialized runtime over 100 iterations (ms).
* **First-Text Latency**: Time from frame capture to initial Arabic overlay draw (ms).
* **Cached Translation Latency**: Time to serve stabilized text from LRU cache to overlay draw (ms).
* **Peak Memory (RAM)**: Maximum process RSS recorded during active translation (MB).
* **Sustained Memory (RAM)**: Average memory usage after 15 minutes of gameplay (MB).
* **Thermal Impact**: Temperature rise ($\Delta T$) after 30 minutes of continuous capture and translation ($^\circ\text{C}$).

---

## 2. TARGET PERFORMANCE BUDGETS [BENCHMARK REQUIRED]

The metrics below represent **target design budgets** for Snapdragon 7 Gen 4. None of these numbers are claimed as verified facts or proven pass/fail requirements until empirical baseline measurements are established on physical target hardware.

| Pipeline Stage / Metric | Target Design Budget | Critical Boundary Target | Claim Verification Status |
| :--- | :--- | :--- | :--- |
| **Change Detection** | $< 8\text{ ms}$ | $> 16\text{ ms}$ | **BENCHMARK REQUIRED** |
| **Text Detection ROI (PP-OCRv4)** | $< 25\text{ ms}$ | $> 50\text{ ms}$ | **BENCHMARK REQUIRED** |
| **OCR Recognition (PP-OCRv4)** | $< 35\text{ ms}$ | $> 80\text{ ms}$ | **BENCHMARK REQUIRED** |
| **Uncached NMT Model (Opus-MT)** | $< 90\text{ ms}$ | $> 200\text{ ms}$ | **BENCHMARK REQUIRED** |
| **Cached NMT Lookup** | $< 2\text{ ms}$ | $> 10\text{ ms}$ | **BENCHMARK REQUIRED** |
| **End-to-End First-Text** | $< 180\text{ ms}$ | $> 350\text{ ms}$ | **BENCHMARK REQUIRED** |
| **Process Peak RAM** | $< 200\text{ MB}$ | $> 350\text{ MB}$ | **BENCHMARK REQUIRED** |
| **30-Min Thermal Delta** | $< 4.0^\circ\text{C}$ (Target Budget) | $> 8.0^\circ\text{C}$ | **TARGET BUDGET / BENCHMARK REQUIRED** |

---

## 3. BENCHMARK LOG ENTRIES

### ENTRY 000: SYSTEM BENCHMARK INITIALIZATION & CLAIM AUDIT
* **Date**: 2026-09-24
* **Target Hardware**: Qualcomm Snapdragon 7 Gen 4 (8GB RAM, Android 14 API 34).
* **Runtime Candidates**: LiteRT 2.x (TFLite) / ONNX Runtime Mobile evaluated with OpenCL GPU Candidate, QNN Candidate, & XNNPACK CPU Baseline.
* **Status**: Claim audit completed. All target latency, RAM, and thermal figures classified as **TARGET BUDGETS / BENCHMARK REQUIRED**. Awaiting Phase 3/4 runtime builds.

```yaml
BENCHMARK_RUN: 000-VERIFICATION-PASS
DATE: 2026-09-24
ENVIRONMENT:
  DEVICE: Reference Target (Snapdragon 7 Gen 4)
  OS: Android 14 (API 34)
  RAM: 8 GB
  ABI: arm64-v8a
RESULTS:
  COLD_MODEL_LOAD_MS: UNVERIFIED (Benchmark Required)
  WARM_DETECTION_P50_MS: UNVERIFIED (Benchmark Required)
  WARM_OCR_P50_MS: UNVERIFIED (Benchmark Required)
  WARM_NMT_P50_MS: UNVERIFIED (Benchmark Required)
  UNCACHED_E2E_P50_MS: UNVERIFIED (Benchmark Required)
  CACHED_E2E_P50_MS: UNVERIFIED (Benchmark Required)
  PEAK_RAM_MB: UNVERIFIED (Benchmark Required)
  THERMAL_DELTA_C: UNVERIFIED (Target Budget / Benchmark Required)
NOTES: Audit verified PP-OCRv4 Mobile ONNX size (~15 MB FP32 total) and Opus-MT size (~298 MB FP32 PyTorch). All latencies, RAM, and thermal targets are design budgets requiring physical device benchmarks.
```

### ENTRY 001: ON-DEVICE NMT INFERENCE — OPUS-MT-EN-AR INT8 (FIRST PHYSICAL MEASUREMENT)
* **Date**: 2026-10-03
* **Device**: VKJ-NX9 (Honor), Android 16, Snapdragon 7 Gen 4 (SM7750), arm64-v8a, 11,642,184 kB RAM (~11.1 GB)
* **Runtime**: ONNX Runtime Mobile 1.17.0, CPU backend, 4 intra-op threads, BASIC_OPT
* **Models**: opus-mt-en-ar INT8 QOperator — encoder 51.6 MB + decoder 90.6 MB + decoder-with-past 87.4 MB (~229.6 MB, bundled as APK assets for this proof run)
* **Method**: `connectedDebugAndroidTest` (`OnnxOnDeviceInferenceTest`, 2/2 passed); cold load timed around `loadModels()` incl. 232 MB asset copy on fresh install; warm latency = per-`translate()` wall time incl. tokenize + inference + detokenize; RAM = process total PSS via `ActivityManager.getProcessMemoryInfo`
* **Results**:
  - Cold load: **2441 ms** (includes first-install 232 MB asset copy; steady-state reload will be lower — NOT separately measured)
  - Warm per-sentence: **47.4 ms** (`Press E…` → `الصحافة "إي" لتفتح الباب`), **43.6 ms** (`You have 50 HP…` → `لديك "فارلو" باقي`, see ISSUE-004), **36.8 ms** (`Hello {playerName}…` → `مرحباً بكم في العودة`), **34.3 ms** (`The enemy…` → `العدو يقترب من الشمال`, identical to PyTorch), **10.4 ms** (`Save Game` → empty, genuine immediate-EOS model behavior)
  - PSS: baseline **277 MB** → after load **518 MB** (**+241 MB** for 3 sessions) → after inference **518.5 MB** (+0.5 MB)
  - Crashes/failures: **0** (after fixing tensor-lifecycle SIGSEGV, see ISSUE-005)
* **Budget check**: warm latency 34–47 ms is well inside the < 90 ms uncached-NMT budget ✅; +241 MB session footprint EXCEEDS the < 150 MB (and < 200 MB peak) budget ❌ — model-delivery/quantization strategy must be revisited before production (see implementation-plan Phase 9/12).

```yaml
BENCHMARK_RUN: 001-ONNX-NMT-INT8-VKJ-NX9
DATE: 2026-10-03
ENVIRONMENT:
  DEVICE: VKJ-NX9 (Honor, Snapdragon 7 Gen 4 / SM7750, arm64-v8a)
  OS: Android 16
  RAM: 11642184 kB
  RUNTIME: onnxruntime-android 1.17.0, CPU 4 threads, BASIC_OPT
  MODELS: opus-mt-en-ar INT8 (encoder 51.6 MB + decoder 90.6 MB + decoder_with_past 87.4 MB)
RESULTS:
  COLD_MODEL_LOAD_MS: 2441 (includes first-install 232 MB asset copy)
  WARM_NMT_MS: [47.4, 43.6, 36.8, 34.3, 10.4]
  WARM_NMT_CONTENT_TOKENS_DEV_PARITY: [6, 6, 5, 4, 0] (dev-machine reference; device token counts not logged)
  PSS_BASELINE_MB: 277
  PSS_AFTER_LOAD_MB: 518
  PSS_AFTER_INFER_MB: 518.5
  CRASHES: 0
NOTES: Real Arabic output verified on-device for 4/5 sentences; 5th (Save Game) empty per genuine model behavior. VAR0 placeholder mangling observed (ISSUE-004). Prior SIGSEGV from tensor lifecycle fixed before measurement (ISSUE-005).
```

### ENTRY 002: ON-DEVICE RE-VALIDATION — SENTINEL TOKEN PROTECTION (ISSUE-004 CLOSURE)
* **Date**: 2026-10-04
* **Device**: VKJ-NX9 (Honor, serial ADQNUT6615002454), Android 16 (SDK 36), Snapdragon 7 Gen 4 (SM7750), arm64-v8a, 11,642,184 kB RAM (~11.1 GB) — same model profile as ENTRY 001
* **Runtime**: ONNX Runtime Mobile 1.17.0, CPU backend, 4 intra-op threads, BASIC_OPT (unchanged code path: greedy loop extracted byte-identical to `runGreedyInference()`)
* **Models**: opus-mt-en-ar INT8 QOperator — encoder 51.6 MB + decoder 90.6 MB + decoder-with-past 87.4 MB (~229.6 MB, bundled as APK assets for this proof run)
* **Method**: `connectedDebugAndroidTest` (`OnnxOnDeviceInferenceTest`, 2/2 passed, incl. strengthened sentinel assertions: per-sentence value round-trip, honest `protectedTokensRestored` flag, no `98\d{4}` residue) + single-method rerun with streaming logcat capture; warm latency = per-`translate()` wall time incl. tokenize + inference + detokenize; RAM = process total PSS via `ActivityManager.getProcessMemoryInfo`
* **Results** (new numeric-sentinel masking path, real inference `CPU_ONNX_RUNTIME_4T`, 0 crashes):
  - Cold load: **2909 ms** (fresh-install 232 MB asset copy; cf. 2441 ms ENTRY 001 — difference is install/copy state, not code)
  - Warm per-sentence: **50.7 ms** (`Press E…` → `الصحافة "إي" لتفتح الباب`, flag false = no spans, correct), **61.7 ms** (`You have 50 HP remaining.` → `لديك 50 HP باق.`, flag **true** — the exact ISSUE-004 case), **82.8 ms** (`Hello {playerName}…` → `مرحباً يا {playerName} مرحباً بكم مرة أخرى`, flag **true** — slot previously dropped), **38.1 ms** (`The enemy…` → `العدو يقترب من الشمال`, identical to PyTorch), **11.6 ms** (`Save Game` → empty, genuine immediate-EOS)
  - PSS: baseline **84,195 KB** → after load **543,366 KB** → after inference **544,246 KB**
  - No `98\d{4}` sentinel residue in any output; no fallback triggered (all sentinels verified); no crash/native fault
* **Budget check**: warm latency 38–83 ms stays inside the < 90 ms uncached-NMT budget ✅; post-load PSS (~543 MB total, ~+459 MB over this run's fresh-process baseline) still EXCEEDS the < 150 MB (and < 200 MB peak) budget ❌ — NOTE: this run's baseline (84 MB, fresh cold process) differs from ENTRY 001's (277 MB); post-load/infer totals are comparable (543/544 vs 518/518.5 MB), so the delta gap is baseline-state, not a code regression (the fix adds no sessions or retained buffers). Model-delivery/quantization strategy stays open for Phase 9/12.

```yaml
BENCHMARK_RUN: 002-ONNX-NMT-INT8-SENTINEL-VKJ-NX9
DATE: 2026-10-04
ENVIRONMENT:
  DEVICE: VKJ-NX9 (Honor, serial ADQNUT6615002454, Snapdragon 7 Gen 4 / SM7750, arm64-v8a)
  OS: Android 16 (SDK 36)
  RAM: 11642184 kB
  RUNTIME: onnxruntime-android 1.17.0, CPU 4 threads, BASIC_OPT
  MODELS: opus-mt-en-ar INT8 (encoder 51.6 MB + decoder 90.6 MB + decoder_with_past 87.4 MB)
RESULTS:
  COLD_MODEL_LOAD_MS: 2909 (includes first-install 232 MB asset copy)
  WARM_NMT_MS: [50.7, 61.7, 82.8, 38.1, 11.6]
  WARM_NMT_FLAGS_RESTORED: [false, true, true, false, false]
  SENTINEL_RESIDUE_98XXXX: none in any output
  PSS_BASELINE_KB: 84195
  PSS_AFTER_LOAD_KB: 543366
  PSS_AFTER_INFER_KB: 544246
  CRASHES: 0
NOTES: ISSUE-004 closure run — sentinel round-trips verified on-device (50 HP + {playerName} restored, flags honest). PSS delta vs ENTRY 001 is baseline-state, not a regression (see note above).
```

### ENTRY 003: TRANSLATION CACHE-HIT LATENCY (JVM DEV-MACHINE, REAL CACHE API)
* **Date**: 2026-10-04
* **Device**: dev machine (JVM unit-test runner, NOT Android — ART numbers will differ; on-device cache measurement outstanding)
* **Method**: `TranslationCacheBenchmarkTest` through the real `CachedTranslationEngine.translate()` API (normalize → LRU lookup → result construction), 2000 timed hits per scenario, per-call `System.nanoTime`
* **Results**:
  - Single-key hot hit: **mean 6.39 µs, p50 4.80 µs, p95 12.6 µs**
  - 500-key round-robin cycling hit (full cache, every access recency-moving): **mean 4.06 µs, p50 4.10 µs, p95 5.30 µs**
* **Budget check**: both p50/p95 are ~80–200× inside the < 1 ms cached-lookup target ✅ (JVM-measured; target stays BENCHMARK REQUIRED on-device)

```yaml
BENCHMARK_RUN: 003-CACHE-HIT-JVM
DATE: 2026-10-04
ENVIRONMENT:
  DEVICE: dev-machine JVM (unit-test runner — NOT Android)
  API: CachedTranslationEngine.translate() incl. normalize + LRU get + result build
RESULTS:
  HOT_HIT_SINGLE_KEY_NS: {n: 2000, mean: 6390.9, p50: 4800, p95: 12600}
  CYCLING_HIT_500_KEYS_NS: {n: 2000, mean: 4061.5, p50: 4100, p95: 5300}
  TARGET_CACHED_LOOKUP_MS: 1
  WITHIN_TARGET: true (JVM only)
NOTES: ART on-device numbers outstanding; no NMT-workload-reduction claim made (requires pipeline integration, Phase 7).
```

### ENTRY 004: SPATIAL TRACKER + STABILIZER FRAME COST (JVM DEV-MACHINE)
* **Date**: 2026-10-04
* **Device**: dev machine (JVM unit-test runner, NOT Android — ART numbers will differ)
* **Method**: `SpatialTrackerBenchmarkTest` — full `RegionTracker.update()` + `pruneStaleRegions()` + per-region `stabilize()` over synthetic 12-region frames with deterministic ±2 px jitter, 1000 timed frames
* **Results**: **mean 31.6 µs, p50 16.7 µs, p95 92.8 µs per frame**
* **Budget check**: p50/p95 ~50–300× inside the 5 ms hot-path budget ✅ (JVM-measured; pure integer geometry + small windows — no bitmaps, no frame copies)

```yaml
BENCHMARK_RUN: 004-TRACKER-STABILIZER-JVM
DATE: 2026-10-04
ENVIRONMENT:
  DEVICE: dev-machine JVM (unit-test runner — NOT Android)
  WORKLOAD: tracker.update + prune + stabilize x12 regions, deterministic jitter
RESULTS:
  N_FRAMES: 1000
  MEAN_NS: 31618.2
  P50_NS: 16700
  P95_NS: 92800
  HOT_PATH_BUDGET_NS: 5000000
  WITHIN_BUDGET: true (JVM only)
NOTES: Typical game UI carries far fewer than 12 live regions per OCR cycle (5 Hz max).
```

### ENTRY 005: RENDER-MODEL COST — MAPPER + BIDI (JVM DEV-MACHINE)
* **Date**: 2026-10-04
* **Device**: dev machine (JVM unit-test runner, NOT Android — ART numbers will differ)
* **Method**: `RenderModelBenchmarkTest` — `CoordinateMapper.toOverlay()` + `BidiUtils.baseDirection()`/`runCount()` over 20 synthetic regions × 5000 iterations
* **Results**: **mean 22.3 µs, p50 12.3 µs, p95 46.7 µs per 20-region frame**
* **Notes**: pure-model cost only (no `StaticLayout` — device build timing is logged by `ArabicLayoutDeviceTest` in Stage B). No budget target set for this micro-op; recorded for regression reference.

```yaml
BENCHMARK_RUN: 005-RENDER-MODEL-JVM
DATE: 2026-10-04
ENVIRONMENT:
  DEVICE: dev-machine JVM (unit-test runner — NOT Android)
  WORKLOAD: mapper.toOverlay + bidi base/runCount x20 regions
RESULTS:
  N: 5000
  MEAN_NS: 22258.6
  P50_NS: 12300
  P95_NS: 46700
NOTES: StaticLayout build cost outstanding (Stage B device run).
```

### ENTRY 006: PHASE 6 STAGE B — ON-DEVICE OVERLAY VALIDATION (VKJ-NX9)
* **Date**: 2026-10-04
* **Device**: HONOR VKJ-NX9 (serial ADQNUT6615002454), Android 16 (SDK 36), Snapdragon 7 Gen 4 (SM7750), arm64-v8a, 11,642,184 kB RAM; locale ar-EG; display 1264×2728, density 560 (scaledDensity 3.5)
* **Method**: `connectedDebugAndroidTest` full suite (20/20 incl. 3 new + 2 fixed layout asserts, dispatched-touch, lifecycle/rapid/refusal) + view-bitmap pixel gate (ink/transparency/bg asserts + host-pulled PNG inspected by eye) + live-window dumpsys + tap/swipe-through-overlay against the real launcher + uiautomator before/after diffs + focus tracking. Overlay grant via `appops set ... allow`.
* **Results**:
  - `connectedDebugAndroidTest`: **20/20 PASS, 0 skipped** (12 ArabicLayout + 2 ONNX + 5 service incl. window-alpha assert + 3 touch). Three initial layout failures were all diagnosed to test-side convention errors, fixed with device-measured evidence (NOT weakened): `getParagraphDirection` returns −1 for RTL / +1 for LTR; bare `setMaxLines` does not truncate without ellipsize (15 lines generated); `getLineWidth` includes trailing wrap-space (+15 px) → replaced by visible-ink measurement.
  - Visual (real minikin pixels, seen by eye): pure Arabic shaped+RTL ✓; English LTR ✓; mixed `اضغط E للفتح` / `لديك 50 HP باق` / `الدقة 100%` ordered ✓; `{playerName}` intact ✓; `$100`/`50G` ordered ✓; narrow-box 4-line wrap ✓; maxLines=2 + `…` ellipsis ✓; outline/shadow/bg-translucency ✓; outside-region transparency ✓ (pixel-asserted). Programmatic order proof: `getPrimaryHorizontal(0)` right-of-last for RTL and mirror for LTR.
  - Touch: tap at (194,1033) INSIDE an overlay Arabic-text region launched Shazam (focus verified) → BACK → home intact; swipe at y=1000 through overlay text paged the launcher (uiautomator before/after differed: 32→35 nodes, new page); focus never stolen (game was focused earlier with overlay up; `canReceiveKeys=false`); dispatched tap/swipe streams all unconsumed (3/3 device tests).
  - Lifecycle: show/hide/update/clear/rapid-50×/repeats/refusal/empty all green on-device; no crash anywhere; render failures isolated per-region by design.
  - Perf (device, real API): StaticLayout mixed-Arabic build **p50 768 µs / p95 926 µs** (n=200); 50× multi-region update **mean 2.2 ms (quiet) vs 9.9 ms (loaded)** — environmental variance, updates are event-driven (≤5 Hz) with identical-input short-circuit, so no frame-budget claim is made.
* **Device-observed behaviors (accepted, documented)**:
  - WindowState `alpha=0.8` server-side while client `layoutParams.alpha==1.0` (asserted green): the system (HONOR build) damps third-party overlay opacity; app-side cannot override. Readability unaffected (pixel-verified full-alpha glyphs); revisit cross-OEM in Phase 11.
  - `touchableRegion` in dumpsys is bookkeeping only (NOT_TOUCHABLE windows are skipped by the dispatcher) — never used as evidence; behavior proven by delivery tests.
  - Test-run reinstall wipes the appops grant on this build AND the run uninstalls the app afterwards: grant must be (re-)applied around installs (a re-grant loop was used during runs); saves must be pulled mid-run (post-run uninstall wipes app storage; `/data/local/tmp` is EACCES for apps — use external files + hold, or pull during the run).
  - Secure keyguard denies `screencap` (`FB is protected`); device PIN state is user-controlled — screenshots were impossible while locked, hence the in-process bitmap + pull procedure.
* **Budget check**: no end-to-end claims (Phase 6 forbids them); component numbers above are within their local budgets.

```yaml
BENCHMARK_RUN: 006-PHASE6-STAGEB-VKJ-NX9
DATE: 2026-10-04
ENVIRONMENT:
  DEVICE: HONOR VKJ-NX9 (ADQNUT6615002454, Snapdragon 7 Gen 4 / SM7750, arm64-v8a)
  OS: Android 16 (SDK 36), locale ar-EG
  DISPLAY: 1264x2728, density 560, scaledDensity 3.5
RESULTS:
  CONNECTED_TESTS: 20/20 pass, 0 skipped (incl. refusal, rapid-50x, dispatched-touch)
  LAYOUT_BUILD_MIXED_AR_NS: {n: 200, p50: 767969, p95: 926354}
  MULTI_REGION_UPDATE_50X_NS: {quiet_mean: 2197062, loaded_mean: 9856251}
  VISUAL_GATE: pass (eye-verified bitmap: shaping/RTL/mixed/wrap/clip/ellipsis/styles/transparency)
  TOUCH_GATE: pass (tap->Shazam launch, swipe->page, focus never stolen)
  LIFECYCLE_GATE: pass (show/hide/update/clear/rapid/repeats/refusal, 0 crashes)
  WINDOW_ALPHA_OBSERVED: 0.8 server-side (client requests 1.0; OEM policy, accepted)
  CRASHES: 0
NOTES: Phase 6 COMPLETE. See testing.md Stage B for procedure details.
```


### ENTRY 007: INTEGRATED PIPELINE — SYNTHETIC E2E + BURST (JVM DEV-MACHINE)
* **Date**: 2026-10-04
* **Device**: dev machine (JVM unit-test runner, NOT Android — ART/thermal behavior will differ; real-model latencies dominate by orders of magnitude)
* **Method**: `TranslationPipelineBenchmarkTest` — REAL coordinator (tracker+stabilizer) + REAL pipeline (normalize→bounded translate→overlay) with instant scripted models at the seams; per-emission pacing like production (~5 Hz OCR cadence)
* **Results**:
  - Synthetic E2E (changed-frame emission → overlay update, n=10): **mean ~514 ms, p50 ~515 ms** — includes ~525 ms of harness pacing delays + stabilization windows by construction; proves end-to-end liveness/order, NOT component latency.
  - 10-region burst, 1 permit: **launched=10, completed=10, skippedBusy=54, stale=0, maxConcurrent=1, overlay=10** — shedding absorbs the burst, the 200 ms retry pump (not re-emissions, which StateFlow conflates away) drains it, permits never exceeded.
* **Budget check**: no end-to-end claims (Phase 7 forbids them); component budgets stand (NMT < 90 ms uncached on-device per ENTRY 001/002; cache p50 ~4–5 µs JVM per ENTRY 003; tracker p50 ~17 µs JVM per ENTRY 004; layout p50 768 µs on-device per ENTRY 006).

```yaml
BENCHMARK_RUN: 007-PIPELINE-SYNTHETIC-JVM
DATE: 2026-10-04
ENVIRONMENT:
  DEVICE: dev-machine JVM (unit-test runner — NOT Android)
  MODELS: instant scripted fakes at OCR/NMT seams (real coordinator+pipeline+cache+protector)
RESULTS:
  E2E_SYNTHETIC_N: 10
  E2E_P50_NS: 515154300
  E2E_NOTE: includes ~525ms harness pacing + stabilization windows (liveness proof only)
  BURST_10_REGIONS_1_PERMIT: {launched: 10, completed: 10, skippedBusy: 54, stale: 0, maxConcurrent: 1}
  STALENESS: 0 stale Arabic outputs across all runs
NOTES: On-device E2E with real models pending Phase 9/11.
```

### ENTRY 008: TIER SELECTION + STYLING COST (JVM DEV-MACHINE)
* **Date**: 2026-10-04
* **Device**: dev machine (JVM unit-test runner, NOT Android — ART numbers will differ)
* **Method**: `TierRenderBenchmarkTest` — real `ExplicitOverlayRenderPolicy` (default Tier 3 + 2 overrides) + real `DefaultOverlayTierRenderer` + real `TieredOverlaySink` over 20 synthetic regions (mixed Arabic/English/numbers/placeholders) × 2000 timed `update()` calls incl. no-op delegate forward, per-call `System.nanoTime`, 200-iteration warmup
* **Results**: **mean 5517.8 ns, p50 2800 ns, p95 15900 ns per 20-region update** (~140 ns p50 per region)
* **Budget check**: no formal budget was set for this micro-op; it is ~270× smaller than the on-device `StaticLayout` build p50 768 µs (ENTRY 006), so the tier layer cannot dominate frame cost. Recorded for regression reference (JVM only).

```yaml
BENCHMARK_RUN: 008-TIER-RENDER-JVM
DATE: 2026-10-04
ENVIRONMENT:
  DEVICE: dev-machine JVM (unit-test runner — NOT Android)
  WORKLOAD: policy.tierFor + renderer.render x20 regions + sink forward
RESULTS:
  N: 2000
  MEAN_NS: 5517.8
  P50_NS: 2800
  P95_NS: 15900
NOTES: StaticLayout build cost unchanged (ENTRY 006); tier path is style-only, no bitmaps.
```

### ENTRY 009: PP-OCR REFERENCE VALIDATION (DEV-MACHINE x64 CPU)
* **Date**: 2026-10-05
* **Device**: Dev machine (x64 CPU, Windows), Python 3.10 / ORT 1.30 CPU reference implementation
* **Models**: PaddleOCR PP-OCRv3 Detection (FP32 ONNX, ~4.6 MB), PP-OCRv4 Mobile Recognition (FP32 ONNX, ~10.4 MB, 97-character dictionary)
* **Corpus**: 16 standard game UI test images (`models/ocr/reference_run.json`)
* **Results**:
  - Corpus Accuracy: **16/16 exact text and bounding box parity**; empty/blank images produce 0 detections.
  - Detection Latency: Cold load 75–131 ms; warm P50 73–90 ms.
  - Recognition Latency: 10–232 ms scaled linearly with detected region count (~10–15 ms per region on x64 desktop CPU).
* **Budget check**: Reference run for algorithmic parity and golden assertion pinning; mobile performance unverified until physical device execution (ENTRY 010).

```yaml
BENCHMARK_RUN: 009-PPOCR-DEV-MACHINE-REFERENCE
DATE: 2026-10-05
ENVIRONMENT:
  DEVICE: Dev machine x64 CPU
  RUNTIME: onnxruntime 1.30, Python reference
  MODELS: PP-OCRv3 Det FP32 + PP-OCRv4 Rec FP32
RESULTS:
  CORPUS_EXACT_MATCH: 16/16
  DET_COLD_MS: 75-131
  DET_WARM_P50_MS: 73-90
  REC_PER_REGION_MS: ~10-15
NOTES: Provided numerical golden targets for Kotlin DBDecoder, CtcDecoder, and Preprocess port verification.
```

### ENTRY 010: ON-DEVICE REAL OCR VALIDATION (PHASE 9 VKJ-NX9)
* **Date**: 2026-10-05
* **Device**: HONOR VKJ-NX9 (ADQNUT6615002454), Android 16 (SDK 36), Snapdragon 7 Gen 4 (SM7750, `sun`), arm64-v8a, 11,642,184 kB RAM, display 1264×2728
* **Runtime**: onnxruntime-android 1.17.0, CPU backend (4 intra-op threads)
* **Models**: PP-OCRv3 Det FP32 (~4.6 MB) + PP-OCRv4 Mobile Rec FP32 (~10.4 MB)
* **Method**: Instrumented device tests (`OcrOnDeviceTest`, 4 runs) + live consented capture E2E on MLBB lobby.
* **Results**:
  - Cold Model Load: **332–360 ms** (dict size 95, `CPU_ORT_4T`).
  - Warm Detection Latency: **384–396 ms** on corpus PNGs; **223–230 ms** live in MLBB lobby.
  - Warm Recognition Latency: **~29–32 ms/region** on corpus; **~24 ms/region** live.
  - Corpus Accuracy: Exact matches on `{"100%", "50 HP", "Press E to open"}` (scores 0.800, 0.832, 0.937); menu exact; blank → 0.
  - Memory Footprint: PSS 299 MB active → 196 MB after UI stop.
  - Stability: 0 crashes, 0 FATAL, 0 SIGSEGV.
* **Budget check**: Detection (223–396 ms) and single-region OCR recognition (~24–32 ms) exceed design targets (< 25 ms and < 35 ms total). Pipeline absorbs latency via 5 Hz gate and async stabilization; tracked in ISSUE-008 for Phase 10 optimization.

```yaml
BENCHMARK_RUN: 010-ONDEVICE-REAL-OCR-VKJ-NX9
DATE: 2026-10-05
ENVIRONMENT:
  DEVICE: HONOR VKJ-NX9 (Snapdragon 7 Gen 4 / SM7750, arm64-v8a)
  OS: Android 16 (SDK 36)
  RUNTIME: onnxruntime-android 1.17.0 CPU 4 threads
  MODELS: PP-OCRv3 Det FP32 + PP-OCRv4 Rec FP32
RESULTS:
  COLD_LOAD_MS: 332-360
  DET_WARM_CORPUS_MS: 384-396
  DET_WARM_LIVE_MS: 223-230
  REC_PER_REGION_MS: ~24-32
  PSS_ACTIVE_MB: 299
  PSS_AFTER_STOP_MB: 196
  CRASHES: 0
NOTES: Phase 9 gate passed. Flattening index bug caught and resolved. High latency drives Phase 10 optimizations.
```

### ENTRY 011: PHASE 10 OPTIMIZATIONS — DOWNSCALING, NNAPI PROBE & RESCAN GATING
* **Date**: 2026-10-15
* **Device**: HONOR VKJ-NX9 (serial ADQNUT6615002454), Android 16 (SDK 36), Snapdragon 7 Gen 4 (SM7750), arm64-v8a, 11,642,184 kB RAM, display 1264×2728
* **Runtime**: onnxruntime-android 1.17.0 (CPU 4-thread baseline vs NNAPI Execution Provider experiment)
* **Method**:
  1. A/B downscaler microbenchmark on VKJ-NX9 (`OcrStageBenchmarkDeviceTest`, N=8 interleaved): Old baseline vs Opt A (packed-read hoist + `blendChannel` in `SnapshotDownscaler`).
  2. Stage timing breakdown on VKJ-NX9 across preprocessing, ORT inference, DB postprocessing, crop extraction, and CTC decoding.
  3. NNAPI probe (`OcrNnapiProbeDeviceTest`) comparing NNAPI execution provider vs CPU on PP-OCR Det/Rec.
  4. Rescan gating & instrumentation repair: analysis of 13.3 MB live MLBB logcat (`rescan_run.txt`), correction of `recognizedRegionCount` reporting, relaxation of static IoU threshold to 0.90 to absorb detector coordinate jitter.
* **Results**:
  - Downscaling (2728×1264 → 960×448):
    * Old Baseline: **P50 385.22 ms** (P95 1179.85 ms, Max 1179.85 ms).
    * Opt A: **P50 115.18 ms** (P95 151.42 ms, Max 151.42 ms).
    * **Speedup: 3.34× faster** on physical hardware (bit-identical RGB byte assert validated).
  - ART Stage Breakdown (VKJ-NX9 hardware):
    * `det_preprocess`: P50 **22.66 ms** (P95 23.91 ms, N=5).
    * `ort_det` (CPU 4T): P50 **221.98 ms** (P95 249.30 ms, N=8).
    * `db_decode`: P50 **4.27 ms** (P95 9.64 ms, Max 11.85 ms, N=50).
    * `rec_crop_preprocess` (w=108): P50 **0.37 ms** (P95 0.52 ms, N=20).
    * `ort_rec` (w=108, CPU 4T): P50 **31.18 ms** (P95 53.28 ms, N=10).
    * `ctc_decode`: P50 **0.00 ms** (P95 0.29 ms, Max 2.20 ms, N=100).
    * `engine_detect_full`: P50 **354.18 ms** (P95 368.70 ms, N=5).
    * `engine_recognize_full`: P50 **31.44 ms** (P95 50.59 ms, N=5).
  - NNAPI Probe Experiment:
    * NNAPI Det P50: **146.77 ms** (Max 183.96 ms, N=5).
    * CPU Det P50 (isolated warmed): **102–142 ms**.
    * Verdict: NNAPI provides no speedup over tuned CPU multi-threading on Snapdragon 7 Gen 4; keep CPU backend.
  - Rescan Gating:
    * Identified instrumentation defect: `recognizedRegionCount` was reporting total regions `rawResults.size` instead of unskipped count. Corrected to `rawResults.size - skippedIds.size`.
    * Identified gating bottleneck: `RESCAN_STABLE_IOU = 0.98` was too strict for real detector outputs under game ambient rendering (1-2 px detector jitter yields IoU 0.91–0.95). Relaxed default to `0.90` (configurable), verified with unit tests (278/278 passed) and full on-device suite (23/23 passed).
* **Budget check**: Downscaling overhead reduced from ~385 ms to ~115 ms. Single-region OCR recognition is ~31 ms (inside < 35 ms budget). Total full-frame detection remains at ~222 ms ORT + ~115 ms downscale, absorbing within 5 Hz asynchronous throttle.

```yaml
BENCHMARK_RUN: 011-PHASE10-OPTIMIZATIONS-VKJ-NX9
DATE: 2026-10-15
ENVIRONMENT:
  DEVICE: HONOR VKJ-NX9 (Snapdragon 7 Gen 4 / SM7750, arm64-v8a)
  OS: Android 16 (SDK 36)
  RUNTIME: onnxruntime-android 1.17.0 (CPU 4-thread default; NNAPI probed)
RESULTS:
  DOWNSCALE_OLD_P50_MS: 385.22
  DOWNSCALE_NEW_OPTA_P50_MS: 115.18
  DOWNSCALE_SPEEDUP: 3.34x
  DET_PREPROCESS_P50_MS: 22.66
  ORT_DET_CPU_P50_MS: 221.98
  DB_DECODE_P50_MS: 4.27
  REC_CROP_PREPROCESS_P50_MS: 0.37
  ORT_REC_CPU_P50_MS: 31.18
  CTC_DECODE_P50_MS: 0.00
  NNAPI_DET_P50_MS: 146.77
  CONNECTED_TESTS: 23/23 passed (0 failures)
  JVM_TESTS: 278/278 passed (0 failures)
NOTES: Opt A verified on hardware. NNAPI rejected as slower than CPU. Rescan gating loosened to IoU 0.90 to tolerate detector jitter.
```

