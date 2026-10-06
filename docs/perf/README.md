# Performance reports

**This directory is the only legitimate source of performance numbers in this project.**

If a figure appears in the README, in `ARCHITECTURE.md`, on a resume bullet, or in an
interview answer, it must trace back to a file here — or be explicitly labelled an
estimate.

Each report must state:

- date measured
- exact instance types / container resources for both server **and load generator**
- the k6 scenario file used
- p50 / p95 / p99, not just an average
- CPU and memory headroom **on the load generator**, to prove it wasn't the bottleneck

## Reports

- [2026-10-01 — rolling deploy under live games, kind](2026-10-01-rolling-deploy-kind.md) (Phase 8.3)
- [Baseline — 100 / 500 / 1,000 connections, kind](baseline.md) (Phase 9.2)
- [Optimisation 01 — a memory budget that holds its own worst case](optimisation-01.md) (Phase 9.3)
- [Optimisation 02 — password hashing that cannot stop the rest of the server](optimisation-02.md) (Phase 9.4, AWS Fargate)
