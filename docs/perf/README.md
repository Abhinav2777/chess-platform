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

Empty until Phase 9. That is the correct state right now, and it stays that way until
something is actually measured.
