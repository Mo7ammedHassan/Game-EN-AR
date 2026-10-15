# Handoff — Phase 10 Recovery → Gemini

Date: 2026-10-15 (all times local to dev machine).
Status on handoff: **recovery complete, Phase 10 IN PROGRESS (PARTIAL)**. No code changes made during handoff. No Git operations performed.

## 1. Project path and remote

- Local path: `D:\GamesEn-Ar`
- GitHub URL: https://github.com/Mo7ammedHassan/Game-EN-AR.git
- Target device: HONOR 600 / VKJ-NX9 (ID `ADQNUT6615002454`), Android 16, Snapdragon 7 Gen 4 / SM7750, arm64-v8a. Device was connected and authorized at handoff time.

## 2. Documentation Gemini must read first

In this order: `AGENTS.md`, `docs/README.md`, `docs/memory.md` (§4 handoff = Phase 9 COMPLETE state), `docs/current-state.md`, `docs/architecture.md` (§7, §7B), `docs/implementation-plan.md` (Phase 10 section), `docs/decisions.md` (ADR-011, ADR-013, ADR-014), `docs/testing.md` (Phase 9 gate logs), `docs/benchmarks.md` (ends at ENTRY 008 — ENTRY 009/010 exist only as claims in `memory.md`/prior reports, see §6 below), `docs/issues.md` (ISSUE-008 is the Phase 10 driver; ISSUE-006/007 context), `docs/changelog.md` ([0.14.1] latest).

## 3. Exact last completed action

A ~10-minute live rescan-verification run on the MLBB lobby (consented MediaProjection, production build with `ocrRescanIntervalCycles = 4`):
- Full logcat streamed to `C:\Users\asus\AppData\Local\Temp\opencode\rescan_run.txt` (13.3 MB — parse, do not re-capture blindly).
- Parsed summary at `...\opencode\rescan_parsed.txt`. Key facts: 40 full OCR cycles; rec p50 **590 ms** (NOT reduced); snapshots pub=133/over=131 (98% overwrite waste persists); translation launched=162, hits=112/miss=50, busySkip=242; overlay updates=520; FATAL=0, SIGSEGV=0.
- End screenshot `...\opencode\rescan_end.png` shows overlay co-located with game labels.
- Capture is now STOPPED (0 ServiceRecords, no app pid) — no cleanup pending.

## 4. Exact working-tree state and modified files

No Git repository exists (never initialized). Do not assume history. Modified files, by session:
- Prior session (2026-10-05, JVM-verified, noVM device numbers lost with cleaned temp files):
  - `app/.../ocr/SnapshotDownscaler.kt` — Opt A packed-read hoist + `blendChannel` (claims bit-identical).
  - `app/.../render/TieredOverlaySink.kt` — `Log.v sink update ok` line.
  - `app/.../SnapshotPixelMathTest.kt` — identity-exact + ramp-tolerance tests.
  - `app/.../OcrStageBenchmarkDeviceTest.kt` — A/B old-vs-new harness, warmup, `Locale.US`.
- This session, verified complete:
  - `app/.../MainActivity.kt` (02:28) — `ocrRescanIntervalCycles = 4` + `recognized=` in Verbose stats log.
  - `app/.../OcrNnapiProbeDeviceTest.kt` (new, 02:39) — NNAPI experiment, ran OK.
  - JVM gate post-change: **277/277, 0 failures** (`.../Temp/opencode/gate2.log`, XMLs 02:0x).
- Untouched: OCR models, tokenizer, translation engines, overlay tiers, tracker/stabilizer.

## 5. Verified test and benchmark results

- JVM: 277/277 PASS (post-rescan-change, this session). Prior: 275/275 → +2 pin tests.
- Android full suite: 21/21 PASS is **Phase 9 (pre-rescan)** — NOT re-run after the interval change.
- `OcrNnapiProbeDeviceTest`: OK on device — NNAPI sessions create+infer correctly (`100%`, 0.80) but det p50 123 ms ≈ CPU 102–142 ms: **no benefit → keep CPU**, no production change.
- A/B downscale bench (this session): old p50 **357 ms** / new p50 **124 ms** (N=8 interleaved, byte-identical asserts held) — Opt A wins **2.9×** on the conversion itself.
- ART stage bench (warmed): det-pre ~24 ms, ort_det ~102–142 ms, db ~4–9 ms, rec ~12–19 ms/region, ctc ~0.2 ms, change-det live p50 ~21–30 ms.
- Missing/invalid: the old `354 ms` figures (no warmup — invalid, do not use); ENTRY 009/010 log entries (absent from `benchmarks.md`; numbers survive only in `memory.md` claims — treat as unverified until re-measured; ENTRY 011 for the A/B has not been written yet).

## 6. Phase 10 items: completed / incomplete / unverified

- COMPLETED + verified: Step-1 instrumentation (Phase10Diag, sink log); stage microbench harness; Opt A implemented + JVM-proven + device-proven 2.9×; NNAPI experiment (negative result, documented above); rescan=4 implemented + JVM mechanism tests pre-existing; live rescan run captured with zero crashes.
- IMPLEMENTED but NOT verified: rescan=4 live effect — the captured run shows rec p50 590 ms (no reduction vs ~600 ms pre-rescan baseline) and 98% snapshot overwrite waste; likely cause is an animated scene (tracks rarely STABLE+static, so skips rarely trigger), but NONE-cycle `recognized=` distribution was never isolated to prove it. This analysis is the immediate gap.
- UNFINISHED: sustained thermal run; memory/GC pressure verdict; translation-scheduling numbers beyond Phase10Diag counters; full Android suite re-run post-rescan; ENTRY 011 (+ missing 009/010 repair); ADR/docs updates; GitHub publish (no `.git`, no `.gitignore`, remote is EMPTY — `ls-remote` exit 0 with zero refs, so no history conflict; push will need authentication which was never tested).

## 7. Precise next action for Gemini

1. Parse NONE-cycle `recognized=` vs `regions=` from the existing `rescan_run.txt` (13 MB already on disk — no new capture needed) to determine whether rescan skips are triggering at all.
2. Based on that: either (a) rescan works but the MLBB lobby defeats it → test on a STATIC screen, or (b) skips never trigger → debug `scheduleRecognition` gating before touching anything else.
3. Then: full Android suite re-run, ENTRY 011, docs, and only then GitHub init/publish.

## 8. Commands and failure modes not to repeat blindly

- Never pipe a long Gradle/adb command through `Select-Object -First N` — a previous pipe triggered `Unknown: ChildProcess.kill`, detached Gradle, and wedged two JVMs. Redirect to log files (`> file.log 2>&1`) and read the file after.
- Never run overlapping Gradle builds; check `Get-Process java` first. Idle Gradle daemon (PPID orphaned) + Kotlin worker are normal — leave them unless actively wedged (frozen CPU + no output).
- Never `am instrument` without streaming logcat to a file in parallel — the device buffer rotates in under a minute.
- `String.format` in device tests MUST use `Locale.US` (ar-EG locale shapes digits otherwise).
- `connectedDebugAndroidTest` uninstalls the app afterwards; reinstall + re-grant overlay (`appops set ... SYSTEM_ALERT_WINDOW allow`) before live runs.
- Do not tap the game UI (user session protection); app buttons/consent only, with screencap verification.
- Leave PIDs 21228/44244 (idle build daemons) alone.

## 9. Explicit scope restrictions

- Phase 10 only: performance/scheduling/memory/thermal. No architecture redesign without measured evidence; no model/tokenizer/stabilizer/concurrency changes.
- Do NOT investigate the known UI/translation observations (English instead of Arabic, oversized/clipped overlay text, English visible under overlay, slow disappear/appear, incomplete long text) — reserved for a later task. Exception only with a concrete, stated blocker.
- Do not mark Phase 10 COMPLETE: acceptance criteria on live rescan effect, sustained thermals, full-suite re-run, and ENTRY 011 are all still open. PARTIAL is the honest status.
