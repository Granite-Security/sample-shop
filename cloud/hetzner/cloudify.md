# Cloudify: kind → Hetzner VPS (88.99.149.31)

Step-by-step migration of the locally-tested `k8s/kind` deployment onto the Hetzner VPS at
`88.99.149.31`. The Kustomize overlay for this target already exists at `cloud/hetzner/` — this
file is the runbook for turning it on, not a redesign of it. Read `k8s/kind/kind.md` first if
you haven't; every env var and route this plan touches is documented there.

Nothing in `k8s/base/` changes. Everything below is either a one-time VPS/cluster setup step, or
`kubectl apply -k cloud/hetzner/app`.

---

## Architecture Overview: internet → services

```
Internet (browser, and Let's Encrypt's ACME servers during cert issuance/renewal)
        │
        │  DNS: <DOMAIN> → 88.99.149.31  (granite-security.org, Cloudflare A record, DNS-only)
        ▼
Hetzner VPS 88.99.149.31 — single node "node1"
        │
        │  :80 / :443 — arrives directly on the node's public interface, no LB in front
        │  (see "Do you need an HAProxy?" below for why)
        ▼
┌──────────────────────────────────────────────────────────────────┐
│ Traefik — hostNetwork DaemonSet, binds the host's :80/:443        │
│ directly (platform/traefik-values.yaml)                           │
│                                                                     │
│  Gateway "granite-gateway"  (app/gateway.yaml)                     │
│   ├─ :443 HTTPS listener → TLS terminates here                    │
│   │     cert from cert-manager + Let's Encrypt                     │
│   │     (ClusterIssuer "letsencrypt-prod")                        │
│   └─ :80  HTTP listener  → 301 redirect to HTTPS, except           │
│         /.well-known/acme-challenge/* (cert-manager's own          │
│         temporary HTTPRoute, present only during cert renewal)     │
└──────────────────────────────────────────────────────────────────┘
        │  HTTPRoute "granite-route": path / → ui-shop:80
        ▼
ui-shop  (nginx, ClusterIP)  — SPA + reverse proxy
        │  proxies /api, /auth, /oauth2 → gateway:8080  (k8s/base/ui-shop.yaml)
        ▼
gateway  (Spring Cloud Gateway, ClusterIP — internal only, never touches the Gateway
          API Gateway above; unfortunate name collision, see note below)
        │  routes by path prefix (k8s/base/gateway.yaml)
        ├──► auth-server:9090   (OIDC provider — issues/validates JWTs)
        ├──► greetings:8060
        ├──► shop:8061 ────────► payment:8062, kafka:9092
        ├──► payment:8062 ─────► kafka:9092
        ├──► profile:8064
        └──► delivery:8063 ────► kafka:9092
                │
                ▼
        postgres-{auth,shop,payment,profile,delivery}  (ClusterIP, local-path-
        provisioner PVCs)  +  kafka  (ClusterIP, local-path-provisioner PVC)
```

> **Naming collision, not a typo:** "Gateway" means two different things in this stack —
> the Gateway API `Gateway` resource (Traefik, at the cluster edge) and the app's own
> `gateway` Spring Cloud Gateway service (internal reverse proxy, unrelated component that
> predates this migration). Both appear above; the ASCII box is the Gateway API one.

**Internal-only path (why `coredns-custom.yaml` exists):** `auth-server`, `greetings`, `shop`,
`payment`, `profile`, and `delivery` all validate JWTs by fetching
`https://<DOMAIN>/auth/.well-known/openid-configuration` at startup. Without the split-horizon
override, that lookup would leave the cluster, cross the public internet via Cloudflare, and
hairpin back to the same node — slow, and not guaranteed to work depending on the network path.
`platform/coredns-custom.yaml` makes CoreDNS resolve `<DOMAIN>` straight to Traefik's in-cluster
ClusterIP instead — identical TLS cert and Gateway, just skipping the round trip.

### Do you need an HAProxy (or similar) in front of the Gateway?

**No, not with this topology.** A load balancer like HAProxy exists to spread traffic across
*multiple* backend instances sharing one public IP. Here there's only one instance in the
relevant sense: a single Kubernetes node, with Traefik in `hostNetwork` mode already bound
directly to that node's public IP on 80/443. Putting a proxy in front of a single endpoint adds a
hop with no failover benefit — nothing to fail over to.

This calculus changes only if you scale to more than one node for HA, which is called out in
§13 as an explicit non-goal, not a silent gap. At that point something has to own the single
public IP across multiple nodes, since Traefik can't `hostNetwork`-bind the same IP on two
machines simultaneously:

| Option | Fit |
|---|---|
| Hetzner Cloud LoadBalancer | Simplest, but only available if you migrate off a bare VPS onto Hetzner Cloud with an LB product attached — not applicable to a plain dedicated/VPS box |
| MetalLB (L2 mode) | Native to bare-metal k8s clusters; Traefik would go back to a normal `LoadBalancer`-type Service instead of hostNetwork — the more common self-hosted answer |
| keepalived + HAProxy | The traditional VRRP floating-IP + L4 LB pair; works with zero cloud dependency but is more moving parts to operate yourself |

Not designed further here since it's out of scope until a second node actually exists.

---

## 0. Domain decision

The overlay hardcodes a hostname in four places: `app/gateway.yaml` (Gateway listener hostname),
`app/config-patch.yaml` (OIDC issuer URLs), `platform/cluster-issuer.yaml` (ACME account, not
host-specific), and `platform/coredns-custom.yaml` (split-horizon override). Let's Encrypt's
HTTP-01 solver (what `cluster-issuer.yaml` uses) cannot issue a cert for a bare IP, so **some**
hostname is required.

**Decided:** `granite-security.org`, registered and pointed at `88.99.149.31` via a Cloudflare `A`
record. Confirmed resolving correctly (`dig +short granite-security.org` → `88.99.149.31`,
nameservers are Cloudflare's `blair`/`razvan.ns.cloudflare.com`, DNS-only / grey-cloud — no
Cloudflare proxy in front, which matters below).

Keep the record **grey-cloud / DNS-only**, not orange-cloud (proxied): Cloudflare's proxy
terminates TLS at their edge, which conflicts with `cert-manager`'s HTTP-01 solver expecting to
see the raw ACME challenge request land directly on Traefik. (Orange-cloud is possible later via a
DNS-01 solver with a Cloudflare API token, but that's a follow-up, not part of this plan.)

The sslip.io fallback and the domain-substitution step originally planned here are no longer
needed — `granite-security.org` is already burned into `app/gateway.yaml`, `app/config-patch.yaml`,
and `platform/coredns-custom.yaml`. From here on this doc uses `<DOMAIN>` as shorthand for
`granite-security.org` specifically, not a placeholder to substitute.

---

## 1. Cluster already exists — confirm, don't reinstall

The Kubernetes control plane is already up on `88.99.149.31`: a kubeadm/Kubespray cluster (Calico
CNI, `nodelocaldns` + `dns-autoscaler` in `kube-system`), single control-plane node `node1`,
untainted and schedulable, 8 CPU / 32GB RAM. This matches the kubeadm assumption already baked
into `platform/coredns-custom.yaml`'s comments — no rewrite needed there.

Your kubeconfig has multiple contexts (`davide-hetzner-admin`, `hetzner-s4v3`, `kind-granite`,
`kind-kind`). Pin the one you use for every command below so nothing lands on the wrong cluster
by accident:

```bash
kubectl config use-context davide-hetzner-admin
kubectl config current-context   # confirm before every apply in this doc
```

What's confirmed **not** installed yet (checked via `kubectl get storageclass` / `helm list -A` /
`kubectl get pods -A`): no StorageClass, no ingress controller, no cert-manager. Steps 2-4 below
install exactly those three things — everything else on the cluster stays untouched.

One thing `kubectl` can't confirm remotely: the node's firewall must allow inbound 80/443 (ingress-
nginx binds them directly via hostNetwork, see step 3). Verify over SSH:

```bash
ssh -i ~/.ssh/davide_k8s root@88.99.149.31 'ufw status'
```

**Done** — `ufw` was active with only OpenSSH/6443/cluster-CIDR rules; 80/tcp and 443/tcp have been
added (`ufw allow 80/tcp && ufw allow 443/tcp`, both v4 and v6 rules present now).

---

## 2. Install a StorageClass

No default StorageClass exists yet, and the 5x Postgres + Kafka PVCs in `k8s/base/postgres.yaml` /
`kafka.yaml` need one to bind. Since this is a single schedulable node, Rancher's
[local-path-provisioner](https://github.com/rancher/local-path-provisioner) (the same one k3s
bundles, but it installs standalone on any cluster) is the simplest fit — no separate CSI driver,
no cloud block-storage account needed.

```bash
kubectl apply -f https://raw.githubusercontent.com/rancher/local-path-provisioner/v0.0.36/deploy/local-path-storage.yaml
kubectl patch storageclass local-path -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'
kubectl get storageclass   # confirm "local-path" is (default)
```

---

## 3. DNS

**Done** — Cloudflare `A` record for `granite-security.org` → `88.99.149.31`, DNS-only (grey
cloud). Confirmed propagated: `dig +short granite-security.org` returns `88.99.149.31`.

---

## 4. Install cluster add-ons

**Decision, flagged for review:** the Kubernetes project is retiring the community
`kubernetes/ingress-nginx` controller (maintainer burden, not a security issue with the classic
`Ingress` API itself, which remains supported). Rather than adopting a different classic-Ingress
controller, this plan moves straight to [Gateway API](https://gateway-api.sigs.k8s.io/) — the
project's own recommended replacement — using **Traefik** as the implementation. Traefik was
picked over Envoy Gateway / NGINX Gateway Fabric for being the lightest-weight option for a
single-node box (one binary, no separate Envoy data plane to run).

**4.1 Traefik** (values file already in this repo — `platform/traefik-values.yaml`, runs as a
hostNetwork DaemonSet bound to :80/:443, same reasoning as the old ingress-nginx values: no cloud
LoadBalancer on a bare VPS):

```bash
helm repo add traefik https://traefik.github.io/charts
helm repo update
helm install traefik traefik/traefik \
  -n traefik --create-namespace \
  -f cloud/hetzner/platform/traefik-values.yaml

kubectl get gatewayclass          # expect ACCEPTED: True for GatewayClass "traefik"
kubectl get pods -n traefik       # expect 1/1 Running
kubectl get svc -n traefik        # expect ClusterIP (not LoadBalancer — see values file note)
```

The chart installs its own bundled Gateway API CRDs on first install (a deprecation warning in the
Helm output says future chart majors will stop doing this) — no separate CRD install needed *today*,
but apply the current standalone release afterward to not be stuck on whatever version the chart
happened to bundle:

```bash
kubectl apply -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.6.1/standard-install.yaml
```

**Two real failures hit installing this on the actual cluster, both already fixed in
`platform/traefik-values.yaml`** — worth knowing about since they'll resurface if this values file
is ever rewritten from scratch:

1. `maxUnavailable should be greater than 0 when using hostNetwork` — the chart's default rollout
   strategy relies on a surge pod, which can't work here: a second pod can't bind the same host
   port 80/443 on the same node before the old one is removed. Fixed by setting
   `updateStrategy.rollingUpdate: {maxUnavailable: 1, maxSurge: 0}` (Kubernetes also rejects
   `maxSurge` non-zero alongside non-zero `maxUnavailable` on a DaemonSet, so both had to be set
   together, not just one).
2. `listen tcp :80: bind: permission denied`, even with `capabilities.add: [NET_BIND_SERVICE]` set
   and confirmed present in the container's `CapBnd` — but **not** in `CapEff`. This is a
   long-standing Kubernetes gap, not a config mistake: Kubernetes never populates the Linux
   "ambient" capability set from `securityContext.capabilities.add`, so a *non-root* process only
   gets the capability in its Bounding set, not Effective, and privileged-port `bind()` still fails
   ([kubernetes/kubernetes#56374](https://github.com/kubernetes/kubernetes/issues/56374)). Fixed
   using Traefik's own documented workaround: two init containers copy the binary to a shared
   `emptyDir` and `setcap cap_net_bind_service=+ep` it directly — file capabilities apply at exec
   time regardless of the ambient-set gap.

**4.2 cert-manager**, installed with Gateway API support enabled (`config.enableGatewayAPI=true` —
without this flag the `gatewayHTTPRoute` solver in `cluster-issuer.yaml` won't be recognized), then
the ClusterIssuer already in this repo:

```bash
helm repo add jetstack https://charts.jetstack.io
helm repo update
helm install cert-manager jetstack/cert-manager \
  -n cert-manager --create-namespace \
  --set crds.enabled=true \
  --set config.enableGatewayAPI=true

kubectl apply -f cloud/hetzner/platform/cluster-issuer.yaml
```

**4.3 coredns-custom** (split-horizon so in-cluster pods resolve `<DOMAIN>/auth` to the in-cluster
Gateway instead of round-tripping through the public internet — see the comment block in the file
for the full why):

```bash
TRAEFIK_IP=$(kubectl get svc -n traefik traefik -o jsonpath='{.spec.clusterIP}')
sed "s/REPLACE_WITH_TRAEFIK_CLUSTER_IP/$TRAEFIK_IP/" \
  cloud/hetzner/platform/coredns-custom.yaml | kubectl apply -f -
kubectl rollout restart deployment coredns -n kube-system
```

---

## 5. Point the app overlay at `<DOMAIN>` — done

`granite-security.org` is already substituted into `app/gateway.yaml`, `app/config-patch.yaml`,
and `platform/coredns-custom.yaml` (the domain-substitution `sed` this step originally called for
has already been run). Nothing left to do here except, if desired, update the ACME contact email
in `platform/cluster-issuer.yaml` — still `mr.vrabie@gmail.com`, change it if that's not where you
want cert expiry/renewal notices sent.

---

## 6. Build and publish images

The kind workflow uses `kind load docker-image` for a purely local Docker daemon — that doesn't
exist on a remote node, so images need a real registry. `app/kustomization.yaml` is already
pointed at `docker.io/gluonstream/granite-*` (Docker Hub, not GHCR).

**6.1 One-time: authenticate Docker to Docker Hub.** Create an access token at
[hub.docker.com/settings/security](https://hub.docker.com/settings/security) (Account Settings →
Security → New Access Token, Read & Write scope) rather than using your account password directly:

```bash
export DOCKERHUB_TOKEN=<paste the token>
echo $DOCKERHUB_TOKEN | docker login -u gluonstream --password-stdin
```

**6.2 Build the JARs, then the images, tagged with the git short SHA** (not `latest` — see the
comment already in `kustomization.yaml` for why: `imagePullPolicy: IfNotPresent` + a floating tag
means a redeploy would silently reuse the stale cached image).

**Cross-architecture warning, learned the hard way:** if you're building on Apple Silicon (`arm64`)
— check with `uname -m` — plain `docker build` targets your Mac's own architecture by default. The
Hetzner node is `amd64` (`kubectl get node node1 -o jsonpath='{.status.nodeInfo.architecture}'`).
Pushing an arm64 image and deploying it on an amd64 node doesn't fail at pull time — it fails at
container *start* with `exec /opt/java/openjdk/bin/java: exec format error`, which reads like a
broken JAR rather than an architecture mismatch. Always build with `docker buildx build --platform
linux/amd64` explicitly (Docker Desktop ships `buildx` with cross-arch emulation already
configured — `docker buildx ls` should show `linux/amd64` in one builder's platform list):

```bash
export TAG=$(git rev-parse --short HEAD)
export TAG=latest

for s in auth-server gateway greetings shop payment profile delivery; do
  (cd $s && ./gradlew build -x test)
  docker buildx build --platform linux/amd64 -t docker.io/gluonstream/granite-$s:$TAG --push $s/
done

docker buildx build --platform linux/amd64 -t docker.io/gluonstream/granite-ui-shop:$TAG --push ui-shop/
```

(`--push` in the same `buildx build` invocation, rather than a separate `docker build` + `docker
push` — `buildx` can't `docker load` a foreign-architecture image into the local daemon, so pushing
straight from the build is the only option when cross-compiling like this.)

**6.3 Point the overlay at this tag:**

```bash
sed -i '' "s/newTag: latest/newTag: $TAG/" cloud/hetzner/app/kustomization.yaml
```

**6.4 Repository visibility — decision needed.** Docker Hub free personal accounts auto-create a
repo as **public** on first `docker push` unless your account has "default private repos" turned
on, or you've already hit the free-plan private-repo limit (in which case the push fails outright
rather than silently landing private) — and nothing in `production-patches.yaml` currently wires
up an `imagePullSecret`, so a private repo will fail to pull with `ImagePullBackOff`. Two ways to
resolve, pick one:

| Option | How | Trade-off |
|---|---|---|
| **Keep repos public** (recommended for this demo/portfolio app) | Default behavior — just confirm each repo's visibility at `hub.docker.com/r/gluonstream/granite-<svc>/settings` after the first push | Simplest — no secret to create or keep in sync; fine since there's no proprietary code concern here |
| Private + imagePullSecret | `kubectl -n granite create secret docker-registry dockerhub-pull --docker-server=docker.io --docker-username=gluonstream --docker-password=$DOCKERHUB_TOKEN`, then add `imagePullSecrets: [{name: dockerhub-pull}]` to every Deployment's pod spec in `production-patches.yaml` | More setup, and the secret needs manual rotation whenever the token expires |

This plan defaults to **public repos** given the recommendation above; switch to the
imagePullSecret route only if you'd rather keep images private.

---

## 7. Secrets

```bash
cp cloud/hetzner/app/secrets-patch.yaml.example cloud/hetzner/app/secrets-patch.yaml
```

Fill in real values per the comments already in that file (strong DB passwords via
`openssl rand -base64 32`, a real bcrypt hash for `oidc-client-secret-encoded` instead of the
kind-only `{noop}` plaintext, live or test Stripe keys, Google OAuth redirect URI updated to
`https://<DOMAIN>/login/oauth2/code/google` in the Google Cloud Console before cutover).
`secrets-patch.yaml` is gitignored — never commit it.

---

## 8. Apply

```bash
kubectl config current-context   # must read davide-hetzner-admin, per step 1
kubectl apply -k cloud/hetzner/app
kubectl -n granite get pods -w
```

Wait for infra first, same ordering as kind:

```bash
kubectl -n granite wait --for=condition=ready pod -l app=postgres-auth --timeout=120s
kubectl -n granite wait --for=condition=ready pod -l app=kafka --timeout=120s
kubectl -n granite wait --for=condition=ready pod -l app=auth-server --timeout=180s
kubectl -n granite wait --for=condition=ready pod -l app=gateway --timeout=180s
```

Check the cert issued:

```bash
kubectl -n granite describe certificate granite-security.org-tls
```

---

## 9. Verify end to end

```bash
curl -sI https://granite-security.org/            # 200 from ui-shop through the Gateway + TLS
curl -s https://granite-security.org/api/greetings/public | jq .
```

Then in a browser: full login flow (`user`/`user`), same seed accounts as kind. Confirm the JWT
`iss` claim is `https://granite-security.org/auth` (not the in-cluster `gateway:8080` host from
kind) — check via browser devtools or `kubectl -n granite logs deploy/auth-server`.

---

## 10. Stateful data — explicit decision

Postgres (x5) and Kafka stay **in-cluster** with `local-path-provisioner`-backed PVCs, same as
kind, just with resource requests/limits added (`production-patches.yaml` already covers this — a
one-node VPS has no headroom for one pod to starve the others).

This is a deliberate choice, not a default: `local-path-provisioner` ties each PVC's data to this
specific node's disk. Fine for a demo/portfolio deployment; if this ever needs real durability,
migrating to a managed Postgres (e.g. Hetzner's own managed DB offering, if/when available in your
region) or at minimum scheduling `pg_dump` backups off-box would be the next step — out of scope
here.

---

## 11. Changing domains later (if ever applicable)

Not needed now — `granite-security.org` is live (§0, §3) — but kept as a runbook in case the
domain ever changes again:

1. Create the Cloudflare `A` record (DNS-only) for the new domain.
2. `grep -rl 'granite-security.org' cloud/hetzner/app cloud/hetzner/platform | xargs sed -i '' "s/granite-security.org/<NEW_DOMAIN>/g"`.
3. Re-run step 4.4 (coredns-custom) with the new domain.
4. `kubectl apply -k cloud/hetzner/app` — cert-manager will request a fresh cert for the new host
   automatically (the `cert-manager.io/cluster-issuer` annotation on `app/gateway.yaml`'s Gateway
   re-triggers Certificate generation whenever the listener `hostname` changes).
5. Update the Google OAuth redirect URI and Stripe webhook URL to the new domain before switching
   real traffic over.

---

## 12. Ongoing operations

| Task | Command |
|---|---|
| Roll out a new image | Bump `newTag` in `app/kustomization.yaml` to the new short SHA → `kubectl apply -k cloud/hetzner/app` |
| Tail logs | `kubectl -n granite logs -f deploy/<service>` |
| Resource pressure check | `kubectl top pods -n granite` (tune `production-patches.yaml` limits from here) |
| Full teardown | `kubectl delete namespace granite` (leaves Traefik/cert-manager/the cluster itself intact) |

---

## 13. Non-goals

Explicitly out of scope for this plan — flag if any of these turn out to matter:

- High availability / multi-node (this is one VPS, one node, no failover).
- Managed Postgres/Kafka migration (see §10).
- Autoscaling (no HPA wired up; fixed replica counts from `k8s/base`).
- Backups/disaster recovery automation.
- CI/CD (image build+push in §6 is manual; wiring GitHub Actions to do it is a natural follow-up
  but not covered here).

---

## 14. Apply the coredns-custom split-horizon override

`platform/coredns-custom.yaml` ships with a literal placeholder
(`REPLACE_WITH_TRAEFIK_CLUSTER_IP`) and is **not applied automatically** by
§8's `kubectl apply -k`. Without this, backend pods (`auth-server`,
`greetings`, `shop`, `payment`, `profile`, `delivery`) resolve
`https://<DOMAIN>/auth/.well-known/openid-configuration` by leaving the
cluster through Cloudflare and hairpinning back to the same node — slow, and
not guaranteed to work depending on the network path. This override makes
`<DOMAIN>` resolve straight to Traefik's in-cluster `ClusterIP` instead, so the
call never leaves the node.

```bash
INGRESS_IP=$(kubectl get svc -n traefik traefik -o jsonpath='{.spec.clusterIP}')
echo "Traefik ClusterIP: $INGRESS_IP"   # sanity check before substituting

sed -i '' "s/REPLACE_WITH_TRAEFIK_CLUSTER_IP/$INGRESS_IP/" cloud/hetzner/platform/coredns-custom.yaml
# (drop the '' after -i if running this directly on the VPS/Linux instead of macOS)

kubectl apply -f cloud/hetzner/platform/coredns-custom.yaml
kubectl rollout restart deployment coredns -n kube-system
kubectl rollout status deployment coredns -n kube-system
```

Verify it actually took effect from inside the cluster:
```bash
kubectl -n granite exec deploy/shop -- getent hosts <DOMAIN>
# should print the Traefik ClusterIP above, not a public Cloudflare IP
```

Note: the substituted IP is Traefik's `ClusterIP`, which is stable as long as
its Service object isn't deleted/recreated — if that ever happens (e.g.
`helm uninstall traefik` + reinstall), re-run this section with the new IP.


# 15 Redeployment

If you want data to survive a delete -k / apply -k cycle in the future, the fix would be one of:
- Split the PVCs out of the kustomization scope (e.g. into a separate storage kustomization applied/deleted   
  independently from app), so kubectl delete -k app never touches them.
- Or use `kubectl delete deployment -n granite --all` / delete specific resources rather than delete -k, when   
  you want to tear down workloads but keep data.
