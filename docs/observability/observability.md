# Observability Plan

## Decisions made

These were chosen up front so the rest of this document can be concrete rather than presenting options:

| Decision | Choice | Why |
|---|---|---|
| Stack | **Grafana LGTM** (Grafana + Loki + Tempo + Prometheus/Mimir) | Free, self-hosted, and OpenTelemetry is a first-class citizen — one SDK in each service, one collector, three backends fed from the same pipeline. |
| Scope | **Logs + metrics + traces**, all three from day one | This is a multi-hop OAuth2 flow (gateway → auth-server → greetings/shop/payment/…); traces are what let you see one request across services, which is the whole point of doing this now rather than later. |
| Deployment | **In-cluster**, same k8s cluster as the app (Hetzner) | Simplest to wire up, no second cluster to provision on Hetzner. Trade-off accepted: if the cluster is down, you lose observability at the same time — call out `alertmanager`/external uptime check as a future mitigation, not a blocker now. |

Two more decisions were needed and are now settled:

| Decision | Choice | Why |
|---|---|---|
| Retention | **3 days** for logs, traces, and metrics | Keeps PVC sizes small on Hetzner block storage; short enough that near-term debugging is all this stack needs to support right now. |
| Instrumentation method | **OpenTelemetry Java agent** (`-javaagent:`), baked into each Dockerfile — not the Spring Boot starter | Superseded an earlier decision to use the starter (see "Instrumentation method: reversed" below) after the starter's WebFlux module turned out to be fundamentally broken on Spring Framework 7. The agent hooks into Reactor/Netty via bytecode weaving instead of Spring's WebFilter API, sidestepping that bug entirely, and needs zero `build.gradle`/`application.yaml` changes — configuration is still just the same `OTEL_*` env vars. |

## What each piece does

- **OpenTelemetry (OTel)** — vendor-neutral instrumentation standard. Each service emits logs/metrics/traces in OTel format instead of a vendor-specific one. This is the thing that goes *into* your services.
- **OTel Collector** — a single deployment in the cluster that receives OTel data from every service and routes it to the right backend (Loki for logs, Tempo for traces, Prometheus/Mimir for metrics). Services only ever talk to the collector, never to the backends directly.
- **Loki** — log storage, built to be cheap and to index only labels (service name, pod, level), not full text — logs are stored as compressed blobs and queried like `grep`, not like a search engine. This is why it's cheaper to run than Elasticsearch for this use case.
- **Tempo** — trace storage. Stores full request traces (spans) so you can see "this HTTP call to `/api/secured/greet` took 400ms, and 380ms of that was the call from gateway to greetings."
- **Prometheus / Mimir** — metrics storage. Prometheus scrapes `/actuator/prometheus` on each service; Mimir is optional (only needed if you outgrow single-node Prometheus — skip it for now, plain Prometheus is enough at this scale).
- **Grafana** — the UI. One pane of glass across all three backends; you can jump from a trace straight to the logs for that request.

## Step-by-step plan

### Phase 0 — prerequisites
1. Confirm the Hetzner k8s cluster (see `cloud/hetzner/install-k8s-on-hetzner.md`) has enough headroom: budget roughly 1–2 vCPU / 2–3 GB RAM total for Grafana + Loki + Tempo + Prometheus + Collector at this project's scale (single-node deployments, short retention).
2. Add a `helm` repo setup step to your k8s tooling if you don't already use Helm — the LGTM components are all installed via the official Grafana Helm charts, not hand-written manifests, so they stay upgradable.
3. Provision PersistentVolume sizes for a 3-day retention window (small — this is the main benefit of deciding retention early).

### Phase 1 — deploy the storage backends (Loki, Tempo, Prometheus)
1. Add the Grafana Helm repo: `helm repo add grafana https://grafana.github.io/helm-charts`.
2. Install **Loki** in single-binary/monolithic mode (not the distributed `loki-distributed` chart — that's for much higher log volume than this project has) via the `loki` chart, backed by a PersistentVolume on Hetzner's block storage, into a dedicated `granite-observability` namespace.
3. Install **Tempo** the same way (`tempo` chart, single-binary mode, own PVC).
4. Install **Prometheus** via `kube-prometheus-stack` into `granite-observability` too (this also gives you `kube-state-metrics` and node exporters for free, which you'll want for cluster-level dashboards, not just app-level ones).
5. Confirm each is reachable in-cluster before moving on (`kubectl port-forward` to each and hit their local UI/API).

### Phase 2 — deploy the OTel Collector
1. Install the **OpenTelemetry Collector** via the `opentelemetry-collector` Helm chart, in "gateway" deployment mode (one shared Collector Deployment behind a k8s Service — not a DaemonSet/sidecar-per-pod, which is unnecessary at this scale and harder to reconfigure).
2. Configure three exporters in the Collector: `otlphttp` → Loki, `otlp` → Tempo, `prometheusremotewrite` (or a `prometheus` scrape endpoint) → Prometheus.
3. Install the Collector into `granite-observability` too, and point every service at it via one env var: `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector-opentelemetry-collector.granite-observability.svc.cluster.local:4318`. **Use port 4318 (HTTP), not 4317 (gRPC)** — the OpenTelemetry Java agent defaults to the `http/protobuf` protocol, and pointing it at the gRPC port causes every export to fail with `Connection reset` (found the hard way — see Phase 4).

### Phase 3 — deploy Grafana and wire up datasources
1. Install **Grafana** via the `grafana` chart into `granite-observability`.
2. Add Loki, Tempo, and Prometheus as datasources (can be done via the chart's `datasources` values block so it's declarative/GitOps-friendly, not click-ops in the UI).
3. Enable **trace-to-logs** and **trace-to-metrics** correlation in the Tempo datasource config — this is what lets you click a span in a trace and jump straight to the matching logs, which is the main ergonomic win of doing logs+traces+metrics together instead of picking just one.
4. Expose Grafana at **`grafana-granite.granite-security.org`**. This cluster uses **Gateway API** (Traefik `Gateway`/`HTTPRoute`), not classic Ingress. A brand-new, separate `Gateway` object turned out not to work: the `letsencrypt-prod` `ClusterIssuer`'s ACME HTTP-01 solver has `parentRefs` hardcoded to one specific Gateway — `granite-gateway` in the `granite` namespace (`cloud/hetzner/platform/cluster-issuer.yaml`) — so any other Gateway has no way to get a certificate issued. Instead:
   - Add `grafana-granite.granite-security.org` as two more listeners (`https-grafana`/`http-grafana`) on the *existing* `granite-gateway`, in `cloud/hetzner/app-multi/gateway.yaml` — additive only, the existing `granite-security.org`/`sichocolate.com` listeners are untouched. Set `allowedRoutes.namespaces.from: All` on these two listeners specifically (the others stay `Same`), since Grafana's Service lives in `granite-observability`, not `granite`.
   - Add the `HTTPRoute`s themselves in `k8s/observability/grafana-gateway.yaml`, living in `granite-observability` (same namespace as the `grafana` Service — no `ReferenceGrant` needed, since only the *parentRef* crosses into `granite`, and Gateway API allows that directly once the listener's `allowedRoutes` permits it).
   - Add the Cloudflare DNS `A` record for the subdomain (same pattern as `cloud/hetzner/cloudify.md`'s DNS step) — this is the one step that needs to be done outside the cluster/repo.
   - **Hostname collision, found the hard way:** this Hetzner box is shared with another team's stack (Davide's, ArgoCD-managed, namespace `infra-global-observability`), which already had its own Grafana claiming plain `grafana.granite-security.org`. Two HTTPRoutes with the same hostname attached to the same Gateway listener meant external traffic landed on *his* Grafana, not ours — which looked exactly like a broken login (accepted credentials via `kubectl port-forward` straight to our Service, rejected the same credentials externally) until `kubectl get httproute -A` surfaced the second route. Renamed ours to `grafana-granite.granite-security.org` to avoid it. If a route ever seems to silently ignore config you know is correct, check for a same-hostname collision across the whole cluster before anything else — namespace-scoped `kubectl get httproute` won't show it, you need `-A`.

### Phase 4 — instrument the services
This is the part specific to your codebase (Spring Boot 4.0.6 / Java 25, gateway is WebFlux-reactive, others are presumably Servlet-based).

**Instrumentation method: reversed mid-implementation.** The original plan was the OpenTelemetry Spring Boot starter (a `build.gradle` dependency), on the grounds that the Java agent doesn't support GraalVM native-image. In practice the starter turned out to be broken in a way that mattered more:

1. First attempt (starter): added `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter` to every service's `build.gradle`. Compiling alone (`./gradlew compileJava`) looked fine everywhere. Running the *real* build (`./gradlew build`, what the Dockerfiles actually run) surfaced two AOT-time failures — `NoClassDefFoundError: RestClientCustomizer` (the starter's `spring-web` module assumes a `spring-boot-starter-web` class none of these services have) and, in `shop` only, `NoClassDefFoundError: DefaultKafkaProducerFactoryCustomizer`. Both were worked around by disabling those specific instrumentation modules per-service.
2. That got every service compiling and passing AOT — but the real bug was still hiding, because none of that exercises an actual HTTP request. Once the images were rebuilt and rolled out to the live cluster, gateway's new pod **failed its readiness probe on every single request** with `NoSuchMethodError: HttpHeaders.get(Object)` thrown from `opentelemetry-instrumentation-spring-webflux-5.3`. That module calls a Spring Framework 5.3-era `HttpHeaders` API that no longer exists on Spring Framework 7 (what Spring Boot 4 ships) — not fixable by config, since it's a real binary incompatibility in the instrumentation library itself. (The rolling update's default strategy meant the *old* pod kept serving traffic throughout, so this was never a production outage — just a stuck rollout.)
3. **Switched to the OpenTelemetry Java agent instead**, since it instruments Reactor/Netty/JDBC/etc. via bytecode weaving at JVM startup rather than Spring's `WebFilter` API, sidestepping the broken code path entirely:
   - Removed the starter dependency and BOM from every `build.gradle.kts`, and removed the per-service `otel.instrumentation.*.enabled: false` workarounds from `application.yaml` — none of that is needed anymore.
   - Added the agent jar to every Dockerfile:
     ```dockerfile
     ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.30.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
     ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "app.jar"]
     ```
   - No k8s manifest changes needed — the agent reads the same `OTEL_SERVICE_NAME`/`OTEL_EXPORTER_OTLP_ENDPOINT` env vars the starter did.
   - Verified locally (both `curl` returning HTTP 200 through a WebFlux endpoint, and the agent attempting a real OTLP export with no `NoSuchMethodError`) before rebuilding and redeploying all 7 images.
4. **GraalVM native-image note, now moot for this decision:** all 7 Dockerfiles build plain JVM JARs for deployment (`./gradlew build` → copy the jar), not native binaries — the `org.graalvm.buildtools.native` plugin present in several `build.gradle.kts` files is unused for actual deployment. So switching to the Java agent cost nothing in practice, even though the agent and native-image are mutually exclusive in general. If native-image deployment is ever adopted later, tracing would need to be revisited (agent doesn't work there; the starter would, modulo the WebFlux bug above being fixed upstream first).
5. Set `OTEL_SERVICE_NAME` per service (`gateway`, `auth-server`, `greetings`, `shop`, `payment`, `profile`, `delivery`) so traces/logs are attributable — added alongside the existing per-service env vars in each `k8s/base/*.yaml`.
6. Verify structured JSON logging is enabled (Spring Boot's `logging.structured.format.console=ecs` or the OTel log appender) so log lines carry trace/span IDs automatically — this is what makes trace-to-logs correlation in Grafana work. Not yet confirmed against real log output — check this during the end-to-end check.

### Phase 5 — dashboards and alerts
1. Import the standard **Spring Boot / JVM** community Grafana dashboard (via `kube-prometheus-stack`'s dashboard sidecar or Grafana's dashboard provisioning) as a starting point rather than building one from scratch.
2. Build one small custom dashboard for the OAuth2 flow specifically: request rate + latency + error rate for `/api/secured/**` at the gateway, since that's the path that touches every other service.
3. Add basic Alertmanager rules for the failure modes you already know about (e.g. auth-server restart invalidating JWTs — alert on a spike in 401s across services, since that's the direct symptom).

### Phase 6 — retention and cost control
1. Set Loki/Tempo/Prometheus retention to 3 days.
2. Add a periodic reminder (or automated check) to watch PVC usage on the Hetzner volumes backing these — this is the one part of a self-hosted stack that has no safety net if you don't set retention correctly upfront.

## Grafana basics (new to Grafana? start here)

Log in at `https://grafana-granite.granite-security.org` with `admin` / (password from `kubectl -n granite-observability get secret grafana -o jsonpath='{.data.admin-password}' | base64 -d`). Three things to know:

1. **Explore** (left sidebar, compass icon) — the "just let me look at raw data" tool. Pick a datasource (Loki, Tempo, or Prometheus) at the top and run an ad-hoc query without building a dashboard first. For logs, the simplest useful query is `{service_name="gateway"}` (swap the service name) — this is where you'll spend most of your time when actually debugging something.
2. **Dashboards** (left sidebar, grid icon) — pre-built, saved views. You have two: **JVM (Micrometer)** (per-service JVM/GC/memory stats — pick the service from its variable dropdown) and **Gateway Request Overview** (request rate, latency, error rate for the gateway specifically).
3. **Trace → logs correlation** — this is the one feature that makes running Loki+Tempo+Prometheus together worth it over just picking one. In Explore, switch to Tempo, find a trace, click into it, then click the **"Logs for this span"** button on any span — it jumps straight to the matching log lines in Loki for that exact request, filtered by trace ID.

## Health checks — verifying each component is actually working

Do these checks right after installing each piece, not just at the end — it's much easier to tell which layer broke if you confirm each one before adding the next.

### Loki
```bash
kubectl -n granite-observability get pods -l app.kubernetes.io/name=loki
kubectl -n granite-observability port-forward svc/loki 3100:3100
curl -s http://localhost:3100/ready          # expect "ready"
curl -s http://localhost:3100/metrics | head # confirm it's serving metrics about itself
```

### Tempo
```bash
kubectl -n granite-observability get pods -l app.kubernetes.io/name=tempo
kubectl -n granite-observability port-forward svc/tempo 3200:3200
curl -s http://localhost:3200/ready          # expect "ready"
```

### Prometheus (kube-prometheus-stack)
```bash
kubectl -n granite-observability get pods -l app.kubernetes.io/name=prometheus
kubectl -n granite-observability port-forward svc/prometheus-operated 9090:9090
# open http://localhost:9090/targets — every target should be "UP"; if the
# Collector or app services already export a Prometheus scrape endpoint, they should
# appear here too
```

### OTel Collector
```bash
kubectl -n granite-observability get pods -l app.kubernetes.io/name=opentelemetry-collector
kubectl -n granite-observability logs deploy/otel-collector | tail -50
# look for "Everything is ready" and no repeated export errors to Loki/Tempo/Prometheus
kubectl -n granite-observability port-forward svc/otel-collector 13133:13133
curl -s http://localhost:13133/    # health_check extension, expect 200
```

### Grafana
```bash
kubectl -n granite-observability get pods -l app.kubernetes.io/name=grafana
kubectl -n granite-observability port-forward svc/grafana 3000:80
curl -s http://localhost:3000/api/health   # expect {"database": "ok", ...}
```
Then in the UI: **Connections → Data sources** — each of Loki/Tempo/Prometheus should show a green "Data source is working" when you click "Test". Once exposed at `grafana-granite.granite-security.org`, also confirm:
```bash
curl -sI https://grafana-granite.granite-security.org/api/health   # 200 through Traefik + TLS
kubectl -n granite-observability describe certificate grafana-granite.granite-security.org-tls  # Ready: True
```

### End-to-end check (after Phase 4 instrumentation)
This is the check that actually matters — it proves the whole pipeline works, not just that each piece is "up":
1. Hit a real endpoint, e.g. `curl https://granite-security.org/api/secured/greet` (with a valid session/token).
2. In Grafana **Explore**, switch to the **Tempo** datasource and search by service name (`gateway`) — a trace for that request should appear within a few seconds.
3. Click into the trace — you should see child spans for the downstream call into `greetings` (or whichever service it hit).
4. Click **"Logs for this span"** (the trace-to-logs button) — it should jump to Loki and show the matching log lines with the same trace ID.
5. Switch to the **Prometheus** datasource and query `http_server_request_duration_seconds_count{exported_job="gateway"}` — it should be incrementing. Note the label is `exported_job`, not `service_name` — Prometheus prefixes the original `job` label with `exported_` to avoid colliding with the scrape target's own `job` label (which is `otel-collector-opentelemetry-collector`, since Prometheus scrapes the Collector's `/metrics` endpoint, not each service directly).

If steps 2–5 all work, the pipeline (service → Java agent → Collector → Loki/Tempo/Prometheus → Grafana) is fully verified end to end. **Confirmed working end-to-end** with real traffic (`curl` through `gateway` → `greetings`) — see logs in Loki (`service_name="gateway"`), traces in Tempo (`rootServiceName: gateway`), and metrics in Prometheus (`exported_job="gateway"`, `exported_job="auth-server"`, `exported_job="greetings"`) all populated during this implementation.

## Open follow-up

- Native-image build for gateway with the OTel starter: validated (see Phase 4 step 4 above) — `processAot` and native-image compilation both succeed once `otel.instrumentation.spring-web.enabled: false` is set. Not yet validated: whether the resulting native binary actually emits correct traces/metrics/logs at *runtime* (AOT-time success doesn't guarantee GraalVM's reflection/proxy restrictions don't silently drop some instrumentation at runtime) — confirm via the end-to-end check once gateway is redeployed with this build.
- Log export via OTLP: the `OTEL_EXPORTER_OTLP_ENDPOINT` env var and starter dependency cover traces and metrics automatically, but whether Logback's OTLP appender is auto-installed (vs. needing an explicit `logback-spring.xml` appender entry) hasn't been confirmed against real application log output — check this specifically during the end-to-end check, not just the synthetic OTLP smoke test that was used to validate the Collector→Loki path.
