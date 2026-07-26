# Storage service & product media — implementation plan

Status: **in progress** · Last updated: 2026-07-26

- [x] Step 0 — service bootstrap (verified: compiles, conventions OK; config block added)
- [x] Step 1 — Garage in root compose, `garage-init.sh` bootstrap, presign round-trip
  spike **green against a real Garage** (presigned PUT → direct upload → anonymous GET).
  Learnings baked into the artifacts: Garage v2 has no anonymous S3-API access — public
  reads are served by the `s3_web` website endpoint, which is **virtual-host only**
  (no path-style). Locally: `root_domain = ".localhost"` →
  `http://product-media.localhost:3902/<key>`. On the cluster, name the bucket after
  its domain (bare-host rule) so `https://media.granite-security.org/<key>` just works.
- [x] Step 2 — Storage service API: `route/StorageRoute.java` +
  `handler/StorageHandler.java` (`POST /api/storage/presign`,
  `DELETE /api/storage/objects`), `handler/HealthHandler.java`
  (`GET /actuator/health`), `service/StorageService.java` (contentType +
  scope allow-lists, filename sanitization via basename extraction — collapses
  `../../etc/evil.txt` to `evil.txt`, key prefix guard on delete),
  `config/S3Config.java` (`S3Presigner` + `S3Client` beans, path-style +
  endpoint override), `handler/GlobalErrorHandler.java` (mirrors
  `ShopException`/`GlobalErrorHandler`, but Jackson 3 —
  `tools.jackson.databind.ObjectMapper`, not `com.fasterxml`, since storage has
  no springdoc dependency pulling in Jackson 2 like shop does).
- [x] Step 3 — Security: `StorageSec` now has the `SecurityWebFilterChain` +
  CORS bean copied from `ShopSec` (kept the existing JWT decoder/converter
  beans from step 0). `presign`/`deleteObjects` → `hasAnyRole("ADMIN",
  "MANAGER")`; `/actuator/health` → permitAll; rest → authenticated.
  `StorageServiceTest` (Mockito + StepVerifier) covers contentType rejection,
  scope rejection, key prefixing/sanitization, and the delete prefix guard.
  Verified: `./gradlew build -x test && ./gradlew test` green (8 tests,
  `PresignRoundTripIT` self-skips without Garage).
- [x] Step 5 — Dockerize + local stack wiring: `storage/Dockerfile` and the
  `storage`/`garage` blocks in root `compose.yaml` already existed from step
  0/1. Added the missing piece: gateway route `/api/storage/**` →
  `storage:8065` (`gateway/.../RouterConfig.java` + `microservices.storage.uri`
  in gateway's `application.yaml`, env override
  `MICROSERVICES_STORAGE_URI`, also added to `storage`'s block in
  `compose.yaml` for the local stack). No `GateSec` change needed — confirmed
  gateway is `anyExchange().permitAll()` for every route; enforcement is
  entirely downstream (matches the plan).
  Verified live in Docker: `docker compose up -d --build garage storage
  gateway` → `GET :8065/actuator/health` → 200; unauthenticated
  `POST /api/storage/presign` and `DELETE /api/storage/objects` **through the
  gateway** → 401 from storage itself (proves the new route resolves to
  `storage:8065` and downstream JWT enforcement fires) — confirmed by running
  a throwaway gateway container on the compose network directly (the
  environment's local `kind` cluster already holds host port 8080, so the
  compose `gateway` container can't bind there right now; that's a pre-existing
  local port conflict, not a code issue — `docker compose up -d gateway` will
  work once the kind cluster is stopped, or via `kubectl -n granite ...` /
  a free host port in the meantime).
- [x] Step 6 — Shop product media fields: `image_url` already existed (added
  in `001-create-schema.sql`, not new). Added
  `007-add-product-media.sql` (`ALTER TABLE product ADD COLUMN media TEXT`,
  registered in the master changelog) + `dto/MediaItem.java`
  (`{key, url, contentType}`). `Product` entity gets a plain `media` field
  (raw JSON text, no `@Column` needed — name matches). `CreateProductRequest`
  / `ProductResponse` both gained a `List<MediaItem> media` field.
  `CatalogService` serializes/deserializes that list to/from the TEXT column
  with a `private static final ObjectMapper` (`com.fasterxml.jackson...`,
  matching `OrderService`/`EventConsumer`'s existing convention — shop still
  has Jackson 2 on the classpath via springdoc, unlike `storage`); null/empty
  media serializes to a `NULL` column, and a null/blank column deserializes to
  `List.of()`. `PUT /api/shop/products/{id}` already existed (not new, per
  plan's "if no update endpoint exists yet" caveat).
  Constructor-churn fallout fixed: `DataInitializer`'s 9 seed products and the
  `ProductResponse`/`CreateProductRequest` call sites in `ShopRouteTest` +
  `CatalogServiceTest` all needed a trailing `null` (or `media` list) arg.
  Added `shouldCreateProductWithMedia` to `CatalogServiceTest` for round-trip
  coverage. Verified: `./gradlew build -x test` green;
  `CatalogServiceTest` (pure Mockito/StepVerifier, no context) green.
  `ShopRouteTest`'s `@SpringBootTest` cases fail same as before my change
  (`DataInitializer`'s `CommandLineRunner` NPEs without a live DB) — confirmed
  via `git stash` that this is pre-existing/environmental, not caused by the
  media field.
- [x] Step 7 — ui-shop product create/edit with media upload: `api/storage.ts`
  (`presignUpload`, `uploadFile` — presign then raw `fetch` PUT straight to
  Garage with `Content-Type` matching what was presigned, bypassing the
  `request()` client helper since this call must NOT carry our
  `Authorization`/JSON headers, and `deleteObject`). `types.ts` gained
  `MediaItem`/`PresignResponse` and `Product`/`CreateProductRequest` gained
  `media`. New pages: `ProductsManagement.tsx` (list + delete, wired at
  `/admin/products`) and `ProductForm.tsx` (create metadata-only →
  `POST /api/shop/products` → redirect to edit; edit mode adds the media
  section — upload persists immediately via `PUT /api/shop/products/{id}`,
  remove calls `storageApi.deleteObject` then re-saves). `Admin.tsx`'s inert
  "Products" chip now links to the list. `ProductDetail.tsx` got a minimal
  thumbnail strip when `media.length > 1` (storefront gallery rendering, kept
  deliberately minimal per this step's scope). Verified: `tsc --noEmit`,
  `npm run build`, and `npm run lint` all clean — lint is back at the
  documented baseline of exactly 7 pre-existing errors (one new one surfaced
  in `ProductsManagement.tsx`'s effect and was fixed, not suppressed).
  **Not verified in a real browser** — see the pivot note below.
- [ ] Steps 8–9 pending

**Pivot (2026-07-26):** step 7's browser verification hit a local port-8080
conflict between the `kind` cluster and `docker compose`'s `gateway` service,
compounded by the OIDC redirect URI being hardcoded to `localhost:8080` (an
alternate gateway port breaks login). Per user direction, stopped the `kind`
cluster and tore down the compose stack entirely, and shifted to preparing
step 8 (`k8s/hetzner/app-multi` kustomize) instead of chasing a docker-compose
workaround. Browser/E2E verification of step 7's UI is still outstanding —
pick it up via a real k8s deploy (kind or Hetzner) once step 8 is applied.

**Step 8 progress (kustomize only — not yet applied to any cluster):**
- `k8s/base/garage.yaml` (new): `ConfigMap` (`garage.toml`, no `rpc_secret` in
  the file — read from `GARAGE_RPC_SECRET` env instead, unlike local dev's
  hardcoded value; no `root_domain` under `[s3_web]`, relying on the bare-Host
  bucket-name match described in step 1's status note) + `PersistentVolumeClaim`
  (5Gi, default `local-path` StorageClass, same as the existing Postgres/Kafka
  PVCs) + `Deployment` (`strategy: Recreate` — single-node Garage on an RWO
  PVC, a rolling update would run two processes against the same LMDB dir) +
  `Service` (ports `3900` s3-api, `3902` s3-web).
- `k8s/base/storage.yaml` (new): `Deployment` (same `wait-for-issuer`
  initContainer pattern as shop/delivery/profile, plus a `wait-for-garage`
  initContainer; `/actuator/health` readiness+liveness probes) + `Service`.
  Added to `k8s/base/kustomization.yaml`.
- `k8s/base/config.yaml`: `MICROSERVICES_STORAGE_URI` +
  `STORAGE_S3_ENDPOINT`/`REGION`/`BUCKET`/`STORAGE_PUBLIC_BASE_URL` (kind
  defaults: cluster-internal `http://garage:...`, fine since kind's browser
  never reaches Garage directly today — see gap note below).
  `k8s/base/gateway.yaml`: wired `MICROSERVICES_STORAGE_URI` through, matching
  the other `MICROSERVICES_*_URI` entries.
  `k8s/base/secrets.yaml.example` (+ the real gitignored `secrets.yaml`, since
  this checkout already has one): `garage-rpc-secret`,
  `storage-s3-access-key`/`secret-key` (blank — filled after the bootstrap
  runbook below).
- `k8s/hetzner/app-multi/`: `kustomization.yaml` images entry for
  `granite-storage`; `production-patches.yaml` resource sizing for `storage`
  (`imagePullPolicy: Always`, matching the other CI-built services) and
  `garage`; `config-patch.yaml` overrides `STORAGE_S3_ENDPOINT` →
  `https://s3.granite-security.org`, `STORAGE_S3_BUCKET` →
  `media.granite-security.org` (bare-Host rule — the bucket must be named
  after the domain serving it), `STORAGE_PUBLIC_BASE_URL` →
  `https://media.granite-security.org`; `secrets-patch.yaml.example` gained
  the same three keys as base. **`MICROSERVICES_STORAGE_URI` is deliberately
  NOT overridden** — the gateway calls storage over the internal Service DNS
  name in every environment; only the three keys above need a public host
  because they get baked into URLs the *browser* follows directly
  (presigned-PUT target and public-read base), bypassing the gateway
  entirely.
  `gateway.yaml`: two new listener pairs (https+http, each with its own
  redirect `HTTPRoute`) — `media.granite-security.org` → `garage:3902`
  (s3_web, public reads) and `s3.granite-security.org` → `garage:3900` (S3
  API, so presigned PUT URLs are internet-reachable). Both scoped to
  `granite-security.org` only, matching the plan's decision not to extend
  product-media to the `sichocolate.com`/ui-demo storefront.
- `.github/workflows/ci.yml`: added `storage` to the `jvm_services`
  changed-directory detection array, so it now builds/pushes
  `moldovean/granite-storage` on `storage/` changes like every other JVM
  service, and `update-gitops` can bump its `newTag`.
- Verified: `kubectl kustomize` renders `k8s/base`, `k8s/kind`, and
  `k8s/hetzner/app-multi` cleanly (no live cluster contact — purely local
  template rendering); spot-checked the `app-multi` output for the resolved
  image name, both `garage` `HTTPRoute` `backendRefs`, and the
  `STORAGE_S3_*`/`STORAGE_PUBLIC_BASE_URL` values. `k8s/hetzner/app` and
  `k8s/hetzner/app-chocolate` fail to render — confirmed via inspection this
  is **pre-existing and unrelated**: both list `secrets-patch.yaml` in their
  `patches`, but `k8s/base` deliberately excludes the gitignored
  `secrets.yaml` from its own resources (so a clean clone still renders),
  leaving nothing for that patch to target. Not touched — out of scope
  (`app`/`app-chocolate` aren't the "current default" `app-multi` this task
  asked about) and not something this change introduced.
  **Caveat:** one `kubectl apply --dry-run=client` was run before checking
  `kubectl config current-context`, which turned out to be the live
  `davide-hetzner-admin` context, not a local cluster — `--dry-run=client`
  only performs reads (needed to diff create-vs-update) and sent no writes,
  so nothing on the real cluster was touched, but this violated this repo's
  own "always confirm current-context first" rule and shouldn't have
  happened. All later validation switched to `kubectl kustomize` (fully
  local, no API calls).

**Known gap — not yet done:** the `kind` overlay has no path for a browser to
reach Garage directly (no `extraPortMappings` for 3900/3902 in
`k8s/kind/kind-config.yaml`, no NodePort Service, no `/etc/hosts`-style
hostname trick like the existing `gateway` one). Presigned URLs generated
under `k8s/kind` today point at `http://garage:3900`, a cluster-internal name
a host-machine browser can't resolve — fine for pod-to-pod traffic, but it
means the upload half of step 7 cannot be exercised end-to-end against `kind`
without this. Flagging rather than fixing silently, since it wasn't part of
what was asked (`app-multi`/Hetzner readiness) and needs a decision on
whether kind should even try to mirror the public S3 split, or just skip
straight to testing on a real cluster.

Goal: product images (and later videos) live in S3-compatible object storage;
product metadata stays in shop's PostgreSQL. A new `storage` microservice
(Spring Boot / WebFlux) mediates uploads. Admins/managers attach media to
products from the ui-shop admin panel.

## Decisions (taken 2026-07-26)

| Decision | Choice | Why |
|---|---|---|
| S3 backend | **Garage** v2.3 (AGPLv3) | Only option that is stable, actively maintained (NLnet-funded, v2.3.0), and honest about small footprints (~1 GB RAM, single binary). Single-node is first-class since v2.3 (`--single-node`). Presigned URLs + multipart fully implemented. Gaps (no bucket policies, no versioning, no notifications) don't affect this workload. AGPL is fine — used as a service over S3, not linked. |
| — rejected | MinIO CE | Repo archived April 2026; community edition effectively dead (console gutted 2025, source-only Oct 2025, maintenance mode Dec 2025). Do not deploy. |
| — rejected | RustFS | Best k3s Helm chart and MinIO-like console, but 1.0.0-beta.11 — no stable release. Revisit after stable 1.0; would need independent backups. |
| Upload path | **Presigned PUT URLs** | Browser asks `storage` for a presigned URL, then PUTs the file **directly to Garage**. No large payloads through the JVM; the service only signs. Best practice for this shape. |
| Read path | **Public bucket** | Product media is public by nature. Bucket toggled public-read; browsers load media straight from the Garage endpoint (`media.granite-security.org`) via the Traefik Gateway — zero JVM on the hot path. |

Consequences worth noting:

- With presigned PUT, the browser sends a **raw PUT body**, not
  `multipart/form-data`. Multipart only becomes relevant for **S3 multipart
  uploads** (per-part presigned URLs) — phase 2 for large videos (see step 9).
- Garage has no bucket policies/IAM: public-read is a Garage bucket toggle
  (`garage bucket allow` / website flag), and CORS for browser PUTs is set via
  Garage's bucket CORS API. Plan for both in bootstrap.
- The `storage` service is **stateless** (no database): the shop owns all
  product↔media references (step 6). Orphaned-object cleanup is a follow-up
  (step 9), not a reason to add a sixth Postgres now.

## Target architecture

```
Admin browser                          Shopper browser
    │                                       │
    │ 1. POST /api/storage/presign          │ GET https://media.granite-security.org/products/42/hero.jpg
    │    (JWT, ROLE_ADMIN/MANAGER)          │
    ▼                                       ▼
┌─────────────────┐                  Traefik Gateway (HTTPRoute)
│ storage service │                         │
│  (WebFlux,      │                         ▼
│   S3Presigner)  │                  ┌────────────────┐     ┌────────────┐
└──────┬──────────┘                  │ Garage (S3)    │◀────│ PVC (data) │
       │ 2. {uploadUrl, publicUrl}   │ Deployment+Svc │     └────────────┘
       ▼                             └───────▲────────┘
 browser ──── 3. PUT file bytes (presigned) ──┘
       │
       │ 4. PUT /api/shop/products/{id}  { imageUrl, media[] }
       ▼
 shop service ──► shopdb (product metadata + media refs)
```

Product flow (per user decision): **create product with metadata first**
(existing `POST /api/shop/products`), then edit it to attach media (new UI +
updated product fields). Media upload never blocks product creation.

## Step 0 — Bootstrap the `storage` service

Follow repo conventions (see AGENTS.md + `greetings` as the minimal reference):

- New sibling directory `storage/` with Gradle wrapper; `build.gradle.kts`
  modelled on `greetings/` (Java 25 toolchain, group `org.granite-security`,
  GraalVM buildtools plugin for consistency).
- Base package `org.granitesecurity.storage`; functional WebFlux routing
  (`RouterFunction` + handlers), constructor injection, Lombok.
- Dependencies: `spring-boot-starter-webflux`,
  `spring-boot-starter-oauth2-resource-server`,
  `software.amazon.awssdk:s3` (+ `url-connection-client` or netty — check what
  other AWS-SDK usage exists; none today, so pick the lightest HTTP client),
  Lombok, `spring-boot-starter-test` + `reactor-test`.
- **No R2DBC/JPA/Liquibase** — stateless.
- `application.yaml` with env-overridable config, following the per-service
  prefix convention: `STORAGE_SERVER_PORT` (next free port, suggest **8065**),
  `STORAGE_S3_ENDPOINT`, `STORAGE_S3_REGION` (Garage default `garage`),
  `STORAGE_S3_BUCKET` (`product-media`), `STORAGE_S3_ACCESS_KEY` /
  `STORAGE_S3_SECRET_KEY`, `STORAGE_PUBLIC_BASE_URL`
  (`https://media.granite-security.org`), plus the standard
  `jwt.jwk-set-uri` / `jwt.trusted-issuers` / `cors.allowed-origins` block
  copied from shop's yaml.

## Step 1 — Garage locally + SDK spike (de-risk first)

- Add a Garage container to the **root `compose.yaml`** (image
  `dxflrs/garage:v2.3.0`, config volume, data volume, port 3900) so the whole
  local stack gets S3 with one `docker compose up`.
- Bootstrap script (`storage/local/garage-init.sh` or a docs runbook):
  `garage layout assign`, create access key, create bucket `product-media`,
  allow read for anonymous (public bucket), set bucket CORS to allow
  `PUT, GET` from the ui-shop origins. Note: Garage v2.3
  `garage server --single-node --default-bucket` automates layout+bucket;
  evaluate it, but keep the explicit script as the documented path.
- Spike the exact production call: a minimal Java main/test using
  `S3Presigner` with `endpointOverride` + `serviceConfiguration(s3 ->
  s3.pathStyleAccessEnabled(true))`, generate a presigned PUT, `curl -X PUT`
  a file against Garage, then GET it anonymously. **Do not proceed past this
  step until the presign→PUT→public-GET round-trip works.**

## Step 2 — Storage service API

Routes (functional style, `route/StorageRoute.java`):

- `POST /api/storage/presign` — body `{fileName, contentType, scope}`
  (scope = e.g. `products`, later `avatars`); handler validates contentType
  against an allow-list (`image/jpeg|png|webp`, later `video/mp4|webm`) and a
  size cap note; generates key `products/{uuid}/{sanitizedFileName}`; returns
  `{key, uploadUrl, publicUrl, expiresIn}` (presign 10 min).
- `DELETE /api/storage/objects` — body `{key}`; S3 `DeleteObject` via the
  async client; used when an admin removes an image from a product. Validate
  the key prefix (only `products/` etc.) to avoid deleting arbitrary objects.
- `GET /actuator/health` — for k8s probes (match other services).

No streaming endpoints needed in this phase — presigned URLs and the public
bucket keep the JVM off the data path. WebFlux is still the right base
(non-blocking signing calls, consistency with the stack, and ready for the
multipart phase).

## Step 3 — Security

- `security/StorageSec.java` copied from `ShopSec` (NimbusReactiveJwtDecoder,
  `DelegatingOAuth2TokenValidator` = timestamp + trusted-issuer allow-list,
  `SCOPE_*` + `roles`-claim `ROLE_*` authorities, explicit CORS allow-list):
  - `POST /api/storage/presign`, `DELETE /api/storage/objects` →
    `hasAnyRole("ADMIN", "MANAGER")`
  - actuator health → permitAll; `anyExchange().authenticated()`.
- Garage credentials come from env vars; k8s secret via the existing
  gitignored `secrets-patch.yaml` pattern (step 8).


## Step 5 — Dockerize + local stack wiring

- `storage/Dockerfile` copied from another JVM service (layered jar pattern).
- Root `compose.yaml`: `storage` service (env: S3 endpoint = `garage:3900`,
  keys from the bootstrap script output, public base URL
  `http://localhost:3900/product-media`) + the `garage` service from step 1.
- Gateway: add route `/api/storage/**` → `storage:8065` in
  `gateway/.../RouterConfig.java` (no `GateSec` change — permitAll gateway,
  enforcement downstream).

## Step 6 — Shop: product media fields

- Liquibase `shop/.../007-add-product-media.sql`: add `image_url VARCHAR(512)`
  and `media TEXT` (JSON array of `{key, url, contentType}` for
  gallery/videos; `TEXT` + app-side JSON matches the codebase's payload
  habits) to `product`. Register in master changelog.
- `Product` entity + product DTO(s): expose `imageUrl` + `media`.
- `CatalogService`/`CatalogHandler`: accept the new fields on product create
  (`POST /api/shop/products`) and add `PUT /api/shop/products/{id}` if no
  update endpoint exists yet (check — writes already require
  ADMIN/MANAGER per `ShopSec`).
- Update shop tests that construct products (constructor churn — same lesson
  as the refund feature).

## Step 7 — ui-shop: product create/edit with media upload

- `api/storage.ts`: `presignUpload(fileName, contentType, scope)`;
  `deleteObject(key)`. `api/catalog.ts`: create/update product calls.
- New `pages/admin/ProductForm.tsx` (or similar):
  1. **Create** mode: metadata form only → `POST /api/shop/products` →
     redirect to edit mode. (Matches the agreed flow: product first, media
     later.)
  2. **Edit** mode: metadata + media section. Per file: `presignUpload` →
     `fetch(uploadUrl, {method:'PUT', body:file})` directly to Garage →
     collect `{key, publicUrl}` into the form → save via product update.
     Show upload progress state per file; remove = `deleteObject` + re-save.
  3. Wire into `Admin.tsx` (replace the inert Products chip) + `App.tsx`
     routes; `isAdmin || isManager` guard like DeliveryManagement.
- Storefront: product cards/detail render `imageUrl` (fallback placeholder);
  gallery/video rendering from `media` can be minimal in this phase.

## Step 8 — Kubernetes & CI

- `k8s/base/storage.yaml` (Deployment+Service, probes, env from
  `config.yaml`/`secrets.yaml` examples) and `k8s/base/garage.yaml`
  (Deployment + PVC on the cluster's default StorageClass + Service exposing
  both `3900` and `3902`; single replica — Recreate strategy). Added both to
  `k8s/base/kustomization.yaml`; updated `secrets.yaml.example` (+ the real
  local `secrets.yaml`) with `garage-rpc-secret` /
  `storage-s3-access-key` / `storage-s3-secret-key`. — **done**, see the
  detailed status note above for exact file/field-level changes.
- `k8s/hetzner/app-multi/`: image patches for the two new services — **done**.
  `gateway.yaml`: added a `media.granite-security.org` listener (HTTPS,
  cert-manager) + HTTPRoute to the `garage` service **port 3902 (s3_web)** for
  the public read path — mirrors the existing shop listener pair. Name the
  cluster bucket `media.granite-security.org`: Garage's website endpoint
  matches the bare Host header to a bucket name, so no hostname rewriting is
  needed (see step 1 status note — s3_web is vhost-only). **Decided:** the S3
  API needs its own public listener too, not just an internal port — the
  browser PUTs directly to the presigned URL, so whatever host
  `STORAGE_S3_ENDPOINT` uses to *sign* that URL must be a host the browser can
  resolve. Added `s3.granite-security.org` → `garage:3900` for exactly this
  (see `config-patch.yaml`'s `STORAGE_S3_ENDPOINT` override). — **done**.
- Garage bootstrap on the cluster — one-time `kubectl exec` runbook (adapt
  `storage/local/garage-init.sh`; same idempotent checks, so it's safe to
  re-run):
  ```bash
  kubectl -n granite exec -it deploy/garage -- /garage status   # get the node id
  kubectl -n granite exec -it deploy/garage -- /garage layout assign -z dc1 -c 5G <node-id>
  kubectl -n granite exec -it deploy/garage -- /garage layout apply --version 1
  kubectl -n granite exec -it deploy/garage -- /garage key create storage-key
  #   ^^^ save the Key ID + Secret — paste into secrets-patch.yaml as
  #       storage-s3-access-key / storage-s3-secret-key, then re-apply the
  #       secret and roll the storage deployment.
  kubectl -n granite exec -it deploy/garage -- /garage bucket create media.granite-security.org
  kubectl -n granite exec -it deploy/garage -- /garage bucket allow --read --write media.granite-security.org --key storage-key
  kubectl -n granite exec -it deploy/garage -- /garage bucket website --allow media.granite-security.org
  ```
  CORS on the bucket must allow `https://granite-security.org` (PUT/GET/HEAD)
  — apply via the AWS CLI against the internal S3 API the same way
  `garage-init.sh` does locally, or `kubectl exec` + `aws` if the image had it
  (it doesn't — run the AWS CLI step from your own machine against a
  port-forward: `kubectl -n granite port-forward deploy/garage 3900:3900`).
  Not yet run against a real cluster — this is the runbook, not a confirmed-
  working transcript.
- Cloudflare DNS `A` records for `media.granite-security.org` AND
  `s3.granite-security.org` (manual step, same as grafana's) — **not done**,
  no access to Cloudflare from this session.
- `.github/workflows/ci.yml`: added `storage` to the changed-directory
  detection/build matrix (image `moldovean/granite-storage`); Garage itself
  ships its upstream image — no CI for it. — **done**.
- `README.md` / `AGENTS.md` updates — **not done**, deferred (this pass
  focused on the kustomize manifests themselves per explicit request).

## Step 9 — Hardening & follow-ups (explicitly later)

- **Videos / large files**: S3 multipart via per-part presigned URLs
  (`CreateMultipartUpload` + presigned `UploadPart` + `CompleteMultipartUpload`)
  — Garage implements all 7 multipart endpoints. Frontend chunking + progress.
- **Orphan cleanup**: scheduled job comparing Garage objects vs shop `media`
  refs (needs `ListObjectsV2` — supported) — decide then whether it lives in
  `storage` or shop.
- **Image variants/thumbnails**: out of scope; revisit with a real need.
- **Backup**: nightly `rclone`/`aws s3 sync` of the Garage data dir or bucket
  to off-VPS storage — single-node means the PVC is the only copy.
- **Rate limiting / abuse** on presign endpoint; virus scanning — probably
  overkill for an admin-only upload surface.

## Verification checklist (end of implementation)

1. `cd storage && ./gradlew build -x test && ./gradlew test`
2. `cd shop && ./gradlew test` (product changes + media fields)
3. `cd ui-shop && npm run build && npm run lint` (baseline: 7 pre-existing
   errors in untouched files — no new ones)
4. Local E2E: `docker compose up --build` → login as `admin` → create product
   → edit → upload image → image visible on storefront product page via the
   Garage public URL.
5. Cluster E2E (post-merge): same flow on granite-security.org with
   `media.granite-security.org` serving the file; confirm the Garage PVC
   retains data across a pod restart.
