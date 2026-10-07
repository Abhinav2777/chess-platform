# ADR-022: CI pipeline — PR gate, scan before push, pinned actions

**Status:** Accepted · **Date:** 2026-09-29
**Builds on:** ADR-021 (the image). **Amends:** the roadmap's "push to ECR" for Phase 6.

## Context

Phase 6 asks for: build → unit → integration → image scan → push → tag, branch protection,
and "a push to `main` produces a scanned, tagged image with zero manual steps; the pipeline
fails on a deliberately failing test".

Found first: **CI had never run.** The Phase 0 workflow triggered on `main` and PRs; GitHub
had only `users/Abhinav/initial` (the default branch), no PRs, zero workflow runs.

## Decision

**Branching:** `main` is the protected default branch; work lands through
PRs. Protection is code — `.github/branch-protection.json`, applied with one `gh api` call —
requiring `backend`, `frontend` and `image`, strict (up to date with `main`), enforced for
admins, no force pushes or deletion.

**`.github/workflows/ci.yml`** — on every PR and every push to `main`:

| Job | Does | Why here |
|---|---|---|
| `backend` | wrapper validation, pinned-Gradle check, unit + ArchUnit, integration (Testcontainers) | the correctness gate |
| `frontend` | `npm ci`, `tsc -b` + `vite build` | was not in CI at all; `npm run build` once went two milestones broken |
| `image` | build (layer cache in GHA) → **Trivy, fixable HIGH/CRITICAL fail** → on `main` only: log in, push | scan what would ship, *before* it ships |

- **Scan before push.** Build and load locally, scan, then push with the same inputs from
  cache. An unscanned image never reaches the registry.
- **Tags:** full commit SHA (immutable — what Phase 7 deploys) + `main` (moving).
- **GHCR now, ECR in Phase 7.** Free for a public repo, authenticated by the
  built-in `GITHUB_TOKEN`, no AWS account needed yet. Phase 7 adds an ECR push via OIDC.
- **Least privilege:** `contents: read` by default; `packages: write` on the image job only,
  used only on `main`. Fork PRs never see registry credentials.
- **Every action pinned to a commit SHA** (version in a comment). Tags are mutable and an
  action runs with the workflow's token; the 2025 `tj-actions/changed-files` compromise
  rewrote tags. Dependabot updates the pins.
- **`--ignore-unfixed`:** blocking on a CVE nobody can fix teaches people to ignore the scan.

**`.github/workflows/e2e.yml` (nightly + manual):** compose dependencies,
`bootRun`, Vite, the runner's Chrome; `e2e:lobby`, then a restart with Valkey fanout for
`e2e:outage`. Not a PR gate: minutes long and timing-sensitive; it guards the whole path
without making every PR wait on it.

**`.github/dependabot.yml`:** gradle (version catalog), npm, github-actions (the SHA pins),
docker (base images); weekly, minor + patch grouped.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **ECR now** | Needs an AWS account, OIDC provider, role and repository before Phase 6 can finish. GHCR costs nothing and defers AWS to the phase that needs it. |
| **Browser checks on every PR** | Several minutes and timing sensitivity on every PR; the cold-start timeout has already failed once. |
| **Action tags (`@v7`)** | Readable, but mutable. SHA + comment is readable enough and immutable. |
| **Renovate** | More capable, but an app to install and configure; Dependabot is built in and handles version catalogs. |
| **Scan after push** | The vulnerable image is already in the registry, and something may deploy it before anyone reads the report. |

## Consequences

- Dependabot cannot see the Boot-managed CVE overrides (plain Gradle properties); the image
  scan is what flags the next one.
- A new GHCR package may start **private** even for a public repository; Phase 7 either makes
  it public or gives the pulling side credentials.
- **First run (2026-09-29, run 99141810504):** `backend` green (unit 48 s, integration 2 m 21 s —
  the Testcontainers suite's first run anywhere but the dev machine) and `frontend` green; `image`
  red — the Trivy target was `ghcr.io/Abhinav2777/…` while metadata-action had built
  `ghcr.io/abhinav2777/…`. Image names must be lowercase; `github.repository_owner` is not.
  Fixed with a bash-lowercased `IMAGE` set once for the job.

## Amendment, 2026-10-01 — docs-only changes skip the build

Found in practice: a docs PR ran the full pipeline (~5 min) for markdown. Pushing docs straight
to `main` was considered and rejected — protection is `enforce_admins`, so it would mean weakening
it for every change, "docs-only" is not something GitHub can enforce on a push, and a push to
`main` triggers the full run (image build + ECR push) anyway.

Instead a `changes` job diffs the PR (or push) and, if every file is `*.md`, `docs/**` or
`SNAPSHOT`, the other jobs are skipped — GitHub reports a skipped job as success to branch
protection. It fails safe (new branch, manual run, empty or unreadable diff → full run), and a
change to the workflow itself is code. The Terraform matrix became two named jobs: a skipped
matrix job reports under its unexpanded name, which would leave `terraform (bootstrap)` /
`terraform (app)` pending forever. Checked against real ranges from this repo's history.

## Questions this decision raises

**Q:** "What does your pipeline do, and what does it refuse?"
**A:** Every PR runs backend unit, architecture and Testcontainers integration tests, the
frontend type-check and build, and builds the image and scans it with Trivy. Main is protected
on all three, admins included. Only a push to main publishes, and only after the scan passes —
the image is scanned before it's pushed, not after. Actions are pinned to commit SHAs because
tags can be moved, which is how the tj-actions compromise worked.

**Q:** "How did you know CI worked?"
**A:** At first I didn't — I found it had never run. The trigger was `main` and PRs, and there
was no `main`. The done-when for this phase is a PR with a deliberately failing test going red.
