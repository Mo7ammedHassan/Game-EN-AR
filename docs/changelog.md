# Chronological Changelog

All notable architectural changes and major milestones for this project will be logged in this file chronologically.

---

## [0.15.0] - 2026-10-15

### Optimized (Phase 10 — Performance & Scheduling Optimizations)
* **Downscaler Optimization (Opt A)**: Packed-read hoist and inline `blendChannel` in `SnapshotDownscaler`. Measured on physical hardware (HONOR VKJ-NX9): downscale latency dropped from P50 385.22 ms to 115.18 ms (**3.34× speedup**, bit-identical output asserted).
* **NNAPI Probe Evaluation**: Bounded experiment (`OcrNnapiProbeDeviceTest`) comparing NNAPI execution provider vs CPU on PP-OCR Det/Rec. NNAPI Det P50 = 146.77 ms vs CPU 102–142 ms: confirmed no benefit, retained ORT CPU 4-thread backend.
* **Rescan Gating & Gating Bug Fix**:
  - Relaxed `RESCAN_STABLE_IOU` from 0.98 to 0.90 (configurable via `rescanStableIou` parameter). Real DBNet detector boxes jitter by 1–2 px under game ambient rendering, dropping IoU below 0.98 on static UI text boxes; 0.90 absorbs detector jitter while strictly detecting moved boxes.
  - Fixed reporting bug in `OcrPipelineCoordinator`: `recognizedRegionCount` previously recorded `rawResults.size` (total regions including skips). Corrected to `rawResults.size - skippedIds.size` so metrics report genuine model-inferred regions.
  - Added `skipped` count to `OCR cycle:` logcat.

### Verified (On-Device & JVM)
* **`connectedDebugAndroidTest` on VKJ-NX9**: **23/23 PASS** (10 layout + 1 NNAPI + 1 OCR + 1 stage bench + 2 NMT + 5 service + 3 touch), 0 failures.
* **`testDebugUnitTest`**: **278/278 PASS** (was 275/275; +1 rescan jitter tolerance test, +2 pin tests).
* **Benchmarks**: Committed missing historical ENTRY 009, ENTRY 010, and new ENTRY 011 to `docs/benchmarks.md`.
* **ADR-015**: Documented pixel downscaling hoisting, NNAPI evaluation, and realistic rescan gating.

## [0.14.1] - 2026-10-05

### Verified (Phase 9 device gate — VKJ-NX9, Phase 9 COMPLETE)
* **`connectedDebugAndroidTest`: 21/21 PASS** (10 layout + 1 OCR + 2 NMT + 5 service + 3 touch), 0 failures.
* **`OcrOnDeviceTest` ×4 runs**: cold 332–360 ms; det warm 384–396 ms; rec ~29–32 ms/region; hud/menu exact text sets; empty clean; repeats byte-identical; release lifecycle green. Exact-set asserts strengthened from this evidence.
* **Live real-pixel E2E (Mobile Legends lobby, consented MediaProjection)**: 25 regions / 13–14 stable / 18 published; co-located Tier-3 overlay in screenshots (dictionary fallback where glossary misses — expected); UI STOP → service destroyed + overlay detached + PSS 299→196 MB; game never stalled. No game taps (session protection).
* **ENTRY 010** recorded (cold/warm/PSS/stability/NMT re-measurement). ISSUE-007 CLOSED. ISSUE-008 opened (det/change-det over aspirational budgets — absorbed architecturally, Phase 10).

### Fixed
* **Real on-device defect**: ORT det-tensor flattening read the channel axis (`nested[0][y][0]`) instead of rows (`nested[0][0][y]`) → silent empty detect. Fixed + pinned by 2 new JVM tests (`flattenDetProb`); engine catch sites now log stacks. JVM 275/275.

### Tests
* `testDebugUnitTest --rerun-tasks`: **275/275 PASS** (was 273/273; +2 pin tests).
* `assembleDebug` + `assembleDebugAndroidTest` + `lintDebug`: **PASS**.
* Stability: 4 instrumented runs + full suite + ~10 min live capture: **0 crashes, 0 FATAL, 0 SIGSEGV**.

## [0.14.0] - 2026-10-05

### Added (Phase 9 — Real OCR Integration, JVM + reference-verified; device gate open)
* **Pixel transport** (`ocr/` package): `OcrSnapshot` (immutable ≤960-side RGB888, ~1.3 MB) + `SnapshotStore` (single lock-free latest-only slot, 200 ms fake-clock throttle, `isDue` pre-check, clear-on-stop/start) + `SnapshotDownscaler` (in-place RGBA bilinear, never copies full-res). Wired into `ScreenCaptureService` (changed+due only, metadata-only fallback). ADR-013.
* **OCR models** (SHA-pinned, Apache-2.0): `en_PP-OCRv3_det_infer.onnx` (2,421,707 B) + `en_PP-OCRv4_rec_mobile.onnx` (7,653,044 B) + `en_dict.txt` → `assets/models/` (+10 MB APK). INT8/v5/`ch_*`/cls rejected with evidence (research §5C).
* **Runtime seam**: `DetSession`/`RecSession`/`OcrSessionProvider` (ORT CPU-4T/BASIC_OPT impl reusing NMT-proven options + ISSUE-005 ownership discipline; scripted fakes for JVM). Delegates unevaluated (CPU is the production path until hardware exists).
* **Paddle-faithful pre/post**: det geometry/normalize (banker's /32 rounding, BGR-swapped constants), simplified DB decode (AABB; golden IoU 0.867–0.953), CTC decode (golden text+score exact), rec crop/norm (batch-1, rot90 rule, width cap). Goldens in `app/src/test/resources/ocr/`.
* **`OnnxOcrEngine`** behind the UNCHANGED `OcrEngine` contract (side-channel snapshots; cold/absent/malformed → empty, never throws; active-snapshot pinning; reading-order sort; text-score gate). `MainActivity` wired (tracker + paired 3/3 grace, rescan 1, off-main load, release on stop). ADR-014 + `architecture.md` §7B.
* **Device gate test** `OcrOnDeviceTest` (cold/warm/corpus/empty/stability/PSS/lifecycle) — compiles, NOT RUN (ISSUE-007).

### Fixed (test bugs only — 7 initial failures, none weakened)
* Byte-index/432-vs-445 dims/contradictory asserts/stride math in new tests; one REAL behavior pin (dilation score-dilution 0.8225, reference-identical); golden IoU gate tightened 0.6 → 0.8 from measured values.

### Tests
* `testDebugUnitTest --rerun-tasks`: **273/273 PASS** (was 227/227; +46 across 5 suites).
* `assembleDebug` + `assembleDebugAndroidTest` + `lintDebug`: **PASS**.
* `connectedDebugAndroidTest`: **NOT RUN — NO DEVICE** (ISSUE-007).

### Benchmarks
* ENTRY 009: dev-machine ORT reference (16/16 exact; det cold 75–131 ms / warm 73–90 ms; rec 10–232 ms) — INFORMATIONAL, not budget claims.

### Known gaps (explicit later work)
* Device gate: cold/warm/PSS/stability/thermals on VKJ-NX9 (ISSUE-007); accelerator evaluation needs hardware; rescan tuning + INT8-OCR are Phase 10 candidates with profiler evidence.

## [0.13.0] - 2026-10-04

### Added (Phase 8 — Tiered Overlay Rendering, JVM-verified)
* **`OverlayRenderTier`** (`render/` package): TIER_1_COVER (opaque dialogue-box replacement) / TIER_2_MASK (translucent region-limited mask) / TIER_3_TRANSPARENT (game visible + outlined Arabic), with alias-tolerant `parseOrNull`/`parseOrFallback` (invalid config → Tier 3, never throws).
* **`OverlayRenderPolicy` + `ExplicitOverlayRenderPolicy`**: decision side — default tier + per-region overrides, O(1) lookup, defensive-copy, blank-id guards, `fromConfig()` with per-entry fallback counting. No auto-detection (deferred, ISSUE-006).
* **`OverlayTierRenderer` + `DefaultOverlayTierRenderer`**: implementation side — pure `OverlayStyle` transforms (JVM-only, no `android.graphics` dependency), id/bounds/text byte-identical, Tier 1 opaque-cover (translucent author boxes upgraded), Tier 2 fixed `0xB3141414` mask, Tier 3 background-strip + bare-style outline fallback; unrenderable input dropped on all tiers.
* **`TieredOverlaySink` + `TierRenderMetrics`/`TierRenderSnapshot`**: `OverlaySink` decorator (policy → render → forward) with Tier 3 fallback on policy/Tier 1-2 failure, safe-drop, per-region isolation, delegate-failure counting; `clear()`/`hide()` pass through.
* **Wiring**: `MainActivity` drives `GameTranslatorPipeline` through `TieredOverlaySink(overlayController)` with the all-Tier-3 default (identity transform — zero visual delta vs Phase 7).
* **ADR-012** + `architecture.md` §5C + ISSUE-006 (on-device visual gate + auto-selection deferred to Phase 11).

### Fixed
* Pre-test production slip: `android.graphics.Color.alpha()` in the renderer would have broken JVM purity (stub returns 0) — replaced with plain-int alpha arithmetic before any test ran.

### Tests
* `testDebugUnitTest --rerun-tasks`: **227/227 PASS** (was 197/197; +30: `OverlayRenderTiersTest` 29 + `TierRenderBenchmarkTest` 1). No Phase 6 test touched.
* `assembleDebug` + `lintDebug`: **PASS**.
* `connectedDebugAndroidTest`: **NOT RE-RUN** (no Android-API behavior changed; 20/20 on VKJ-NX9 stands; tier visual gate is ISSUE-006).

### Benchmarks
* Tier policy+styling, 20 synthetic regions (JVM, ENTRY 008): p50 2.8 µs / p95 15.9 µs per update — ~270× below the on-device layout-build p50.

### Known gaps (not fixed here — explicit later-phase scope)
* On-device Tier 1/2 visual gate + automatic complexity detection (ISSUE-006, Phase 11); pixel transport + real OCR models (Phase 9).

## [0.12.0] - 2026-10-04

### Added (Phase 7 — Integrated Async Pipeline, JVM-verified)
* **`GameTranslatorPipeline`** (`pipeline/` package): stabilized-OCR in, translated-overlay out. Bounded latest-state revisions, semaphore backpressure with shed-and-retry (no queues by design), revision-mismatch stale drops with immediate latest relaunch, 200 ms worker-side retry pump (REQUIRED: conflated flows suppress repeat emissions for static content — measured stall pre-pump), verified-or-span-free publish gate, move-snapped overlay mirror diff, owned single-thread worker + sized translation pool (closed on stop), atomic metrics snapshot.
* **`OcrPipelineCoordinator` extensions** (surgical, legacy-exact defaults): optional `RegionTracker` (downstream ids become trackIds — Part D), `ocrRescanIntervalCycles` (skip re-OCR for stable+static+fresh tracks with bounded re-verify — Part E).
* **`OverlaySink`** (render seam; `OverlayController` conforms unchanged) + `Viewport` config + `PipelineMetrics`/`PipelineSnapshot` diagnostics.
* **Wiring**: `MainActivity` connects coordinator → pipeline → controller with `CachedTranslationEngine(DictionaryTranslationEngine())` (instant, safe-idle; ONNX swap is one constructor argument in Phase 9). Plus `testOptions.unitTests.isReturnDefaultValues=true` (Log no-ops on JVM; no assertion semantics affected).
* **ADR-011** + `architecture.md` §7 (integrated runtime: threads, ownership, stale protocol, lifecycle, failure domains, memory bounds).

### Fixed (all found by the new tests, none weakened)
* REAL: unstable-first arrivals never launched (needsRetry only armed on shed paths) — first translations of real tracks would silently never happen.
* REAL: stable-path supersession uncounted (metric + immediate-relaunch path).
* REAL: pure-motion boxes never refreshed overlay (position stuck until text changed) — move-snapped refresh without re-translation.
* REAL: overlay mirror updated before sink success (failed updates went blind) — mirror commits on success only, failures retry.
* Test-design corrections from measured behavior: StateFlow-conflation pacing/companion forcing, @Volatile test sinks (spin-hoist), warm-recovery via revisit (not identical re-emit), rescan counts (3 window-fill recognizes, then skip), regionsRemoved only counts published removals.

### Tests
* `testDebugUnitTest --rerun-tasks`: **197/197 PASS** (was 163/163; +34: 19 pipeline incl. retry-pump, 8 coordinator, 5 real-component integration, 2 benchmark).
* `assembleDebug` + `lintDebug`: **PASS**.
* `connectedDebugAndroidTest`: **NOT RE-RUN** (no pipeline behavior needs Android APIs; all touched surfaces JVM-provable; 20/20 on VKJ-NX9 stands).

### Benchmarks
* Synthetic E2E frame→overlay p50 ~515 ms harness-paced — liveness proof only, NOT a component claim (ENTRY 007).
* 10-region burst, 1 permit: 10 launched / 10 completed / 54 shed / 0 stale / max-concurrent 1.

### Known gaps (not fixed here — explicit Phase 9/11 scope)
* No pixel transport capture→OCR (`FrameBuffer` metadata-only by design); on-device E2E with real models pending.

---

## [0.10.0] - 2026-10-04

### Added (Phase 6 Stage A — JVM/Unit)
* **Overlay rendering foundation**: pure layer (`OverlayStyle` validated config, `OverlayRegion`/`OverlayRect` with logical-order preservation, `CoordinateMapper` incl. 4-rotation support, `BidiUtils` real-UBA direction/run analysis) + Android layer (`ArabicTextLayout` native `StaticLayout`/FIRSTSTRONG shaping, `OverlayRegionView` transparent non-interactive canvas with cached two-pass outline, `OverlayController` blocking-marshaled failure-isolated window control, `OverlayService` thin lifecycle host + manifest entry). No string reversal/shaping, no bitmaps, layouts rebuild on change only.
* **Device suites written + compiling** (`assembleDebugAndroidTest` green): `ArabicLayoutDeviceTest` (11-input shaping matrix, direction, wrapping, max-lines, empty/invalid contracts, outline/shadow, bitmap draw, build-cost log), `OverlayServiceTest` (lifecycle, exact window params, refusal path, rapid updates), `OverlayTouchTest` (dispatched tap/swipe unconsumed). Robolectric deliberately NOT used (android-all runtime absent offline — ADR-010).
* **ADR-010** + `architecture.md` §5B.

### Fixed
* 2 unit-test expectation slips (rotation-90 corner, letterbox scale+offset) — test bugs, implementation correct.
* 1 lint error the right way (`LineBreaker` vs `Layout` constants); 1 StaticFieldLeak warning eliminated structurally (service-scoped controller, no static-held Context).

### Tests
* `testDebugUnitTest --rerun-tasks`: **163/163 PASS** (was 136/136; +27).
* `assembleDebug` + `assembleDebugAndroidTest` + `lintDebug`: **PASS**.
* `connectedDebugAndroidTest`: **NOT RUN — NO DEVICE CONNECTED** (Stage B pending).

### Benchmarks
* Render-model cost, JVM (ENTRY 005): mapper+bidi 20 regions p50 ~12 µs. `StaticLayout` build cost outstanding (device-logged in Stage B).

### Deferred (Stage B — HONOR device)
* Run device suites (grant `android:system_alert_window` first); manual game-behind-overlay touch + visual shaping gate. Phase 6 NOT complete until then.

---

## [0.11.0] - 2026-10-04

### Verified (Phase 6 Stage B — VKJ-NX9, HONOR, Android 16, SM7750 arm64-v8a)
* **`connectedDebugAndroidTest`: 20/20 PASS, 0 skipped** (12 layout incl. new order/pixel/alpha asserts, 2 ONNX, 5 service, 3 touch). **Phase 6 COMPLETE.**
* **Visual gate (eye-verified real pixels)**: full-matrix bitmap through real `onDraw`, pulled and inspected — shaping, RTL, mixed/Latin/number/`%`/`$` order, placeholder intact, 4-line wrap, maxLines+`…`, outline/shadow/bg-translucency, outside-region transparency. Plus programmatic order proof (`getPrimaryHorizontal` mirrored for RTL/LTR).
* **Touch gate (real system path)**: tap inside overlay Arabic text launched Shazam (focus-verified) → BACK → home intact; swipe through overlay text paged the launcher (uiautomator 32→35 nodes); focus never stolen (game stayed focused with overlay up; `canReceiveKeys=false`); all dispatched streams unconsumed. Live-match taps deliberately avoided (user session protection).
* **Lifecycle gate**: show/hide/update/clear/rapid-50×/repeats/refusal/empty, 0 crashes.
* **Perf (device)**: StaticLayout mixed-Arabic build p50 768 µs / p95 926 µs; 50× multi-region update mean 2.2 ms quiet / 9.9 ms loaded (environmental variance; event-driven only, no frame claim). Full numbers in `benchmarks.md` ENTRY 006.

### Fixed
* **Real alpha-composition bug** (pixel gate found it): `Paint.setAlpha()` clobbered translucent text/background colors to opaque — now composes (`colorAlpha × styleAlpha`). Pinned permanently by pixel asserts.
* Test-convention fixes from device evidence (not weakenings): paragraph-direction constants (−1 RTL/+1 LTR), bare-`setMaxLines` needs ellipsis (contract documented), visible-ink (not raw line width) overflow check.

### Accepted device behaviors (documented, not bugs in our code)
* WindowState alpha 0.8 server-side while client requests 1.0 (asserted) — HONOR build policy on third-party overlays; readability unaffected.
* Test-run reinstall wipes the appops grant + post-run uninstall removes the app (grant around runs; pull artifacts mid-run); `/data/local/tmp` is EACCES for apps; secure keyguard denies `screencap` (in-process bitmap + pull procedure used).
* Live-game tap testing deferred to Phase 11 (session protection); launcher path is the equivalent system mechanism.

---

## [0.9.0] - 2026-10-04

### Added
* **Phase 5b — Spatial ROI Tracker + Temporal Stabilizer hardening (JVM-verified)**:
  - `RegionTracker`: greedy IoU (strictly > 0.6) bipartite matching with deterministic tie-breaks (older track, earlier detection), `track-N` identities rewritten into downstream region ids, grace-aged stale cleanup, creation-order output. Pure integer geometry.
  - `OcrResultStabilizer`: explicit `TrackState` machine (OBSERVING/STABLE/CHANGED/DISAPPEARED), full-window consistency evidence, fresh-window restart on beyond-tolerance change, within-tolerance drift without flicker, empty/below-confidence freeze, grace-counted disappearance with frozen windows, constructor-compatible defaults (legacy behavior preserved).
  - Phase-7 composition contract tested but NOT wired: detections → `update()` → `prune()` → `stabilize()` with `regionId = trackId`; tracker/stabilizer grace windows paired. `OcrPipelineCoordinator` and `MainActivity` untouched.
* **ADR-009** (matching rules, lifecycle, evidence, layer-pairing rule) + `architecture.md` §3B.

### Fixed
* Real greedy-matching crash found by the tie-break unit test (short-circuit set-add poisoned used-sets → NPE on ambiguous matches); fixed with check-then-add discipline. No test weakened.

### Tests
* `testDebugUnitTest --rerun-tasks`: **136/136 PASS** (was 96/96; +40: tracker 19, stabilizer +13 with all 12 legacy tests UNCHANGED, integration 7, benchmark 1).
* `assembleDebug` + `lintDebug`: **PASS**.
* `connectedDebugAndroidTest`: **NOT RE-RUN** (pure JVM logic; no Android APIs, no native code).

### Benchmarks
* Tracker+stabilizer frame cost, 12 synthetic regions (JVM, ENTRY 004): p50 16.7 µs / p95 92.8 µs — inside the 5 ms hot-path budget. Only transient is the O(tracks×detections) candidate list (pooling deferred to profiler evidence).

### Deferred
* Coordinator rewiring + translation-cache/overlay integration (Phase 7); ART hot-path numbers; appearance-based re-ID (out of scope).

---

## [0.8.0] - 2026-10-04

### Added
* **Phase 5a — Bounded LRU Translation Cache + Text Normalizer (JVM-verified)**:
  - `TextNormalizer`: deterministic OCR-output canonicalization (line-break folding, NFKC matching the model's own `nmt_nfkc`, Unicode-space/quote unification, control-char removal; idempotent). Case, punctuation, and protected spans pass through byte-identical by unit-tested contract. Raw text preserved in `TranslationResult.originalText`; normalized form is cache key + model input.
  - `LruTranslationCache`: capacity 500 (planned bound), access-ordered `LinkedHashMap` with deterministic eldest-eviction, O(1) expected ops, single-lock thread-safe, no hot-path allocation beyond map internals. Existing `TranslationCache` String→String interface unchanged (no parallel architecture).
  - `CachedTranslationEngine`: transparent `TranslationEngine` decorator (normalize → lookup → delegate-on-miss). Delegate receives normalized text; hits return caller bounds with `fromCache=true`.
* **ADR-008 admission policy** (`protectedTokensRestored=false` semantics): only warm-engine, verified-restored OR span-free results enter the cache. Flag=false span-bearing outputs (unmasked-retry) and cold-engine echoes are served but NEVER cached — no poisoning, later identical requests re-translate. Hits by construction carry no concern and report flag true.

### Tests
* `testDebugUnitTest`: **96/96 PASS** (was 60/60; +36: `TextNormalizerTest` 13, `LruTranslationCacheTest` 13 incl. 500-bound + concurrency invariants, `CachedTranslationEngineTest` 9 incl. flag-policy matrix, `TranslationCacheBenchmarkTest` 2).
* `assembleDebug` + `lintDebug`: **PASS**.
* `connectedDebugAndroidTest`: **NOT RE-RUN** (no engine/pipeline behavior changed; last 2/2 on VKJ-NX9 stands). ART cache numbers outstanding.

### Benchmarks
* Cache-hit latency through the real engine API (JVM dev machine, ENTRY 003): single-key p50 4.8 µs / p95 12.6 µs; 500-key cycling p50 4.1 µs / p95 5.3 µs — ~80–200× inside the < 1 ms target. No NMT-workload-reduction claim (needs Phase 7 integration).

### Deferred
* Pipeline wiring of the decorator (Phase 7); Spatial ROI Tracker; Temporal Stabilizer hardening; on-device cache numbers.

---

## [0.7.0] - 2026-10-04

### Fixed
* **ISSUE-004 RESOLVED — model-safe token protection (numeric sentinels)**:
  - Root cause proven against the real artifacts: `vocab.json` has no `VAR*` token, so `VAR0` segments as `▁V/AR/0` (three translatable subwords); reproduced mangling end-to-end on the real INT8 model (`في آربيلا`, dropped slots, `000 5`). Any new alphabetic placeholder would fail identically — so none was adopted on assumption.
  - `TokenProtector` now masks with space-padded 6-digit sentinels (base 987650, collision-avoided against source digit-runs), masks `80/100`-style ratios as one span, keeps `100%`-style spans atomic (regex `(?!\w)` boundary fix), and exposes `missingPlaceholders()` for verifiable restoration. Rejected with evidence: alternative placeholder strings, fragment (segment-and-rejoin) translation.
  - `OnnxTranslationEngine`: greedy loop extracted byte-identical to `runGreedyInference()`; `protectedTokensRestored` now honest (verbatim-sentinel check); single unmasked retry on sentinel loss; model-absent/exception fallbacks return ORIGINAL text (sentinels never leak to UI).
  - `DictionaryTranslationEngine`: honest restore flag.
* **Model validation (dev machine, real HF tokenizer + real INT8 ONNX, greedy, deterministic 3/3)**: sentinels copied verbatim in ~25/30 contexts (numbers, `%`, `%d/%s/%.2f`, `{playerName}`, `50 XP`/`100 HP`, `$100`/`50G`, tags, ratios, mixed-multi, repeated, reordered); pathological contexts catalogued (fail with AND without masking — model-quality holes for Phase 11). Full matrix in `research.md` §5C; strategy recorded as ADR-007.

### Tests
* `testDebugUnitTest`: **60/60 PASS** (was 50/50). `TokenProtectorTest` 6→16 regression tests; `TokenizerParityAuditTest` 16/16 (13 original + 3 sentinel shapes); fallback/no-leak assertions; device test asserts value round-trip + honest flag + no `98\d{4}` residue.
* `assembleDebug` + `lintDebug`: **PASS**.
* `connectedDebugAndroidTest`: **2/2 PASS on VKJ-NX9 (2026-10-04)** — sentinel path fully verified on-device: cold load 2909 ms, warm 38–83 ms (`50 HP` + `{playerName}` restored, flags honest), 0 crashes. Full numbers in `benchmarks.md` ENTRY 002. **ISSUE-004 CLOSED.**

### Changed
* Placeholder format is now a measured model-interface contract (any future change must re-run the §5C probe matrix, not just unit tests).
* `app-debug.apk` is 212,446,757 bytes (includes proof-bundled INT8 assets in `app/src/main/assets/`, unchanged by this fix).

---

## [0.6.2] - 2026-10-03

### Verified (on physical hardware)
* **Phase 4.1 CLOSED — first on-device NMT proof (VKJ-NX9, Snapdragon 7 Gen 4, Android 16)**:
  - `connectedDebugAndroidTest` 2/2 PASSED (`OnnxOnDeviceInferenceTest`).
  - Cold load 2441 ms (incl. first-install 232 MB asset copy), warm 34–47 ms per short UI string (10 ms immediate-EOS), PSS +241 MB for 3 sessions, 0 crashes.
  - Real on-device Arabic: `الصحافة "إي" لتفتح الباب`, `لديك "فارلو" باقي` (see ISSUE-004), `مرحباً بكم في العودة`, `العدو يقترب من الشمال` (identical to PyTorch), `(empty)` for Save Game.
  - Full numbers in `benchmarks.md` ENTRY 001.

### Fixed
* **3 on-device defects** (all found empirically, see `issues.md` ISSUE-005): heap (non-direct) `LongBuffer` → `IllegalArgumentException` + silent fallback; `ByteBuffer` overload creating INT8 instead of INT64 tensor; native use-after-free SIGSEGV from closing parent `OrtSession.Result` while retaining its encoder presents — fixed with strict single-ownership discipline.
* Hardened device test to assert Arabic-script output + no `VAR` residue, so fallback can never masquerade as success.
* Added missing `androidx.test:runner` dependency (instrumentation crashed with `ClassNotFoundException: AndroidJUnitRunner`); renamed backtick test names (D8 rejects spaces).

### Known issues opened
* ISSUE-004 (HIGH): model mangles `VARn` placeholders (`فارلو`); pure numbers are copied fine unmasked — token strategy must be rethought in Phase 5.
* +241 MB session PSS exceeds the < 150 MB budget — model-delivery/quantization strategy open for Phase 9/12.

---

## [0.6.1] - 2026-10-03

### Fixed
* **Phase 4.1 correctness audit — tokenizer parity proven (13/13)**:
  - `SentencePieceTokenizer` rewritten to the exact HF algorithm: source.spm unigram-score Viterbi + `vocab.json` ID remap + leading-`▁` + EOS + per-char `<unk>` fallback (previously greedy uniform-score DP, which produced wrong IDs).
  - `TokenizerParityAuditTest`: asserts Android IDs == HF IDs exactly on 13 inputs (5 required sentences + punctuation/numbers/apostrophes/placeholders/tags/single-char/empty/unknown-char).
* **Phase 4.1 correctness audit — 2 decoder bugs fixed**:
  - First generated token was dropped (`if (step > 0)` guard) — prior outputs were missing their first word.
  - Encoder past key/values were re-read from overwritten per-step outputs instead of being retained from step 0 (would corrupt/crash from the 3rd decoder step). Past-index mapping re-verified against measured tensor shapes.
  - Corrected quantized-ONNX outputs now match PyTorch on 2/4 non-empty sentences identically, near-identically on the rest.

### Verified
* `assembleDebug` + `testDebugUnitTest` + `lintDebug`: **PASS** (50/50 tests).
* Tokenizer parity: **VERIFIED 13/13**.
* Dev-machine inference parity: **VERIFIED** (corrected loop).
* On-device ONNX execution: **NOT TESTED** (no `adb` device connected).

---

## [0.6.0] - 2026-10-01

### Added
* **Phase 4.1 Real Translation Inference Proof (ONNX Runtime path)**:
  - `OnnxTranslationEngine`: Full 3-session greedy autoregressive decoder (encoder + decoder + decoder-with-past, INT8) behind the `TranslationEngine` contract, with pass-through fallback when model assets are absent. ONNX Runtime Mobile 1.17.0 integrated via version catalog.
  - `SentencePieceTokenizer`: Pure-Kotlin 62,802-token `vocab.json` parser (with `\uXXXX` unescaping), greedy segmentation, EOS handling — matching HuggingFace `MarianTokenizer` behavior (raw 32k `source.spm` proven insufficient).
  - `OnnxTranslationIntegrationTest` (4 tests): tokenizer encode/decode, game-string handling, engine fallback mode, engine metadata.
  - `scratch/onnx_opus_mt_en_ar*`: FP32 + INT8 ONNX exports (encoder 51.6 MB, decoder 90.6 MB, decoder-with-past 87.4 MB INT8) plus tokenizer artifacts (kept out of APK).

### Verified
* `assembleDebug`: **PASS** (BUILD SUCCESSFUL).
* `testDebugUnitTest --rerun-tasks`: **PASS** (48/48 tests passed across 11 suites).
* `lintDebug`: **PASS** (BUILD SUCCESSFUL).
* `app-debug.apk`: **VERIFIED** (59.91 MB / 59,908,594 bytes; +35 MB from ORT/LiteRT native libs).
* Real NMT inference on dev machine: **VERIFIED** (PyTorch + quantized ONNX, all 5 representative sentences, real Arabic output).
* On-device ONNX execution: **NOT TESTED** (no `adb` device connected).

### Fixed
* Decoder past-key/values index mapping (layer-major `dec_out[1 + i*4]` ordering, not type-grouped).
* `OrtSession.Result` API usage (`get(int)` returns `OnnxValue` directly; `get(String)` returns `Optional`).
* `vocab.json` `\u2581` escape handling (root cause of silent `<unk>` degradation in tokenizer).

---

## [0.5.0] - 2026-09-27

### Added
* **Phase 4 Modular Offline Translation Engine**:
  - `TranslationModelFeasibilityAudit`: Evaluated `Helsinki-NLP/opus-mt-en-ar` (MarianMT Seq2Seq Transformer, ~74.4M params, SentencePiece SPM tokenizer, CC-BY-4.0 license, neutral Modern Standard Arabic output).
  - `TokenProtector`: Single-pass non-overlapping regex pre/post-processing engine preserving game variables (`{player}`, `%d`, `%s`, `$100`, `<color>`, `#1`, `50XP`, `100HP`) and restoring them into translated Arabic output.
  - `DictionaryTranslationEngine`: Deterministic in-memory glossary translating standard video game UI terminology into neutral Modern Standard Arabic (MSA / Fusha), providing an instant (< 1 ms), 100% offline CPU fallback.
  - `LiteRtTranslationEngine`: Modular prototype skeleton for converted LiteRT Seq2Seq execution with separate encoder/decoder model loading off the hot path and CPU multi-threaded (4 threads) XNNPACK execution.
  - `TranslationCache` & `NoOpTranslationCache`: Clean caching abstraction decoupling engines from concrete cache storage.
  - `TranslationPipelineStats`: Latency instrumentation model recording preprocessing, tokenization, inference, detokenization, and total latency breakdown.
  - Unit test suites: `DictionaryTranslationEngineTest` (5 tests), `TokenProtectorTest` (6 tests), `TranslationBaselineTest` (2 tests), `Phase4ModelsTest` (3 tests). Total unit tests across project: 41.

### Verified
* `assembleDebug`: **PASS** (BUILD SUCCESSFUL in 32s).
* `testDebugUnitTest`: **PASS** (41/41 tests passed across 9 suites in 21s).
* `lintDebug`: **PASS** (completed without errors in 29s).
* `app-debug.apk`: **VERIFIED** (24.38 MB / 24,388,880 bytes).
* Physical device validation: **NOT TESTED** (`emulator-5554` visible; physical ARM64 device not connected).

---

## [0.4.0] - 2026-09-27

### Added
* **Phase 3 Change Detection & OCR Prototype**:
  - `FrameChangeDetector`: Level 1 cheap change detector operating directly on `Image` plane buffers on the `CaptureThread`. Uses 4× downscaled luminance block hashing with Hamming distance comparison. Zero full-resolution heap byte copies.
  - `NoOpOcrEngine`: Stub implementation of `OcrEngine` returning empty results, used for testing and baseline wiring without model dependencies.
  - `LiteRtOcrEngine`: Real LiteRT-backed `OcrEngine` skeleton using `org.tensorflow.lite.Interpreter`. Implements memory-mapped model loading off the frame hot path; documents Phase 9 inference integration points.
  - `OcrPipelineCoordinator`: Coroutine-based pipeline coordinator running on `Dispatchers.Default`. Two-level gating: only processes frames where `isChanged == true` and caps OCR invocations at 5 FPS. Emits `ocrResults` and `pipelineStats`.
  - `OcrResultStabilizer`: Minimal 3-frame Levenshtein-based ring-buffer per region ID to filter single-frame recognition jitter.
  - `OcrPipelineStats` & `ChangeDetectionResult`: Instrumentation models recording wall-clock latencies (change detection, text detection, OCR recognition) and skip reasons.
  - Updated `MainActivity` with `OcrPipelineCoordinator` lifecycle binding and diagnostic Flow observation.
  - Unit test suites: `OcrResultStabilizerTest` (12 tests), `Phase3ModelsTest` (7 tests), `NoOpOcrEngineTest` (3 tests). Total unit tests: 25.

### Verified
* `assembleDebug`: **PASS** (BUILD SUCCESSFUL in 27s).
* `testDebugUnitTest`: **PASS** (25/25 tests passed across 5 suites in 22s).
* `lintDebug`: **PASS** (completed without errors in 25s).
* `app-debug.apk`: **VERIFIED** (24.35 MB / 24,351,506 bytes).
* Physical device validation: **NOT TESTED** (`emulator-5554` visible; physical ARM64 device not connected).

---

## [0.3.1] - 2026-09-26

### Fixed
* **Phase 2 Code Review — Shutdown Race Condition (IMPORTANT)**:
  - Added `AtomicBoolean isCapturingActive` field to `ScreenCaptureService`.
  - `onImageAvailable` checks `isCapturingActive.get()` at entry and returns immediately if teardown has begun.
  - `stopCaptureInternal()` uses `compareAndSet(true, false)` to guarantee single execution even when called concurrently from `MediaProjection.Callback.onStop()` (main looper) and `ACTION_STOP` intent (binder thread).
  - `imageReader?.setOnImageAvailableListener(null, null)` is now called first in teardown to detach callbacks before releasing resources.
  - All other 11 lifecycle/memory/backpressure audit checks confirmed PASS; no further code changes required.

### Verified (post-fix)
* `assembleDebug`: **PASS** (BUILD SUCCESSFUL in 47s).
* `testDebugUnitTest`: **PASS** (3/3 tests passed in 50s).
* `lintDebug`: **PASS** (completed without errors in 31s).
* `app-debug.apk`: **VERIFIED** (24.25 MB on disk).

---

## [0.3.0] - 2026-09-25

### Added
* **Phase 2 Screen Capture Engine**:
  - Implemented `ScreenCaptureEngine` interface exposing `frameFlow: StateFlow<FrameBuffer?>` and `captureStatus: StateFlow<CaptureStatus>`.
  - Implemented `FrameRateLimiter` capping frame processing at a non-blocking 30 FPS target.
  - Implemented `ScreenCaptureService` using `MediaProjection` API, `VirtualDisplay`, and `ImageReader` operating on a dedicated `HandlerThread` ("CaptureThread").
  - Updated `MainActivity` with `MediaProjectionManager.createScreenCaptureIntent()` activity result launcher and state observers.
  - Created `FrameRateLimiterTest` unit test suite.

### Verified
* **Phase 2 Verification Gate**:
  - `assembleDebug`: **PASS** (BUILD SUCCESSFUL).
  - `testDebugUnitTest`: **PASS** (3/3 unit tests passed).
  - `lintDebug`: **PASS** (Static checks completed without errors).
  - `app-debug.apk`: **VERIFIED** (Size: 24.25 MB).
  - Physical device check via `adb devices`: 0 devices connected (Marked **NOT TESTED**).
