# AGENTS.md — Permanent AI Operating Instructions

## 1. PROJECT PURPOSE AND HIGH-LEVEL SCOPE
The goal of this project is to build an autonomous, production-quality, offline real-time Android application that translates English text inside games to Arabic in real-time.

The application operates as a background screen-capture and overlay service:
`Game → MediaProjection capture → Staged change detection → Text detection → Text recognition (OCR) → Temporal OCR stabilization → Text normalization → Cache lookup → Offline NMT model → Arabic text layout/shaping → Non-interactive visual overlay`

### Non-Negotiable Constraints:
1. **Fully Offline Pipeline**: Once models are installed, capture, detection, OCR, translation, shaping, and rendering MUST operate 100% offline without any network access.
2. **Real-Time Performance & Non-Blocking**: The translation pipeline runs multi-rate and asynchronously. It MUST NEVER block game rendering or touch input.
3. **No Root / No Invasiveness**: Must not require root access, code injection, accessibility hacks, or unsafe touch interception.
4. **Efficiency & Thermal Stability**: Designed for sustained performance on mid-range to high-end modern ARM64 Android hardware (reference platform: Qualcomm Snapdragon 7 Gen 4).
5. **Quality & Arabic Readability**: High semantic translation fidelity, preservation of game placeholders/variables (`{player}`, `%d`), proper Unicode bidirectional (BIDI) layout, and native Arabic shaping (RTL).

---

## 2. CORE ENGINEERING PRINCIPLES & CONSTRAINTS

1. **Correctness & Empirical Evidence Over Assumptions**:
   - Never assume an API, model, runtime, accelerator, or hardware capability exists without verifying it.
   - Do not replace engineering verification with confidence. Benchmark before claiming performance or memory gains.
2. **Multi-Rate Staged Pipeline**:
   - Never run full OCR/NMT on every frame (30/60/90/120 FPS).
   - Use cheap downsampled change detection, region hashing, ROI tracking, and temporal OCR stabilization.
3. **Modular Component Architecture**:
   - Decouple Capture, Detection/OCR, Stabilization, Normalization/Cache, NMT Engine, and Overlay Renderer behind abstract interfaces so components can be benchmarked and swapped independently.
4. **Zero Overhead on Stale Requests**:
   - Use bounded queues, request cancellation, backpressure, and region ID tracking to drop obsolete work immediately when UI text changes.
5. **Memory and Thermal Awareness**:
   - Avoid frame allocations in hot loops; reuse direct ByteBuffers / HardwareBuffers.
   - Monitor system thermal state and degrade workload gracefully under thermal throttling.

---

## 3. REQUIRED AI STARTUP PROTOCOL

Every AI agent starting or continuing work on this repository MUST follow this protocol in strict order:

1. **Read `/AGENTS.md`** (this file).
2. **Read `/docs/README.md`** to understand documentation map and authority.
3. **Read `/docs/memory.md`** to understand persistent context, goals, current phase, constraints, and handoff status.
4. **Read `/docs/current-state.md`** to inspect the verified reality of the project.
5. **Read `/docs/implementation-plan.md`** to identify active tasks, dependencies, and acceptance criteria.
6. **Read task-specific documentation**:
   - For OCR/Vision: `/docs/architecture.md`, `/docs/research.md`, `/docs/benchmarks.md`
   - For Translation: `/docs/architecture.md`, `/docs/research.md`, `/docs/decisions.md`
   - For Android/Lifecycle: `/docs/architecture.md`, `/docs/research.md`, `/docs/issues.md`
7. **Inspect actual repository code, tests, and configuration**: Verify that code matches documentation.
8. **Check for contradictions**: If code and documentation disagree, investigate empirical behavior first before making assumptions.
9. **Formulate incremental implementation plan**: Establish current state, target state, rationale, affected files, risks, verification method, and documentation updates.

---

## 4. RULES FOR CODE & ARCHITECTURE MODIFICATIONS

1. **No Superfluous Code / No Premature Overengineering**:
   - Do not generate large amounts of speculative code. Implement incrementally with tests.
2. **Strict Interface Scoping**:
   - Core modules (Capture, Vision, Translation, Rendering) must interact via well-defined interfaces (`ScreenCaptureEngine`, `OcrEngine`, `TranslationEngine`, `OverlayRenderer`).
3. **Preserve API Contracts & Backward Compatibility**:
   - Check all invocation sites when modifying method signatures.
4. **Graceful Failure Handling**:
   - Pipeline errors (e.g. MediaProjection termination, tensor execution failure, OOM) MUST be safely caught and handled without crashing the game session. Fall back gracefully (e.g., CPU fallback, cached output, original text).
5. **Input Safety**:
   - Overlay views MUST remain non-interactive (`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`) to prevent touch hijacking.

---

## 5. RULES FOR VERIFICATION, TESTING & BENCHMARKING

1. **Empirical Verification Required**:
   - NEVER claim a feature is complete or fixed without running verification commands (unit tests, instrumentation tests, or runtime benchmarks).
2. **Log Inspection**:
   - Always read exact error logs and tracebacks before diagnosing runtime or build failures. Never hide symptoms or swallow exceptions silently.
3. **No overwriting Historical Benchmarks**:
   - Benchmarks recorded in `/docs/benchmarks.md` are historical ground truth. Append new benchmark runs with full configuration details (device, OS, SoC, delegate, precision, latency P50/P95, peak RAM).
4. **Real Device vs Emulator**:
   - Emulators are allowed for functional unit/UI testing, but performance, hardware acceleration (GPU/NPU delegates), thermals, and MediaProjection screen behavior MUST be validated on physical ARM64 hardware.

---

## 6. RULES FOR PERSISTENT PROJECT MEMORY & DOCUMENTATION

1. **Repository is Ground Truth**:
   - Chat history and model context are temporary. The repository structure and code are the only persistent memory.
2. **No Fabricated Project History**:
   - If something is undocumented, write "Unknown — not documented" and investigate via code/git history.
3. **Documentation Efficiency Rule**:
   - Update only files whose information actually changed after meaningful work.
   - Do NOT update every document after small edits.
4. **Failed Approach Memory**:
   - Record disproven approaches in `/docs/decisions.md` or `/docs/issues.md` with rationale, evidence, and rejected alternatives to prevent repeating past mistakes.
5. **Handoff Requirement**:
   - Before completing a session, update `/docs/memory.md` and `/docs/current-state.md` so any subsequent AI agent can resume work seamlessly without context loss.

---

## 7. DOCUMENTATION MAP UNDER `/docs/`

- `/docs/README.md`: Navigation index and authority mapping.
- `/docs/memory.md`: Concise project state, constraints, phase summary, and section 46 handoff.
- `/docs/architecture.md`: System components, data pipelines, coordinate transformations, concurrency, and HW strategy.
- `/docs/decisions.md`: Architecture Decision Records (ADRs) with rationale and rejected alternatives.
- `/docs/current-state.md`: Actual verified code status, working features, known issues, and last verified test runs.
- `/docs/implementation-plan.md`: Phased task roadmap (Phases 0–12) with verification criteria.
- `/docs/research.md`: Evaluated libraries, APIs, ML runtimes, model licenses, and technical findings.
- `/docs/benchmarks.md`: Measured performance metrics (latency, RAM, CPU/GPU/NPU, thermals).
- `/docs/issues.md`: Tracked technical risks, bugs, failure investigations, and resolutions.
- `/docs/testing.md`: Test plans, test datasets, instrumentation setup, and real-game verification steps.
- `/docs/changelog.md`: Chronological log of major architectural and code milestones.
