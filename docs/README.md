# Offline Real-Time Android Game Translator — Documentation Index

Welcome to the project documentation for the **Offline Real-Time Android Game Translator (English → Arabic)**.

This repository serves as the persistent memory and single source of truth for all architectural, implementation, research, and benchmarking work.

---

## 1. DOCUMENT MAP & AUTHORITY

| Document | Purpose & Description | When to Read | Authority Level |
| :--- | :--- | :--- | :--- |
| [`/AGENTS.md`](../AGENTS.md) | Permanent AI operating manual and developer rules. | Mandatory start of every AI session. | Highest (System Operating Rules) |
| [`memory.md`](memory.md) | High-level persistent project memory, goals, current phase, constraints, and handoff section. | Mandatory start of every session. | High (Project Vision & State) |
| [`current-state.md`](current-state.md) | Verified reality of the code, working vs broken features, last test status. | Mandatory start of implementation. | High (Verified Reality) |
| [`implementation-plan.md`](implementation-plan.md) | Phased roadmap (Phases 0–12), active tasks, dependencies, acceptance criteria. | When selecting or completing tasks. | High (Execution Roadmap) |
| [`architecture.md`](architecture.md) | Technical system design, multi-rate data pipelines, coordinate systems, concurrency, memory/thermal strategy. | Before writing or modifying system design. | High (System Blueprint) |
| [`decisions.md`](decisions.md) | Architecture Decision Records (ADRs) detailing options, rationale, and rejected alternatives. | Before changing core technologies or designs. | High (Historical Rationale) |
| [`research.md`](research.md) | Evaluated technologies, APIs, ML runtimes (LiteRT, ONNX, ExecuTorch), OCR/NMT models, hardware acceleration, licenses. | When evaluating new libraries or models. | Reference (Technical Rationale) |
| [`benchmarks.md`](benchmarks.md) | Recorded empirical benchmark measurements (latency, RAM, CPU/GPU/NPU, thermals). | Before & after performance optimization. | Reference (Empirical Ground Truth) |
| [`issues.md`](issues.md) | Active technical risks, bugs, failure investigations, and post-mortems. | When debugging or addressing risks. | Working Record |
| [`testing.md`](testing.md) | Unit, integration, instrumentation, real-game verification plans, and benchmark datasets. | When writing or executing tests. | Working Record |
| [`changelog.md`](changelog.md) | Chronological log of major architectural milestones and release candidates. | After completing major milestones. | Historical Record |

---

## 2. AI AGENT STARTUP PROTOCOL

Every agent MUST execute the following sequence upon starting work:

1. Read [`/AGENTS.md`](../AGENTS.md)
2. Read [`/docs/README.md`](README.md)
3. Read [`/docs/memory.md`](memory.md)
4. Read [`/docs/current-state.md`](current-state.md)
5. Read [`/docs/implementation-plan.md`](implementation-plan.md)
6. Read task-specific documents as required.
7. Inspect actual codebase and build configurations.
8. Verify consistency between docs and code before making edits.

---

## 3. KEY ARCHITECTURAL PRINCIPLES SUMMARY

1. **Strictly Offline**: All ML models (OCR, NMT) run locally on device after installation. No cloud calls during gameplay.
2. **Multi-Rate Staged Pipeline**: Frame capture runs independently from change detection, OCR (low rate), translation (async queue), and overlay rendering.
3. **Non-Blocking Execution**: Bounded queues, stale request cancellation, backpressure handling. Game rendering and touch inputs remain untouched.
4. **Hardware Acceleration**: Automatic fallback from NPU/GPU delegates (LiteRT / ONNX Runtime) to multi-threaded CPU backend.
5. **Arabic Shaping & Readability**: Native RTL text layout, glyph shaping, mixed bidirectional string handling, outline/shadow overlays for readable in-game display.
