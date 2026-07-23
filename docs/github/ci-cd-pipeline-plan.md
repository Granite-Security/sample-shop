# CI/CD Pipeline Plan (GitHub Actions)

Status: planning only — no workflow files exist yet.

## Goals

- Trigger only on merge to `main` (not on every push to feature branches, not on PR open — PR-time checks are a separate, later concern if we want them).
- Repo is a monorepo with many independently deployable services. A change to one service should not rebuild/republish every other service.
- Start simple: Gradle compile/assemble only, no tests, no Docker. Layer in Docker build + Docker Hub push, and test execution, as later stages.

## Inventory of buildable units

| Service | Type | Build tool | Dockerfile | Image name in k8s manifests |
|---|---|---|---|---|
| auth-server | Java/Spring | Gradle (`./gradlew`) | yes | `granite-auth-server` |
| gateway | Java/Spring | Gradle | yes | `granite-gateway` |
| greetings | Java/Spring | Gradle | yes | `granite-greetings` |
| payment | Java/Spring | Gradle | yes | `granite-payment` |
| profile | Java/Spring | Gradle | yes | `granite-profile` |
| shop | Java/Spring | Gradle | yes | `granite-shop` |
| delivery | Java/Spring | Gradle | yes | `granite-delivery` |
| demo-kot | Kotlin | Gradle | no (check) | not deployed in k8s/base yet |
| ui-demo | Frontend | npm/vite | yes | `granite-ui-demo` |
| ui-shop | Frontend | npm/vite | yes | `granite-ui-shop` |

Each service is a self-contained Gradle (or npm) project — there's no root `settings.gradle.kts` tying them together, so each already builds independently. That's actually convenient for change-detection: a change under `payment/**` can only affect the `payment` build.

Non-buildable directories that should never trigger a build: `docs/`, `k8s/`, `cloud/`,  `Master-Plan.md`, `README.md`, `.claude/`, `.junie/`.

## High-level design

1. **Trigger**: `on: push` to `main` only (this is what "merge to main" looks like from GitHub Actions' point of view — a merged PR produces a push event on `main`). No `pull_request` trigger for now.
2. **Change detection job**: a single lightweight job runs first, diffs the merge commit against its parent, and produces a list of which service directories changed. This becomes the "dispatcher" for everything downstream.
3. **Per-service build jobs**: one job per service (or a single reusable/matrix job parameterized by service name), each gated by the change-detection output so it only runs when its own directory changed.
4. **Later stages** (not built yet, described below for planning purposes): Docker build → tag → push to Docker Hub, gated the same way.

## Step-by-step implementation guide

### Stage 0 — Repo prep
- [x] Create `.github/workflows/` directory.
- [x] Confirm each service's `gradlew` is committed and executable (it is, per current layout).
- [x] Naming convention: single workflow `.github/workflows/ci.yml` for the whole monorepo pipeline, keeping the change-detection logic in one place.

### Stage 1 — Trigger + change detection
- [x] Workflow triggered on `push` to `main` only.
- [x] `detect-changes` job implemented:
  - Checks out with `fetch-depth: 2` and diffs `HEAD^` vs `HEAD` (falls back to diffing against the empty tree if `HEAD^` doesn't exist, e.g. repo's very first commit).
  - Maps changed paths to service names via a bash loop over the known service directory list (no third-party action dependency).
  - Exposes two JSON array outputs — `jvm` and `ui` — consumed by downstream jobs via `fromJson(...)` as dynamic matrix values. An empty array (`[]`) means the downstream job's matrix is empty and it's skipped entirely.
  - Verified against a real multi-commit diff in this repo — correctly picked up the services that changed and excluded the ones that didn't (e.g. `demo-kot` untouched → excluded).

### Stage 2 — Gradle build (compile only, no tests), per service
- [x] `build-jvm` job: matrix over `needs.detect-changes.outputs.jvm`, one run per changed JVM service.
  - JDK: all 8 services target Java 25 via Gradle toolchains (`JavaLanguageVersion.of(25)`), so `actions/setup-java@v4` is configured once with `java-version: '25'` (temurin) for every matrix entry — confirmed consistent across all 8, no per-service divergence needed.
  - Runs `./gradlew build -x test` in the service's own directory, matching what the existing Dockerfiles already do.
  - Gradle dependency caching handled by `actions/setup-java`'s built-in `cache: gradle` option.
  - Verified locally: `cd greetings && ./gradlew build -x test` → `BUILD SUCCESSFUL`.
- [x] `build-ui` job: matrix over `needs.detect-changes.outputs.ui`, for `ui-demo` and `ui-shop`.
  - Node 22, `npm ci` then `npm run build` (verified both `package.json` files define a `build` script: `tsc ... && vite build`).
- [x] Went with a dynamic matrix (JSON array from Stage 1 fed via `fromJson`) rather than duplicated per-service job blocks or a `workflow_call` reusable workflow — one job definition per language, matrix entries computed at runtime. Simplest option that still fully skips unaffected services.
- [ ] Ship Stage 2, verify on a real merge to `main` that only the changed service's job runs. *(Pending — needs a real push to `main` to confirm end-to-end; not verifiable from a feature branch alone.)*

**Note on `demo-kot`:** included in the `jvm` matrix (it's a real, independent Gradle project) so it gets compiled like the others. It has no `Dockerfile` and isn't deployed in `k8s/base`, so it will be excluded when Stage 3 (Docker build/push) is implemented — flagging here rather than in open questions since Stage 2 already made the call to build it.

### Stage 3 (later) — Docker build & push to Docker Hub
- [ ] Add Docker Hub credentials as repo secrets (`DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`) — use an access token, not the account password.
- [ ] Extend each per-service job (or add a follow-on job that `needs` the build job) to:
  - Log in to Docker Hub.
  - Build the image using the service's existing `Dockerfile`.
  - Tag it — decide the tagging scheme now so it doesn't need rework later: at minimum `latest`, plus something traceable back to source, e.g. the short commit SHA. Since `main`-only triggers mean every build is a "release" candidate, SHA tags give a rollback point without needing a separate release/tag workflow.
  - Push to Docker Hub under a name matching what the k8s manifests already expect (`granite-<service>`), just with a real registry namespace prefix (currently the manifests use bare `granite-<service>:latest`, implying local/kind images — this will need to change to `<dockerhub-namespace>/granite-<service>:<tag>` once real images are pushed, and the k8s manifests will need a follow-up update to reference the registry).
- [ ] Reuse the Stage 1 change-detection output so Docker builds are also skipped for unaffected services — no need to re-derive it.
- [ ] Decide whether to build multi-arch images (relevant if Hetzner nodes and any local dev/CI runners differ in architecture) — flagged here since the branch is `feature/hetznerize`, but out of scope to decide in this doc.

### Stage 4 (later, optional) — Tests
- [ ] Once compile-only builds are stable, add a test task per service (`./gradlew test`, `npm test`), still gated by the same change-detection output.
- [ ] Decide whether tests block the Docker push (recommended: yes, once this stage exists) or run in parallel/informational only.

### Stage 5 (later, optional) — Deploy
- [ ] Not asked for yet, but the natural next step once images land in Docker Hub is updating the Hetzner k8s manifests (`k8s/base/*.yaml`, `cloud/hetzner/app*/kustomization.yaml`) to reference the new tag and triggering a rollout. Left out of scope here — call out separately when ready to plan it.

## Open questions to resolve before writing the workflow

1. What JDK version(s) do the Gradle services actually target? (Dockerfiles use `eclipse-temurin:25-jdk-alpine` — confirm this is consistent across all 8.)
2. Is `demo-kot` actually deployed/used, or is it a scratch/demo project that shouldn't be part of CI at all? It has no corresponding `k8s/base` manifest.
3. Squash-merge vs. merge-commit strategy on PRs into `main` — this determines whether `HEAD^` diffing is reliable or whether a changed-files action comparing against the last successful workflow run is safer.
4. Docker Hub namespace/org to publish under, and who owns those credentials.
5. Do we want a status badge / notification (Slack, GitHub status check) on pipeline failure, or is silent-until-checked fine for now?
