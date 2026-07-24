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
| Instrumentation method | **OpenTelemetry Spring Boot starter** (not the Java agent) everywhere, including gateway | The Java agent doesn't support GraalVM native-image at all; using the starter consistently across every service avoids running two different instrumentation approaches. |

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
3. Install the Collector into `granite-observability` too, and point every service at it via one env var: `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector.granite-observability.svc.cluster.local:4317`.

### Phase 3 — deploy Grafana and wire up datasources
1. Install **Grafana** via the `grafana` chart into `granite-observability`.
2. Add Loki, Tempo, and Prometheus as datasources (can be done via the chart's `datasources` values block so it's declarative/GitOps-friendly, not click-ops in the UI).
3. Enable **trace-to-logs** and **trace-to-metrics** correlation in the Tempo datasource config — this is what lets you click a span in a trace and jump straight to the matching logs, which is the main ergonomic win of doing logs+traces+metrics together instead of picking just one.
4. Expose Grafana (Ingress + your existing cert setup in `k8s/certs`, or `kubectl port-forward` while you're still iterating).

### Phase 4 — instrument the services
This is the part specific to your codebase (Spring Boot 4.0.6 / Java 25, gateway is WebFlux-reactive, others are presumably Servlet-based).

1. **Metrics + trace context propagation (no code changes):** add `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter` to each service's `build.gradle`. It auto-instruments Spring MVC/WebFlux, WebClient/RestTemplate, and JDBC, and reads `OTEL_*` env vars for configuration — no code changes needed beyond the dependency and env vars.
2. Add `management.otlp.logging.endpoint` / enable Micrometer's OTLP registry so `actuator` metrics flow through the same Collector endpoint, rather than exposing a separate `/actuator/prometheus` scrape target — keeps one pipeline instead of two.
3. Set `OTEL_SERVICE_NAME` per service (`gateway`, `auth-server`, `greetings`, `shop`, `payment`, `profile`, `delivery`) so traces/logs are attributable — add this alongside the existing per-service env vars in each `k8s/base/*.yaml`.
4. **⚠️ GraalVM native image caveat:** gateway (and possibly other services) build with `org.graalvm.buildtools.native`. The Spring Boot starter's auto-instrumentation coverage under native-image is narrower than on the JVM — confirm this during Phase 4 for gateway specifically (see "Open follow-up" below).
5. Verify structured JSON logging is enabled (Spring Boot's `logging.structured.format.console=ecs` or the OTel log appender) so log lines carry trace/span IDs automatically — this is what makes trace-to-logs correlation in Grafana work.

### Phase 5 — dashboards and alerts
1. Import the standard **Spring Boot / JVM** community Grafana dashboard (via `kube-prometheus-stack`'s dashboard sidecar or Grafana's dashboard provisioning) as a starting point rather than building one from scratch.
2. Build one small custom dashboard for the OAuth2 flow specifically: request rate + latency + error rate for `/api/secured/**` at the gateway, since that's the path that touches every other service.
3. Add basic Alertmanager rules for the failure modes you already know about (e.g. auth-server restart invalidating JWTs — alert on a spike in 401s across services, since that's the direct symptom).

### Phase 6 — retention and cost control
1. Set Loki/Tempo/Prometheus retention to 3 days.
2. Add a periodic reminder (or automated check) to watch PVC usage on the Hetzner volumes backing these — this is the one part of a self-hosted stack that has no safety net if you don't set retention correctly upfront.

## Open follow-up

Full native-image OTel support via the Spring Boot starter is not guaranteed — validate this concretely for gateway during Phase 4 (build the native image with the starter on the classpath and confirm spans/metrics actually show up in Tempo/Prometheus). If coverage turns out to be too narrow for gateway specifically, the fallback is manual Micrometer/OTel SDK instrumentation for gateway only, keeping the starter for the JVM-based services.
