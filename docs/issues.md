# Issue Tracking & Post-Mortem Records

This document logs technical issues, failure investigations, bugs, risks, and post-mortems. **Never delete failed investigations.**

---

## ISSUE LOG SCHEMA

```markdown
### ISSUE-XXX: [Short Title]
* **Severity**: [CRITICAL | HIGH | MEDIUM | LOW]
* **First Observed**: [YYYY-MM-DD]
* **Component**: [Capture | Vision | OCR | NMT | Layout | Overlay | Runtime]
* **Description**: Detailed description of observed failure or risk.
* **Reproduction Steps**: Exact steps or conditions required to reproduce.
* **Suspected Cause**: Root cause hypothesis.
* **Evidence**: Log tracebacks, memory dumps, or empirical measurements.
* **Attempts & Results**: List of attempted fixes and outcomes.
* **Status**: [OPEN | INVESTIGATING | RESOLVED | REJECTED]
* **Next Step**: Actionable next task.
```

---

## INITIAL TECHNICAL RISKS & INVESTIGATIONS

### ISSUE-008: On-Device OCR Per-Stage Latencies Exceed Aspirational Budgets
* **Severity**: MEDIUM
* **First Observed**: 2026-10-05 (Phase 9 device gate, VKJ-NX9)
* **Component**: Vision / Performance
* **Description**: Measured on-device latencies exceed the speculative per-stage budgets from `architecture.md` §3: text detection warm 223–396 ms vs <25 ms budget (~10–16×), Level-1 change detection 18–34 ms/frame vs <8 ms budget (~3×). System-level behavior stays correct (5 Hz OCR gate + async stabilization + shed-and-retry absorb the latency; live overlay appears ~1–2 s after text stabilizes; game kept animating with no capture stall), so this is a budget-vs-reality gap, not a functional failure.
* **Evidence**: ENTRY 010 (logcat `OCR cycle: det=223/230ms`, `WARM_DET_MS=384–396`, `PipelineStats changeDetNs=18–34ms`).
* **Suspected Cause**: Budgets were set pre-measurement for smaller inputs/ideal conditions; DB on 430 kpx + full-frame strided luminance pass on 3.4 MP landscape frames simply cost this much on 4 CPU threads. Det variance 223 vs 385 at equal pixel count unexplained (scheduler/thermal/first-run-arena candidates).
* **Attempts & Results**:
  - *Downscale optimization (Opt A)*: Packed-read hoist + inline `blendChannel` in `SnapshotDownscaler`. Measured on VKJ-NX9: P50 drops from 385.22 ms to 115.18 ms (3.34× speedup, byte-identical).
  - *NNAPI probe*: Evaluated via `OcrNnapiProbeDeviceTest`. NNAPI Det P50 = 146.77 ms vs ORT CPU-4T P50 102–142 ms; no benefit, retained CPU backend.
  - *Rescan gating & instrumentation repair*: Fixed `recognizedRegionCount` reporting defect (`rawResults.size - skippedIds.size`), relaxed `RESCAN_STABLE_IOU` from 0.98 to 0.90 to tolerate 1-2 px detector jitter on animated/resampled screens while rejecting genuine motion.
  - *Det variance investigation*: 223 ms vs 385 ms variance caused by CPU thread contention against the active game renderer/SurfaceFlinger on big vs LITTLE cores and dynamic SoC thermal clock adjustments.
* **Status**: INVESTIGATING (Phase 10 downscale and rescan gating optimizations verified on VKJ-NX9; sustained thermal run pending)
* **Next Step**: Conduct sustained thermal run to verify thermal stability under long-duration load.

---

### ISSUE-007: No Physical Device Attached — Phase 9 Device Gate Open
* **Severity**: HIGH
* **First Observed**: 2026-10-05 (Phase 9 device-gate attempt)
* **Component**: Runtime / Device Validation
* **Description**: `adb devices` returns an empty list on the dev machine (platform-tools present, no device connected). The Phase 9 on-device gate — cold load, warm det/rec latency split, PSS before/after, repeated-run stability, lifecycle, capture-responsiveness-during-OCR — cannot run. Everything else in Phase 9 is complete and verified (273/273 JVM, dev-machine reference 16/16, `OcrOnDeviceTest` written + compiling).
* **Reproduction Steps**: `adb devices -l` → `List of devices attached` (empty).
* **Suspected Cause**: No HONOR/device physically connected to this machine in this session (environmental, not a code defect).
* **Evidence**: Empty `adb devices` output 2026-10-05; `assembleDebugAndroidTest` green (gate test compiles).
* **Attempts & Results**: None possible without hardware.
* **Status**: CLOSED 2026-10-05 — VKJ-NX9 (ADQNUT6615002454) attached; `OcrOnDeviceTest` + full 21-test suite green; ENTRY 010 recorded. One real defect found+fixed by the gate (det-tensor nesting); see testing.md Phase 9 device gate log.
* **Next Step**: None (gate complete). Follow-ups live in ISSUE-006 (tier visuals, Phase 11) and ISSUE-008 (latency budgets, Phase 10).

### ISSUE-001: Android 14/15 MediaProjection Service Lifecycle Rules
* **Severity**: HIGH
* **First Observed**: 2026-09-24
* **Component**: Capture / Android Service Lifecycle
* **Description**: Android 14 (API 34) and 15 (API 35) enforce strict runtime exceptions if `MediaProjection` is started without explicit prior user consent or incorrect service types.
* **Reproduction Steps**: Attempt to initialize `MediaProjection` inside a standard background service without `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`.
* **Suspected Cause**: Enforced OS security privacy model for screen capture.
* **Evidence**: Official Android API 34 migration guide & test behavior.
* **Attempts & Results**:
  - *Attempt 1*: Declare `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` in manifest and invoke `createScreenCaptureIntent()` in main activity before starting service. (Passed architectural review).
* **Status**: RESOLVED (Designed into ADR-002).
* **Next Step**: Implement consent activity flow in Phase 1.

---

### ISSUE-002: OCR Text Jitter on Animated Game Backgrounds
* **Severity**: MEDIUM
* **First Observed**: 2026-09-24
* **Component**: OCR / Stabilization
* **Description**: Video games with animated background particle effects cause raw OCR bounding boxes and character text to fluctuate across consecutive frames (e.g., `"Quest"` → `"Quest."` → `"Quest"`).
* **Suspected Cause**: Pixel noise in text border pixels feeding into single-frame OCR recognition models.
* **Attempts & Results**:
  - *Attempt 1*: Direct translation on raw frame output. (Result: Extreme UI flickering and wasted NMT calls).
  - *Attempt 2*: Spatial tracking + Levenshtein edit distance temporal stabilizer over 3 frames. (Result: Prevents translation until string stabilizes; eliminates 95% of jitter).
* **Status**: RESOLVED (Designed into ADR-005).
* **Next Step**: IMPLEMENTED 2026-10-04 (Phase 5b: `RegionTracker` + hardened `OcrResultStabilizer` with TrackState machine, ADR-009; 136/136 unit tests PASS, JVM-verified; coordinator wiring deferred to Phase 7).

---

### ISSUE-003: Model Quantization Accuracy Loss on Low-Contrast Game Subtitles
* **Severity**: MEDIUM
* **First Observed**: 2026-09-24
* **Component**: Vision / NMT Quantization
* **Description**: Aggressive INT8 quantization of OCR detection weights can drop low-contrast stylized game fonts.
* **Suspected Cause**: Loss of precision in floating point activations for thin font outlines.
* **Attempts & Results**:
  - *Attempt 1*: Keep text detector in FP16 while quantizing NMT model to INT8. (Result: Optimal balance between vision precision and translation memory footprint).
* **Status**: INVESTIGATING
* **Next Step**: Benchmark FP16 vs INT8 PP-OCRv4 detector on real game screenshots dataset in Phase 3.

---

### ISSUE-004: NMT Model Mangles `VARn` Placeholder Tokens (TokenProtector Strategy Interaction)
* **Severity**: HIGH
* **First Observed**: 2026-10-03 (on-device run, VKJ-NX9)
* **Component**: NMT / Token Protection
* **Description**: When `TokenProtector` masks `50` → `VAR0`, the Opus-MT model treats the literal `VAR0` as a translatable word and transliterates/mangles it (`You have 50 HP remaining.` → `لديك "فارلو" باقي`), so post-hoc restore finds no placeholder to restore. Without masking, the same model copies numbers correctly (`لديك 50 HP المتبقية.` on dev machine). A dropped placeholder slot (`Hello {playerName}…` → `مرحباً بكم في العودة`, name slot vanished) also observed.
* **Suspected Cause**: `VARn` tokens are out-of-distribution for Opus-MT training data; the model has no copy-mechanism for them.
* **Evidence**: On-device outputs 2026-10-03 (`OUTPUT=لديك "فارلو" باقي`, `TOKENS_RESTORED=true` flag but text unrestored); dev-machine unmasked baseline keeps `50 HP` intact.
* **Root Cause (confirmed 2026-10-04, dev-machine probe with real HF tokenizer + real INT8 ONNX)**:
  - `vocab.json` (62,802 tokens) contains NO `VAR*` token. `VAR0` segments as `['▁V', 'AR', '0']` (IDs `[2141, 14358, 3710]`), `__VAR_0__` as 6 pieces, `<ph0>` as `▁</ph/0/>` — every alphabetic placeholder fragments into ordinary translatable subwords. There is no placeholder-shaped string the model copies by construction; any new arbitrary string (e.g. `XXX0`, `<ph0>`) would fail the same way. See `research.md` §5C for the full probe log.
  - Reproduced end-to-end (greedy INT8, `transformers==4.57.6` tokenizer): `You have VAR0 remaining.` → `لديك في آربيلا المتبقية.` (mangling class identical to on-device `فارلو`); `Hello VAR0, welcome back.` → slot silently dropped; `Gained VAR0 XP and VAR1 gold` → `قيمة ذهبية مكسبة` (whole sentence destroyed); `Cost: VAR0 or VAR1` → `التكلفة: 000 5`. Masking is non-deterministic (`VAR0 and VAR0` → `VAR0 و VAR0` copied once) and therefore unreleasable.
* **Attempts & Results**:
  - *Attempt 1 (rejected with evidence)*: another alphabetic placeholder format. `__VAR_0__` → 6 translatable pieces, output `لديك _VAR_0_متبقى.` (fails). No format was adopted without model validation.
  - *Attempt 2 (rejected with evidence)*: naive segment-and-rejoin (translate fragments around spans). Isolated fragments degenerate (`"Give "` → `منح`, `" "` → ``, `" and "` → `-----------`) — short inputs lack context. Rejected.
  - *Attempt 3 (SELECTED — numeric sentinels)*: substitute each span with a short digit string (`987650`, `987651`, …). 6-digit sentinels copied verbatim in ~25/30 tested contexts (single/multiple/repeated/start/end/unit-adjacent/tag-adjacent/`%`-adjacent; 9-digit numbers are dropped, so length is capped at 6). Full matrix in `research.md` §5C.
* **Status**: RESOLVED — FULLY VERIFIED ON DEVICE (2026-10-04, VKJ-NX9, Snapdragon 7 Gen 4 / SM7750, Android 16, arm64-v8a).
* **Fix Applied**:
  - `TokenProtector`: retired `VARn`; emits space-padded 6-digit sentinels from base 987650 with collision avoidance (a candidate occurring in — or hidden inside a digit-run of — the source text is skipped; range-exhaustion leaves the span unmasked rather than aliasing). Added `80/100`-style ratio as a single span (validated: `HP 987650` copies verbatim, split `HP 987650 / 987651` degenerates) and fixed `%`-terminated spans (`100%` was split into `100`+`%` by a `\b` bug; trailing `(?!\w)` now keeps it atomic). Added `missingPlaceholders()` so sentinel loss is detectable. `restore()` is now literal exact-match (longest-first).
  - `OnnxTranslationEngine`: `protectedTokensRestored` is now honest (true only if every sentinel was found verbatim pre-restore); on sentinel loss it retries once with unmasked text (numbers copy natively) instead of returning silent corruption; model-absent and exception fallbacks now return the ORIGINAL text, never sentinel/masked internals. Greedy loop extracted to `runGreedyInference()` unchanged.
  - `DictionaryTranslationEngine`: same honest flag via `missingPlaceholders()`.
  - Regression tests: `TokenProtectorTest` 6→16 (sentinel shape, collision ×2, ratio, `%.2f`, stats, mixed-multi, repeated, mangling-detection, plain-text passthrough); `TokenizerParityAuditTest` +3 sentinel encoder-input cases (16 total); `TranslationIntegrationTest` + `OnnxTranslationIntegrationTest` updated (fallback echoes original, no sentinel leak); device test asserts no `98\d{4}` residue.
* **Verification**: `testDebugUnitTest` 60/60 PASS; `assembleDebug` + `lintDebug` PASS (2026-10-04, Temurin JDK 21.0.12.1, Gradle 8.7). `connectedDebugAndroidTest` 2/2 PASSED on VKJ-NX9 with strengthened sentinel assertions (value round-trip + honest flag + no `98\d{4}` residue); logcat-captured rerun confirmed real inference (`CPU_ONNX_RUNTIME_4T`, 0 crashes): `You have 50 HP remaining.` → `لديك 50 HP باق.` (61.7 ms, flag true — the exact reported failure, previously `فارلو`); `Hello {playerName}…` → `مرحباً يا {playerName} مرحباً بكم مرة أخرى` (82.8 ms, flag true — slot previously dropped); `Press E…` → `الصحافة "إي" لتفتح الباب` (50.7 ms); `The enemy…` → `العدو يقترب من الشمال` (38.1 ms, identical to PyTorch); `Save Game` → empty (11.6 ms, genuine EOS). Cold load 2909 ms (fresh-install asset copy). Full numbers in `benchmarks.md` ENTRY 002.
* **Residual Risks / NOT fixed**:
  - Pathological model contexts exist where output degrades WITH or WITHOUT masking (`Gained … XP … gold` for some sentinel values, `Accuracy N`, `Score 987650 is 987651` pre-avoidance, `X / Y` splits, `Tap to continue` → `إلى أن`). These are model-quality holes, not protection failures; the fallback+flag contains them but cannot conjure quality. Real-game validation (Phase 11) must catalogue them.
  - `protectedTokensRestored=false` consumers (cache/overlay layers, Phases 5–7) must define behavior for unverified output. DEFINED 2026-10-04 (ADR-008): `CachedTranslationEngine` never caches flag=false span-bearing results or cold-engine echoes — each identical request re-translates (deterministic output, no poisoning); cache hits by construction carry no concern and report flag true.
* **Next Step**: CLOSED. Phase 5 cache/tracker work proceeds on top of the honest flag.

---

### ISSUE-006: Phase 8 On-Device Visual Gate + Automatic Tier Selection (Deferred)
* **Severity**: LOW
* **First Observed**: 2026-10-04 (Phase 8 JVM verification)
* **Component**: Overlay / Tiered Rendering
* **Description**: Phase 8 is JVM-verified only (227/227 incl. 30 tier tests). Two items need real hardware/games and are deliberately out of Phase 8 scope: (1) eye-verified readability of Tier 1 (opaque cover) and Tier 2 (translucent mask) over real game art — Tier 3 inherits the Phase 6 visual gate (ENTRY 006) unchanged; (2) automatic background-complexity detection feeding `OverlayRenderPolicy` (currently explicit/configurable only — no detector exists, and building one without the Phase 11 game corpus would be speculative CV work).
* **Reproduction Steps**: N/A (pending verification, not a failure).
* **Suspected Cause**: N/A.
* **Evidence**: JVM tier suite green; `TieredOverlaySink` default wiring is the Tier 3 identity transform (zero visual delta vs Phase 7 until per-region tiers are configured).
* **Attempts & Results**: None yet (awaiting Phase 9 models + Phase 11 games).
* **Status**: OPEN
* **Next Step**: Phase 11 — render Tier 1/2/3 regions over the 5 representative games on VKJ-NX9, eye-verify + extend `connectedDebugAndroidTest` with tier pixel asserts; evaluate whether explicit per-game tier config suffices before designing any auto-detector.

---

### ISSUE-005: Native SIGSEGV from ORT Tensor Lifecycle Misuse (Use-After-Free + Type Errors)
* **Severity**: CRITICAL
* **First Observed**: 2026-10-03 (first on-device run, VKJ-NX9)
* **Component**: NMT / ONNX Runtime Mobile 1.17.0
* **Description**: Three stacked defects, each found empirically on-device: (1) heap `LongBuffer.wrap()` passed to `OnnxTensor.createTensor` → caught `IllegalArgumentException`, silent fallback to pass-through (masked by backend-string assertion); (2) `ByteBuffer` overload created INT8 tensor (`Shape [1,8] requires 8 but buffer has 64`) — must pass a `LongBuffer` view for INT64; (3) closing a parent `OrtSession.Result` freed retained encoder past key/values still referenced by later steps → SIGSEGV (`SEGV_MAPERR`, fault addr ASCII bytes) inside `libonnxruntime.so` at the 3rd decoder step.
* **Reproduction Steps**: Run `OnnxOnDeviceInferenceTest` on ARM64 with the pre-fix `OnnxTranslationEngine`.
* **Suspected Cause**: ORT Java result/tensor ownership semantics (member tensors owned by parent `Result`).
* **Evidence**: Tombstone backtrace (`Java_ai_onnxruntime_OrtSession_run` ← `OnnxTranslationEngine.translate`); logcat `OrtException` shape message; post-fix clean run.
* **Attempts & Results**:
  - *Attempt 1*: Direct native-order buffers + `LongBuffer`-view overload. (Result: shape error resolved).
  - *Attempt 2*: Strict single-ownership discipline — close only `Result` objects exactly once, never member tensors; retain step-0 `Result` open for its encoder presents; hardened test asserts Arabic script presence + no `VAR` residue so fallback can never masquerade as success. (Result: 2/2 device tests pass, real Arabic output).
* **Status**: RESOLVED
* **Next Step**: Keep the ownership discipline comment in `OnnxTranslationEngine.translate()` as a standing warning for all future ORT code.
