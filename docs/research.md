# Technical Research & Verification Audit

This document records technical research, API investigations, model evaluations, runtime benchmarks, license compliance audits, and explicit claim verification classifications.

---

## 1. CLAIM CLASSIFICATION SCHEMA

Every concrete technical claim in this project is strictly classified into one of 5 categories:

1. **VERIFIED**: Supported by authoritative official documentation, public specs, or verified local artifacts.
2. **HYPOTHESIS**: Technically plausible based on architecture, but not yet empirically proven.
3. **BENCHMARK REQUIRED**: Cannot be established without testing on physical ARM64 hardware.
4. **DECISION**: An explicit architectural design choice with documented rationale.
5. **REJECTED/UNCERTAIN**: Insufficient or conflicting evidence; discarded or under investigation.

---

## 2. ANDROID MEDIAPROJECTION & OVERLAY DESIGN

| Technical Claim / Feature | Classification | Notes & Validation Plan |
| :--- | :--- | :--- |
| Android 14 (API 34) requires `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` in manifest and `startForeground()` call. | **VERIFIED** | Official Android 14 Security & Foreground Service Documentation. |
| User consent via `createScreenCaptureIntent()` must be obtained *before* `startForeground()`. | **VERIFIED** | Android API 34 runtime enforcement rules (causes `SecurityException` if violated). |
| `MediaProjection` token cannot be cached permanently across app restarts. | **VERIFIED** | Android MediaProjection API policy. Fresh consent required per session launch. |
| Android 15 prohibits launching `mediaProjection` service from `BOOT_COMPLETED` receivers. | **VERIFIED** | Android 15 behavioral changes documentation. |
| System Overlay (`TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCHABLE \| FLAG_NOT_FOCUSABLE`) as non-interactive overlay design. | **HYPOTHESIS / VALIDATION REQUIRED** | Intended overlay design. Validation across supported Android versions and diverse real games is required to verify zero touch interception in all UI/fullscreen cases. |

---

## 3. ON-DEVICE MACHINE LEARNING RUNTIMES & ACCELERATION DELEGATES

| Technical Claim / Delegate | Classification | Notes & Evidence |
| :--- | :--- | :--- |
| **LiteRT (TensorFlow Lite 2.x / 1.0.1)** as primary Android edge ML runtime. | **VERIFIED** | Google AI Edge / LiteRT official documentation. Verified package: `org.tensorflow.lite.Interpreter`. |
| **ONNX Runtime Mobile** as cross-platform execution provider. | **VERIFIED** | ONNX Runtime official documentation & Qualcomm AI Hub integration. |
| LiteRT OpenCL/Vulkan **GPU Delegate** as acceleration candidate. | **CANDIDATE / BENCHMARK REQUIRED** | Evaluated acceleration candidate. Production backend must be selected after benchmarking on target hardware. |
| Qualcomm **AI Engine Direct (QNN) Delegate** as acceleration candidate. | **CANDIDATE / BENCHMARK REQUIRED** | Evaluated NPU candidate for Snapdragon platforms. Requires empirical benchmarking. |
| Multi-threaded CPU fallback (XNNPACK) as baseline execution provider. | **DECISION** | Architectural baseline fallback to guarantee operation on any chip. |

---

## 4. OCR MODEL EVALUATION & FACT CHECK

| Claimed Metric / Feature | Classification | Corrected Reality / Verification Evidence |
| :--- | :--- | :--- |
| **PP-OCRv4 Mobile Detection ONNX Size** | **VERIFIED** | ~4.6 MB (FP32/FP16 ONNX). Quantized INT8 is ~1.2–2.5 MB. |
| **PP-OCRv4 Mobile Recognition ONNX Size** | **VERIFIED** | ~10.4 MB (FP32 ONNX). Quantized INT8 is ~3.0–5.0 MB. |
| **PP-OCRv5 Mobile Recognition ONNX Size** | **VERIFIED** | ~15.8 MB (FP32 ONNX). |
| **Claim: Total PP-OCR Mobile Model is 4.5 MB** | **REJECTED / CORRECTED** | **Correction**: 4.5 MB is ONLY the detector module. Combined PP-OCRv4 Mobile (Det + Rec) is **~15 MB FP32** unquantized, or **~5–8 MB INT8** quantized. |
| **PP-OCR English Character Accuracy > 90%** | **HYPOTHESIS** | High on standard benchmarks; in-game stylized text accuracy requires empirical testing. |
| **PP-OCR Latency < 50ms total** | **BENCHMARK REQUIRED** | Must be measured on target device under varying ROI crop sizes. |

---

## 5. TRANSLATION MODEL FEASIBILITY & SPECIFICATION AUDIT (PHASE 4)

| Feature / Metric | Candidate Ground Truth | Verification Classification |
| :--- | :--- | :--- |
| **Primary Model Candidate** | `Helsinki-NLP/opus-mt-en-ar` (MarianMT Seq2Seq Transformer) | **VERIFIED** |
| **Architecture** | 6 Encoder Layers, 6 Decoder Layers, 8 Attention Heads, Dim 512 (~74.4M-77M parameters) | **VERIFIED** |
| **Source Model Format** | PyTorch / HuggingFace Transformers (`model.safetensors` / `pytorch_model.bin`) | **VERIFIED** |
| **Source FP32 Model Size** | **~298 MB** on disk | **VERIFIED** |
| **Target Quantized INT8 Size** | Estimated **~40–75 MB** (Encoder ~20MB, Decoder ~40MB) | **HYPOTHESIS / BENCHMARK REQUIRED** |
| **Native TFLite Availability** | None provided by Helsinki-NLP; requires PyTorch → ONNX → LiteRT conversion | **VERIFIED** |
| **Tokenizer Specification** | SentencePiece (`source.spm` ~806 KB, `target.spm` ~917 KB, vocab ≈ 60,000 subwords) | **VERIFIED** |
| **Target Arabic Dialect/Style** | Modern Standard Arabic (MSA / Fusha) — neutral, formal, ideal for video games (not dialectal) | **VERIFIED** |
| **Cold-Load Time (INT8)** | Target < 1.0s on modern ARM64 SoC | **BENCHMARK REQUIRED** |
| **Warm Inference Latency (INT8)** | Target 60–100ms for short UI strings (num_beams=1 greedy search) | **BENCHMARK REQUIRED** |
| **Peak RAM Footprint** | Target < 150 MB (INT8 direct memory mapping) | **BENCHMARK REQUIRED** |
| **License** | CC-BY-4.0 (Commercial use and redistribution permitted with attribution) | **VERIFIED** |
| **Deterministic Fallback Engine** | `DictionaryTranslationEngine` (Neutral MSA game UI glossary, < 1ms latency) | **VERIFIED (Implemented & Tested)** |

---

## 5B. PHASE 4.1 — REAL INFERENCE PROOF (ONNX RUNTIME PATH)

Phase 4.1 answered: "Can a real English→Arabic NMT model run offline, and what does it cost?" Runtime decision: **ONNX Runtime** (not LiteRT) — the Opus-MT export produces 3 standard ONNX graphs (encoder, decoder, decoder-with-past) that load in ONNX Runtime Mobile 1.17.0; LiteRT conversion was not pursued further once the ONNX path proved viable.

| Feature / Metric | Measured Ground Truth | Verification Classification |
| :--- | :--- | :--- |
| **Exact model artifact** | `Helsinki-NLP/opus-mt-en-ar`, `transformers==4.57.6` export via `optimum[onnxruntime]` | **VERIFIED** |
| **ONNX FP32 sizes on disk** | Encoder 205.4 MB, Decoder 359.6 MB, Decoder-with-past 347.0 MB | **VERIFIED** |
| **ONNX INT8 (QOperator, QUInt8 act / QInt8 weights) sizes** | Encoder **51,610,655 B (49.2 MB)**, Decoder **90,614,300 B (86.4 MB)**, Decoder-with-past **87,402,693 B (83.4 MB)** — total **~229.6 MB** | **VERIFIED** |
| **Tokenizer artifact** | `vocab.json` **2,306,384 B (2.2 MB)**, 62,802-token shared vocab; `source.spm` 801,074 B; `target.spm` 916,890 B | **VERIFIED** |
| **Critical tokenizer finding** | HF `MarianTokenizer` uses the **62,802-token shared vocab** and appends EOS(0); raw `source.spm` alone is only 32,000 tokens and produces **different token IDs** (e.g. HF `[14951, 616, 9, 1555, 3, 4127, 2, 0]` vs raw-SP `[6237, 425, 8, 764, 3, 1784, 4]` for the same sentence). Android tokenizer MUST use `vocab.json` + EOS handling, not raw SPM. | **VERIFIED** |
| **Tokenizer algorithm (audited)** | HuggingFace segmentation = SentencePiece **unigram Viterbi** over `source.spm` pieces+scores (`nmt_nfkc` normalization, whitespace collapsing, per-char `<unk>` fallback); IDs remapped through `vocab.json` (31,993 of 32,000 spm-internal IDs differ from model IDs). Android `SentencePieceTokenizer` re-implements exactly this: spm protobuf scores + vocab.json ID map + leading-`▁` + EOS(0), no BOS. | **VERIFIED** |
| **Tokenizer parity (13/13 exact)** | Android IDs == HF IDs on all 5 required sentences plus apostrophes, numbers, placeholders, tags, single-char, empty, and unknown-char (`a\|b` → `[15, 1, 340, 0]`) inputs. `TokenizerParityAuditTest` asserts exact ID equality. | **VERIFIED** |
| **`vocab.json` encoding trap** | `vocab.json` stores `▁` as `\u2581` escapes; a naive string parser keeps literal backslash sequences and tokenization silently degrades to `<unk>`. Android parser must unescape `\uXXXX`. | **VERIFIED** |
| **Real PyTorch inference (dev machine, greedy)** | `Press E…` → `الصحافة "إي" لفتح الباب` (255 ms); `You have 50 HP…` → `لديك 50 HP المتبقية.` (165 ms); `Hello {playerName}…` → `مرحباً بعودتكم` (119 ms); `The enemy…` → `العدو يقترب من الشمال` (123 ms); `Save Game` → `` (empty, 34 ms) | **VERIFIED** |
| **Real quantized-ONNX inference (dev machine x86_64 CPU, ORT 1.30, greedy, HF tokenizer, CORRECTED decoder loop)** | `Press E…` → `الصحافة "إي" تفتح الباب` (57 ms, 6 content tokens); `You have 50 HP…` → `لديك 50 HP المتبقية.` (130 ms, 6 tokens — identical to PyTorch); `Hello {playerName}…` → `مرحباً بكم مرة أخرى` (92 ms, 5 tokens); `The enemy…` → `العدو يقترب من الشمال` (39 ms, 4 tokens — identical to PyTorch); `Save Game` → `` (empty, 9 ms, 0 tokens) | **VERIFIED** |
| **Decoder audit fixes (correctness)** | Audit found and fixed 2 decoder bugs: (1) first generated token was dropped (`if (step > 0)` guard — prior outputs were missing their first word); (2) encoder past key/values were re-read from overwritten per-step outputs instead of being retained from step 0 (would crash/corrupt from the 3rd decoder step). Past-index mapping `dec_out[1 + i*4]` (no-past) / `dec_out[1 + i*2]` (with-past) re-verified against measured tensor shapes. | **VERIFIED (dev-machine measurement)** |
| **`Save Game` → empty output** | Genuine model behavior (immediate EOS), reproduced identically on PyTorch and quantized ONNX. NOT an export bug. Mitigation belongs to product layer (dictionary fallback covers `Save Game`). | **VERIFIED** |
| **INT8 quality note** | Quantized ONNX output is reasonable Arabic but slightly degraded vs PyTorch FP32 on some inputs (e.g. dangling diacritic in `ً بكم مرة أخرى` vs `مرحباً بعودتكم`). | **VERIFIED** |
| **Android `OnnxTranslationEngine`** | Full 3-session greedy decoder behind `TranslationEngine`. **Executed on physical ARM64 (VKJ-NX9, Snapdragon 7 Gen 4): cold load 2441 ms, warm 34–47 ms, +241 MB PSS, real Arabic, 0 crashes** (benchmarks.md ENTRY 001). On-device debugging fixed 3 defects: heap (non-direct) buffers, INT8-vs-INT64 tensor overload, native use-after-free via parent-`Result` close (issues.md ISSUE-005). | **VERIFIED ON DEVICE** |
| **Android cold-load / warm latency / peak RAM** | VKJ-NX9 measured: cold 2441 ms (incl. first-install asset copy), warm 34–47 ms, PSS +241 MB — see benchmarks.md ENTRY 001. | **VERIFIED (physical device)** |
| **APK size impact** | `app-debug.apk` grew 24.38 MB → **59.91 MB** from ONNX Runtime + LiteRT native libs (no models bundled). | **VERIFIED** |

---

## 5C. PHASE 5 — ISSUE-004 PROBE: MODEL-SAFE PLACEHOLDER STRATEGY (DEV-MACHINE, REAL MODEL)

Method (2026-10-04, fully offline): HF `MarianTokenizer` (`transformers==4.57.6`,
`local_files_only`, from `scratch/onnx_opus_mt_en_ar_quant`) + INT8 ONNX prose
(`encoder_model_quantized` / `decoder_model_quantized` /
`decoder_with_past_model_quantized`, ORT 1.30 CPU, greedy `num_beams=1`,
`decoder_start=62801`, EOS `0`, `MAX_STEPS=64`, argmax over first 32,000 logits —
the same loop as `OnnxTranslationEngine`). Determinism confirmed (3/3 identical
outputs on repeat runs). All outputs below are measured, not hypothesized.

### Finding 1 — root cause: no alphabetic placeholder can survive (tokenizer level)

`vocab.json` contains zero `VAR*` tokens. Measured HF segmentation:

| Input | Token pieces | Verdict |
| :--- | :--- | :--- |
| `VAR0` | `▁V / AR / 0` (`[2141, 14358, 3710]`) | 3 translatable subwords — must be translated |
| `VAR1` / `VAR` | `▁V / AR / 1`, `▁V / AR` | same |
| `__VAR_0__` | `▁__ / V / AR / _ / 0 / __` (6 pcs) | worse |
| `<ph0>` | `▁< / ph / 0 / >` | fragments |
| `XXX0` | `▁ / XXX / 0` | fragments |

For contrast, model-native copyable shapes are atomic: `▁50` (`1204`), `▁100%`
(`39083`), `▁$100` (`32641`). Conclusion: placeholder safety cannot be assumed for
ANY alphabetic string — it must piggyback on the number-copy behavior.

### Finding 2 — VAR0 failure reproduced end-to-end (real INT8 model)

| Input (masked) | Output | Placeholders restorable? |
| :--- | :--- | :--- |
| `You have VAR0 remaining.` | `لديك في آربيلا المتبقية.` | NO (same mangling class as on-device `فارلو`) |
| `Hello VAR0, welcome back.` | `مرحباً بكم مرة أخرى.` | NO (slot silently dropped) |
| `Gained VAR0 XP and VAR1 gold` | `قيمة ذهبية مكسبة` | NO (whole sentence destroyed) |
| `Cost: VAR0 or VAR1` | `التكلفة: 000 5` | NO |
| `Player VAR0 has 100% accuracy!` | letter-salad transliteration | NO |
| `Give VAR0 VAR1 and VAR2 potions` | `أعطِ VAR00 0 VAR1 و VAR2` | PARTIAL (corrupted copies) |
| `VAR0 and VAR0` | `VAR0 و VAR0` | YES (once — non-deterministic, unreleasable) |
| `You have __VAR_0__ remaining.` | `لديك _VAR_0_متبقى.` | NO |

Unmasked controls: `50 HP` copies intact; `{playerName}` dropped; `<b>` tags →
`<B>WArmann! <B>`; `%d/%s` → `النسبة المئوية…` ("the percentage…"); `$` dropped from
`$100`; `#99` dropped. So fragile spans DO need masking — with a shape the model copies.

### Finding 3 — rejected: fragment (segment-and-rejoin) translation

Translating isolated fragments degenerates for lack of context: `"You have "` →
`لديك`, `" remaining."` → `ما تبقى منه.` (usable) BUT `"Give "` → `منح`, `" "` → ``,
`" and "` → `-----------`, `"Hello "` → `& مرحبا`. Rejected: tiny fragments are
outside the model's usable input distribution.

### Finding 4 — selected: 6-digit numeric sentinels (validated matrix)

Space-padded 6-digit sentinels (`987650`+), tokenizing as `▁98 / 76 / 5x`:

| Masked input | Output | All sentinels verbatim? |
| :--- | :--- | :--- |
| `You have 987654 remaining.` | `لديك 987654 باقٍ` | YES |
| `Hello 987654, welcome back.` | `مرحباً يا 987654 مرحباً بكم مرة أخرى` | YES |
| `Give 987651 987652 and 987653 potions` | `يعطي 987651 987652 و987653 ف` | YES (3 distinct) |
| `Cost: 987651 or 987652` | `التكلفة: 987651 أو 987652` | YES |
| `Gained 987651 XP and 987652 gold` | `مكتسبة 987651 XP و 987652` | YES |
| `Gained 987652 XP and 987653 gold` | `المكسـبـين 987652 XP و 987653` | YES |
| `Player 987651 has 100% accuracy!` | `الـ (بـلايـر) 987651 لديهـم دقيقـة 100%` | YES |
| `999 and 999` | `999 و999` | YES (repeated) |
| `987650 and 987650` | `987650 و 987650` | YES (repeated) |
| `Press 987651 to jump` | `مطبعة 987651 للقفز` | YES |
| `You have 1001 HP and 1002 MP.` | `لديك 1001 HP و1002 MP.` | YES |
| `You received 987650 gold!` | `لقد تلقيت 987650 ذهباً` | YES |
| `Level 987650 unlocked` | `المستوى 987650` | YES |
| `Bring 987650 987651 to the elder` | `أحضروا الى الشيخ 987650 987651` | YES (reordered, map restores) |
| `987650 Warning! 987651` (tags) | `987650 إنذار! 987651` | YES |
| `Progress 987650 complete` (`%.2f%%`) | `التقدم المحرز 987650` | YES |
| `HP 987650` (ratio as ONE span) | `HP 987650` | YES (verbatim) |
| `Ammo 987650` | `Ammo 987650` | YES (verbatim) |
| `Score 987650 is 987652` (avoided collision) | `النتيجة 987650 = 987652` | YES |
| `Hello 123456789, welcome back.` | `مرحباً، مرحباً بكم من جديد` | NO — 9-digit numbers dropped (length cap = 6) |

Known pathological contexts (fail masked AND unmasked — model-quality holes, NOT
protection failures; contained by the fallback + honest flag): `Gained 987650 XP…`
(value-sensitive trajectory flip), `Quest 987650 complete: earn 987651` (2nd sentinel
split into `50ر 98`), `Accuracy 987650` (immediate EOS) / `Accuracy 100%` (`نسبة 100
100 100`), `HP 987650 / 987651` (slash-split `1 - 1 - …` loop), `Tap to continue` →
`إلى أن`. Catalogued for Phase 11 real-game validation.

### Implication for the implementation (ADR-007)

Mask every detected span with a space-padded 6-digit sentinel (base 987650, bumped on
collision with source digit-runs); mask `80/100`-style ratios as ONE span; keep
`100%`-style spans atomic (regex `(?!\w)` fix); verify with `missingPlaceholders()`
and retry unmasked once on loss; never emit sentinels to the UI layer. The Android
tokenizer is untouched (parity extended to 3 sentinel shapes: `You have 987654
remaining.` → `[92, 63, 7844, 5687, 6160, 2, 0]`, etc. — see
`TokenizerParityAuditTest`), and the greedy ONNX loop is byte-identical, only
refactored into `runGreedyInference()`.

---

## 5C. PHASE 9 — OCR MODEL SELECTION + DEV-MACHINE REFERENCE PROOF

Phase 9 answered: "Which real OCR models feed the snapshot path, on which
runtime, and do they read game-like English?" Runtime decision: **ONNX
Runtime (CPU)** — the same `onnxruntime-android 1.17.0` already proven by the
NMT path. LiteRT was NOT pursued for OCR: no pre-built PP-OCR `.tflite`
exists (same finding as Phase 4 for NMT), and converting DB/CTC graphs
risks Flex-op delegate gaps with zero accuracy benefit. The `LiteRtOcrEngine`
skeleton stays untouched as the fallback seam.

### Selected artifacts (all SHA-pinned, dev-machine verified)

| Artifact | Bytes on disk | SHA256 (pinned) | Source |
| :--- | :--- | :--- | :--- |
| `en_PP-OCRv3_det_infer.onnx` (DB detector, FP32) | 2,421,707 | `ea07c15d…9ed39c` | RapidAI/RapidOCR ModelScope `v2.1.0/onnx/PP-OCRv4/det/` |
| `en_PP-OCRv4_rec_mobile.onnx` (CTC recognizer, FP32) | 7,653,044 | `e8770c96…abd3e0e318` | RapidAI/RapidOCR ModelScope `v3.9.2/onnx/PP-OCRv4/rec/` |
| `en_dict.txt` (95 entries, space-terminated) | 190 | content-pinned (space at index 94) | RapidAI/RapidOCR ModelScope `v2.1.0/paddle/…/en_dict.txt` |

SHA lesson (method, not a model defect): the published YAML hash for the det
file is 64 hex chars; a truncated 63-char copy compared unequal while
`onnx.checker` passed and bytes were later proven identical to the verified
download. Pin full hashes; treat checker-pass + mismatch as "re-download and
compare", never as "ship it".

### Verified model interface (inspected with `onnx`, NOT assumed)

| Property | Detector | Recognizer |
| :--- | :--- | :--- |
| Opset / IR | 14 / 7 | 14 / 7 |
| Input | `[N,3,H,W]` float32, fully dynamic | `[N,3,48,W]` float32, dynamic W (single-region width routinely exceeds the 320 training width, e.g. 330 — works) |
| Output | single sigmoid map `[N,1,h,w]` at FULL input resolution (no /4 stride — the export upsamples) | batch-major CTC logits `[B,T,97]` (softmaxed) |
| Ops | Conv/BN/HardSwish/Resize/Sigmoid only — CPU-safe, no control flow | +MatMul/ReduceMean/Slice — CPU-safe |
| Dict math | n/a | 95 file lines + `blank` prefix + appended ` ` suffix = **97 classes = output width** (the file's own trailing space + the decoder's appended space both required) |

### Reference configuration (dev-machine proof, `rapidocr_onnxruntime`)

Detector: `limit_side_len=960, limit_type=max` (PaddleOCR mobile-det inference
default — NOT rapidocr's packaged 736/min doc-scan default), `thresh=0.3`,
`box_thresh=0.5`, `max_candidates=1000`, `unclip_ratio=1.6`, 2×2 dilation,
fast polygon-mean score, clockwise-order + clip + 3px filter. Recognizer:
`rec_image_shape=[3,48,320]`, batch 6, `en_dict`, no angle-cls
(`use_angle_cls=false` — game text is upright; tall-crop rot90 rule kept),
`text_score=0.5` gate. Classifier skipped entirely (no cls model bundled).

### Corpus result (dev machine, ORT 1.30 CPU, 8 game-like images)

16/16 text lines recognized EXACTLY (`Press E to open the door.`,
`You have 50 HP remaining.`, `100%`, `50 HP`, `Save Game`, `New Game`,
`Inventory`, `Press Enter`, `The enemy is approaching.`, multiline quest
3/3, `GO`, light-panel 2/2), blank image → 0 boxes. Full matrix:
`models/ocr/reference_run.json`. Dev-machine latencies (x64 CPU,
INFORMATIONAL — not budget claims, ART will differ): det cold 75–131 ms /
warm 73–90 ms; rec 10 ms (1 region) … 232 ms (4 regions, ≈58 ms/region).

### Kotlin parity goldens (`app/src/test/resources/ocr/`)

`det_probmap_f32.raw` (real 960×448 map) + meta → decoder must yield 3 boxes
at IoU ≥ 0.8 vs reference (measured 0.867/0.949/0.953 — residual is the
documented AABB-vs-rotated-quad delta); `rec_logits_f32.raw` (real
`[1,41,97]`) → exact `Press E to open` + score 0.9371 ± 1e-3 (arbitrates the
`(sum+eps)/(n+1)` confidence formula); `resize_src/dst_u8.raw` (cv2
INTER_LINEAR pair) → Kotlin bilinear within max-diff ≤ 3 (edge-rounding only).

### Rejected for Phase 9 (with reason)

* INT8-quantized PP-OCR (no pinned RapidOCR INT8 EN artifacts found; FP32
  10 MB total is already small — quantization is Phase 10 work with profiler
  evidence, not an assumption).
* PP-OCRv5 (larger rec ≈ 15.8 MB, no measured accuracy need on game UI).
* `ch_*` models (Chinese-vocabulary CTC tables bloat classes and risk
  Latin confusions; EN-only tables match the English-only scope).
* Angle classifier (upright-text scope; rot90 rule retained for tall crops).
* GPU/NNAPI/QNN delegates for OCR (unevaluated without a device; CPU is the
  retained fallback AND the current production path — §4 accelerator
  evaluation stays open).

---

## 6. SYSTEM PERFORMANCE & THERMAL ASSUMPTIONS

| Claimed Metric / Feature | Classification | Status & Next Steps |
| :--- | :--- | :--- |
| **End-to-End Latency Target (< 180ms)** | **BENCHMARK REQUIRED** | Performance target budget. Must be measured from MediaProjection frame acquire to overlay draw. |
| **Peak Memory Target (< 200 MB)** | **BENCHMARK REQUIRED** | Performance target budget. Must be verified using Android Profiler on physical device. |
| **30-Minute Thermal Target ($\Delta T < 4^\circ\text{C}$)** | **BENCHMARK REQUIRED** | Performance target budget; not a proven pass/fail requirement until baseline measurements are established. |
| **Multi-rate pipeline prevents game FPS drop** | **DECISION** | Architectural design rule to decouple capture (30 FPS) from OCR/NMT inference. |

---

## 7. LICENSE COMPLIANCE AUDIT

| Asset / Library | License | Commercial Use | Weight Redistribution | Classification |
| :--- | :--- | :--- | :--- | :--- |
| **LiteRT / TensorFlow** | Apache 2.0 | Allowed | Allowed | **VERIFIED** |
| **PaddleOCR (PP-OCRv4)** | Apache 2.0 | Allowed | Allowed | **VERIFIED** |
| **RapidOCR ONNX exports (PP-OCR EN det/rec)** | Apache 2.0 (repo LICENSE verified 2026-10-05) | Allowed | Allowed | **VERIFIED** |
| **Opus-MT (Helsinki-NLP)** | CC-BY 4.0 | Allowed | Allowed with attribution | **VERIFIED** |
| **AndroidX Libraries** | Apache 2.0 | Allowed | N/A | **VERIFIED** |
