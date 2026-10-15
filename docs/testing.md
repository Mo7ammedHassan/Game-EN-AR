# Testing Strategy & Verification Gate Log

This document details test plans, dataset specifications, and verification gate results.

---

## 1. PHASE VERIFICATION GATE LOGS

### Phase 9 device gate: ON-DEVICE OCR VALIDATION [VERIFIED 2026-10-05 — VKJ-NX9, Phase 9 COMPLETE]
* **Device**: HONOR VKJ-NX9 (ADQNUT6615002454), Android 16 (SDK 36), arm64-v8a, SM7750 (`sun`), 1264×2728.
* **Full suite (`.\gradlew.bat connectedDebugAndroidTest`, overlay grant via appops re-grant loop)**: **21/21 PASS, 0 failures** (10 layout + 1 OCR + 2 NMT + 5 service + 3 touch). App is uninstalled by the run (reinstalled after for live validation).
* **`OcrOnDeviceTest` (4 instrumented runs, all OK)**: cold load 332–360 ms (dict 95, `CPU_ORT_4T`); det warm 384–396 ms on corpus PNGs; rec ~29–32 ms/region; hud 3 boxes + exact texts `{"100%","50 HP","Press E to open"}` (scores 0.800/0.832/0.937); menu 4 boxes + exact texts; empty → 0; repeat runs byte-identical (deterministic ORT CPU); release → cold + empty. Exact-set asserts STRENGTHENED from this evidence (were presence-level).
* **Real defect found by the gate (fixed, re-verified)**: ORT det-tensor flattening indexed `nested[0][y][0]` (channel axis) instead of `nested[0][0][y]` (rows) → `ArrayIndexOutOfBounds (src.length=448 … length=960)` → silent empty detect. Only visible on-device (JVM tests use scripted sessions). Fixed + covered by 2 new JVM pin tests (`flattenDetProb`); engine catch sites now log the stack, not just the message. JVM 275/275 after fix.
* **Live real-pixel E2E (Mobile Legends lobby, consented MediaProjection)**: 25 regions detected, 13–14 stable, 18 published; det 223–230 ms / rec ~24 ms/region live; Tier-3 overlay co-located with EN labels in screenshots (dictionary fallback Latin where glossary misses — expected; Arabic where covered); stable across minutes; UI STOP → service destroyed + overlay detached + PSS 299→196 MB. Game kept animating (no capture stall). No game taps performed (user session protection); overlay touch itself stays Phase-6-proven.
* **Non-failure observations (no code change)**: change detection 18–34 ms/frame live (over the <8 ms budget) and det 223–396 ms (over the <25 ms budget) — system absorbs via 5 Hz gate + async stabilization; recorded as ISSUE-008 (Phase 10). Possible near-duplicate boxes under "Inventory" (Latin fallback + tiny marks co-located) — hypothesis only, Phase-11 tuning note. Human concurrently using the phone confounded one UI-stop attempt (retried cleanly).
* **Stability**: 4 instrumented runs + ~10 min live capture + full suite: **0 crashes, 0 FATAL, 0 SIGSEGV**; PSS-after-infer stable (+221 KB across full reruns — first-inference arena growth, not a leak). Thermal Status 2 (MODERATE), CPU ~52 °C, battery 36 °C during live run — short observation, NOT CONCLUSIVE.

### Phase 9: Real OCR Integration [JVM-VERIFIED 2026-10-05 — device gate has since PASSED, see log above]
* **Date**: 2026-10-05
* **JDK Environment**: Eclipse Temurin OpenJDK 21.0.12.1 LTS (`C:\Users\asus\.jdks\jdk-21.0.12.1+1`)
* **Gradle Version**: Gradle 8.7 (project catalog says 8.7; commands via `.\gradlew.bat`)
* **Unit Tests Task (`.\gradlew.bat testDebugUnitTest --rerun-tasks`)**: **PASS** (273/273 tests — was 227/227; +46 new: `SnapshotStoreTest` (8: latest-wins/overwrite/throttle/clear/repeat-read/generations/validation), `SnapshotPixelMathTest` (9: bilinear solid/interp + cv2 golden ≤3 levels/2%, downscale aspect+stride+no-upscale, det dims incl. banker's 12.5→12, BGR-swapped normalize exact), `OcrDecodeTest` (14: DB synthetic incl. exact score-dilution pin + golden 3-box IoU ≥ 0.8 + CTC collapse/blank/formula/mismatch/tie + golden exact text/score + crop/rot90/norm/caps), `OnnxOcrEngineTest` (12: cold/no-snapshot/release/mapping/sort/filter/malformed/throw paths/decode/score-gate/out-of-frame/metadata), `OcrSnapshotIntegrationTest` (3: store→engine→tracker→stabilizer stable `track-*` e2e with real 192×96 preprocess dims, grace drain on clear, rapid start-stop))
* **Notable debugging (none weakened)**: 7 initial failures all root-caused to TEST bugs (byte-index math, 432-vs-445 dims, contradictory null asserts, missing ×3 stride) — except one REAL behavior insight (dilation dilutes AABB scores to 0.8225, reference-identical, now pinned exactly); golden IoU gate tightened 0.6 → 0.8 from measured 0.867–0.953.
* **Build + Lint (`assembleDebug assembleDebugAndroidTest lintDebug`)**: **PASS** (BUILD SUCCESSFUL; androidTest compiles incl. new `OcrOnDeviceTest`).
* **Reference validation (dev machine, ORT 1.30 CPU)**: 16/16 corpus lines exact, blank → 0 boxes (`models/ocr/reference_run.json`); Kotlin goldens pin the port (see research §5C).
* **Physical Device Validation**: **NOT RUN — NO DEVICE ATTACHED** (`adb devices` empty 2026-10-05; `OcrOnDeviceTest` ready: cold/warm/corpus/empty/stability/PSS/lifecycle). See ISSUE-007.

### Phase 8: Tiered Overlay Rendering [VERIFIED — JVM; on-device visual gate pending]
* **Date**: 2026-10-04
* **JDK Environment**: Eclipse Temurin OpenJDK 21.0.12.1 LTS (`C:\Users\asus\.jdks\jdk-21.0.12.1+1`)
* **Gradle Version**: Gradle 8.7
* **Unit Tests Task (`.\gradlew.bat testDebugUnitTest --rerun-tasks`)**: **PASS** (227/227 tests — was 197/197; +30 new: `OverlayRenderTiersTest` (29: default/override policy, mutation-immunity, blank-id guards, Tier 1 opaque incl. keep/upgrade, Tier 2 mask alpha, Tier 3 transparency/identity/bare-outline, constructor validation, Tier 1→3 + policy fallback with text preservation, alias parsing, parseOrFallback, fromConfig count, mixed-direction ×6 on all tiers, long-text passthrough, blank/degenerate drops, sink drop/filtering, 3-region mixed-tier update, poison-region isolation, delegate throw/refusal counting, clear/hide passthrough, mapper-derived bounds preservation ×2), `TierRenderBenchmarkTest` (1))
* **Build + Lint (`.\gradlew.bat assembleDebug lintDebug`)**: **PASS** (BUILD SUCCESSFUL; only pre-existing warnings — `scaledDensity` deprecation, Phase3ModelsTest `is`-check notes)
* **Benchmarks (real tier API, JVM)**: policy+styling over 20 synthetic regions p50 2.8 µs / p95 15.9 µs per update (ENTRY 008)
* **Notable**: one new-test compile fix (Kotlin-interface SAM → `object :` expression); one production fix pre-tests (`android.graphics.Color` → plain-int alpha so the renderer stays JVM-pure). No Phase 6 test touched; all 163 pre-existing overlay/pipeline tests green unchanged.
* **Physical Device Validation**: **NOT RUN** (pure-JVM style transforms; Tier 1/2 on-screen appearance + readability over real game art needs the on-device visual gate — ISSUE-006; `connectedDebugAndroidTest` 20/20 on VKJ-NX9 stands from Phase 6)

### Phase 7: Integrated Async Pipeline [VERIFIED — JVM]
* **Date**: 2026-10-04
* **JDK Environment**: Eclipse Temurin OpenJDK 21.0.12.1 LTS (`C:\Users\asus\.jdks\jdk-21.0.12.1+1`)
* **Gradle Version**: Gradle 8.7
* **Unit Tests Task (`.\gradlew.bat testDebugUnitTest --rerun-tasks`)**: **PASS** (197/197 tests — was 163/163; +34 new: `TranslationPipelineTest` (20: miss/hit/supersession/stale/multi/disappearance/bounds-snap/lifecycle/rapid/failures/empty/cold/flag-policy/viewport/metrics/cache/retry-pump), `OcrPipelineCoordinatorTest` (8: gating/legacy-ids/tracker-remap/rescan-skip/move+change/interval-1/failure/stop), `TranslationPipelineIntegrationTest` (5: real cache+protector+tracker+stabilizer e2e, rescan e2e, jitter containment, flag-false suppression, dictionary e2e), `TranslationPipelineBenchmarkTest` (2))
* **Build Task (`.\gradlew.bat assembleDebug lintDebug`)**: **PASS** (BUILD SUCCESSFUL; required one enabling build change: `testOptions.unitTests.isReturnDefaultValues=true` so android.util.Log no-ops on JVM instead of throwing "not mocked" — no production or assertion semantics affected; Robolectric runtime unavailable offline)
* **Benchmarks (real APIs, JVM)**: synthetic E2E frame→overlay p50 ~515 ms harness-paced (NOT a component claim); 10-region burst 10 launched / 10 completed / 54 shed / 0 stale / max-concurrent 1 (ENTRY 007)
* **Notable debugging (all resolved, none weakened)**: StateFlow-conflation test races (pacing + companion forcing + @Volatile sinks); a REAL pipeline bug (unstable-first arrivals never launched — needsRetry fix); a REAL greedy-matching-class oversight (superseded metric on stable path); a REAL publish-gate hole (move-refresh without re-translation); bare-`setMaxLines`-style expectation corrections from device evidence (Phase 6)
* **Physical Device Validation**: **NOT RUN** (pure JVM logic + already-device-proven components; on-device E2E needs real OCR models — Phase 9 — and games — Phase 11)

### Phase 6 Stage A: Overlay Foundation — JVM/Unit Implementation [VERIFIED — JVM]
* **Date**: 2026-10-04
* **JDK Environment**: Eclipse Temurin OpenJDK 21.0.12.1 LTS (`C:\Users\asus\.jdks\jdk-21.0.12.1+1`)
* **Gradle Version**: Gradle 8.7
* **Unit Tests Task (`.\gradlew.bat testDebugUnitTest --rerun-tasks`)**: **PASS** (163/163 tests — was 136/136; +27 new: `BidiUtilsTest` (10, real UBA runs/directions incl. mixed/placeholders/weak-only), `CoordinateMapperTest` (11, identity/scale/offset/4 rotations corner-verified/invalid), `OverlayStyleRegionTest` (5, defaults/validation/renderability/logical-order byte-identity), `RenderModelBenchmarkTest` (1))
* **Android test COMPILE (`assembleDebugAndroidTest`)**: **PASS** (device suites compile: `ArabicLayoutDeviceTest`, `OverlayServiceTest`, `OverlayTouchTest`)
* **Build + Lint (`assembleDebug lintDebug`)**: **PASS** (BUILD SUCCESSFUL; 1 lint error fixed the right way — `LineBreaker` vs `Layout` constants per API; 1 StaticFieldLeak warning eliminated structurally — service-scoped controller, no static-held Context)
* **JVM render benchmark**: mapper+bidi over 20 synthetic regions p50 ~12 µs (ENTRY 005, JVM-measured)
* **Physical Device Validation**: **NOT RUN — NO DEVICE CONNECTED** (Stage B pending)

### Phase 6 Stage B: Android/Device Validation [VERIFIED 2026-10-04 — VKJ-NX9]
* **Device**: HONOR VKJ-NX9 (ADQNUT6615002454), Android 16 (SDK 36), SM7750 arm64-v8a, 1264×2728, ar-EG locale
* **Procedure (reproducible)**: `installDebug` → verify `pm list packages -U` (UID for user 0) → `appops set … SYSTEM_ALERT_WINDOW allow` → verify `allow` → `connectedDebugAndroidTest`. CAVEATS (measured): the run reinstalls (wipes the grant — re-grant around/during runs) and uninstalls afterwards (pull artifacts mid-run); secure keyguard denies `screencap` (use in-process bitmap + pull); no Robolectric (no offline runtime).
* **`connectedDebugAndroidTest`: 20/20 PASS, 0 skipped** (12 layout incl. order/pixel/alpha asserts, 2 ONNX, 5 service, 3 touch). Initial 3 layout failures root-caused to test conventions (direction constants, maxLines-without-ellipsis, trailing-space line width) and fixed with device-measured evidence — plus 1 real implementation bug found+fixed (background/text alpha composition clobbering).
* **Visual gate: PASS** — full-matrix bitmap rendered through real `onDraw`, pulled and eye-verified (shaping, RTL, mixed order, numbers, `%`/`$`, placeholder, wrap, ellipsis, outline/shadow/bg, transparency) + pixel asserts (ink, transparent margins, exact bg color).
* **Touch gate: PASS** — tap inside overlay Arabic text launched Shazam (focus-verified) → BACK → home intact; swipe through overlay text paged the launcher (uiautomator 32→35 nodes, new page); focus never stolen (`canReceiveKeys=false`, game stayed focused earlier); all dispatched streams unconsumed. Live game taps deliberately avoided (user's session protection); launcher-based delivery is the equivalent system path.
* **Lifecycle gate: PASS** — show/hide/update/clear/rapid-50×/repeats/refusal/empty on-device, 0 crashes.
* **Perf (device)**: layout build p50 768 µs / p95 926 µs; 50× multi-region update mean 2.2 ms (quiet) / 9.9 ms (loaded, environmental variance — event-driven updates only, no frame claim).
* **Accepted device behaviors**: window alpha 0.8 server-side (client requests 1.0; OEM policy, readability unaffected); `touchableRegion` dumpsys field is not evidence (delivery tests are).

### Phase 5b: Spatial ROI Tracker + Temporal Stabilizer Hardening [VERIFIED — JVM]
* **Date**: 2026-10-04
* **JDK Environment**: Eclipse Temurin OpenJDK 21.0.12.1 LTS (`C:\Users\asus\.jdks\jdk-21.0.12.1+1`)
* **Gradle Version**: Gradle 8.7
* **Unit Tests Task (`.\gradlew.bat testDebugUnitTest --rerun-tasks`)**: **PASS** (136/136 tests — was 96/96; +40 new: `RegionTrackerTest` (19: IoU incl. strict-0.6 boundary/degenerate, identity, appear/disappear/expiry, multi/ambiguous/tie-determinism, guards, reset), `OcrResultStabilizerTest` +13 (state lifecycle, drift, 1-/2-frame corruption, empty/low-conf freeze, grace DISAPPEARED/resume/removal, reappearance-change, cross-isolation, guards — all 12 pre-existing tests pass UNCHANGED), `TrackerStabilizerIntegrationTest` (7: static/jitter/teleport/change/gap-short/gap-long/two-dialogues), `SpatialTrackerBenchmarkTest` (1))
* **Notable**: the tie-break test exposed a REAL greedy-matching bug (short-circuit set-add poisoned used-sets → NPE on ambiguity); fixed with check-then-add discipline. No test was weakened.
* **Build Task (`.\gradlew.bat assembleDebug lintDebug`)**: **PASS** (BUILD SUCCESSFUL)
* **Hot-path benchmark (real tracker+stabilizer cycle, 12 regions, JVM)**: p50 16.7 µs / p95 92.8 µs per frame — inside the 5 ms budget (ENTRY 004, JVM-measured)
* **Integration scope**: tracker + stabilizer independently correct and composition-tested; `OcrPipelineCoordinator` NOT rewired (constructor-compatible defaults; wiring is Phase 7)
* **Physical Device Validation**: **NOT REQUIRED** (pure JVM geometry/state-machine logic; no Android APIs, no native code)

### Phase 5a: Bounded LRU Translation Cache + Text Normalizer [VERIFIED — JVM]
* **Date**: 2026-10-04
* **JDK Environment**: Eclipse Temurin OpenJDK 21.0.12.1 LTS (`C:\Users\asus\.jdks\jdk-21.0.12.1+1`)
* **Gradle Version**: Gradle 8.7
* **Unit Tests Task (`.\gradlew.bat testDebugUnitTest --rerun-tasks`)**: **PASS** (96/96 tests — was 60/60; +36 new: `TextNormalizerTest` (13: whitespace/newlines/Unicode-spaces/NFKC/punctuation/case/spans/idempotency/controls), `LruTranslationCacheTest` (13: miss/hit/update/500-bound/LRU-order/recency/clear/guards/key-isolation/noop/concurrency), `CachedTranslationEngineTest` (9: miss/hit/normalized-key/flag-false-policy ×2/flag-true/cold-engine/transparency), `TranslationCacheBenchmarkTest` (2))
* **Build Task (`.\gradlew.bat assembleDebug lintDebug`)**: **PASS** (BUILD SUCCESSFUL)
* **Cache-hit benchmark (real engine API, JVM)**: single-key p50 4.8 µs / p95 12.6 µs; 500-key cycling p50 4.1 µs / p95 5.3 µs — inside the < 1 ms target (ENTRY 003, JVM-measured)
* **Admission policy (ADR-008)**: flag=false span-bearing + cold-engine results are served but NEVER cached; verified by focused integration tests
* **Physical Device Validation**: **NOT TESTED** (JVM-only this session; ART cache numbers + pipeline wiring belong to Phase 7/device runs)

### ISSUE-004 Fix: Model-Safe Token Protection [VERIFIED — JVM unit + dev-machine model]
* **Date**: 2026-10-04
* **JDK Environment**: Eclipse Temurin OpenJDK 21.0.12.1 LTS (`C:\Users\asus\.jdks\jdk-21.0.12.1+1`)
* **Gradle Version**: Gradle 8.7
* **Unit Tests Task (`.\gradlew.bat testDebugUnitTest`)**: **PASS** (60/60 tests passed across 12 suites — was 50/50; `TokenProtectorTest` 6→16 incl. sentinel-shape, collision-avoidance ×2, ratio-span, `%.2f`, stats, mixed-multi, repeated, mangling-detection regression, plain-text passthrough; `TokenizerParityAuditTest` asserts 16/16 exact ID matches incl. 3 sentinel encoder-input shapes)
* **Build Task (`.\gradlew.bat assembleDebug lintDebug`)**: **PASS** (BUILD SUCCESSFUL)
* **APK Output**: `d:\GamesEn-Ar\app\build\outputs\apk\debug\app-debug.apk` (212,446,757 bytes — includes proof-bundled INT8 assets in `app/src/main/assets/`, unchanged by this fix)
* **Model validation (dev machine, NOT Android)**: **VERIFIED**. Real HF tokenizer + real INT8 ONNX prose, greedy: `VAR0` mangling reproduced (`في آربيلا`, dropped slots, `000 5`); 6-digit sentinels copied verbatim in ~25/30 contexts covering every required class (numbers, `%`, `%d/%s/%.2f`, `{playerName}`, `50 XP`/`100 HP`, `$100`/`50G`, `<color>` tags, `80/100` ratio, mixed-multi, repeated); pathological contexts catalogued (fail masked AND unmasked — model-quality holes). Full matrix in `research.md` §5C. Determinism 3/3.
* **Physical Device Validation**: **VERIFIED 2026-10-04 (VKJ-NX9, Snapdragon 7 Gen 4 / SM7750, Android 16, arm64-v8a)**. `connectedDebugAndroidTest` 2/2 PASSED with strengthened sentinel assertions (value round-trip, honest flag, no `98\d{4}` residue); logcat-captured rerun: cold load 2909 ms, warm 38–83 ms, `50 HP` + `{playerName}` restored with flag true, 0 crashes. Full numbers in `benchmarks.md` ENTRY 002. ISSUE-004 is FULLY VERIFIED/CLOSED.

### Phase 4.1: Real Translation Inference Proof [IMPLEMENTED — DEV-MACHINE INFERENCE VERIFIED, ON-DEVICE EXECUTION NOT TESTED]
* **Date**: 2026-10-01
* **JDK Environment**: Eclipse Temurin OpenJDK 21.0.12.1 LTS (`C:\Users\asus\.jdks\jdk-21.0.12.1+1`)
* **Gradle Version**: Gradle 8.7
* **Build Task (`./gradlew.bat assembleDebug`)**: **PASS** (BUILD SUCCESSFUL)
* **Unit Tests Task (`./gradlew.bat testDebugUnitTest --rerun-tasks`)**: **PASS** (50/50 tests passed across 12 suites, incl. `TokenizerParityAuditTest` (3): exact ID parity on 13 inputs, round-trip, unknown-char behavior; `OnnxTranslationIntegrationTest` (3))
* **Lint / Static Checks (`./gradlew.bat lintDebug`)**: **PASS** (BUILD SUCCESSFUL)
* **APK Output**: **VERIFIED** (`d:\GamesEn-Ar\app\build\outputs\apk\debug\app-debug.apk`, 59.91 MB / 59,908,594 bytes)
* **Real NMT inference (dev machine, NOT Android)**: **VERIFIED**. PyTorch (`transformers` 4.57.6, greedy) and quantized ONNX (ORT 1.30, greedy, HF tokenizer) both executed end-to-end on all 5 representative sentences; real Arabic output recorded in `research.md` §5B.
* **Tokenizer parity audit (2026-10-03)**: **VERIFIED 13/13**. `TokenizerParityAuditTest` asserts Android IDs == HF `MarianTokenizer` IDs exactly, on all 5 required sentences plus apostrophes, numbers, placeholders, tags, single-char, empty, and unknown-char inputs. Audit also found+fixed 2 decoder bugs (dropped first token; encoder past-key/values not retained) — corrected quantized-ONNX outputs recorded in `research.md` §5B.
* **On-device ONNX execution (2026-10-03, VKJ-NX9)**: **VERIFIED**. `connectedDebugAndroidTest` 2/2 passed (`OnnxOnDeviceInferenceTest`): cold load 2441 ms, warm 34–47 ms, PSS +241 MB, real Arabic output, 0 crashes. Full numbers in `benchmarks.md` ENTRY 001. Path here included 2 fixed defects (non-direct buffers; tensor ownership SIGSEGV — see `issues.md` ISSUE-005) and a hardened test that asserts Arabic-script output so fallback can never masquerade as success.
* **Android cold-load / warm latency / peak RAM**: **BENCHMARK REQUIRED** (not measured).

### Phase 4: Modular Offline Translation Engine [VERIFIED]
* **Date**: 2026-09-27
* **JDK Environment**: Eclipse Temurin OpenJDK 21.0.12.1 LTS (`C:\Users\asus\.jdks\jdk-21.0.12.1+1`)
* **Gradle Version**: Gradle 8.7
* **Build Task (`./gradlew.bat assembleDebug`)**: **PASS** (BUILD SUCCESSFUL in 32s)
* **Unit Tests Task (`./gradlew.bat testDebugUnitTest`)**: **PASS** (41/41 tests passed in 21s across 9 suites: `DictionaryTranslationEngineTest` (5), `FoundationSanityTest` (2), `FrameRateLimiterTest` (1), `NoOpOcrEngineTest` (3), `OcrResultStabilizerTest` (12), `Phase3ModelsTest` (7), `Phase4ModelsTest` (3), `TokenProtectorTest` (6), `TranslationBaselineTest` (2))
* **Lint / Static Checks (`./gradlew.bat lintDebug`)**: **PASS** (Completed cleanly in 29s, report generated)
* **APK Output**: **VERIFIED** (`d:\GamesEn-Ar\app\build\outputs\apk\debug\app-debug.apk`, 24.38 MB / 24,388,880 bytes)
* **Physical Device Validation**: **NOT TESTED** (`emulator-5554` visible; physical ARM64 device not connected; per rules marked NOT TESTED)

### Phase 3: Change Detection & OCR Prototype [VERIFIED]
* **Date**: 2026-09-27
* **JDK Environment**: Eclipse Temurin OpenJDK 21.0.12.1 LTS (`C:\Users\asus\.jdks\jdk-21.0.12.1+1`)
* **Gradle Version**: Gradle 8.7
* **Build Task (`./gradlew.bat assembleDebug`)**: **PASS** (BUILD SUCCESSFUL in 27s)
* **Unit Tests Task (`./gradlew.bat testDebugUnitTest`)**: **PASS** (25/25 tests passed in 22s across 5 suites)
* **Lint / Static Checks (`./gradlew.bat lintDebug`)**: **PASS** (Completed cleanly in 25s, report generated)
* **APK Output**: **VERIFIED** (`d:\GamesEn-Ar\app\build\outputs\apk\debug\app-debug.apk`, 24.35 MB / 24,351,506 bytes)
* **Physical Device Validation**: **NOT TESTED** (`emulator-5554` visible; physical ARM64 device not connected)

### Phase 2: Screen Capture & Code Review [VERIFIED]
* **Date**: 2026-09-26
* **JDK Environment**: Eclipse Temurin OpenJDK 21 LTS (`C:\Users\asus\.jdks\jdk-21.0.12.1+1`)
* **Gradle Version**: Gradle 8.7
* **Code Review Status**: **COMPLETE & VERIFIED**. Audited 12 core lifecycle and memory questions. Single **IMPORTANT** issue (Service shutdown race condition) resolved using atomic `isCapturingActive` state guard and clearing `ImageReader` listeners prior to resource disposal.
* **Build Task (`./gradlew.bat assembleDebug`)**: **PASS** (BUILD SUCCESSFUL in 47s)
* **Unit Tests Task (`./gradlew.bat testDebugUnitTest`)**: **PASS** (3/3 tests passed in 50s: `FoundationSanityTest`, `FrameRateLimiterTest`)
* **Lint / Static Checks (`./gradlew.bat lintDebug`)**: **PASS** (Completed cleanly in 31s)
* **APK Generated**: **YES** (Verified at `d:\GamesEn-Ar\app\build\outputs\apk\debug\app-debug.apk`, size: 24.25 MB)
* **Device Launch**: **NOT TESTED** (`adb devices` verified 0 connected devices)

---

## 2. REPRESETATIVE TEST DATASET SPECIFICATION

To ensure real-world accuracy beyond clean synthetic text, the test dataset `/test-dataset/` contains game screenshots categorized into 6 test classes:

| Class ID | Description | Sample Cases | Primary Challenge |
| :--- | :--- | :--- | :--- |
| `DS-01` | **Standard Dialogue** | Multiline RPG text boxes, subtitles. | Paragraph line wrapping & sentence context. |
| `DS-02` | **Short UI Commands** | "PRESS E TO OPEN", "GO", "RUN", "EXIT", "YES", "NO". | Avoid false rejection by length filters. |
| `DS-03` | **Variable Tokens** | `"Give {playerName} $100 (50% bonus)"`. | Preserve placeholders, numbers, currencies. |
| `DS-04` | **Stylized / Glow Fonts**| Low contrast, drop shadows, fantasy fonts. | OCR detection bounding box accuracy. |
| `DS-05` | **Moving Backgrounds** | Dialogue rendered over active 3D gameplay scenes. | Frame change detection & temporal stabilization. |
| `DS-06` | **Mixed Language / Keys**| `"Press KEY_SPACE to cast السحر!"`. | BIDI layout & Unicode directionality. |

---

## 3. VERIFICATION COMMANDS

```bash
# Set JDK 21 environment and run verification tasks
$env:JAVA_HOME="C:\Users\asus\.jdks\jdk-21.0.12.1+1"
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```
